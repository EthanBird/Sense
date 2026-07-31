#pragma once

#include <msctf.h>

#include <functional>

namespace sense::tsf {

class LambdaEditSession final : public ITfEditSession {
public:
    using Callback = std::function<HRESULT(TfEditCookie)>;

    LambdaEditSession(IUnknown* owner, Callback callback);

    HRESULT STDMETHODCALLTYPE QueryInterface(
        REFIID iid,
        void** object
    ) override;
    ULONG STDMETHODCALLTYPE AddRef() override;
    ULONG STDMETHODCALLTYPE Release() override;
    HRESULT STDMETHODCALLTYPE DoEditSession(TfEditCookie cookie) override;

private:
    ~LambdaEditSession();

    volatile long references_ = 1;
    IUnknown* owner_ = nullptr;
    Callback callback_;
};

}  // namespace sense::tsf
