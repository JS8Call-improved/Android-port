add_library(js8core STATIC EXCLUDE_FROM_ALL
  src/placeholder.cpp
  src/engine/engine.cpp
  src/decoder/legacy_decoder.cpp
  src/dsp/flatten.cpp
  src/dsp/resampler.cpp
  src/tx/modulator.cpp
  src/protocol/submode.cpp
  src/protocol/costas.cpp
  src/protocol/constants.cpp
  src/protocol/time_utils.cpp
  src/protocol/varicode.cpp
  src/protocol/jsc.cpp
  src/protocol/jsc_map.cpp
  src/protocol/jsc_list.cpp
)

add_library(js8core::js8core ALIAS js8core)

target_compile_features(js8core PUBLIC cxx_std_20)

target_include_directories(js8core
  PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
    $<INSTALL_INTERFACE:include>
)

target_include_directories(js8core
  PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}/../vendor
    ${Boost_INCLUDE_DIRS}
)

target_link_libraries(js8core
  PUBLIC
    ${FFTW3_LIBRARIES}
)

# Link Android log library if building for Android
if(ANDROID)
  target_link_libraries(js8core PUBLIC log)
endif()

add_executable(js8core-varicode-test EXCLUDE_FROM_ALL
  tools/varicode_roundtrip.cpp
)

target_link_libraries(js8core-varicode-test PRIVATE js8core)

add_executable(js8core-vox-test EXCLUDE_FROM_ALL
  tools/vox_modulator_test.cpp
  src/tx/modulator.cpp
)
target_compile_features(js8core-vox-test PRIVATE cxx_std_20)
target_include_directories(js8core-vox-test PRIVATE include)
