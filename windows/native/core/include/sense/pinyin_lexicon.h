#pragma once

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace sense {

enum class MatchKind {
    Exact,
    Composed,
    Hybrid,
    Initials,
    Prefix,
};

struct Candidate {
    std::wstring text;
    float score = 0.0F;
    std::string canonical_pinyin;
    std::string canonical_initials;
    MatchKind match_kind = MatchKind::Exact;
};

/**
 * Read-only decoder for Sense's compact SPLX v3 lexicon.
 *
 * The file is memory mapped, while a compact offset table is retained per
 * process. Pages containing dictionary records can therefore be shared by the
 * Windows cache instead of copying the 35 MB asset into every TSF host.
 */
class PinyinLexicon final {
public:
    PinyinLexicon();
    ~PinyinLexicon();

    PinyinLexicon(PinyinLexicon&&) noexcept;
    PinyinLexicon& operator=(PinyinLexicon&&) noexcept;

    PinyinLexicon(const PinyinLexicon&) = delete;
    PinyinLexicon& operator=(const PinyinLexicon&) = delete;

    [[nodiscard]] bool Open(
        const std::filesystem::path& path,
        std::wstring* error = nullptr
    );

    void Close() noexcept;

    [[nodiscard]] bool is_open() const noexcept;
    [[nodiscard]] std::size_t record_count() const noexcept;
    [[nodiscard]] const std::filesystem::path& path() const noexcept;

    [[nodiscard]] std::vector<Candidate> Decode(
        std::string_view composing,
        std::size_t limit = 50
    ) const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace sense
