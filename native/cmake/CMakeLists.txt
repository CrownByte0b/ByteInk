# Builds google/ink's JNI library, libink.so, for Linux x86_64 without Bazel. Flatpak/Flathub
# builds use it: they run offline with the Freedesktop SDK's compiler, and upstream's Bazel build
# fetches its toolchain, sysroot and module graph from the network. native/build-linux.sh remains
# the build byteink publishes.
#
# The inputs are those native/build-linux.sh builds from: the google/ink commit pinned in
# upstream/pins.properties with the whole native/patches series applied, plus the abseil-cpp,
# protobuf, zlib and libtess2 versions that commit's MODULE.bazel resolves (pinned there too).
# Everything links statically and the version script exports only the JNI entry points, as in
# upstream's //ink/jni:libink.so. native/build-linux-cmake.sh fetches the pinned inputs and runs
# this on a Linux host.
cmake_minimum_required(VERSION 3.25)
project(byteink_ink LANGUAGES C CXX)

set(INK_SOURCE_DIR "" CACHE PATH "google/ink checkout with native/patches applied")
set(ABSEIL_SOURCE_DIR "" CACHE PATH "abseil-cpp source")
set(PROTOBUF_SOURCE_DIR "" CACHE PATH "protobuf source")
set(ZLIB_SOURCE_DIR "" CACHE PATH "zlib source")
set(LIBTESS2_SOURCE_DIR "" CACHE PATH "libtess2 source")
foreach(dir INK_SOURCE_DIR ABSEIL_SOURCE_DIR PROTOBUF_SOURCE_DIR ZLIB_SOURCE_DIR LIBTESS2_SOURCE_DIR)
  if(NOT IS_DIRECTORY "${${dir}}")
    message(FATAL_ERROR "Set ${dir} to an existing directory")
  endif()
endforeach()

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
# Upstream's opt configuration: -O2 -DNDEBUG, each function and datum in its own section so the
# linker can drop what no JNI entry point reaches.
set(CMAKE_C_FLAGS_RELEASE "-O2 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG")
add_compile_options(-ffunction-sections -fdata-sections)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF)

find_path(JNI_INCLUDE_DIR jni.h HINTS "$ENV{JAVA_HOME}/include" REQUIRED NO_DEFAULT_PATH)
find_path(JNI_MD_INCLUDE_DIR jni_md.h HINTS "${JNI_INCLUDE_DIR}/linux" REQUIRED NO_DEFAULT_PATH)

set(ABSL_PROPAGATE_CXX_STD ON)
set(ABSL_BUILD_TESTING OFF)
set(ABSL_ENABLE_INSTALL OFF)
add_subdirectory("${ABSEIL_SOURCE_DIR}" abseil-cpp EXCLUDE_FROM_ALL SYSTEM)

set(protobuf_ABSL_PROVIDER module)
set(protobuf_BUILD_TESTS OFF)
set(protobuf_BUILD_CONFORMANCE OFF)
set(protobuf_BUILD_EXAMPLES OFF)
set(protobuf_INSTALL OFF)
set(protobuf_WITH_ZLIB OFF)
add_subdirectory("${PROTOBUF_SOURCE_DIR}" protobuf EXCLUDE_FROM_ALL SYSTEM)

# zlib and libtess2 as upstream's Bazel targets compile them: their sources only, no configure.
set(zlib_sources
  adler32.c compress.c crc32.c deflate.c gzclose.c gzlib.c gzread.c gzwrite.c infback.c
  inffast.c inflate.c inftrees.c trees.c uncompr.c zutil.c)
list(TRANSFORM zlib_sources PREPEND "${ZLIB_SOURCE_DIR}/")
add_library(byteink_zlib OBJECT ${zlib_sources})
target_include_directories(byteink_zlib SYSTEM PUBLIC "${ZLIB_SOURCE_DIR}")
target_compile_options(byteink_zlib PRIVATE -w)
# What zlib's own CMake and configure builds detect; GCC rejects its gz* I/O calls without it.
# Only file I/O depends on it; deflate output does not.
target_compile_definitions(byteink_zlib PUBLIC Z_HAVE_UNISTD_H)

# Upstream includes it as "libtess2/tesselator.h".
configure_file("${LIBTESS2_SOURCE_DIR}/Include/tesselator.h"
  "${CMAKE_CURRENT_BINARY_DIR}/include/libtess2/tesselator.h" COPYONLY)
add_library(byteink_libtess2 OBJECT
  "${LIBTESS2_SOURCE_DIR}/Source/bucketalloc.c"
  "${LIBTESS2_SOURCE_DIR}/Source/dict.c"
  "${LIBTESS2_SOURCE_DIR}/Source/geom.c"
  "${LIBTESS2_SOURCE_DIR}/Source/mesh.c"
  "${LIBTESS2_SOURCE_DIR}/Source/priorityq.c"
  "${LIBTESS2_SOURCE_DIR}/Source/sweep.c"
  "${LIBTESS2_SOURCE_DIR}/Source/tess.c")
target_include_directories(byteink_libtess2 PRIVATE "${LIBTESS2_SOURCE_DIR}/Include")
target_include_directories(byteink_libtess2 SYSTEM PUBLIC "${CMAKE_CURRENT_BINARY_DIR}/include")
target_compile_options(byteink_libtess2 PRIVATE -w)

# //ink/storage/proto, generated as upstream's cc_proto_library rules do.
set(ink_protos brush brush_family coded_numeric_run color options stroke_input_batch)
set(proto_out "${CMAKE_CURRENT_BINARY_DIR}/proto")
set(proto_files)
set(proto_sources)
foreach(name IN LISTS ink_protos)
  list(APPEND proto_files "${INK_SOURCE_DIR}/ink/storage/proto/${name}.proto")
  list(APPEND proto_sources
    "${proto_out}/ink/storage/proto/${name}.pb.cc" "${proto_out}/ink/storage/proto/${name}.pb.h")
endforeach()
add_custom_command(
  OUTPUT ${proto_sources}
  COMMAND "${CMAKE_COMMAND}" -E make_directory "${proto_out}"
  COMMAND protobuf::protoc
    "--proto_path=${INK_SOURCE_DIR}" "--proto_path=${PROTOBUF_SOURCE_DIR}/src"
    "--cpp_out=${proto_out}" ${proto_files}
  DEPENDS ${proto_files} protobuf::protoc
  COMMENT "Generating ink/storage/proto C++ sources"
  VERBATIM)

# Every ink source //ink/jni:libink.so compiles at the pinned commit with the patch series
# (bazel query 'kind("source file", deps(//ink/jni:libink.so))').
set(ink_sources
  ink/brush/brush.cc
  ink/brush/brush_behavior.cc
  ink/brush/brush_coat.cc
  ink/brush/brush_family.cc
  ink/brush/brush_paint.cc
  ink/brush/brush_tip.cc
  ink/brush/color_function.cc
  ink/brush/easing_function.cc
  ink/brush/stock_brushes.cc
  ink/brush/internal/jni/brush_behavior_jni.cc
  ink/brush/internal/jni/brush_behavior_native.cc
  ink/brush/internal/jni/brush_behavior_node_jni.cc
  ink/brush/internal/jni/brush_behavior_node_native.cc
  ink/brush/internal/jni/brush_coat_jni.cc
  ink/brush/internal/jni/brush_coat_native.cc
  ink/brush/internal/jni/brush_family_jni.cc
  ink/brush/internal/jni/brush_family_native.cc
  ink/brush/internal/jni/brush_jni.cc
  ink/brush/internal/jni/brush_native.cc
  ink/brush/internal/jni/brush_native_helper.cc
  ink/brush/internal/jni/brush_paint_jni.cc
  ink/brush/internal/jni/brush_paint_native.cc
  ink/brush/internal/jni/brush_tip_jni.cc
  ink/brush/internal/jni/brush_tip_native.cc
  ink/brush/internal/jni/easing_function_jni.cc
  ink/brush/internal/jni/easing_function_native.cc
  ink/brush/internal/jni/input_tool_type_jni.cc
  ink/brush/internal/jni/input_tool_type_native.cc
  ink/brush/internal/jni/self_overlap_jni.cc
  ink/brush/internal/jni/self_overlap_native.cc
  ink/brush/internal/jni/stock_brushes_jni.cc
  ink/brush/internal/jni/stock_brushes_native.cc
  ink/color/color.cc
  ink/color/color_space.cc
  ink/color/internal/jni/color_jni_helper.cc
  ink/color/internal/jni/color_native_helper.cc
  ink/geometry/affine_transform.cc
  ink/geometry/angle.cc
  ink/geometry/distance.cc
  ink/geometry/envelope.cc
  ink/geometry/intersects.cc
  ink/geometry/mesh.cc
  ink/geometry/mesh_format.cc
  ink/geometry/mesh_packing_types.cc
  ink/geometry/mutable_mesh.cc
  ink/geometry/partitioned_mesh.cc
  ink/geometry/point.cc
  ink/geometry/quad.cc
  ink/geometry/rect.cc
  ink/geometry/segment.cc
  ink/geometry/tessellator.cc
  ink/geometry/triangle.cc
  ink/geometry/vec.cc
  ink/geometry/internal/algorithms.cc
  ink/geometry/internal/circle.cc
  ink/geometry/internal/intersects_internal.cc
  ink/geometry/internal/legacy_segment_intersection.cc
  ink/geometry/internal/legacy_triangle_contains.cc
  ink/geometry/internal/legacy_vector_utils.cc
  ink/geometry/internal/lerp.cc
  ink/geometry/internal/mesh_packing.cc
  ink/geometry/internal/modulo.cc
  ink/geometry/internal/outline_processing.cc
  ink/geometry/internal/polyline_processing.cc
  ink/geometry/internal/static_rtree.cc
  ink/geometry/internal/jni/affine_transform_jni.cc
  ink/geometry/internal/jni/affine_transform_native.cc
  ink/geometry/internal/jni/angle_jni.cc
  ink/geometry/internal/jni/angle_native.cc
  ink/geometry/internal/jni/box_accumulator_jni_helper.cc
  ink/geometry/internal/jni/box_jni.cc
  ink/geometry/internal/jni/box_jni_helper.cc
  ink/geometry/internal/jni/box_native.cc
  ink/geometry/internal/jni/intersection_jni.cc
  ink/geometry/internal/jni/intersection_native.cc
  ink/geometry/internal/jni/mesh_format_jni.cc
  ink/geometry/internal/jni/mesh_format_native.cc
  ink/geometry/internal/jni/mesh_jni.cc
  ink/geometry/internal/jni/mesh_native.cc
  ink/geometry/internal/jni/parallelogram_jni.cc
  ink/geometry/internal/jni/parallelogram_jni_helper.cc
  ink/geometry/internal/jni/parallelogram_native.cc
  ink/geometry/internal/jni/partitioned_mesh_jni.cc
  ink/geometry/internal/jni/partitioned_mesh_native.cc
  ink/geometry/internal/jni/triangle_jni.cc
  ink/geometry/internal/jni/triangle_native.cc
  ink/geometry/internal/jni/vec_jni.cc
  ink/geometry/internal/jni/vec_jni_helper.cc
  ink/geometry/internal/jni/vec_native.cc
  ink/jni/byteink_compression_jni.cc
  ink/jni/byteink_geometry_jni.cc
  ink/jni/byteink_input_codec_jni.cc
  ink/jni/internal/jni_buffer_util.cc
  ink/jni/internal/jni_jvm_interface.cc
  ink/jni/internal/jni_proto_util.cc
  ink/jni/internal/jni_string_util.cc
  ink/jni/internal/status_jni.cc
  ink/jni/internal/status_jni_helper.cc
  ink/jni/internal/status_native.cc
  ink/rendering/android/internal/jni/canvas_mesh_renderer_jni.cc
  ink/rendering/common/stroke_attribute_helpers.cc
  ink/rendering/skia/common_internal/mesh_specification_data.cc
  ink/storage/brush.cc
  ink/storage/color.cc
  ink/storage/input_batch.cc
  ink/storage/numeric_run.cc
  ink/storage/stroke_input_batch.cc
  ink/storage/internal/jni/brush_family_serialization_jni.cc
  ink/storage/internal/jni/brush_family_serialization_jni_helper.cc
  ink/storage/internal/jni/brush_family_serialization_native.cc
  ink/storage/internal/jni/brush_family_serialization_native_helper.cc
  ink/storage/internal/jni/byte_array_native_helper.cc
  ink/storage/internal/jni/stroke_input_batch_serialization_jni.cc
  ink/storage/internal/jni/stroke_input_batch_serialization_native.cc
  ink/strokes/in_progress_stroke.cc
  ink/strokes/stroke.cc
  ink/strokes/input/stroke_input.cc
  ink/strokes/input/stroke_input_batch.cc
  ink/strokes/input/internal/stroke_input_validation_helpers.cc
  ink/strokes/internal/brush_tip_extruder.cc
  ink/strokes/internal/brush_tip_extrusion.cc
  ink/strokes/internal/brush_tip_modeler.cc
  ink/strokes/internal/brush_tip_modeler_helpers.cc
  ink/strokes/internal/brush_tip_shape.cc
  ink/strokes/internal/brush_tip_state.cc
  ink/strokes/internal/circular_extrusion_helpers.cc
  ink/strokes/internal/constrain_brush_tip_extrusion.cc
  ink/strokes/internal/easing_implementation.cc
  ink/strokes/internal/modeled_stroke_input.cc
  ink/strokes/internal/noise_generator.cc
  ink/strokes/internal/rounded_polygon.cc
  ink/strokes/internal/stroke_input_modeler.cc
  ink/strokes/internal/stroke_outline.cc
  ink/strokes/internal/stroke_segmentation.cc
  ink/strokes/internal/stroke_shape_builder.cc
  ink/strokes/internal/stroke_shape_update.cc
  ink/strokes/internal/stroke_subtraction.cc
  ink/strokes/internal/stroke_vertex.cc
  ink/strokes/internal/brush_tip_extruder/derivative_calculator.cc
  ink/strokes/internal/brush_tip_extruder/derivative_calculator_helpers.cc
  ink/strokes/internal/brush_tip_extruder/directed_partial_outline.cc
  ink/strokes/internal/brush_tip_extruder/extruded_vertex.cc
  ink/strokes/internal/brush_tip_extruder/find_clockwise_winding_segment.cc
  ink/strokes/internal/brush_tip_extruder/geometry.cc
  ink/strokes/internal/brush_tip_extruder/mutable_mesh_view.cc
  ink/strokes/internal/brush_tip_extruder/simplify.cc
  ink/strokes/internal/jni/in_progress_stroke_jni.cc
  ink/strokes/internal/jni/in_progress_stroke_native.cc
  ink/strokes/internal/jni/in_progress_stroke_native_helper.cc
  ink/strokes/internal/jni/mesh_creation_jni.cc
  ink/strokes/internal/jni/mesh_creation_native.cc
  ink/strokes/internal/jni/stroke_input_batch_jni.cc
  ink/strokes/internal/jni/stroke_input_batch_native.cc
  ink/strokes/internal/jni/stroke_input_jni_helper.cc
  ink/strokes/internal/jni/stroke_jni.cc
  ink/strokes/internal/jni/stroke_native.cc
  ink/strokes/internal/stroke_input_modeler/passthrough_input_modeler.cc
  ink/strokes/internal/stroke_input_modeler/sliding_window_input_modeler.cc
  ink/types/duration.cc
  ink/types/physical_distance.cc
)
list(TRANSFORM ink_sources PREPEND "${INK_SOURCE_DIR}/")

add_library(ink SHARED ${ink_sources} ${proto_sources})
target_include_directories(ink PRIVATE "${INK_SOURCE_DIR}" "${proto_out}")
target_include_directories(ink SYSTEM PRIVATE "${JNI_INCLUDE_DIR}" "${JNI_MD_INCLUDE_DIR}")
target_link_libraries(ink PRIVATE
  byteink_zlib byteink_libtess2
  protobuf::libprotobuf
  absl::algorithm_container absl::core_headers absl::nullability absl::cleanup absl::btree
  absl::flat_hash_map absl::flat_hash_set absl::inlined_vector absl::function_ref
  absl::overload absl::absl_check absl::absl_log absl::log absl::bits absl::status
  absl::status_macros absl::statusor absl::str_format absl::strings absl::string_view
  absl::synchronization absl::time absl::span)
set(version_script "${INK_SOURCE_DIR}/ink/jni/jni.lds")
target_link_options(ink PRIVATE
  -Wl,--gc-sections -Wl,-z,relro,-z,now -Wl,--build-id=md5 -Wl,--hash-style=gnu
  "-Wl,--version-script=${version_script}")
set_target_properties(ink PROPERTIES LINK_DEPENDS "${version_script}")
