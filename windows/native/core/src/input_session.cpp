#include "sense/input_session.h"

#include <algorithm>
#include <cctype>

namespace sense {

InputSession::InputSession(const PinyinLexicon* lexicon) : lexicon_(lexicon) {}

void InputSession::SetLexicon(const PinyinLexicon* lexicon) {
    lexicon_ = lexicon;
    Refresh();
}

bool InputSession::Type(char value) {
    if (composing_.size() >= kMaximumComposingLength) {
        return false;
    }
    const unsigned char raw = static_cast<unsigned char>(value);
    char normalized = static_cast<char>(std::tolower(raw));
    if ((normalized < 'a' || normalized > 'z') && normalized != '\'') {
        return false;
    }
    if (normalized == '\'' &&
        (composing_.empty() || composing_.back() == '\'')) {
        return false;
    }
    composing_.push_back(normalized);
    Refresh();
    return true;
}

bool InputSession::Backspace() {
    if (composing_.empty()) {
        return false;
    }
    composing_.pop_back();
    Refresh();
    return true;
}

void InputSession::Reset() {
    composing_.clear();
    candidates_.clear();
    selection_ = 0;
}

bool InputSession::MoveSelection(int delta) {
    if (candidates_.empty() || delta == 0) {
        return false;
    }
    const std::size_t count = candidates_.size();
    const long long current = static_cast<long long>(selection_);
    const long long size = static_cast<long long>(count);
    long long next = (current + delta) % size;
    if (next < 0) {
        next += size;
    }
    selection_ = static_cast<std::size_t>(next);
    return true;
}

bool InputSession::Select(std::size_t index) {
    if (index >= candidates_.size()) {
        return false;
    }
    selection_ = index;
    return true;
}

bool InputSession::empty() const noexcept {
    return composing_.empty();
}

std::string_view InputSession::composing() const noexcept {
    return composing_;
}

const std::vector<Candidate>& InputSession::candidates() const noexcept {
    return candidates_;
}

std::size_t InputSession::selection() const noexcept {
    return selection_;
}

const Candidate* InputSession::selected_candidate() const noexcept {
    if (selection_ >= candidates_.size()) {
        return nullptr;
    }
    return &candidates_[selection_];
}

void InputSession::Refresh() {
    selection_ = 0;
    if (lexicon_ == nullptr || !lexicon_->is_open() || composing_.empty()) {
        candidates_.clear();
        return;
    }
    candidates_ = lexicon_->Decode(composing_, kCandidateLimit);
}

}  // namespace sense
