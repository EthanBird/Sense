#include "sense_tsf/module.h"

#include <Windows.h>
#include <msctf.h>
#include <wrl/client.h>

#include <iostream>

namespace {

using GetClassObject = HRESULT(STDAPICALLTYPE*)(
    REFCLSID,
    REFIID,
    void**
);
using CanUnloadNow = HRESULT(STDAPICALLTYPE*)();

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) {
        std::wcerr << L"usage: SenseTsfSmokeTests <SenseTsf.dll>\n";
        return 2;
    }
    HMODULE module = LoadLibraryW(argv[1]);
    if (module == nullptr) {
        std::wcerr << L"LoadLibrary failed: " << GetLastError() << L'\n';
        return 1;
    }
    const auto get_class_object = reinterpret_cast<GetClassObject>(
        GetProcAddress(module, "DllGetClassObject")
    );
    const auto can_unload_now = reinterpret_cast<CanUnloadNow>(
        GetProcAddress(module, "DllCanUnloadNow")
    );
    if (get_class_object == nullptr || can_unload_now == nullptr) {
        std::cerr << "required COM exports are missing\n";
        FreeLibrary(module);
        return 1;
    }

    Microsoft::WRL::ComPtr<IClassFactory> factory;
    HRESULT result = get_class_object(
        sense::tsf::kTextServiceClsid,
        IID_PPV_ARGS(&factory)
    );
    if (FAILED(result)) {
        std::cerr << "class factory creation failed\n";
        FreeLibrary(module);
        return 1;
    }
    Microsoft::WRL::ComPtr<ITfTextInputProcessorEx> service;
    result = factory->CreateInstance(
        nullptr,
        IID_PPV_ARGS(&service)
    );
    if (FAILED(result)) {
        std::cerr << "text service creation failed\n";
        factory.Reset();
        FreeLibrary(module);
        return 1;
    }
    Microsoft::WRL::ComPtr<ITfKeyEventSink> key_sink;
    result = service.As(&key_sink);
    if (FAILED(result)) {
        std::cerr << "key event sink interface is missing\n";
        service.Reset();
        factory.Reset();
        FreeLibrary(module);
        return 1;
    }

    key_sink.Reset();
    service.Reset();
    factory.Reset();
    if (can_unload_now() != S_OK) {
        std::cerr << "COM lifetime count did not return to zero\n";
        FreeLibrary(module);
        return 1;
    }
    FreeLibrary(module);
    std::cout << "SenseTsfSmokeTests passed\n";
    return 0;
}
