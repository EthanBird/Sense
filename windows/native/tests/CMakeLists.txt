add_executable(
    SenseCoreTests
    decoder_tests.cpp
)

target_link_libraries(SenseCoreTests PRIVATE SenseCore)

target_compile_definitions(
    SenseCoreTests
    PRIVATE
        UNICODE
        _UNICODE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
)

if(MSVC)
    target_compile_options(
        SenseCoreTests
        PRIVATE
            /W4
            /permissive-
            /utf-8
            /EHsc
    )
endif()

add_test(
    NAME SenseCoreTests
    COMMAND SenseCoreTests "${SENSE_PINYIN_LEXICON}"
)

add_executable(
    SenseTsfSmokeTests
    tsf_smoke_tests.cpp
)

target_include_directories(
    SenseTsfSmokeTests
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/../tsf/include"
)

target_compile_definitions(
    SenseTsfSmokeTests
    PRIVATE
        UNICODE
        _UNICODE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
)

if(MSVC)
    target_compile_options(
        SenseTsfSmokeTests
        PRIVATE
            /W4
            /permissive-
            /utf-8
            /EHsc
    )
endif()

target_link_libraries(SenseTsfSmokeTests PRIVATE ole32 uuid)
add_dependencies(SenseTsfSmokeTests SenseTsf)

add_test(
    NAME SenseTsfSmokeTests
    COMMAND SenseTsfSmokeTests "$<TARGET_FILE:SenseTsf>"
)
