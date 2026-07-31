#include "sense_tsf/edit_session.h"

#include <Windows.h>

#include <utility>

namespace sense::tsf {

LambdaEditSession::LambdaEditSession(IUnknown* owner, Callback callback)
    : owner_(owner), callback_(std::move(callback)) {
    if (owner_ != nullptr) {
        owner_->AddRef();
    }
}

LambdaEditSession::~LambdaEditSession() {
    if (owner_ != nullptr) {
        owner_->Release();
        owner_ = nullptr;
    }
}

HRESULT LambdaEditSession::QueryInterface(REFIID iid, void** object) {
    if (object == nullptr) {
        return E_INVALIDARG;
    }
    *object = nullptr;
    if (iid == IID_IUnknown || iid == IID_ITfEditSession) {
        *object = static_cast<ITfEditSession*>(this);
        AddRef();
        return S_OK;
    }
    return E_NOINTERFACE;
}

ULONG LambdaEditSession::AddRef() {
    return static_cast<ULONG>(InterlockedIncrement(&references_));
}

ULONG LambdaEditSession::Release() {
    const ULONG remaining =
        static_cast<ULONG>(InterlockedDecrement(&references_));
    if (remaining == 0) {
        delete this;
    }
    return remaining;
}

HRESULT LambdaEditSession::DoEditSession(TfEditCookie cookie) {
    if (!callback_) {
        return E_UNEXPECTED;
    }
    return callback_(cookie);
}

}  // namespace sense::tsf
