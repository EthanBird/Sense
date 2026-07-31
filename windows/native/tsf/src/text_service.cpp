#include "sense_tsf/text_service.h"

#include <new>
#include <utility>

namespace sense::tsf {
namespace {

bool IsPressed(int virtual_key) {
    return (GetKeyState(virtual_key) & 0x8000) != 0;
}

std::wstring AsWide(std::string_view value) {
    std::wstring result;
    result.reserve(value.size());
    for (const unsigned char character : value) {
        result.push_back(static_cast<wchar_t>(character));
    }
    return result;
}

bool SameComIdentity(IUnknown* left, IUnknown* right) {
    if (left == nullptr || right == nullptr) {
        return left == right;
    }
    Microsoft::WRL::ComPtr<IUnknown> left_identity;
    Microsoft::WRL::ComPtr<IUnknown> right_identity;
    if (FAILED(left->QueryInterface(IID_PPV_ARGS(&left_identity))) ||
        FAILED(right->QueryInterface(IID_PPV_ARGS(&right_identity)))) {
        return left == right;
    }
    return left_identity.Get() == right_identity.Get();
}

}  // namespace

TextService::TextService() : session_(&lexicon_) {
    candidate_ui_ = new (std::nothrow) CandidateUi(
        [this](std::size_t index) {
            CandidateSelectionChanged(index);
        },
        [this] {
            FinalizeCandidate();
        },
        [this] {
            AbortCandidate();
        }
    );
}

TextService::~TextService() {
    CloseCandidateUi();
    UnadviseSinks();
    composition_.Reset();
    composition_context_.Reset();
    thread_manager_.Reset();
    if (candidate_ui_ != nullptr) {
        candidate_ui_->Release();
        candidate_ui_ = nullptr;
    }
}

HRESULT TextService::QueryInterface(REFIID iid, void** object) {
    if (object == nullptr) {
        return E_INVALIDARG;
    }
    *object = nullptr;
    if (iid == IID_IUnknown ||
        iid == IID_ITfTextInputProcessor ||
        iid == IID_ITfTextInputProcessorEx) {
        *object = static_cast<ITfTextInputProcessorEx*>(this);
    } else if (iid == IID_ITfKeyEventSink) {
        *object = static_cast<ITfKeyEventSink*>(this);
    } else if (iid == IID_ITfThreadMgrEventSink) {
        *object = static_cast<ITfThreadMgrEventSink*>(this);
    } else if (iid == IID_ITfCompositionSink) {
        *object = static_cast<ITfCompositionSink*>(this);
    } else {
        return E_NOINTERFACE;
    }
    AddRef();
    return S_OK;
}

ULONG TextService::AddRef() {
    return static_cast<ULONG>(InterlockedIncrement(&references_));
}

ULONG TextService::Release() {
    const ULONG remaining =
        static_cast<ULONG>(InterlockedDecrement(&references_));
    if (remaining == 0) {
        delete this;
    }
    return remaining;
}

HRESULT TextService::Activate(
    ITfThreadMgr* thread_manager,
    TfClientId client_id
) {
    return ActivateEx(thread_manager, client_id, 0);
}

HRESULT TextService::ActivateEx(
    ITfThreadMgr* thread_manager,
    TfClientId client_id,
    DWORD flags
) {
    if (thread_manager == nullptr || client_id == TF_CLIENTID_NULL) {
        return E_INVALIDARG;
    }
    if (thread_manager_ != nullptr) {
        Deactivate();
    }
    thread_manager_ = thread_manager;
    client_id_ = client_id;
    activation_flags_ = flags;
    LoadSettings();
    (void)EnsureLexicon();
    session_.SetLexicon(lexicon_.is_open() ? &lexicon_ : nullptr);
    const HRESULT result = AdviseSinks();
    if (FAILED(result)) {
        Deactivate();
    }
    return result;
}

HRESULT TextService::Deactivate() {
    Microsoft::WRL::ComPtr<ITfContext> context = composition_context_;
    if (context != nullptr && composition_ != nullptr) {
        Cancel(context.Get());
    }
    CloseCandidateUi();
    UnadviseSinks();
    composition_.Reset();
    composition_context_.Reset();
    session_.Reset();
    thread_manager_.Reset();
    client_id_ = TF_CLIENTID_NULL;
    activation_flags_ = 0;
    return S_OK;
}

HRESULT TextService::OnSetFocus(BOOL) {
    return S_OK;
}

HRESULT TextService::OnTestKeyDown(
    ITfContext*,
    WPARAM wparam,
    LPARAM,
    BOOL* eaten
) {
    if (eaten == nullptr) {
        return E_INVALIDARG;
    }
    *eaten = ShouldEat(wparam) ? TRUE : FALSE;
    return S_OK;
}

HRESULT TextService::OnTestKeyUp(
    ITfContext*,
    WPARAM,
    LPARAM,
    BOOL* eaten
) {
    if (eaten == nullptr) {
        return E_INVALIDARG;
    }
    *eaten = FALSE;
    return S_OK;
}

HRESULT TextService::OnKeyDown(
    ITfContext* context,
    WPARAM wparam,
    LPARAM,
    BOOL* eaten
) {
    if (context == nullptr || eaten == nullptr) {
        return E_INVALIDARG;
    }
    const ClassifiedKey key = ClassifyKey(wparam);
    *eaten = key.action == KeyAction::Pass ? FALSE : TRUE;
    if (*eaten != FALSE) {
        HandleKey(context, key);
    }
    return S_OK;
}

HRESULT TextService::OnKeyUp(
    ITfContext*,
    WPARAM,
    LPARAM,
    BOOL* eaten
) {
    if (eaten == nullptr) {
        return E_INVALIDARG;
    }
    *eaten = FALSE;
    return S_OK;
}

HRESULT TextService::OnPreservedKey(
    ITfContext*,
    REFGUID,
    BOOL* eaten
) {
    if (eaten != nullptr) {
        *eaten = FALSE;
    }
    return S_OK;
}

HRESULT TextService::OnInitDocumentMgr(ITfDocumentMgr*) {
    return S_OK;
}

HRESULT TextService::OnUninitDocumentMgr(
    ITfDocumentMgr* document_manager
) {
    if (composition_context_ != nullptr) {
        Microsoft::WRL::ComPtr<ITfDocumentMgr> composition_document;
        if (SUCCEEDED(composition_context_->GetDocumentMgr(
                &composition_document
            )) &&
            SameComIdentity(composition_document.Get(), document_manager)) {
            Cancel(composition_context_.Get());
        }
    }
    return S_OK;
}

HRESULT TextService::OnSetFocus(
    ITfDocumentMgr* focused,
    ITfDocumentMgr* previous
) {
    if (composition_context_ != nullptr && previous != nullptr &&
        !SameComIdentity(focused, previous)) {
        Cancel(composition_context_.Get());
    }
    return S_OK;
}

HRESULT TextService::OnPushContext(ITfContext*) {
    return S_OK;
}

HRESULT TextService::OnPopContext(ITfContext* context) {
    if (composition_context_ != nullptr &&
        SameComIdentity(composition_context_.Get(), context)) {
        Cancel(composition_context_.Get());
    }
    return S_OK;
}

HRESULT TextService::OnCompositionTerminated(
    TfEditCookie,
    ITfComposition* composition
) {
    if (composition_ == nullptr ||
        SameComIdentity(composition_.Get(), composition)) {
        composition_.Reset();
        composition_context_.Reset();
        session_.Reset();
        CloseCandidateUi();
    }
    return S_OK;
}

void TextService::CandidateSelectionChanged(std::size_t index) {
    if (!session_.Select(index)) {
        return;
    }
    Microsoft::WRL::ComPtr<ITfContext> context = composition_context_;
    if (context == nullptr) {
        context = FocusedContext();
    }
    if (context != nullptr) {
        UpdateComposition(context.Get());
    }
}

void TextService::FinalizeCandidate() {
    Microsoft::WRL::ComPtr<ITfContext> context = composition_context_;
    if (context == nullptr) {
        context = FocusedContext();
    }
    if (context != nullptr) {
        CommitSelected(context.Get());
    }
}

void TextService::AbortCandidate() {
    Microsoft::WRL::ComPtr<ITfContext> context = composition_context_;
    if (context != nullptr) {
        Cancel(context.Get());
    }
}

HRESULT TextService::AdviseSinks() {
    if (thread_manager_ == nullptr || client_id_ == TF_CLIENTID_NULL) {
        return E_UNEXPECTED;
    }
    Microsoft::WRL::ComPtr<ITfKeystrokeMgr> keystroke_manager;
    HRESULT result = thread_manager_.As(&keystroke_manager);
    if (FAILED(result)) {
        return result;
    }
    result = keystroke_manager->AdviseKeyEventSink(
        client_id_,
        static_cast<ITfKeyEventSink*>(this),
        TRUE
    );
    if (FAILED(result)) {
        return result;
    }

    Microsoft::WRL::ComPtr<ITfSource> source;
    result = thread_manager_.As(&source);
    if (FAILED(result)) {
        keystroke_manager->UnadviseKeyEventSink(client_id_);
        return result;
    }
    result = source->AdviseSink(
        IID_ITfThreadMgrEventSink,
        static_cast<ITfThreadMgrEventSink*>(this),
        &thread_sink_cookie_
    );
    if (FAILED(result)) {
        thread_sink_cookie_ = TF_INVALID_COOKIE;
        keystroke_manager->UnadviseKeyEventSink(client_id_);
    }
    return result;
}

void TextService::UnadviseSinks() {
    if (thread_manager_ == nullptr) {
        thread_sink_cookie_ = TF_INVALID_COOKIE;
        return;
    }
    Microsoft::WRL::ComPtr<ITfSource> source;
    if (thread_sink_cookie_ != TF_INVALID_COOKIE &&
        SUCCEEDED(thread_manager_.As(&source))) {
        source->UnadviseSink(thread_sink_cookie_);
    }
    thread_sink_cookie_ = TF_INVALID_COOKIE;

    Microsoft::WRL::ComPtr<ITfKeystrokeMgr> keystroke_manager;
    if (client_id_ != TF_CLIENTID_NULL &&
        SUCCEEDED(thread_manager_.As(&keystroke_manager))) {
        keystroke_manager->UnadviseKeyEventSink(client_id_);
    }
}

bool TextService::EnsureLexicon() {
    if (lexicon_.is_open()) {
        return true;
    }
    if (lexicon_attempted_) {
        return false;
    }
    lexicon_attempted_ = true;
    const std::filesystem::path path =
        ModuleDirectory() / L"data" / L"pinyin_lexicon.bin";
    std::wstring error;
    return lexicon_.Open(path, &error);
}

void TextService::LoadSettings() {
    wchar_t local_app_data[32'768]{};
    const DWORD length = GetEnvironmentVariableW(
        L"LOCALAPPDATA",
        local_app_data,
        ARRAYSIZE(local_app_data)
    );
    if (length == 0 || length >= ARRAYSIZE(local_app_data)) {
        chinese_mode_ = true;
        chinese_punctuation_ = true;
        return;
    }
    std::filesystem::path settings(local_app_data);
    settings /= L"Sense";
    settings /= L"settings.ini";
    chinese_mode_ = GetPrivateProfileIntW(
        L"input",
        L"default_chinese_mode",
        1,
        settings.c_str()
    ) != 0;
    chinese_punctuation_ = GetPrivateProfileIntW(
        L"input",
        L"chinese_punctuation",
        1,
        settings.c_str()
    ) != 0;
}

TextService::ClassifiedKey TextService::ClassifyKey(WPARAM wparam) const {
    const UINT key = static_cast<UINT>(wparam);
    const bool control = IsPressed(VK_CONTROL);
    const bool alt = IsPressed(VK_MENU);
    const bool windows_key = IsPressed(VK_LWIN) || IsPressed(VK_RWIN);
    const bool shift = IsPressed(VK_SHIFT);

    if (key == VK_SPACE && control && !alt && !windows_key) {
        return {KeyAction::ToggleMode};
    }
    if (!chinese_mode_ || !lexicon_.is_open() || alt || control ||
        windows_key) {
        return {};
    }

    const bool composing = !session_.empty();
    if (key >= 'A' && key <= 'Z') {
        if (!composing && (GetKeyState(VK_CAPITAL) & 1) != 0) {
            return {};
        }
        return {
            KeyAction::Type,
            static_cast<char>('a' + key - 'A'),
        };
    }
    if (key == VK_OEM_7 && !shift && composing) {
        return {KeyAction::Type, '\''};
    }
    if (!composing) {
        return {};
    }

    if (shift) {
        switch (key) {
            case '1':
                return {
                    KeyAction::CommitWithPunctuation,
                    '\0',
                    0,
                    chinese_punctuation_ ? L"！" : L"!",
                };
            case VK_OEM_2:
                return {
                    KeyAction::CommitWithPunctuation,
                    '\0',
                    0,
                    chinese_punctuation_ ? L"？" : L"?",
                };
            case VK_OEM_1:
                return {
                    KeyAction::CommitWithPunctuation,
                    '\0',
                    0,
                    chinese_punctuation_ ? L"：" : L":",
                };
            default:
                break;
        }
    }
    if (key >= '1' && key <= '5' && !shift) {
        const std::size_t page_start =
            (session_.selection() / CandidateUi::kPageSize) *
            CandidateUi::kPageSize;
        return {
            KeyAction::SelectNumber,
            '\0',
            page_start + static_cast<std::size_t>(key - '1'),
        };
    }

    switch (key) {
        case VK_BACK:
            return {KeyAction::Backspace};
        case VK_ESCAPE:
            return {KeyAction::Cancel};
        case VK_SPACE:
            return {KeyAction::CommitCandidate};
        case VK_RETURN:
            return {KeyAction::CommitRaw};
        case VK_LEFT:
        case VK_UP:
            return {KeyAction::MovePrevious};
        case VK_RIGHT:
        case VK_DOWN:
        case VK_TAB:
            return {KeyAction::MoveNext};
        case VK_PRIOR:
            return {KeyAction::PreviousPage};
        case VK_NEXT:
            return {KeyAction::NextPage};
        case VK_OEM_COMMA:
            return {
                KeyAction::CommitWithPunctuation,
                '\0',
                0,
                chinese_punctuation_ ? L"，" : L",",
            };
        case VK_OEM_PERIOD:
            return {
                KeyAction::CommitWithPunctuation,
                '\0',
                0,
                chinese_punctuation_ ? L"。" : L".",
            };
        case VK_OEM_1:
            return {
                KeyAction::CommitWithPunctuation,
                '\0',
                0,
                chinese_punctuation_ ? L"；" : L";",
            };
        default:
            return {};
    }
}

bool TextService::ShouldEat(WPARAM wparam) const {
    return ClassifyKey(wparam).action != KeyAction::Pass;
}

void TextService::HandleKey(
    ITfContext* context,
    const ClassifiedKey& key
) {
    switch (key.action) {
        case KeyAction::Type:
            if (session_.Type(key.character)) {
                UpdateComposition(context);
            }
            break;
        case KeyAction::Backspace:
            if (session_.Backspace()) {
                if (session_.empty()) {
                    Cancel(context);
                } else {
                    UpdateComposition(context);
                }
            }
            break;
        case KeyAction::Cancel:
            Cancel(context);
            break;
        case KeyAction::CommitCandidate:
            CommitSelected(context);
            break;
        case KeyAction::CommitRaw:
            CommitRaw(context);
            break;
        case KeyAction::MovePrevious:
            if (session_.MoveSelection(-1)) {
                UpdateComposition(context);
            }
            break;
        case KeyAction::MoveNext:
            if (session_.MoveSelection(1)) {
                UpdateComposition(context);
            }
            break;
        case KeyAction::PreviousPage:
            if (session_.MoveSelection(
                    -static_cast<int>(CandidateUi::kPageSize)
                )) {
                UpdateComposition(context);
            }
            break;
        case KeyAction::NextPage:
            if (session_.MoveSelection(
                    static_cast<int>(CandidateUi::kPageSize)
                )) {
                UpdateComposition(context);
            }
            break;
        case KeyAction::SelectNumber:
            if (session_.Select(key.candidate_offset)) {
                CommitSelected(context);
            }
            break;
        case KeyAction::ToggleMode:
            if (!session_.empty()) {
                Cancel(context);
            }
            chinese_mode_ = !chinese_mode_;
            break;
        case KeyAction::CommitWithPunctuation:
            CommitSelected(context, key.punctuation);
            break;
        case KeyAction::Pass:
            break;
    }
}

HRESULT TextService::RequestEdit(
    ITfContext* context,
    DWORD flags,
    LambdaEditSession::Callback callback
) {
    if (context == nullptr || client_id_ == TF_CLIENTID_NULL) {
        return E_UNEXPECTED;
    }
    auto* edit_session = new (std::nothrow) LambdaEditSession(
        static_cast<ITfTextInputProcessorEx*>(this),
        std::move(callback)
    );
    if (edit_session == nullptr) {
        return E_OUTOFMEMORY;
    }
    HRESULT session_result = E_FAIL;
    const HRESULT request_result = context->RequestEditSession(
        client_id_,
        edit_session,
        flags,
        &session_result
    );
    edit_session->Release();
    return FAILED(request_result) ? request_result : session_result;
}

void TextService::UpdateComposition(ITfContext* context) {
    if (context == nullptr || session_.empty()) {
        return;
    }
    const std::wstring composing = AsWide(session_.composing());
    Microsoft::WRL::ComPtr<ITfContext> context_reference = context;
    const HRESULT update_result = RequestEdit(
        context,
        TF_ES_SYNC | TF_ES_READWRITE,
        [this, context_reference, composing](TfEditCookie cookie) -> HRESULT {
            if (composition_ == nullptr) {
                Microsoft::WRL::ComPtr<ITfInsertAtSelection> insertion;
                HRESULT result = context_reference.As(&insertion);
                if (FAILED(result)) {
                    return result;
                }
                Microsoft::WRL::ComPtr<ITfRange> insertion_range;
                result = insertion->InsertTextAtSelection(
                    cookie,
                    TF_IAS_QUERYONLY,
                    nullptr,
                    0,
                    &insertion_range
                );
                if (FAILED(result)) {
                    return result;
                }
                Microsoft::WRL::ComPtr<ITfContextComposition>
                    context_composition;
                result = context_reference.As(&context_composition);
                if (FAILED(result)) {
                    return result;
                }
                Microsoft::WRL::ComPtr<ITfComposition> composition;
                result = context_composition->StartComposition(
                    cookie,
                    insertion_range.Get(),
                    static_cast<ITfCompositionSink*>(this),
                    &composition
                );
                if (FAILED(result) || composition == nullptr) {
                    return FAILED(result) ? result : E_FAIL;
                }
                composition_ = composition;
                composition_context_ = context_reference;
            }

            Microsoft::WRL::ComPtr<ITfRange> range;
            HRESULT result = composition_->GetRange(&range);
            if (FAILED(result)) {
                return result;
            }
            result = range->SetText(
                cookie,
                0,
                composing.data(),
                static_cast<LONG>(composing.size())
            );
            if (FAILED(result)) {
                return result;
            }

            PublishCandidates(context_reference.Get(), cookie, range.Get());

            result = range->Collapse(cookie, TF_ANCHOR_END);
            if (FAILED(result)) {
                return result;
            }
            TF_SELECTION selection{};
            selection.range = range.Get();
            selection.style.ase = TF_AE_NONE;
            selection.style.fInterimChar = FALSE;
            return context_reference->SetSelection(cookie, 1, &selection);
        }
    );
    (void)update_result;
}

void TextService::Commit(ITfContext* context, std::wstring text) {
    if (context == nullptr) {
        return;
    }
    Microsoft::WRL::ComPtr<ITfComposition> composition = composition_;
    Microsoft::WRL::ComPtr<ITfContext> context_reference = context;
    CloseCandidateUi();
    const HRESULT commit_result = RequestEdit(
        context,
        TF_ES_SYNC | TF_ES_READWRITE,
        [this, context_reference, composition, text = std::move(text)](
            TfEditCookie cookie
        ) -> HRESULT {
            if (composition != nullptr) {
                Microsoft::WRL::ComPtr<ITfRange> range;
                HRESULT result = composition->GetRange(&range);
                if (FAILED(result)) {
                    return result;
                }
                result = range->SetText(
                    cookie,
                    0,
                    text.data(),
                    static_cast<LONG>(text.size())
                );
                if (FAILED(result)) {
                    return result;
                }
                result = composition->EndComposition(cookie);
                composition_.Reset();
                composition_context_.Reset();
                return result;
            }

            Microsoft::WRL::ComPtr<ITfInsertAtSelection> insertion;
            HRESULT result = context_reference.As(&insertion);
            if (FAILED(result)) {
                return result;
            }
            Microsoft::WRL::ComPtr<ITfRange> inserted;
            return insertion->InsertTextAtSelection(
                cookie,
                0,
                text.data(),
                static_cast<LONG>(text.size()),
                &inserted
            );
        }
    );
    if (SUCCEEDED(commit_result)) {
        session_.Reset();
    } else if (!session_.empty() && composition_ != nullptr) {
        UpdateComposition(context);
    }
}

void TextService::CommitSelected(
    ITfContext* context,
    std::wstring_view suffix
) {
    std::wstring text;
    if (const sense::Candidate* candidate = session_.selected_candidate();
        candidate != nullptr) {
        text = candidate->text;
    } else {
        text = AsWide(session_.composing());
    }
    text.append(suffix);
    Commit(context, std::move(text));
}

void TextService::CommitRaw(ITfContext* context) {
    Commit(context, AsWide(session_.composing()));
}

void TextService::Cancel(ITfContext* context) {
    Commit(context, {});
}

void TextService::PublishCandidates(
    ITfContext* context,
    TfEditCookie cookie,
    ITfRange* composition_range
) {
    if (candidate_ui_ == nullptr || thread_manager_ == nullptr ||
        context == nullptr || composition_range == nullptr) {
        return;
    }
    Microsoft::WRL::ComPtr<ITfDocumentMgr> document_manager;
    if (FAILED(context->GetDocumentMgr(&document_manager)) ||
        document_manager == nullptr) {
        return;
    }
    if (FAILED(candidate_ui_->Begin(
            thread_manager_.Get(),
            document_manager.Get()
        ))) {
        return;
    }

    RECT anchor{};
    BOOL clipped = FALSE;
    Microsoft::WRL::ComPtr<ITfContextView> view;
    if (FAILED(context->GetActiveView(&view)) ||
        view == nullptr ||
        FAILED(view->GetTextExt(
            cookie,
            composition_range,
            &anchor,
            &clipped
        ))) {
        POINT cursor{};
        GetCursorPos(&cursor);
        anchor = {cursor.x, cursor.y, cursor.x + 1, cursor.y + 24};
    }
    candidate_ui_->Update(
        AsWide(session_.composing()),
        session_.candidates(),
        session_.selection(),
        anchor
    );
}

void TextService::CloseCandidateUi() {
    if (candidate_ui_ != nullptr) {
        candidate_ui_->End();
    }
}

Microsoft::WRL::ComPtr<ITfContext> TextService::FocusedContext() const {
    Microsoft::WRL::ComPtr<ITfContext> context;
    if (thread_manager_ == nullptr) {
        return context;
    }
    Microsoft::WRL::ComPtr<ITfDocumentMgr> document_manager;
    if (SUCCEEDED(thread_manager_->GetFocus(&document_manager)) &&
        document_manager != nullptr) {
        document_manager->GetTop(&context);
    }
    return context;
}

}  // namespace sense::tsf
