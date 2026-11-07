// ByteInk's browser binding. All modelling, meshing, storage and intersection work stays in Ink.
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>
#include <zlib.h>

#include <emscripten/bind.h>
#include <emscripten/val.h>

#include "absl/status/status.h"
#include "ink/brush/brush.h"
#include "ink/brush/color_function.h"
#include "ink/brush/stock_brushes.h"
#include "ink/color/color.h"
#include "ink/geometry/angle.h"
#include "ink/geometry/affine_transform.h"
#include "ink/geometry/internal/polyline_processing.h"
#include "ink/geometry/partitioned_mesh.h"
#include "ink/geometry/rect.h"
#include "ink/geometry/tessellator.h"
#include "ink/geometry/triangle.h"
#include "ink/storage/brush.h"
#include "ink/storage/proto/stroke_input_batch.pb.h"
#include "ink/storage/stroke_input_batch.h"
#include "ink/strokes/in_progress_stroke.h"
#include "ink/strokes/input/stroke_input_batch.h"
#include "ink/strokes/stroke.h"

namespace byteink::wasm {
using emscripten::val;
constexpr size_t kInputStride = 8;
constexpr size_t kMaxInputs = 1'000'000;
constexpr size_t kMaxProtoBytes = 64 * 1024 * 1024;

[[noreturn]] void Fail(const std::string& message, bool data_error = true) {
  auto error = val::global("Error").new_(message);
  error.set("name", std::string(data_error ? "ByteInkDataError" : "ByteInkEngineError"));
  error.throw_();
  std::abort();
}

void Check(const absl::Status& status) {
  if (!status.ok()) Fail(status.ToString(), status.code() == absl::StatusCode::kInvalidArgument ||
      status.code() == absl::StatusCode::kFailedPrecondition || status.code() == absl::StatusCode::kOutOfRange);
}

template <typename T>
val CopyArray(const std::vector<T>& data, const char* type) {
  return val::global(type).new_(val(emscripten::typed_memory_view(data.size(), data.data())));
}

std::string EncodeProto(const ink::StrokeInputBatch& inputs) {
  ink::proto::CodedStrokeInputBatch proto;
  ink::EncodeStrokeInputBatch(inputs, proto);
  return proto.SerializeAsString();
}

val ByteArray(const std::string& bytes) {
  return val::global("Uint8Array").new_(val(emscripten::typed_memory_view(
      bytes.size(), reinterpret_cast<const uint8_t*>(bytes.data()))));
}

val Encode(const ink::StrokeInputBatch& inputs) { return ByteArray(EncodeProto(inputs)); }

// Exactly the classic zlib/Android envelope used by ViveInkCodec, including private proto field 10.
// Raw upstream protobuf APIs above intentionally do not add that Android application field.
val EncodeAndroid(const ink::StrokeInputBatch& inputs) {
  std::string bytes = EncodeProto(inputs);
  bytes.append("\x55\0\0\0\0", 5);
  z_stream stream{};
  if (deflateInit2(&stream, 6, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY) != Z_OK) Fail("Cannot initialize gzip", false);
  struct End { z_stream* s; ~End() { deflateEnd(s); } } end{&stream};
  gz_header header{};
  header.os = 255;
  if (deflateSetHeader(&stream, &header) != Z_OK) Fail("Cannot initialize gzip header", false);
  std::string output(deflateBound(&stream, bytes.size()), '\0');
  stream.next_in = reinterpret_cast<Bytef*>(bytes.data());
  stream.avail_in = bytes.size();
  stream.next_out = reinterpret_cast<Bytef*>(output.data());
  stream.avail_out = output.size();
  if (deflate(&stream, Z_FINISH) != Z_STREAM_END) Fail("Cannot finish gzip", false);
  output.resize(stream.total_out);
  return ByteArray(output);
}

ink::StrokeInputBatch DecodeData(const uint8_t* data, size_t size) {
  ink::proto::CodedStrokeInputBatch proto;
  if (!proto.ParseFromArray(data, size)) Fail("Invalid input protobuf");
  // Reject oversized numeric runs before decoding allocates a full batch.
  if (proto.x_stroke_space().deltas_size() > kMaxInputs) Fail("Too many stroke inputs");
  auto inputs = ink::DecodeStrokeInputBatch(proto);
  if (!inputs.ok()) Fail(inputs.status().ToString());
  return *std::move(inputs);
}

ink::StrokeInputBatch Decode(const val& bytes) {
  if (bytes["length"].as<size_t>() > kMaxProtoBytes) Fail("Input protobuf exceeds 64 MiB");
  auto data = emscripten::convertJSArrayToNumberVector<uint8_t>(bytes);
  return DecodeData(data.data(), data.size());
}

ink::StrokeInputBatch DecodeAndroid(const val& bytes) {
  if (bytes["length"].as<size_t>() > kMaxProtoBytes) Fail("Input gzip exceeds 64 MiB");
  auto data = emscripten::convertJSArrayToNumberVector<uint8_t>(bytes);
  z_stream stream{};
  if (inflateInit2(&stream, 31) != Z_OK) Fail("Cannot initialize gzip decoder", false);
  struct End { z_stream* s; ~End() { inflateEnd(s); } } end{&stream};
  stream.next_in = data.data();
  stream.avail_in = data.size();
  std::vector<uint8_t> output;
  constexpr size_t chunk = 16384;
  int status;
  do {
    size_t offset = output.size();
    if (offset >= kMaxProtoBytes) Fail("Inflated input protobuf exceeds 64 MiB");
    output.resize(offset + chunk);
    stream.next_out = output.data() + offset;
    stream.avail_out = chunk;
    status = inflate(&stream, Z_NO_FLUSH);
    if (status == Z_MEM_ERROR) Fail("Cannot allocate gzip decoder storage", false);
    if (status != Z_OK && status != Z_STREAM_END) Fail("Invalid or truncated input gzip");
    output.resize(offset + chunk - stream.avail_out);
  } while (status != Z_STREAM_END);
  if (stream.avail_in != 0) Fail("Trailing bytes after input gzip");
  return DecodeData(output.data(), output.size());
}

// Flat input stride: x, y, elapsed milliseconds, tool, pressure, tilt, orientation, cm/unit.
// Optional values use Ink's own sentinels (-1 for pressure/angles, 0 for unit length).
ink::StrokeInputBatch ReadInputs(const val& samples) {
  const size_t length = samples["length"].as<size_t>();
  if (length % kInputStride || length / kInputStride > kMaxInputs) Fail("Invalid input array length");
  auto values = emscripten::convertJSArrayToNumberVector<double>(samples);
  ink::StrokeInputBatch inputs;
  for (size_t i = 0; i < values.size(); i += kInputStride) {
    for (size_t j = 0; j < kInputStride; ++j) {
      if (!std::isfinite(values[i + j])) Fail("Stroke input values must be finite");
    }
    const double tool = values[i + 3];
    if (tool < 0 || tool > 3 || std::trunc(tool) != tool) Fail("Invalid input tool type");
    ink::StrokeInput input{
        .tool_type = static_cast<ink::StrokeInput::ToolType>(static_cast<int>(tool)),
        .position = {static_cast<float>(values[i]), static_cast<float>(values[i + 1])},
        .elapsed_time = ink::Duration32::Millis(values[i + 2]),
        .stroke_unit_length = ink::PhysicalDistance::Centimeters(values[i + 7]),
        .pressure = static_cast<float>(values[i + 4]),
        .tilt = ink::Angle::Radians(values[i + 5]),
        .orientation = ink::Angle::Radians(values[i + 6]),
    };
    Check(inputs.Append(input));
  }
  return inputs;
}

val InputArray(const ink::StrokeInputBatch& inputs) {
  std::vector<double> values;
  values.reserve(inputs.Size() * kInputStride);
  for (int i = 0; i < inputs.Size(); ++i) {
    const auto input = inputs.Get(i);
    values.insert(values.end(), {input.position.x, input.position.y,
        input.elapsed_time.ToMillis(), static_cast<double>(input.tool_type), input.pressure,
        input.tilt.ValueInRadians(), input.orientation.ValueInRadians(),
        input.stroke_unit_length.ToCentimeters()});
  }
  return CopyArray(values, "Float64Array");
}

ink::Brush MakeBrush(const ink::BrushFamily& family, uint32_t argb, float size, float epsilon) {
  auto brush = ink::Brush::Create(family, ink::Color::FromUint8(
      (argb >> 16) & 255, (argb >> 8) & 255, argb & 255, argb >> 24), size, epsilon);
  if (!brush.ok()) Fail(brush.status().ToString());
  return *std::move(brush);
}

ink::Brush MakeBrush(int family, uint32_t argb, float size, float epsilon) {
  ink::BrushFamily brush_family;
  switch (family) {
    case 0: brush_family = ink::stock_brushes::Marker(ink::stock_brushes::MarkerVersion::kV1); break;
    case 1: brush_family = ink::stock_brushes::PressurePen(ink::stock_brushes::PressurePenVersion::kV1); break;
    case 2: brush_family = ink::stock_brushes::Highlighter(
        ink::BrushPaint::SelfOverlap::kDiscard, ink::stock_brushes::HighlighterVersion::kV1); break;
    case 3: brush_family = ink::stock_brushes::DashedLine(ink::stock_brushes::DashedLineVersion::kV1); break;
    default: Fail("Unknown stock brush");
  }
  return MakeBrush(brush_family, argb, size, epsilon);
}

ink::Brush MakeBrush(const val& encoded_family, uint32_t argb, float size, float epsilon, bool allow_development = false) {
  if (encoded_family["length"].as<size_t>() > kMaxProtoBytes) Fail("Brush protobuf exceeds 64 MiB");
  auto data = emscripten::convertJSArrayToNumberVector<uint8_t>(encoded_family);
  ink::proto::BrushFamily proto;
  if (!proto.ParseFromArray(data.data(), data.size())) Fail("Invalid brush family protobuf");
  auto family = ink::DecodeBrushFamily(proto, allow_development ? ink::Version::kDevelopment() : ink::Version::kMaxSupported());
  if (!family.ok()) Fail(family.status().ToString());
  return MakeBrush(*family, argb, size, epsilon);
}

val CatalogFamily(int kind, int pressure, int window_millis, float min_size, float max_size) {
  if (kind < 0 || kind > 4 || pressure < 0 || pressure > 5 || window_millis < -1 || window_millis > 120 ||
      !std::isfinite(min_size) || !std::isfinite(max_size)) Fail("Invalid catalog definition");
  ink::BrushFamily family;
  if (kind == 4) {
    using B = ink::BrushBehavior;
    std::vector<B> behaviors;
    if (pressure != 0) {
      auto behavior = [&](B::Source source, float end, float low, float high, B::EnabledToolTypes tools,
                          std::string comment) {
        return B{.nodes = {
            B::SourceNode{.source = source, .source_value_range = {0.f, end}},
            B::DampingNode{.damping_source = B::ProgressDomain::kDistanceInMultiplesOfBrushSize, .damping_gap = .75f},
            B::ToolTypeFilterNode{.enabled_tool_types = tools},
            B::TargetNode{.target = B::Target::kSizeMultiplier, .target_modifier_range = {low, high}}},
            .developer_comment = std::move(comment)};
      };
      behaviors.push_back(behavior(B::Source::kNormalizedPressure, 1, min_size, max_size, {.stylus = true},
          "Stylus pressure flex for calligraphy level " + std::to_string(pressure) + "."));
      behaviors.push_back(behavior(B::Source::kSpeedInMultiplesOfBrushSizePerSecond, 20, max_size, min_size,
          {.unknown = true, .mouse = true, .touch = true},
          "Speed fallback for calligraphy level " + std::to_string(pressure) + "."));
    }
    auto result = ink::BrushFamily::Create(
        ink::BrushTip{.scale = {1.f, .22f}, .corner_rounding = .2f, .rotation = ink::Angle::Degrees(45),
                      .behaviors = std::move(behaviors)}, ink::BrushPaint{}, ink::BrushFamily::DefaultInputModel(),
        {.developer_comment = "ViveNotes calligraphy v1: fixed 45-degree broad nib, pressure level " + std::to_string(pressure) + "."});
    if (!result.ok()) Fail(result.status().ToString());
    family = *std::move(result);
  } else family = MakeBrush(kind, 0xff000000, 4, .25f).GetFamily();
  if (window_millis >= 0) {
    ink::BrushFamily::InputModel model = ink::BrushFamily::PassthroughModel{};
    if (window_millis > 0) model = ink::BrushFamily::SlidingWindowModel{
        .window_size = ink::Duration32::Millis(window_millis), .upsampling_period = ink::Duration32::Seconds(1) / 180.f};
    auto result = ink::BrushFamily::Create(family.GetCoats(), model, family.GetMetadata());
    if (!result.ok()) Fail(result.status().ToString());
    family = *std::move(result);
  }
  ink::proto::BrushFamily proto;
  ink::EncodeBrushFamily(family, proto);
  return ByteArray(proto.SerializeAsString());
}

ink::AffineTransform Transform(const val& values) {
  if (values["length"].as<size_t>() != 4) Fail("Invalid page transform length");
  auto v = emscripten::convertJSArrayToNumberVector<float>(values);
  for (float value : v) if (!std::isfinite(value)) Fail("Non-finite page transform");
  if (v[0] <= 0 || v[1] <= 0) Fail("Page scales must be positive");
  ink::AffineTransform transform(v[0], 0, v[2], 0, v[1], v[3]);
  if (!transform.Inverse()) Fail("Non-invertible page transform");
  return transform;
}

template <typename Mesh>
val MeshSnapshot(const Mesh& mesh) {
  std::vector<float> positions;
  std::vector<uint32_t> triangles;
  positions.reserve(mesh.VertexCount() * 2);
  triangles.reserve(mesh.TriangleCount() * 3);
  for (uint32_t i = 0; i < mesh.VertexCount(); ++i) {
    const auto p = mesh.VertexPosition(i);
    positions.insert(positions.end(), {p.x, p.y});
  }
  for (uint32_t i = 0; i < mesh.TriangleCount(); ++i) {
    const auto indices = mesh.TriangleIndices(i);
    triangles.insert(triangles.end(), indices.begin(), indices.end());
  }
  auto result = val::object();
  result.set("positions", CopyArray(positions, "Float32Array"));
  result.set("triangles", CopyArray(triangles, "Uint32Array"));
  return result;
}

val Geometry(const ink::PartitionedMesh& shape) {
  auto groups = val::array();
  for (uint32_t g = 0; g < shape.RenderGroupCount(); ++g) {
    auto group = val::object();
    auto meshes = val::array();
    uint32_t m = 0;
    for (const auto& mesh : shape.RenderGroupMeshes(g)) meshes.set(m++, MeshSnapshot(mesh));
    auto outlines = val::array();
    for (uint32_t o = 0; o < shape.OutlineCount(g); ++o) {
      std::vector<float> positions;
      for (uint32_t v = 0; v < shape.OutlineVertexCount(g, o); ++v) {
        const auto p = shape.OutlinePosition(g, o, v);
        positions.insert(positions.end(), {p.x, p.y});
      }
      outlines.set(o, CopyArray(positions, "Float32Array"));
    }
    group.set("meshes", meshes);
    group.set("outlines", outlines);
    groups.set(g, group);
  }
  return groups;
}

// The canonical desktop rendering layout, decoded by Ink rather than reinterpreting packed bytes.
template <typename Mesh>
val RenderingMesh(const Mesh& mesh) {
  using Id = ink::MeshFormat::AttributeId;
  constexpr Id ids[] = {Id::kPosition, Id::kOpacityShift, Id::kColorShiftHsl,
      Id::kSideDerivative, Id::kSideLabel, Id::kForwardDerivative, Id::kForwardLabel,
      Id::kSurfaceUv, Id::kAnimationOffset};
  constexpr int offsets[] = {0, 2, 3, 6, 8, 9, 11, 12, 14};
  constexpr int components[] = {2, 1, 3, 2, 1, 2, 1, 2, 1};
  auto result = MeshSnapshot(mesh);
  std::vector<float> vertices(mesh.VertexCount() * 15, 0.f);
  int mask = 0;
  const auto attributes = mesh.Format().Attributes();
  for (size_t a = 0; a < attributes.size(); ++a) {
    for (int k = 0; k < 9; ++k) {
      if (attributes[a].id != ids[k]) continue;
      if (ink::MeshFormat::ComponentCount(attributes[a].type) != components[k])
        Fail("Incompatible rendering attribute component count");
      mask |= 1 << k;
      for (uint32_t v = 0; v < mesh.VertexCount(); ++v) {
        const auto decoded = mesh.FloatVertexAttribute(v, a);
        for (int c = 0; c < components[k]; ++c) vertices[v * 15 + offsets[k] + c] = decoded[c];
      }
    }
  }
  result.set("vertices", CopyArray(vertices, "Float32Array"));
  result.set("attributeMask", mask);
  return result;
}

val Paints(const ink::Brush& brush, uint32_t coat, const ink::MeshFormat& format) {
  auto paints = val::array();
  uint32_t p = 0;
  for (const auto& paint : brush.GetFamily().GetCoats()[coat].paint_preferences) {
    auto result = val::object();
    absl::flat_hash_set<ink::MeshFormat::AttributeId> required;
    ink::brush_internal::AddAttributeIdsRequiredByPaint(paint, required);
    for (const auto& attribute : format.Attributes()) required.erase(attribute.id);
    result.set("compatible", required.empty());
    result.set("overlap", static_cast<int>(paint.self_overlap));
    auto color = ink::ColorFunction::ApplyAll(paint.color_functions, brush.GetColor()).InColorSpace(ink::ColorSpace::kSrgb);
    auto linear = color.AsFloat(ink::Color::Format::kLinear);
    auto srgb = color.AsFloat(ink::Color::Format::kGammaEncoded);
    result.set("linearColor", CopyArray(std::vector<float>{linear.r, linear.g, linear.b, linear.a}, "Float32Array"));
    result.set("srgbColor", CopyArray(std::vector<float>{srgb.r, srgb.g, srgb.b, srgb.a}, "Float32Array"));
    auto layers = val::array();
    uint32_t t = 0;
    for (const auto& layer : paint.texture_layers) {
      auto texture = val::object();
      std::visit([&](const auto& value) {
        texture.set("id", value.client_texture_id);
        texture.set("blend", static_cast<int>(value.blend_mode));
        if constexpr (std::is_same_v<std::decay_t<decltype(value)>, ink::BrushPaint::TilingTexture>) {
          texture.set("mapping", 0);
          texture.set("parameters", CopyArray(std::vector<float>{
              static_cast<float>(value.origin), static_cast<float>(value.size_unit),
              static_cast<float>(value.wrap_x), static_cast<float>(value.wrap_y),
              value.size.x, value.size.y, value.offset.x, value.offset.y, value.rotation.ValueInRadians()}, "Float32Array"));
        } else {
          texture.set("mapping", 1);
          texture.set("parameters", CopyArray(std::vector<float>{static_cast<float>(value.animation_frames),
              static_cast<float>(value.animation_rows), static_cast<float>(value.animation_columns),
              static_cast<float>(absl::ToInt64Milliseconds(value.animation_duration)),
              static_cast<float>(value.animation_repeat_mode)}, "Float32Array"));
        }
      }, layer);
      layers.set(t++, texture);
    }
    result.set("textures", layers);
    paints.set(p++, result);
  }
  return paints;
}

val RenderingHeader(const ink::Brush& brush, const ink::StrokeInputBatch& inputs, const val& groups) {
  auto result = val::object();
  result.set("groups", groups);
  result.set("brushSize", brush.GetSize());
  const auto first = inputs.IsEmpty() ? ink::Point{} : inputs.Get(0).position;
  const auto last = inputs.IsEmpty() ? ink::Point{} : inputs.Get(inputs.Size() - 1).position;
  result.set("inputPositions", CopyArray(std::vector<float>{first.x, first.y, last.x, last.y}, "Float32Array"));
  return result;
}

val Rendering(const ink::Stroke& stroke) {
  auto groups = wasm::Geometry(stroke.GetShape());
  for (uint32_t g = 0; g < stroke.GetShape().RenderGroupCount(); ++g) {
    auto meshes = val::array();
    uint32_t m = 0;
    for (const auto& mesh : stroke.GetShape().RenderGroupMeshes(g)) meshes.set(m++, RenderingMesh(mesh));
    groups[g].set("meshes", meshes);
    groups[g].set("paints", Paints(stroke.GetBrush(), g, stroke.GetShape().RenderGroupFormat(g)));
  }
  return RenderingHeader(stroke.GetBrush(), stroke.GetInputs(), groups);
}

class WasmStroke {
 public:
  explicit WasmStroke(ink::Stroke stroke) : stroke_(std::move(stroke)) {}
  val Geometry() const { return wasm::Geometry(stroke_.GetShape()); }
  val Rendering() const { return wasm::Rendering(stroke_); }
  val Inputs() const { return InputArray(stroke_.GetInputs()); }
  val EncodeInputs() const { return Encode(stroke_.GetInputs()); }
  val EncodeAndroidInputs() const { return EncodeAndroid(stroke_.GetInputs()); }
  val Bounds() const {
    auto bounds = stroke_.GetShape().Bounds().AsRect();
    if (!bounds) return val::null();
    return CopyArray(std::vector<float>{bounds->XMin(), bounds->YMin(), bounds->XMax(), bounds->YMax()}, "Float32Array");
  }
  std::unique_ptr<WasmStroke> Copy() const { return std::make_unique<WasmStroke>(stroke_); }
  bool Intersects(const WasmStroke& other, const val& this_to_page, const val& other_to_page) const {
    auto transform = *Transform(this_to_page).Inverse() * Transform(other_to_page);
    if (stroke_.GetShape().Bounds().IsEmpty() || other.stroke_.GetShape().Bounds().IsEmpty()) return false;
    return stroke_.GetShape().CoverageIsGreaterThan(other.stroke_.GetShape(), 0.f, transform);
  }
  std::unique_ptr<WasmStroke> Subtract(const WasmStroke& mask, const val& this_to_page) const {
    return std::make_unique<WasmStroke>(stroke_.Subtract(mask.stroke_.GetShape(), {}, Transform(this_to_page)));
  }
  val Split(const val& this_to_page) const {
    auto result = val::array();
    uint32_t i = 0;
    for (auto& stroke : stroke_.Split(Transform(this_to_page), 0.f))
      result.set(i++, val(std::make_unique<WasmStroke>(std::move(stroke))));
    return result;
  }
  bool TouchesTriangle(const val& points, const val& this_to_page) const {
    if (points["length"].as<size_t>() != 6) Fail("A triangle needs three points");
    auto p = emscripten::convertJSArrayToNumberVector<float>(points);
    for (float value : p) if (!std::isfinite(value)) Fail("Non-finite triangle");
    if (stroke_.GetShape().Bounds().IsEmpty()) return false;
    return stroke_.GetShape().CoverageIsGreaterThan(ink::Triangle{{p[0], p[1]}, {p[2], p[3]}, {p[4], p[5]}}, 0.f,
                                                    *Transform(this_to_page).Inverse());
  }
  bool OverlapsPolygon(const val& coordinates, const val& this_to_page) const {
    const size_t count = coordinates["length"].as<size_t>();
    if (count < 6 || count % 2 || count > kMaxInputs * 2) Fail("Invalid polygon length");
    auto values = emscripten::convertJSArrayToNumberVector<float>(coordinates);
    std::vector<ink::Point> points;
    for (size_t i = 0; i < values.size(); i += 2) {
      if (!std::isfinite(values[i]) || !std::isfinite(values[i + 1])) Fail("Non-finite polygon");
      ink::Point point{values[i], values[i + 1]};
      if (points.empty() || point != points.back()) points.push_back(point);
    }
    if (points.front() != points.back()) points.push_back(points.front());
    auto mesh = ink::CreateMeshFromPolyline(ink::geometry_internal::CreateClosedShape(points));
    if (!mesh.ok() || stroke_.GetShape().Bounds().IsEmpty()) return false;
    auto region = ink::PartitionedMesh::FromMeshes(absl::MakeSpan(&*mesh, 1));
    if (!region.ok()) Fail(region.status().ToString());
    return stroke_.GetShape().CoverageIsGreaterThan(*region, 0.f, *Transform(this_to_page).Inverse());
  }
  bool Contains(float x, float y) const {
    if (!std::isfinite(x) || !std::isfinite(y)) Fail("Hit point must be finite");
    if (stroke_.GetShape().Bounds().IsEmpty()) return false;
    bool hit = false;
    stroke_.GetShape().VisitIntersectedTriangles(ink::Point{x, y},
        [&hit](ink::TriangleIndexPair) {
          hit = true;
          return ink::PartitionedMesh::FlowControl::kBreak;
        });
    return hit;
  }
  float Coverage(float x_min, float y_min, float x_max, float y_max) const {
    if (!std::isfinite(x_min) || !std::isfinite(y_min) || !std::isfinite(x_max) ||
        !std::isfinite(y_max) || x_min > x_max || y_min > y_max) Fail("Invalid query box");
    if (stroke_.GetShape().Bounds().IsEmpty()) return 0;
    return stroke_.GetShape().Coverage(ink::Rect::FromTwoPoints({x_min, y_min}, {x_max, y_max}));
  }
 private:
  ink::Stroke stroke_;
};

std::unique_ptr<WasmStroke> CreateStroke(int family, uint32_t color, float size, float epsilon,
                                       const val& samples) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon), ReadInputs(samples)));
}

std::unique_ptr<WasmStroke> DecodeStroke(int family, uint32_t color, float size, float epsilon,
                                       const val& bytes) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon), Decode(bytes)));
}

std::unique_ptr<WasmStroke> CreateStrokeWithFamily(const val& family, uint32_t color, float size,
                                                 float epsilon, const val& samples, bool allow_development) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon, allow_development), ReadInputs(samples)));
}

std::unique_ptr<WasmStroke> DecodeStrokeWithFamily(const val& family, uint32_t color, float size,
                                                 float epsilon, const val& bytes, bool allow_development) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon, allow_development), Decode(bytes)));
}

std::unique_ptr<WasmStroke> DecodeAndroidStroke(int family, uint32_t color, float size, float epsilon,
                                              const val& bytes) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon), DecodeAndroid(bytes)));
}

std::unique_ptr<WasmStroke> DecodeAndroidStrokeWithFamily(const val& family, uint32_t color, float size,
                                                        float epsilon, const val& bytes, bool allow_development) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon, allow_development), DecodeAndroid(bytes)));
}

class WasmLiveStroke {
 public:
  explicit WasmLiveStroke(const ink::Brush& brush) { live_.Start(brush); }
  void Enqueue(const val& real, const val& predicted) {
    Check(live_.EnqueueInputs(ReadInputs(real), ReadInputs(predicted)));
  }
  void Update(float elapsed_millis) {
    if (!std::isfinite(elapsed_millis)) Fail("Elapsed time must be finite");
    Check(live_.UpdateShape(ink::Duration32::Millis(elapsed_millis)));
    last_elapsed_millis_ = elapsed_millis;
  }
  val Geometry() const {
    if (finished_) return wasm::Geometry(finished_->GetShape());
    auto groups = val::array();
    for (uint32_t g = 0; g < live_.BrushCoatCount(); ++g) {
      auto group = val::object();
      auto meshes = val::array();
      meshes.set(0, MeshSnapshot(live_.GetMesh(g)));
      auto outlines = val::array();
      uint32_t o = 0;
      for (const auto& outline : live_.GetCoatOutlines(g)) {
        std::vector<float> positions;
        for (auto index : outline) {
          const auto p = live_.GetMesh(g).VertexPosition(index);
          positions.insert(positions.end(), {p.x, p.y});
        }
        outlines.set(o++, CopyArray(positions, "Float32Array"));
      }
      group.set("meshes", meshes);
      group.set("outlines", outlines);
      groups.set(g, group);
    }
    return groups;
  }
  val Rendering() const {
    if (finished_) return wasm::Rendering(*finished_);
    auto groups = Geometry();
    for (uint32_t g = 0; g < live_.BrushCoatCount(); ++g) {
      auto meshes = val::array();
      meshes.set(0, RenderingMesh(live_.GetMesh(g)));
      groups[g].set("meshes", meshes);
      groups[g].set("paints", Paints(*live_.GetBrush(), g, live_.GetMesh(g).Format()));
    }
    return RenderingHeader(*live_.GetBrush(), live_.GetInputs(), groups);
  }
  bool NeedsUpdate() const { return !finished_ && live_.NeedsUpdate(); }
  std::unique_ptr<WasmStroke> Finish(float elapsed_millis) {
    // Validate before mutating, then clear predictions and consume any remaining real inputs.
    if (!std::isfinite(elapsed_millis) || elapsed_millis < last_elapsed_millis_ || elapsed_millis < 0)
      Fail("Finish time must be finite, nonnegative and non-decreasing");
    Check(live_.EnqueueInputs({}, {}));
    live_.FinishInputs();
    Update(elapsed_millis);
    // A final update may have already consumed all queued real inputs before pointer-up. In that
    // case upstream UpdateShape exits early and does not flush its input modeler. Rebuild from
    // real inputs so this API always returns the same finished shape as the Stroke constructor.
    finished_.emplace(*live_.GetBrush(), live_.GetInputs());
    return std::make_unique<WasmStroke>(*finished_);
  }
  int RealInputCount() const { return live_.RealInputCount(); }
  int PredictedInputCount() const { return live_.PredictedInputCount(); }
 private:
  ink::InProgressStroke live_;
  float last_elapsed_millis_ = 0;
  std::optional<ink::Stroke> finished_;
};

std::unique_ptr<WasmLiveStroke> StartStroke(int family, uint32_t color, float size, float epsilon) {
  return std::make_unique<WasmLiveStroke>(MakeBrush(family, color, size, epsilon));
}

std::unique_ptr<WasmLiveStroke> StartStrokeWithFamily(const val& family, uint32_t color, float size, float epsilon, bool allow_development) {
  return std::make_unique<WasmLiveStroke>(MakeBrush(family, color, size, epsilon, allow_development));
}
}  // namespace byteink::wasm

EMSCRIPTEN_BINDINGS(byteink) {
  using namespace byteink::wasm;
  emscripten::function("abiVersion", +[]() { return 4; });
  emscripten::function("mathProfile", +[]() { return std::string("bionic-acosf-v1"); });
  emscripten::function("engineCommit", +[]() { return std::string(BYTEINK_ENGINE_COMMIT); });
  emscripten::function("createStroke", &CreateStroke);
  emscripten::function("decodeStroke", &DecodeStroke);
  emscripten::function("createStrokeWithFamily", &CreateStrokeWithFamily);
  emscripten::function("decodeStrokeWithFamily", &DecodeStrokeWithFamily);
  emscripten::function("decodeAndroidStroke", &DecodeAndroidStroke);
  emscripten::function("decodeAndroidStrokeWithFamily", &DecodeAndroidStrokeWithFamily);
  emscripten::function("startStroke", &StartStroke);
  emscripten::function("startStrokeWithFamily", &StartStrokeWithFamily);
  emscripten::function("catalogFamily", &CatalogFamily);
  emscripten::class_<WasmStroke>("Stroke")
      .function("geometry", &WasmStroke::Geometry)
      .function("rendering", &WasmStroke::Rendering)
      .function("inputs", &WasmStroke::Inputs)
      .function("encodeInputs", &WasmStroke::EncodeInputs)
      .function("encodeAndroidInputs", &WasmStroke::EncodeAndroidInputs)
      .function("bounds", &WasmStroke::Bounds)
      .function("copy", &WasmStroke::Copy)
      .function("intersects", &WasmStroke::Intersects)
      .function("subtract", &WasmStroke::Subtract)
      .function("split", &WasmStroke::Split)
      .function("touchesTriangle", &WasmStroke::TouchesTriangle)
      .function("overlapsPolygon", &WasmStroke::OverlapsPolygon)
      .function("coverage", &WasmStroke::Coverage)
      .function("contains", &WasmStroke::Contains);
  emscripten::class_<WasmLiveStroke>("LiveStroke")
      .function("enqueue", &WasmLiveStroke::Enqueue)
      .function("update", &WasmLiveStroke::Update)
      .function("geometry", &WasmLiveStroke::Geometry)
      .function("rendering", &WasmLiveStroke::Rendering)
      .function("needsUpdate", &WasmLiveStroke::NeedsUpdate)
      .function("finish", &WasmLiveStroke::Finish)
      .function("realInputCount", &WasmLiveStroke::RealInputCount)
      .function("predictedInputCount", &WasmLiveStroke::PredictedInputCount);
}
