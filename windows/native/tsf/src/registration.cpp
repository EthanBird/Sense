#include "sense_tsf/module.h"

#include <Windows.h>
#include <msctf.h>
#include <wrl/client.h>

#include <array>
#include <string>
#include <string_view>

namespace sense::tsf {
namespace {

const std::array<GUID, 6> kSupportedCategories = {
    GUID_TFCAT_TIP_KEYBOARD,
    GUID_TFCAT_TIPCAP_UIELEMENTENABLED,
    GUID_TFCAT_TIPCAP_SECUREMODE,
    GUID_TFCAT_TIPCAP_INPUTMODECOMPARTMENT,
    GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT,
    GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT,
};

HRESULT SetStringValue(
    HKEY key,
    const wchar_t* name,
    std::wstring_view value
) {
    const DWORD size =
        static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t));
    const LONG result = RegSetValueExW(
        key,
        name,
        0,
        REG_SZ,
        reinterpret_cast<const BYTE*>(value.data()),
        size
    );
    return HRESULT_FROM_WIN32(result);
}

std::wstring ClassRegistryPath() {
    wchar_t guid[64]{};
    if (StringFromGUID2(kTextServiceClsid, guid, ARRAYSIZE(guid)) == 0) {
        return {};
    }
    return std::wstring(L"CLSID\\") + guid;
}

HRESULT RegisterComServer() {
    const std::wstring registry_path = ClassRegistryPath();
    const std::filesystem::path module_path = ModulePath();
    if (registry_path.empty() || module_path.empty()) {
        return E_FAIL;
    }

    HKEY class_key = nullptr;
    LONG result = RegCreateKeyExW(
        HKEY_CLASSES_ROOT,
        registry_path.c_str(),
        0,
        nullptr,
        REG_OPTION_NON_VOLATILE,
        KEY_WRITE,
        nullptr,
        &class_key,
        nullptr
    );
    if (result != ERROR_SUCCESS) {
        return HRESULT_FROM_WIN32(result);
    }
    HRESULT status = SetStringValue(class_key, nullptr, kServiceDescription);
    if (SUCCEEDED(status)) {
        HKEY server_key = nullptr;
        result = RegCreateKeyExW(
            class_key,
            L"InprocServer32",
            0,
            nullptr,
            REG_OPTION_NON_VOLATILE,
            KEY_WRITE,
            nullptr,
            &server_key,
            nullptr
        );
        if (result == ERROR_SUCCESS) {
            status = SetStringValue(
                server_key,
                nullptr,
                module_path.native()
            );
            if (SUCCEEDED(status)) {
                status = SetStringValue(
                    server_key,
                    L"ThreadingModel",
                    L"Apartment"
                );
            }
            RegCloseKey(server_key);
        } else {
            status = HRESULT_FROM_WIN32(result);
        }
    }
    RegCloseKey(class_key);
    return status;
}

void UnregisterComServer() {
    const std::wstring registry_path = ClassRegistryPath();
    if (!registry_path.empty()) {
        RegDeleteTreeW(HKEY_CLASSES_ROOT, registry_path.c_str());
    }
}

HRESULT RegisterProfile() {
    Microsoft::WRL::ComPtr<ITfInputProcessorProfileMgr> manager;
    HRESULT result = CoCreateInstance(
        CLSID_TF_InputProcessorProfiles,
        nullptr,
        CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&manager)
    );
    if (FAILED(result)) {
        return result;
    }
    const std::wstring module_path = ModulePath().native();
    constexpr ULONG description_length =
        static_cast<ULONG>(ARRAYSIZE(kServiceDescription) - 1);
    return manager->RegisterProfile(
        kTextServiceClsid,
        kSimplifiedChineseLanguageId,
        kLanguageProfileGuid,
        kServiceDescription,
        description_length,
        module_path.c_str(),
        static_cast<ULONG>(module_path.size()),
        static_cast<UINT>(-static_cast<int>(kProfileIconResourceId)),
        nullptr,
        0,
        TRUE,
        0
    );
}

void UnregisterProfile() {
    Microsoft::WRL::ComPtr<ITfInputProcessorProfileMgr> manager;
    if (SUCCEEDED(CoCreateInstance(
            CLSID_TF_InputProcessorProfiles,
            nullptr,
            CLSCTX_INPROC_SERVER,
            IID_PPV_ARGS(&manager)
        ))) {
        manager->UnregisterProfile(
            kTextServiceClsid,
            kSimplifiedChineseLanguageId,
            kLanguageProfileGuid,
            0
        );
    }
}

HRESULT RegisterCategories() {
    Microsoft::WRL::ComPtr<ITfCategoryMgr> manager;
    HRESULT result = CoCreateInstance(
        CLSID_TF_CategoryMgr,
        nullptr,
        CLSCTX_INPROC_SERVER,
        IID_PPV_ARGS(&manager)
    );
    if (FAILED(result)) {
        return result;
    }
    for (const GUID& category : kSupportedCategories) {
        result = manager->RegisterCategory(
            kTextServiceClsid,
            category,
            kTextServiceClsid
        );
        if (FAILED(result)) {
            return result;
        }
    }
    return S_OK;
}

void UnregisterCategories() {
    Microsoft::WRL::ComPtr<ITfCategoryMgr> manager;
    if (FAILED(CoCreateInstance(
            CLSID_TF_CategoryMgr,
            nullptr,
            CLSCTX_INPROC_SERVER,
            IID_PPV_ARGS(&manager)
        ))) {
        return;
    }
    for (const GUID& category : kSupportedCategories) {
        manager->UnregisterCategory(
            kTextServiceClsid,
            category,
            kTextServiceClsid
        );
    }
}

class ComScope final {
public:
    ComScope() : result_(CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED)) {}

    ~ComScope() {
        if (SUCCEEDED(result_)) {
            CoUninitialize();
        }
    }

    [[nodiscard]] HRESULT result() const {
        return result_ == RPC_E_CHANGED_MODE ? S_OK : result_;
    }

private:
    HRESULT result_;
};

}  // namespace
}  // namespace sense::tsf

extern "C" HRESULT __stdcall DllRegisterServer() {
    sense::tsf::ComScope com;
    if (FAILED(com.result())) {
        return com.result();
    }
    HRESULT result = sense::tsf::RegisterComServer();
    if (SUCCEEDED(result)) {
        result = sense::tsf::RegisterProfile();
    }
    if (SUCCEEDED(result)) {
        result = sense::tsf::RegisterCategories();
    }
    if (FAILED(result)) {
        sense::tsf::UnregisterCategories();
        sense::tsf::UnregisterProfile();
        sense::tsf::UnregisterComServer();
    }
    return result;
}

extern "C" HRESULT __stdcall DllUnregisterServer() {
    sense::tsf::ComScope com;
    if (SUCCEEDED(com.result())) {
        sense::tsf::UnregisterCategories();
        sense::tsf::UnregisterProfile();
    }
    sense::tsf::UnregisterComServer();
    return S_OK;
}
