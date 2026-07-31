#pragma once

#include "sense/pinyin_lexicon.h"

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace sense {

/**
 * Platform-neutral composition state used by the TSF adapter.
 *
 * It deliberately contains no COM or window handles, which keeps key handling
 * deterministic and makes the Windows text service a thin transaction layer.
 */
class InputSession final {
public:
    static constexpr std::size_t kMaximumComposingLength = 96;
    static constexpr std::size_t kCandidateLimit = 50;

    explicit InputSession(const PinyinLexicon* lexicon = nullptr);

    void SetLexicon(const PinyinLexicon* lexicon);
    [[nodiscard]] bool Type(char value);
    [[nodiscard]] bool Backspace();
    void Reset();

    [[nodiscard]] bool MoveSelection(int delta);
    [[nodiscard]] bool Select(std::size_t index);

    [[nodiscard]] bool empty() const noexcept;
    [[nodiscard]] std::string_view composing() const noexcept;
    [[nodiscard]] const std::vector<Candidate>& candidates() const noexcept;
    [[nodiscard]] std::size_t selection() const noexcept;
    [[nodiscard]] const Candidate* selected_candidate() const noexcept;

private:
    void Refresh();

    const PinyinLexicon* lexicon_ = nullptr;
    std::string composing_;
    std::vector<Candidate> candidates_;
    std::size_t selection_ = 0;
};

}  // namespace sense
