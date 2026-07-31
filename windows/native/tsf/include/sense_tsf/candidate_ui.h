#pragma once

#include "sense/pinyin_lexicon.h"
#include "sense_tsf/candidate_window.h"
#include "sense_tsf/module.h"

#include <Windows.h>
#include <msctf.h>
#include <wrl/client.h>

#include <cstddef>
#include <functional>
#include <string>
#include <vector>

namespace sense::tsf {

class CandidateUi final : public ITfCandidateListUIElementBehavior {
public:
    static constexpr std::size_t kPageSize = 5;

    using SelectionHandler = std::function<void(std::size_t)>;
    using ActionHandler = std::function<void()>;

    CandidateUi(
        SelectionHandler on_selection,
        ActionHandler on_finalize,
        ActionHandler on_abort
    );

    HRESULT STDMETHODCALLTYPE QueryInterface(
        REFIID iid,
        void** object
    ) override;
    ULONG STDMETHODCALLTYPE AddRef() override;
    ULONG STDMETHODCALLTYPE Release() override;

    HRESULT STDMETHODCALLTYPE GetDescription(BSTR* description) override;
    HRESULT STDMETHODCALLTYPE GetGUID(GUID* guid) override;
    HRESULT STDMETHODCALLTYPE Show(BOOL show) override;
    HRESULT STDMETHODCALLTYPE IsShown(BOOL* shown) override;

    HRESULT STDMETHODCALLTYPE GetUpdatedFlags(DWORD* flags) override;
    HRESULT STDMETHODCALLTYPE GetDocumentMgr(
        ITfDocumentMgr** document_manager
    ) override;
    HRESULT STDMETHODCALLTYPE GetCount(UINT* count) override;
    HRESULT STDMETHODCALLTYPE GetSelection(UINT* index) override;
    HRESULT STDMETHODCALLTYPE GetString(UINT index, BSTR* value) override;
    HRESULT STDMETHODCALLTYPE GetPageIndex(
        UINT* indices,
        UINT capacity,
        UINT* page_count
    ) override;
    HRESULT STDMETHODCALLTYPE SetPageIndex(
        UINT* indices,
        UINT page_count
    ) override;
    HRESULT STDMETHODCALLTYPE GetCurrentPage(UINT* page) override;

    HRESULT STDMETHODCALLTYPE SetSelection(UINT index) override;
    HRESULT STDMETHODCALLTYPE Finalize() override;
    HRESULT STDMETHODCALLTYPE Abort() override;

    [[nodiscard]] HRESULT Begin(
        ITfThreadMgr* thread_manager,
        ITfDocumentMgr* document_manager
    );
    void Update(
        std::wstring_view composing,
        const std::vector<sense::Candidate>& candidates,
        std::size_t selection,
        const RECT& anchor
    );
    void End();
    [[nodiscard]] bool active() const noexcept;

private:
    ~CandidateUi();
    void Notify(DWORD flags);

    ObjectLifetime lifetime_;
    volatile long references_ = 1;
    Microsoft::WRL::ComPtr<ITfThreadMgr> thread_manager_;
    Microsoft::WRL::ComPtr<ITfUIElementMgr> ui_element_manager_;
    Microsoft::WRL::ComPtr<ITfDocumentMgr> document_manager_;
    std::vector<std::wstring> candidates_;
    std::wstring composing_;
    std::size_t selection_ = 0;
    DWORD updated_flags_ = 0;
    DWORD element_id_ = static_cast<DWORD>(-1);
    BOOL service_should_show_ = TRUE;
    bool requested_visible_ = true;
    CandidateWindow window_;
    SelectionHandler on_selection_;
    ActionHandler on_finalize_;
    ActionHandler on_abort_;
};

}  // namespace sense::tsf
