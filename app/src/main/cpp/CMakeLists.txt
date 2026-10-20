# libneutts_jni.so: NeuTTS Nano runtime (llama.cpp backbone, eSpeak NG phonemes, NeuCodec decoder through the
# sherpa-onnx AAR's libonnxruntime.so). Sources are the pinned zips in third_party/native-source (fetch-sources.ps1);
# a missing zip is downloaded from its pinned URL and checked against the same SHA-256.
cmake_minimum_required(VERSION 3.22)
project(neutts_jni C CXX)

set(LV_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../..)
set(LV_NATIVE_SOURCE ${LV_ROOT}/third_party/native-source)
# Extracted at configure time without FetchContent: its sub-build adds path depth that breaks Windows' 260-char limit.
function(lv_pinned_source var file url sha256)
  set(zip ${LV_NATIVE_SOURCE}/${file})
  if(NOT EXISTS ${zip})
    set(zip ${CMAKE_CURRENT_BINARY_DIR}/${file})
    if(NOT EXISTS ${zip})
      file(DOWNLOAD ${url} ${zip} EXPECTED_HASH SHA256=${sha256} TLS_VERIFY ON)
    endif()
  endif()
  file(SHA256 ${zip} actual)
  if(NOT actual STREQUAL sha256)
    message(FATAL_ERROR "${zip}: SHA-256 ${actual}, expected ${sha256}")
  endif()
  string(SUBSTRING ${sha256} 0 8 tag)
  set(root ${CMAKE_CURRENT_BINARY_DIR}/s${tag})
  if(NOT EXISTS ${root}/src)
    file(REMOVE_RECURSE ${root})
    file(ARCHIVE_EXTRACT INPUT ${zip} DESTINATION ${root}/tmp)
    file(GLOB top LIST_DIRECTORIES true ${root}/tmp/*)
    file(RENAME ${top} ${root}/src)  # drop the 50-char "<repo>-<commit>" top directory
    file(REMOVE_RECURSE ${root}/tmp)
  endif()
  set(${var} ${root}/src PARENT_SCOPE)
endfunction()

lv_pinned_source(LV_LLAMA llama.cpp-412a363c655b479279d3050d0d1c67a5733697b4.zip
  https://codeload.github.com/ggml-org/llama.cpp/zip/412a363c655b479279d3050d0d1c67a5733697b4
  252c63125e87d2e8d0ddd6f622243f75c1764a54c32047fe67b67698dc5f4788)
lv_pinned_source(LV_ESPEAK espeak-ng-69bfd1efde0236654ccc117a28d04aef27673a30.zip
  https://codeload.github.com/espeak-ng/espeak-ng/zip/69bfd1efde0236654ccc117a28d04aef27673a30
  ffa1a5453f556c4361abc3f41adb04212f3eefb9402460c2109638be1f8143e2)

# llama.cpp b11729: static, CPU only, no OpenMP (ggml thread pool), no tools.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(option LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER
        LLAMA_BUILD_APP LLAMA_OPENSSL LLAMA_CURL GGML_NATIVE GGML_OPENMP GGML_BACKEND_DL)
  set(${option} OFF CACHE BOOL "" FORCE)
endforeach()
if(ANDROID_ABI STREQUAL "arm64-v8a")
  # Tensor G2 and newer: dotprod+fp16 (the variant the official b11729 Android build selects on Pixel 7 Pro).
  # The JNI load refuses CPUs without asimddp before any ggml code runs.
  set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
add_subdirectory(${LV_LLAMA} l EXCLUDE_FROM_ALL)

# eSpeak NG 69bfd1ef (the commit the neutts 1.4.1 wheel bundles): library sources only; its own CMake project
# would git-clone libsonic at configure time.
set(ESPEAK_SRC ${LV_ESPEAK}/src)
set(HAVE_MKSTEMP 1)
foreach(feature USE_ASYNC USE_KLATT USE_LIBPCAUDIO USE_LIBSONIC USE_MBROLA USE_SPEECHPLAYER)
  set(${feature} 0)
endforeach()
set(PROJECT_VERSION 1.52.0.1)
configure_file(${ESPEAK_SRC}/libespeak-ng/config.h.in ${CMAKE_CURRENT_BINARY_DIR}/espeak-config/config.h)
add_library(lv_espeak STATIC
  ${ESPEAK_SRC}/ucd-tools/src/case.c ${ESPEAK_SRC}/ucd-tools/src/categories.c ${ESPEAK_SRC}/ucd-tools/src/ctype.c
  ${ESPEAK_SRC}/ucd-tools/src/proplist.c ${ESPEAK_SRC}/ucd-tools/src/scripts.c ${ESPEAK_SRC}/ucd-tools/src/tostring.c)
foreach(file common mnemonics error ieee80 compiledata compiledict dictionary encoding intonation langopts numbers
        phoneme phonemelist readclause setlengths soundicon spect ssml synthdata synthesize tr_languages translate
        translateword voices wavegen speech espeak_api)
  target_sources(lv_espeak PRIVATE ${ESPEAK_SRC}/libespeak-ng/${file}.c)
endforeach()
target_include_directories(lv_espeak PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/espeak-config ${ESPEAK_SRC}/ucd-tools/src/include
  PUBLIC ${ESPEAK_SRC}/include)
target_compile_definitions(lv_espeak PUBLIC LIBESPEAK_NG_EXPORT=1 PRIVATE PATH_ESPEAK_DATA="/nonexistent")
target_compile_options(lv_espeak PRIVATE -fPIC -fvisibility=hidden -fno-exceptions -fwrapv -w)

# ONNX Runtime: link the exact libonnxruntime.so the sherpa-onnx AAR packages (symbol version VERS_1.28.2);
# Gradle packages that copy, so only one libonnxruntime.so ships.
set(LV_AAR ${LV_ROOT}/app/libs/sherpa-onnx-1.13.8.aar)
set(LV_ORT_DIR ${CMAKE_CURRENT_BINARY_DIR}/ort)
if(NOT EXISTS ${LV_ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so)
  if(NOT EXISTS ${LV_AAR})
    message(FATAL_ERROR "Missing ${LV_AAR}: run fetch-runtime.ps1")
  endif()
  file(ARCHIVE_EXTRACT INPUT ${LV_AAR} DESTINATION ${LV_ORT_DIR} PATTERNS jni/${ANDROID_ABI}/libonnxruntime.so)
endif()
add_library(lv_onnxruntime SHARED IMPORTED)
set_target_properties(lv_onnxruntime PROPERTIES IMPORTED_LOCATION ${LV_ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so
  IMPORTED_NO_SONAME OFF)

add_library(neutts_jni SHARED neutts_jni.cpp)
target_include_directories(neutts_jni PRIVATE ${LV_ROOT}/third_party/onnxruntime-c-api-1.28.2 ${LV_LLAMA}/src)
target_link_libraries(neutts_jni PRIVATE llama lv_espeak lv_onnxruntime log)
target_compile_options(neutts_jni PRIVATE -fvisibility=hidden)
# Keep llama/ggml/eSpeak symbols private so they never interpose on sherpa-onnx's own eSpeak copy.
target_link_options(neutts_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384)
