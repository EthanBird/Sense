cmake_minimum_required(VERSION 3.21)

project(
    SenseWindows
    VERSION 0.1.0
    DESCRIPTION "Sense Windows Text Services Framework input method"
    LANGUAGES CXX RC
)

if(NOT WIN32)
    message(FATAL_ERROR "Sense Windows requires a Windows toolchain.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

option(SENSE_BUILD_TESTS "Build the native decoder and protocol tests" ON)

set(SENSE_REPOSITORY_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(
    SENSE_PINYIN_LEXICON
    "${SENSE_REPOSITORY_ROOT}/ime-service/src/main/assets/pinyin_lexicon.bin"
    CACHE FILEPATH
    "Path to the version 3 SPLX pinyin lexicon"
)

if(NOT EXISTS "${SENSE_PINYIN_LEXICON}")
    message(FATAL_ERROR "Sense pinyin lexicon is missing: ${SENSE_PINYIN_LEXICON}")
endif()

add_subdirectory(native/core)
add_subdirectory(native/tsf)

if(SENSE_BUILD_TESTS)
    include(CTest)
    enable_testing()
    add_subdirectory(native/tests)
endif()
