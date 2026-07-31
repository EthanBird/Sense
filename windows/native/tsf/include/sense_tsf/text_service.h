#pragma once

#include "sense/input_session.h"
#include "sense/pinyin_lexicon.h"
#include "sense_tsf/candidate_ui.h"
#include "sense_tsf/edit_session.h"
#include "sense_tsf/module.h"

#include <Windows.h>
#include <msctf.h>
#include <wrl/client.h>

#include <atomic>
#include <filesystem>
#include <functional>
#include <string>

namespace sense::tsf {

class TextService final :
    public ITfTextInputProcessorEx,
    public ITfKeyEventSink,
    public ITfThreadMgrEventSink,
    public ITfCompositionSink {
public:
    TextService();

    HRESULT STDMETHODCALLTYPE QueryInterface(
        REFIID iid,
        void** object
    ) override;
    ULONG STDMETHODCALLTYPE AddRef() override;
    ULONG STDMETHODCALLTYPE Release() override;

    HRESULT STDMETHODCALLTYPE Activate(
        ITfThreadMgr* thread_manager,
        TfClientId client_id
    ) override;
    HRESULT STDMETHODCALLTYPE Deactivate() override;
    HRESULT STDMETHODCALLTYPE ActivateEx(
        ITfThreadMgr* thread_manager,
        TfClientId client_id,
        DWORD flags
    ) override;

    HRESULT STDMETHODCALLTYPE OnSetFocus(BOOL foreground) override;
    HRESULT STDMETHODCALLTYPE OnTestKeyDown(
        ITfContext* context,
        WPARAM wparam,
        LPARAM lparam,
        BOOL* eaten
    ) override;
    HRESULT STDMETHODCALLTYPE OnTestKeyUp(
        ITfContext* context,
        WPARAM wparam,
        LPARAM lparam,
        BOOL* eaten
    ) override;
    HRESULT STDMETHODCALLTYPE OnKeyDown(
        ITfContext* context,
        WPARAM wparam,
        LPARAM lparam,
        BOOL* eaten
    ) override;
    HRESULT STDMETHODCALLTYPE OnKeyUp(
        ITfContext* context,
        WPARAM wparam,
        LPARAM lparam,
        BOOL* eaten
    ) override;
    HRESULT STDMETHODCALLTYPE OnPreservedKey(
        ITfContext* context,
        REFGUID guid,
        BOOL* eaten
    ) override;

    HRESULT STDMETHODCALLTYPE OnInitDocumentMgr(
        ITfDocumentMgr* document_manager
    ) override;
    HRESULT STDMETHODCALLTYPE OnUninitDocumentMgr(
        ITfDocumentMgr* document_manager
    ) override;
    HRESULT STDMETHODCALLTYPE OnSetFocus(
        ITfDocumentMgr* focused,
        ITfDocumentMgr* previous
    ) override;
    HRESULT STDMETHODCALLTYPE OnPushContext(ITfContext* context) override;
    HRESULT STDMETHODCALLTYPE OnPopContext(ITfContext* context) override;

    HRESULT STDMETHODCALLTYPE OnCompositionTerminated(
        TfEditCookie cookie,
        ITfComposition* composition
    ) override;

    void CandidateSelectionChanged(std::size_t index);
    void FinalizeCandidate();
    void AbortCandidate();

private:
    ~TextService();

    enum class KeyAction {
        Pass,
        Type,
        Backspace,
        Cancel,
        CommitCandidate,
        CommitRaw,
        MovePrevious,
        MoveNext,
        PreviousPage,
        NextPage,
        SelectNumber,
        ToggleMode,
        CommitWithPunctuation,
    };

    struct ClassifiedKey {
        KeyAction action = KeyAction::Pass;
        char character = '\0';
        std::size_t candidate_offset = 0;
        std::wstring punctuation;
    };

    [[nodiscard]] HRESULT AdviseSinks();
    void UnadviseSinks();
    [[nodiscard]] bool EnsureLexicon();
    void LoadSettings();
    [[nodiscard]] ClassifiedKey ClassifyKey(WPARAM wparam) const;
    [[nodiscard]] bool ShouldEat(WPARAM wparam) const;
    void HandleKey(ITfContext* context, const ClassifiedKey& key);

    [[nodiscard]] HRESULT RequestEdit(
        ITfContext* context,
        DWORD flags,
        LambdaEditSession::Callback callback
    );
    void UpdateComposition(ITfContext* context);
    void Commit(ITfContext* context, std::wstring text);
    void CommitSelected(
        ITfContext* context,
        std::wstring_view suffix = {}
    );
    void CommitRaw(ITfContext* context);
    void Cancel(ITfContext* context);
    void PublishCandidates(
        ITfContext* context,
        TfEditCookie cookie,
        ITfRange* composition_range
    );
    void CloseCandidateUi();
    [[nodiscard]] Microsoft::WRL::ComPtr<ITfContext> FocusedContext() const;

    ObjectLifetime lifetime_;
    volatile long references_ = 1;
    Microsoft::WRL::ComPtr<ITfThreadMgr> thread_manager_;
    Microsoft::WRL::ComPtr<ITfComposition> composition_;
    Microsoft::WRL::ComPtr<ITfContext> composition_context_;
    TfClientId client_id_ = TF_CLIENTID_NULL;
    DWORD thread_sink_cookie_ = TF_INVALID_COOKIE;
    DWORD activation_flags_ = 0;
    bool chinese_mode_ = true;
    bool chinese_punctuation_ = true;
    bool lexicon_attempted_ = false;
    sense::PinyinLexicon lexicon_;
    sense::InputSession session_;
    CandidateUi* candidate_ui_ = nullptr;
};

}  // namespace sense::tsf
