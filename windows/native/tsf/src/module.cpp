#include "sense_tsf/module.h"

#include <array>

namespace sense::tsf {

HINSTANCE g_module_instance = nullptr;
std::atomic<long> g_server_locks = 0;
std::atomic<long> g_live_objects = 0;

std::filesystem::path ModulePath() {
    std::array<wchar_t, 32'768> buffer{};
    const DWORD length = GetModuleFileNameW(
        g_module_instance,
        buffer.data(),
        static_cast<DWORD>(buffer.size())
    );
    if (length == 0 || length >= buffer.size()) {
        return {};
    }
    return std::filesystem::path(
        std::wstring_view(buffer.data(), static_cast<std::size_t>(length))
    );
}

std::filesystem::path ModuleDirectory() {
    return ModulePath().parent_path();
}

}  // namespace sense::tsf

BOOL APIENTRY DllMain(HINSTANCE instance, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        sense::tsf::g_module_instance = instance;
        DisableThreadLibraryCalls(instance);
    }
    return TRUE;
}
