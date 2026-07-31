#include "sense_tsf/module.h"
#include "sense_tsf/text_service.h"

#include <Windows.h>
#include <Unknwn.h>

#include <new>

namespace sense::tsf {
namespace {

class ClassFactory final : public IClassFactory {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(
        REFIID iid,
        void** object
    ) override {
        if (object == nullptr) {
            return E_INVALIDARG;
        }
        *object = nullptr;
        if (iid == IID_IUnknown || iid == IID_IClassFactory) {
            *object = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        return E_NOINTERFACE;
    }

    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&references_));
    }

    ULONG STDMETHODCALLTYPE Release() override {
        const ULONG remaining =
            static_cast<ULONG>(InterlockedDecrement(&references_));
        if (remaining == 0) {
            delete this;
        }
        return remaining;
    }

    HRESULT STDMETHODCALLTYPE CreateInstance(
        IUnknown* outer,
        REFIID iid,
        void** object
    ) override {
        if (object == nullptr) {
            return E_INVALIDARG;
        }
        *object = nullptr;
        if (outer != nullptr) {
            return CLASS_E_NOAGGREGATION;
        }
        auto* service = new (std::nothrow) TextService();
        if (service == nullptr) {
            return E_OUTOFMEMORY;
        }
        const HRESULT result = service->QueryInterface(iid, object);
        service->Release();
        return result;
    }

    HRESULT STDMETHODCALLTYPE LockServer(BOOL lock) override {
        if (lock != FALSE) {
            ++g_server_locks;
        } else {
            --g_server_locks;
        }
        return S_OK;
    }

private:
    ~ClassFactory() = default;

    ObjectLifetime lifetime_;
    volatile long references_ = 1;
};

}  // namespace
}  // namespace sense::tsf

extern "C" HRESULT __stdcall DllCanUnloadNow() {
    return sense::tsf::g_live_objects.load() == 0 &&
            sense::tsf::g_server_locks.load() == 0
        ? S_OK
        : S_FALSE;
}

extern "C" HRESULT __stdcall DllGetClassObject(
    REFCLSID class_id,
    REFIID iid,
    void** object
) {
    if (object == nullptr) {
        return E_INVALIDARG;
    }
    *object = nullptr;
    if (class_id != sense::tsf::kTextServiceClsid) {
        return CLASS_E_CLASSNOTAVAILABLE;
    }
    auto* factory = new (std::nothrow) sense::tsf::ClassFactory();
    if (factory == nullptr) {
        return E_OUTOFMEMORY;
    }
    const HRESULT result = factory->QueryInterface(iid, object);
    factory->Release();
    return result;
}
