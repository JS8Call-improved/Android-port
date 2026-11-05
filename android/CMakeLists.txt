cmake_minimum_required(VERSION 3.22 FATAL_ERROR)

#------------------------------------------------------------------------------#
# JS8Call Android Build Configuration
#
# This CMake configuration builds the complete JS8Call stack for Android:
#   - libjs8core (platform-agnostic core)
#   - libjs8core-android-adapter (Android platform adapters)
#   - libjs8core-jni (JNI bridge for Java/Kotlin)
#
# Building:
#   From Android Studio: Configure in build.gradle
#   Standalone: Use Android NDK toolchain file
#------------------------------------------------------------------------------#

project(JS8Call-Android VERSION 2.5.0 LANGUAGES C CXX)

# Require C++20
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Android-specific settings
if(ANDROID)
  message(STATUS "Building for Android ${ANDROID_PLATFORM} (ABI: ${ANDROID_ABI})")

  # Enable exceptions and RTTI
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")

  # LTO adds -fuse-ld=gold with NDK Clang; disable to keep macOS builds working.
  if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_INTERPROCEDURAL_OPTIMIZATION OFF)
  endif()
else()
  message(WARNING "Not building for Android - some features may not work")
endif()

#------------------------------------------------------------------------------#
# Find dependencies
#------------------------------------------------------------------------------#

# Oboe (required for Android)
if(ANDROID)
  find_package(oboe REQUIRED CONFIG)
  message(STATUS "Found Oboe: ${oboe_VERSION}")
endif()

#------------------------------------------------------------------------------#
# Add subdirectories
#------------------------------------------------------------------------------#

# Core library (platform-agnostic)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../core ${CMAKE_CURRENT_BINARY_DIR}/core)

# Android adapters
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../adapters/android ${CMAKE_CURRENT_BINARY_DIR}/adapters/android)

# JNI bridge
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../adapters/android/jni ${CMAKE_CURRENT_BINARY_DIR}/jni)

#------------------------------------------------------------------------------#
# Installation
#------------------------------------------------------------------------------#

# Install libraries to jniLibs directory for Android Studio
if(ANDROID)
  install(TARGETS js8core-jni
    LIBRARY DESTINATION ${CMAKE_CURRENT_SOURCE_DIR}/js8core-lib/src/main/jniLibs/${ANDROID_ABI}
  )
endif()

#------------------------------------------------------------------------------#
# Summary
#------------------------------------------------------------------------------#

message(STATUS "")
message(STATUS "==================================================")
message(STATUS "JS8Call Android Build Configuration Summary")
message(STATUS "==================================================")
message(STATUS "  Version: ${PROJECT_VERSION}")
message(STATUS "  Build type: ${CMAKE_BUILD_TYPE}")
if(ANDROID)
  message(STATUS "  Android ABI: ${ANDROID_ABI}")
  message(STATUS "  Android Platform: ${ANDROID_PLATFORM}")
  message(STATUS "  Android STL: ${ANDROID_STL}")
endif()
message(STATUS "  C++ Standard: ${CMAKE_CXX_STANDARD}")
if(ANDROID)
  message(STATUS "  Oboe: ${oboe_VERSION}")
endif()
message(STATUS "==================================================")
message(STATUS "")
