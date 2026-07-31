add_library(
    SenseTsf SHARED
    src/candidate_ui.cpp
    src/candidate_window.cpp
    src/edit_session.cpp
    src/exports.cpp
    src/module.cpp
    src/registration.cpp
    src/text_service.cpp
    resources/sense_tsf.rc
    SenseTsf.def
)

target_include_directories(
    SenseTsf
    PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

target_compile_definitions(
    SenseTsf
    PRIVATE
        UNICODE
        _UNICODE
        WIN32_LEAN_AND_MEAN
        NOMINMAX
        SENSE_TSF_EXPORTS
)

if(MSVC)
    target_compile_options(
        SenseTsf
        PRIVATE
            /W4
            /permissive-
            /utf-8
            /EHsc
    )
    target_link_options(SenseTsf PRIVATE /DYNAMICBASE /NXCOMPAT)
endif()

target_link_libraries(
    SenseTsf
    PRIVATE
        SenseCore
        ole32
        oleaut32
        uuid
        shlwapi
        dwmapi
        user32
        gdi32
        advapi32
)

set_target_properties(
    SenseTsf
    PROPERTIES
        OUTPUT_NAME "SenseTsf"
        PREFIX ""
)

add_custom_command(
    TARGET SenseTsf
    POST_BUILD
    COMMAND
        "${CMAKE_COMMAND}" -E make_directory
        "$<TARGET_FILE_DIR:SenseTsf>/data"
    COMMAND
        "${CMAKE_COMMAND}" -E copy_if_different
        "${SENSE_PINYIN_LEXICON}"
        "$<TARGET_FILE_DIR:SenseTsf>/data/pinyin_lexicon.bin"
    COMMAND
        "${CMAKE_COMMAND}" -E copy_if_different
        "${SENSE_REPOSITORY_ROOT}/ime-service/src/main/assets/LICENSE.txt"
        "$<TARGET_FILE_DIR:SenseTsf>/data/LICENSE.txt"
    COMMAND
        "${CMAKE_COMMAND}" -E copy_if_different
        "${SENSE_REPOSITORY_ROOT}/ime-service/src/main/assets/NOTICE.txt"
        "$<TARGET_FILE_DIR:SenseTsf>/data/NOTICE.txt"
    VERBATIM
)
