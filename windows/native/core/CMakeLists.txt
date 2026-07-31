add_library(
    SenseCore STATIC
    src/pinyin_lexicon.cpp
    src/input_session.cpp
    src/agent_protocol.cpp
)

target_include_directories(
    SenseCore
    PUBLIC
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

target_compile_definitions(
    SenseCore
    PRIVATE
        UNICODE
        _UNICODE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
)

if(MSVC)
    target_compile_options(
        SenseCore
        PRIVATE
            /W4
            /permissive-
            /utf-8
            /EHsc
    )
endif()

target_link_libraries(SenseCore PUBLIC kernel32)
