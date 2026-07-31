#pragma once

#include <Windows.h>

#include <atomic>
#include <filesystem>

namespace sense::tsf {

extern HINSTANCE g_module_instance;
extern std::atomic<long> g_server_locks;
extern std::atomic<long> g_live_objects;

// {D24D7D5D-A4B3-4C28-AD93-C04E0C6B3501}
inline constexpr CLSID kTextServiceClsid = {
    0xd24d7d5d,
    0xa4b3,
    0x4c28,
    {0xad, 0x93, 0xc0, 0x4e, 0x0c, 0x6b, 0x35, 0x01},
};

// {4B62FA54-922C-4CCB-91A7-BCAF8732CDA9}
inline constexpr GUID kLanguageProfileGuid = {
    0x4b62fa54,
    0x922c,
    0x4ccb,
    {0x91, 0xa7, 0xbc, 0xaf, 0x87, 0x32, 0xcd, 0xa9},
};

// {7E627E7B-CE07-44C4-91D4-65D36C6D1C14}
inline constexpr GUID kCandidateUiGuid = {
    0x7e627e7b,
    0xce07,
    0x44c4,
    {0x91, 0xd4, 0x65, 0xd3, 0x6c, 0x6d, 0x1c, 0x14},
};

inline constexpr LANGID kSimplifiedChineseLanguageId = 0x0804;
inline constexpr wchar_t kServiceDescription[] = L"Sense 先思输入法";
inline constexpr UINT kProfileIconResourceId = 101;

[[nodiscard]] std::filesystem::path ModulePath();
[[nodiscard]] std::filesystem::path ModuleDirectory();

class ObjectLifetime final {
public:
    ObjectLifetime() {
        ++g_live_objects;
    }

    ~ObjectLifetime() {
        --g_live_objects;
    }

    ObjectLifetime(const ObjectLifetime&) = delete;
    ObjectLifetime& operator=(const ObjectLifetime&) = delete;
};

}  // namespace sense::tsf
