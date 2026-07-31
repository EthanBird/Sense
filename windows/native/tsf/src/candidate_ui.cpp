#include "sense_tsf/candidate_ui.h"

#include <OleAuto.h>

#include <algorithm>
#include <utility>

namespace sense::tsf {

CandidateUi::CandidateUi(
    SelectionHandler on_selection,
    ActionHandler on_finalize,
    ActionHandler on_abort
)
    : on_selection_(std::move(on_selection)),
      on_finalize_(std::move(on_finalize)),
      on_abort_(std::move(on_abort)) {}

CandidateUi::~CandidateUi() {
    End();
}

HRESULT CandidateUi::QueryInterface(REFIID iid, void** object) {
    if (object == nullptr) {
        return E_INVALIDARG;
    }
    *object = nullptr;
    if (iid == IID_IUnknown ||
        iid == IID_ITfUIElement ||
        iid == IID_ITfCandidateListUIElement ||
        iid == IID_ITfCandidateListUIElementBehavior) {
        *object = static_cast<ITfCandidateListUIElementBehavior*>(this);
        AddRef();
        return S_OK;
    }
    return E_NOINTERFACE;
}

ULONG CandidateUi::AddRef() {
    return static_cast<ULONG>(InterlockedIncrement(&references_));
}

ULONG CandidateUi::Release() {
    const ULONG remaining =
        static_cast<ULONG>(InterlockedDecrement(&references_));
    if (remaining == 0) {
        delete this;
    }
    return remaining;
}

HRESULT CandidateUi::GetDescription(BSTR* description) {
    if (description == nullptr) {
        return E_INVALIDARG;
    }
    constexpr wchar_t value[] = L"Sense 候选词";
    *description = SysAllocStringLen(value, ARRAYSIZE(value) - 1);
    return *description != nullptr ? S_OK : E_OUTOFMEMORY;
}

HRESULT CandidateUi::GetGUID(GUID* guid) {
    if (guid == nullptr) {
        return E_INVALIDARG;
    }
    *guid = kCandidateUiGuid;
    return S_OK;
}

HRESULT CandidateUi::Show(BOOL show) {
    requested_visible_ = show != FALSE;
    window_.Show(service_should_show_ != FALSE && requested_visible_);
    return S_OK;
}

HRESULT CandidateUi::IsShown(BOOL* shown) {
    if (shown == nullptr) {
        return E_INVALIDARG;
    }
    *shown = window_.is_visible() ? TRUE : FALSE;
    return S_OK;
}

HRESULT CandidateUi::GetUpdatedFlags(DWORD* flags) {
    if (flags == nullptr) {
        return E_INVALIDARG;
    }
    *flags = updated_flags_;
    return S_OK;
}

HRESULT CandidateUi::GetDocumentMgr(ITfDocumentMgr** document_manager) {
    if (document_manager == nullptr) {
        return E_INVALIDARG;
    }
    *document_manager = document_manager_.Get();
    if (*document_manager == nullptr) {
        return E_FAIL;
    }
    (*document_manager)->AddRef();
    return S_OK;
}

HRESULT CandidateUi::GetCount(UINT* count) {
    if (count == nullptr) {
        return E_INVALIDARG;
    }
    *count = static_cast<UINT>(candidates_.size());
    return S_OK;
}

HRESULT CandidateUi::GetSelection(UINT* index) {
    if (index == nullptr) {
        return E_INVALIDARG;
    }
    if (candidates_.empty()) {
        *index = 0;
        return S_FALSE;
    }
    *index = static_cast<UINT>(selection_);
    return S_OK;
}

HRESULT CandidateUi::GetString(UINT index, BSTR* value) {
    if (value == nullptr) {
        return E_INVALIDARG;
    }
    *value = nullptr;
    if (index >= candidates_.size()) {
        return E_INVALIDARG;
    }
    const std::wstring& candidate = candidates_[index];
    *value = SysAllocStringLen(
        candidate.data(),
        static_cast<UINT>(candidate.size())
    );
    return *value != nullptr ? S_OK : E_OUTOFMEMORY;
}

HRESULT CandidateUi::GetPageIndex(
    UINT* indices,
    UINT capacity,
    UINT* page_count
) {
    if (page_count == nullptr || (capacity > 0 && indices == nullptr)) {
        return E_INVALIDARG;
    }
    const std::size_t pages =
        candidates_.empty()
        ? 0
        : (candidates_.size() + kPageSize - 1) / kPageSize;
    *page_count = static_cast<UINT>(pages);
    const std::size_t writable = (std::min)(
        pages,
        static_cast<std::size_t>(capacity)
    );
    for (std::size_t page = 0; page < writable; ++page) {
        indices[page] = static_cast<UINT>(page * kPageSize);
    }
    return writable == pages ? S_OK : S_FALSE;
}

HRESULT CandidateUi::SetPageIndex(UINT* indices, UINT page_count) {
    if (page_count == 0) {
        return S_OK;
    }
    if (indices == nullptr) {
        return E_INVALIDARG;
    }
    for (UINT page = 0; page < page_count; ++page) {
        if (indices[page] >= candidates_.size()) {
            return E_INVALIDARG;
        }
        if (page > 0 && indices[page] <= indices[page - 1]) {
            return E_INVALIDARG;
        }
    }
    return S_OK;
}

HRESULT CandidateUi::GetCurrentPage(UINT* page) {
    if (page == nullptr) {
        return E_INVALIDARG;
    }
    *page = candidates_.empty()
        ? 0
        : static_cast<UINT>(selection_ / kPageSize);
    return S_OK;
}

HRESULT CandidateUi::SetSelection(UINT index) {
    if (index >= candidates_.size()) {
        return E_INVALIDARG;
    }
    selection_ = index;
    if (on_selection_) {
        on_selection_(selection_);
    }
    Notify(TF_CLUIE_SELECTION | TF_CLUIE_CURRENTPAGE);
    return S_OK;
}

HRESULT CandidateUi::Finalize() {
    if (candidates_.empty()) {
        return S_FALSE;
    }
    if (on_finalize_) {
        on_finalize_();
    }
    return S_OK;
}

HRESULT CandidateUi::Abort() {
    if (on_abort_) {
        on_abort_();
    }
    return S_OK;
}

HRESULT CandidateUi::Begin(
    ITfThreadMgr* thread_manager,
    ITfDocumentMgr* document_manager
) {
    if (thread_manager == nullptr || document_manager == nullptr) {
        return E_INVALIDARG;
    }
    if (active()) {
        if (document_manager_.Get() != document_manager) {
            document_manager_ = document_manager;
        }
        return S_OK;
    }

    thread_manager_ = thread_manager;
    document_manager_ = document_manager;
    HRESULT result = thread_manager_->QueryInterface(
        IID_PPV_ARGS(ui_element_manager_.ReleaseAndGetAddressOf())
    );
    if (FAILED(result)) {
        End();
        return result;
    }
    service_should_show_ = TRUE;
    result = ui_element_manager_->BeginUIElement(
        static_cast<ITfUIElement*>(this),
        &service_should_show_,
        &element_id_
    );
    if (FAILED(result)) {
        End();
        return result;
    }

    if (service_should_show_ != FALSE) {
        if (!window_.Create([this](std::size_t index) {
                if (SUCCEEDED(SetSelection(static_cast<UINT>(index)))) {
                    Finalize();
                }
            })) {
            End();
            return E_FAIL;
        }
    }
    requested_visible_ = true;
    updated_flags_ =
        TF_CLUIE_DOCUMENTMGR | TF_CLUIE_COUNT | TF_CLUIE_SELECTION |
        TF_CLUIE_STRING | TF_CLUIE_PAGEINDEX | TF_CLUIE_CURRENTPAGE;
    return S_OK;
}

void CandidateUi::Update(
    std::wstring_view composing,
    const std::vector<sense::Candidate>& candidates,
    std::size_t selection,
    const RECT& anchor
) {
    composing_.assign(composing);
    candidates_.clear();
    candidates_.reserve(candidates.size());
    for (const auto& candidate : candidates) {
        candidates_.push_back(candidate.text);
    }
    selection_ = candidates_.empty()
        ? 0
        : (std::min)(selection, candidates_.size() - 1);
    updated_flags_ =
        TF_CLUIE_COUNT | TF_CLUIE_SELECTION | TF_CLUIE_STRING |
        TF_CLUIE_PAGEINDEX | TF_CLUIE_CURRENTPAGE;

    if (service_should_show_ != FALSE) {
        window_.Update(
            composing_,
            candidates_,
            selection_,
            anchor
        );
        window_.Show(requested_visible_);
    }
    Notify(updated_flags_);
}

void CandidateUi::End() {
    window_.Destroy();
    if (ui_element_manager_ != nullptr &&
        element_id_ != static_cast<DWORD>(-1)) {
        ui_element_manager_->EndUIElement(element_id_);
    }
    element_id_ = static_cast<DWORD>(-1);
    updated_flags_ = 0;
    candidates_.clear();
    composing_.clear();
    document_manager_.Reset();
    ui_element_manager_.Reset();
    thread_manager_.Reset();
}

bool CandidateUi::active() const noexcept {
    return element_id_ != static_cast<DWORD>(-1);
}

void CandidateUi::Notify(DWORD flags) {
    updated_flags_ = flags;
    if (ui_element_manager_ != nullptr &&
        element_id_ != static_cast<DWORD>(-1)) {
        ui_element_manager_->UpdateUIElement(element_id_);
    }
}

}  // namespace sense::tsf
