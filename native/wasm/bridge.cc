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
#include "ink/brush/stock_brushes.h"
#include "ink/color/color.h"
#include "ink/geometry/angle.h"
#include "ink/geometry/partitioned_mesh.h"
#include "ink/geometry/rect.h"
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

[[noreturn]] void Fail(const std::string& message) {
  val::global("Error").new_(message).throw_();
  std::abort();
}

void Check(const absl::Status& status) {
  if (!status.ok()) Fail(status.ToString());
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
  if (deflateInit2(&stream, 6, Z_DEFLATED, 31, 8, Z_DEFAULT_STRATEGY) != Z_OK) Fail("Cannot initialize gzip");
  struct End { z_stream* s; ~End() { deflateEnd(s); } } end{&stream};
  gz_header header{};
  header.os = 255;
  if (deflateSetHeader(&stream, &header) != Z_OK) Fail("Cannot initialize gzip header");
  std::string output(deflateBound(&stream, bytes.size()), '\0');
  stream.next_in = reinterpret_cast<Bytef*>(bytes.data());
  stream.avail_in = bytes.size();
  stream.next_out = reinterpret_cast<Bytef*>(output.data());
  stream.avail_out = output.size();
  if (deflate(&stream, Z_FINISH) != Z_STREAM_END) Fail("Cannot finish gzip");
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
  if (inflateInit2(&stream, 31) != Z_OK) Fail("Cannot initialize gzip decoder");
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

ink::Brush MakeBrush(const val& encoded_family, uint32_t argb, float size, float epsilon) {
  if (encoded_family["length"].as<size_t>() > kMaxProtoBytes) Fail("Brush protobuf exceeds 64 MiB");
  auto data = emscripten::convertJSArrayToNumberVector<uint8_t>(encoded_family);
  ink::proto::BrushFamily proto;
  if (!proto.ParseFromArray(data.data(), data.size())) Fail("Invalid brush family protobuf");
  auto family = ink::DecodeBrushFamily(proto);
  if (!family.ok()) Fail(family.status().ToString());
  return MakeBrush(*family, argb, size, epsilon);
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

class WasmStroke {
 public:
  explicit WasmStroke(ink::Stroke stroke) : stroke_(std::move(stroke)) {}
  val Geometry() const { return wasm::Geometry(stroke_.GetShape()); }
  val Inputs() const { return InputArray(stroke_.GetInputs()); }
  val EncodeInputs() const { return Encode(stroke_.GetInputs()); }
  val EncodeAndroidInputs() const { return EncodeAndroid(stroke_.GetInputs()); }
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
                                                 float epsilon, const val& samples) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon), ReadInputs(samples)));
}

std::unique_ptr<WasmStroke> DecodeStrokeWithFamily(const val& family, uint32_t color, float size,
                                                 float epsilon, const val& bytes) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon), Decode(bytes)));
}

std::unique_ptr<WasmStroke> DecodeAndroidStroke(int family, uint32_t color, float size, float epsilon,
                                              const val& bytes) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon), DecodeAndroid(bytes)));
}

std::unique_ptr<WasmStroke> DecodeAndroidStrokeWithFamily(const val& family, uint32_t color, float size,
                                                        float epsilon, const val& bytes) {
  return std::make_unique<WasmStroke>(ink::Stroke(MakeBrush(family, color, size, epsilon), DecodeAndroid(bytes)));
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

std::unique_ptr<WasmLiveStroke> StartStrokeWithFamily(const val& family, uint32_t color, float size, float epsilon) {
  return std::make_unique<WasmLiveStroke>(MakeBrush(family, color, size, epsilon));
}
}  // namespace byteink::wasm

EMSCRIPTEN_BINDINGS(byteink) {
  using namespace byteink::wasm;
  emscripten::function("abiVersion", +[]() { return 1; });
  emscripten::function("engineCommit", +[]() { return std::string(BYTEINK_ENGINE_COMMIT); });
  emscripten::function("createStroke", &CreateStroke);
  emscripten::function("decodeStroke", &DecodeStroke);
  emscripten::function("createStrokeWithFamily", &CreateStrokeWithFamily);
  emscripten::function("decodeStrokeWithFamily", &DecodeStrokeWithFamily);
  emscripten::function("decodeAndroidStroke", &DecodeAndroidStroke);
  emscripten::function("decodeAndroidStrokeWithFamily", &DecodeAndroidStrokeWithFamily);
  emscripten::function("startStroke", &StartStroke);
  emscripten::function("startStrokeWithFamily", &StartStrokeWithFamily);
  emscripten::class_<WasmStroke>("Stroke")
      .function("geometry", &WasmStroke::Geometry)
      .function("inputs", &WasmStroke::Inputs)
      .function("encodeInputs", &WasmStroke::EncodeInputs)
      .function("encodeAndroidInputs", &WasmStroke::EncodeAndroidInputs)
      .function("coverage", &WasmStroke::Coverage)
      .function("contains", &WasmStroke::Contains);
  emscripten::class_<WasmLiveStroke>("LiveStroke")
      .function("enqueue", &WasmLiveStroke::Enqueue)
      .function("update", &WasmLiveStroke::Update)
      .function("geometry", &WasmLiveStroke::Geometry)
      .function("finish", &WasmLiveStroke::Finish)
      .function("realInputCount", &WasmLiveStroke::RealInputCount)
      .function("predictedInputCount", &WasmLiveStroke::PredictedInputCount);
}
