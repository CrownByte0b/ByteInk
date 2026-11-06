# Builds google/ink's JNI library, libink.so, for Linux (x86_64 or arm64) without Bazel. Flatpak/Flathub
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

if(EMSCRIPTEN)
  # Native Wasm exception handling also unwinds C++ owners on JavaScript binding errors.
  add_compile_options(-fwasm-exceptions)
  add_link_options(-fwasm-exceptions)
endif()

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
# No fused multiply-add contraction, as in every byteink arm64 build (native/common.sh): GCC fuses
# a*b+c by default (-ffp-contract=fast in GNU mode) wherever the target has FMA, as aarch64 always
# does, and the result then rounds differently from x86_64's baseline builds. A no-op on x86_64.
add_compile_options(-ffp-contract=off)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF)

if(NOT EMSCRIPTEN)
  find_path(JNI_INCLUDE_DIR jni.h HINTS "$ENV{JAVA_HOME}/include" REQUIRED NO_DEFAULT_PATH)
  find_path(JNI_MD_INCLUDE_DIR jni_md.h HINTS "${JNI_INCLUDE_DIR}/linux" REQUIRED NO_DEFAULT_PATH)
endif()

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
if(EMSCRIPTEN)
  # protoc must run on the build host, using exactly the same pinned protobuf sources.
  if(NOT EXISTS "${WITH_PROTOC}")
    message(FATAL_ERROR "Set WITH_PROTOC to the host protobuf 33.5 protoc executable")
  endif()
  set(protobuf_BUILD_PROTOC_BINARIES OFF)
  set(protobuf_BUILD_LIBPROTOC OFF)
  set(protobuf_BUILD_LIBUPB OFF)
endif()
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

include("${CMAKE_CURRENT_LIST_DIR}/ink-sources.cmake")
if(EMSCRIPTEN)
  list(FILTER ink_sources EXCLUDE REGEX "/jni/")
  list(FILTER ink_sources EXCLUDE REGEX "^ink/rendering/(android|skia)/")
endif()
list(TRANSFORM ink_sources PREPEND "${INK_SOURCE_DIR}/")

if(EMSCRIPTEN)
  add_executable(ink ${ink_sources} ${proto_sources} "${CMAKE_CURRENT_LIST_DIR}/../wasm/bridge.cc"
    "${CMAKE_CURRENT_LIST_DIR}/../wasm/float_math.cc")
  target_compile_definitions(ink PRIVATE BYTEINK_ENGINE_COMMIT="${BYTEINK_ENGINE_COMMIT}")
  set_target_properties(ink PROPERTIES OUTPUT_NAME byteink-engine SUFFIX .mjs)
else()
  add_library(ink SHARED ${ink_sources} ${proto_sources})
  target_include_directories(ink SYSTEM PRIVATE "${JNI_INCLUDE_DIR}" "${JNI_MD_INCLUDE_DIR}")
endif()
target_include_directories(ink PRIVATE "${INK_SOURCE_DIR}" "${proto_out}")
target_link_libraries(ink PRIVATE
  byteink_zlib byteink_libtess2
  protobuf::libprotobuf
  absl::algorithm_container absl::core_headers absl::nullability absl::cleanup absl::btree
  absl::flat_hash_map absl::flat_hash_set absl::inlined_vector absl::function_ref
  absl::overload absl::absl_check absl::absl_log absl::log absl::bits absl::status
  absl::status_macros absl::statusor absl::str_format absl::strings absl::string_view
  absl::synchronization absl::time absl::span)
if(EMSCRIPTEN)
  target_link_options(ink PRIVATE
    -lembind --no-entry -sMODULARIZE=1 -sEXPORT_ES6=1 -sENVIRONMENT=web,worker,node
    -sALLOW_MEMORY_GROWTH=1 -sMAXIMUM_MEMORY=536870912 -sSTACK_SIZE=1048576
    -sFILESYSTEM=0 -sASSERTIONS=1)
else()
  set(version_script "${INK_SOURCE_DIR}/ink/jni/jni.lds")
  target_link_options(ink PRIVATE
    -Wl,--gc-sections -Wl,-z,relro,-z,now -Wl,--build-id=md5 -Wl,--hash-style=gnu
    "-Wl,--version-script=${version_script}")
  set_target_properties(ink PROPERTIES LINK_DEPENDS "${version_script}")
endif()
