#include "sense/pinyin_lexicon.h"

#include <Windows.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <map>
#include <span>
#include <unordered_map>
#include <utility>

namespace sense {
namespace {

constexpr std::array<std::uint8_t, 4> kMagic = {'S', 'P', 'L', 'X'};
constexpr std::uint16_t kVersion = 3;
constexpr std::size_t kHeaderSize = 10;
constexpr std::size_t kMaximumRecords = 1'000'000;
constexpr std::size_t kMaximumDecodeCandidates = 255;
constexpr std::size_t kMaximumSegmentCodeLength = 24;
constexpr std::size_t kCandidatesPerSegment = 8;
constexpr std::size_t kCompositionBeamWidth = 32;
constexpr std::size_t kPrefixScanLimit = 96;
constexpr std::size_t kHybridScanLimit = 96;
constexpr float kWordBoundaryCost = 0.65F;
constexpr float kFallbackSourcePenalty = 1.0F;

std::wstring Win32Error(const wchar_t* action) {
    const DWORD code = GetLastError();
    wchar_t* message = nullptr;
    const DWORD length = FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
            FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr,
        code,
        0,
        reinterpret_cast<wchar_t*>(&message),
        0,
        nullptr
    );
    std::wstring result(action);
    result += L" (";
    result += std::to_wstring(code);
    result += L")";
    if (length != 0 && message != nullptr) {
        result += L": ";
        result.append(message, length);
        while (!result.empty() &&
               (result.back() == L'\r' || result.back() == L'\n')) {
            result.pop_back();
        }
    }
    if (message != nullptr) {
        LocalFree(message);
    }
    return result;
}

std::wstring Utf8ToWide(std::span<const std::uint8_t> bytes) {
    if (bytes.empty() ||
        bytes.size() > static_cast<std::size_t>(std::numeric_limits<int>::max())) {
        return {};
    }
    const auto* source = reinterpret_cast<const char*>(bytes.data());
    const int source_size = static_cast<int>(bytes.size());
    const int size = MultiByteToWideChar(
        CP_UTF8,
        MB_ERR_INVALID_CHARS,
        source,
        source_size,
        nullptr,
        0
    );
    if (size <= 0) {
        return {};
    }
    std::wstring result(static_cast<std::size_t>(size), L'\0');
    if (MultiByteToWideChar(
            CP_UTF8,
            MB_ERR_INVALID_CHARS,
            source,
            source_size,
            result.data(),
            size
        ) != size) {
        return {};
    }
    return result;
}

std::size_t CodePointCount(std::wstring_view value) {
    std::size_t count = 0;
    for (std::size_t index = 0; index < value.size(); ++index) {
        const wchar_t current = value[index];
        if (current >= 0xD800 && current <= 0xDBFF &&
            index + 1 < value.size()) {
            const wchar_t next = value[index + 1];
            if (next >= 0xDC00 && next <= 0xDFFF) {
                ++index;
            }
        }
        ++count;
    }
    return count;
}

float SourcePrior(
    MatchKind kind,
    bool has_canonical_exact,
    bool has_canonical_composition
) {
    switch (kind) {
        case MatchKind::Exact:
            return has_canonical_exact ? 8.0F : 1.2F;
        case MatchKind::Composed:
            return !has_canonical_exact && has_canonical_composition
                ? 6.0F
                : 0.55F;
        case MatchKind::Hybrid:
            return 0.55F;
        case MatchKind::Initials:
            return 0.10F;
        case MatchKind::Prefix:
            return -0.75F;
    }
    return 0.0F;
}

int MatchPriority(MatchKind kind) {
    switch (kind) {
        case MatchKind::Exact:
            return 0;
        case MatchKind::Composed:
            return 1;
        case MatchKind::Hybrid:
            return 2;
        case MatchKind::Initials:
            return 3;
        case MatchKind::Prefix:
            return 4;
    }
    return 5;
}

struct ParsedQuery {
    std::string code;
    // A true entry at i means an apostrophe requires a boundary before code[i].
    std::vector<bool> forced_boundaries;
    bool valid = true;
};

ParsedQuery ParseQuery(std::string_view raw) {
    ParsedQuery result;
    result.code.reserve(raw.size());
    result.forced_boundaries.reserve(raw.size() + 1);
    bool pending_boundary = false;
    for (const unsigned char value : raw) {
        if (value == '\'') {
            if (result.code.empty() || pending_boundary) {
                result.valid = false;
                return result;
            }
            pending_boundary = true;
            continue;
        }
        char normalized = static_cast<char>(value);
        if (normalized >= 'A' && normalized <= 'Z') {
            normalized = static_cast<char>(normalized - 'A' + 'a');
        }
        if (normalized < 'a' || normalized > 'z') {
            result.valid = false;
            return result;
        }
        result.forced_boundaries.push_back(pending_boundary);
        result.code.push_back(normalized);
        pending_boundary = false;
    }
    if (pending_boundary) {
        result.valid = false;
    }
    result.forced_boundaries.push_back(false);
    return result;
}

bool CrossesForcedBoundary(
    const std::vector<bool>& boundaries,
    std::size_t start,
    std::size_t end
) {
    if (boundaries.empty()) {
        return false;
    }
    for (std::size_t offset = start + 1; offset < end; ++offset) {
        if (offset < boundaries.size() && boundaries[offset]) {
            return true;
        }
    }
    return false;
}

bool IsCompositionEdgeAllowed(
    std::string_view query,
    std::size_t start,
    std::size_t end,
    const std::vector<bool>& forced_boundaries
) {
    if (start >= end || end > query.size() ||
        end - start > kMaximumSegmentCodeLength ||
        CrossesForcedBoundary(forced_boundaries, start, end)) {
        return false;
    }
    if (end - start > 1) {
        return true;
    }
    return query[start] == 'a' || query[start] == 'e' || query[start] == 'o';
}

}  // namespace

class PinyinLexicon::Impl final {
public:
    ~Impl() {
        Close();
    }

    bool Open(const std::filesystem::path& path, std::wstring* error) {
        Close();
        file_ = CreateFileW(
            path.c_str(),
            GENERIC_READ,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
            nullptr,
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL | FILE_FLAG_RANDOM_ACCESS,
            nullptr
        );
        if (file_ == INVALID_HANDLE_VALUE) {
            return Fail(Win32Error(L"打开拼音词库失败"), error);
        }

        LARGE_INTEGER file_size{};
        if (!GetFileSizeEx(file_, &file_size) || file_size.QuadPart <= 0 ||
            static_cast<unsigned long long>(file_size.QuadPart) >
                std::numeric_limits<std::size_t>::max()) {
            return Fail(L"拼音词库大小无效", error);
        }
        size_ = static_cast<std::size_t>(file_size.QuadPart);

        mapping_ = CreateFileMappingW(file_, nullptr, PAGE_READONLY, 0, 0, nullptr);
        if (mapping_ == nullptr) {
            return Fail(Win32Error(L"创建词库映射失败"), error);
        }
        data_ = static_cast<const std::uint8_t*>(
            MapViewOfFile(mapping_, FILE_MAP_READ, 0, 0, 0)
        );
        if (data_ == nullptr) {
            return Fail(Win32Error(L"映射拼音词库失败"), error);
        }

        if (!BuildIndex(error)) {
            Close();
            return false;
        }
        path_ = path;
        return true;
    }

    void Close() noexcept {
        offsets_.clear();
        path_.clear();
        if (data_ != nullptr) {
            UnmapViewOfFile(data_);
            data_ = nullptr;
        }
        if (mapping_ != nullptr) {
            CloseHandle(mapping_);
            mapping_ = nullptr;
        }
        if (file_ != INVALID_HANDLE_VALUE) {
            CloseHandle(file_);
            file_ = INVALID_HANDLE_VALUE;
        }
        size_ = 0;
    }

    [[nodiscard]] bool is_open() const noexcept {
        return data_ != nullptr && !offsets_.empty();
    }

    [[nodiscard]] std::size_t record_count() const noexcept {
        return offsets_.size();
    }

    [[nodiscard]] const std::filesystem::path& path() const noexcept {
        return path_;
    }

    std::vector<Candidate> Decode(
        std::string_view raw_query,
        std::size_t requested_limit
    ) const {
        if (!is_open() || requested_limit == 0) {
            return {};
        }
        const ParsedQuery parsed = ParseQuery(raw_query);
        if (!parsed.valid || parsed.code.empty() || parsed.code.size() > 96) {
            return {};
        }
        const std::size_t limit =
            (std::min)(requested_limit, kMaximumDecodeCandidates);
        const std::string& query = parsed.code;
        const bool has_forced_boundaries = std::any_of(
            parsed.forced_boundaries.begin(),
            parsed.forced_boundaries.end(),
            [](bool value) { return value; }
        );
        std::vector<Candidate> values;
        values.reserve(limit * 3);

        const std::size_t exact_index =
            has_forced_boundaries ? kNotFound : FindExact(query);
        const bool has_exact = exact_index != kNotFound;
        if (has_exact) {
            Append(
                values,
                ReadCandidates(
                    exact_index,
                    limit,
                    MatchKind::Exact,
                    query,
                    0.0F
                )
            );
        }

        std::vector<Candidate> composed =
            Compose(query, parsed.forced_boundaries, limit);
        const bool has_composition = !composed.empty();
        Append(values, std::move(composed));

        if (!has_forced_boundaries && query.size() >= 2) {
            const std::string initials_key = "~" + query;
            const std::size_t initials_index = FindExact(initials_key);
            if (initials_index != kNotFound) {
                auto initials = ReadCandidates(
                    initials_index,
                    limit,
                    MatchKind::Initials,
                    {},
                    0.0F
                );
                for (auto& candidate : initials) {
                    candidate.canonical_initials = query;
                    if (CodePointCount(candidate.text) == query.size()) {
                        candidate.score += query.size() == 4 ? 3.0F : 0.35F;
                    }
                }
                Append(values, std::move(initials));
            }
        }

        if (!has_forced_boundaries) {
            Append(values, ReadHybrid(query, limit));
        }

        if (!has_forced_boundaries && !has_exact) {
            const std::string statistical_key = "{" + query;
            const std::size_t statistical_index = FindExact(statistical_key);
            if (statistical_index != kNotFound) {
                Append(
                    values,
                    ReadCandidates(
                        statistical_index,
                        limit,
                        MatchKind::Prefix,
                        {},
                        0.0F
                    )
                );
            }
            Append(values, ReadPrefix(query, limit));
        }

        return Rank(
            std::move(values),
            limit,
            has_exact,
            has_composition
        );
    }

private:
    static constexpr std::size_t kNotFound =
        std::numeric_limits<std::size_t>::max();

    struct CompositionPath {
        std::wstring text;
        std::string initials;
        std::size_t segments = 0;
        float raw_score = 0.0F;
    };

    bool Fail(const std::wstring& message, std::wstring* error) {
        if (error != nullptr) {
            *error = message;
        }
        Close();
        return false;
    }

    bool BuildIndex(std::wstring* error) {
        if (size_ < kHeaderSize) {
            return Fail(L"拼音词库头已截断", error);
        }
        if (!std::equal(kMagic.begin(), kMagic.end(), data_)) {
            return Fail(L"拼音词库魔数不匹配", error);
        }
        const std::uint16_t version =
            static_cast<std::uint16_t>(data_[4] << 8U | data_[5]);
        if (version != kVersion) {
            return Fail(
                L"拼音词库版本不受支持：" + std::to_wstring(version),
                error
            );
        }
        const std::uint32_t record_count =
            (static_cast<std::uint32_t>(data_[6]) << 24U) |
            (static_cast<std::uint32_t>(data_[7]) << 16U) |
            (static_cast<std::uint32_t>(data_[8]) << 8U) |
            static_cast<std::uint32_t>(data_[9]);
        if (record_count == 0 || record_count > kMaximumRecords) {
            return Fail(L"拼音词库记录数无效", error);
        }

        offsets_.reserve(record_count);
        std::size_t cursor = kHeaderSize;
        for (std::uint32_t record = 0; record < record_count; ++record) {
            if (cursor >= size_) {
                return Fail(L"拼音词库记录已截断", error);
            }
            offsets_.push_back(static_cast<std::uint32_t>(cursor));
            const std::size_t code_length = data_[cursor++];
            if (code_length == 0 || !CanRead(cursor, code_length + 1)) {
                return Fail(L"拼音编码记录无效", error);
            }
            cursor += code_length;
            const std::size_t candidate_count = data_[cursor++];
            if (candidate_count == 0) {
                return Fail(L"拼音编码没有候选词", error);
            }
            for (std::size_t candidate = 0; candidate < candidate_count;
                 ++candidate) {
                if (!CanRead(cursor, 1)) {
                    return Fail(L"拼音候选长度缺失", error);
                }
                const std::size_t text_length = data_[cursor++];
                if (text_length == 0 || !CanRead(cursor, text_length + 5)) {
                    return Fail(L"拼音候选记录已截断", error);
                }
                cursor += text_length + 4;
                const std::size_t initials_length = data_[cursor++];
                if (initials_length == 0 ||
                    !CanRead(cursor, initials_length + 1)) {
                    return Fail(L"拼音候选首字母记录无效", error);
                }
                cursor += initials_length;
                const std::uint8_t source_tier = data_[cursor++];
                if (source_tier > 1) {
                    return Fail(L"拼音候选来源层级无效", error);
                }
            }
        }
        if (cursor != size_) {
            return Fail(L"拼音词库包含尾随数据", error);
        }
        return true;
    }

    [[nodiscard]] bool CanRead(
        std::size_t offset,
        std::size_t length
    ) const noexcept {
        return offset <= size_ && length <= size_ - offset;
    }

    [[nodiscard]] std::string_view Code(std::size_t index) const {
        const std::size_t offset = offsets_[index];
        const std::size_t length = data_[offset];
        return {
            reinterpret_cast<const char*>(data_ + offset + 1),
            length,
        };
    }

    [[nodiscard]] int CompareCode(
        std::size_t index,
        std::string_view query
    ) const {
        return Code(index).compare(query);
    }

    [[nodiscard]] std::size_t LowerBound(std::string_view query) const {
        std::size_t low = 0;
        std::size_t high = offsets_.size();
        while (low < high) {
            const std::size_t middle = low + (high - low) / 2;
            if (CompareCode(middle, query) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    [[nodiscard]] std::size_t FindExact(std::string_view query) const {
        const std::size_t index = LowerBound(query);
        if (index < offsets_.size() && CompareCode(index, query) == 0) {
            return index;
        }
        return kNotFound;
    }

    [[nodiscard]] static std::uint32_t ReadUInt32(
        const std::uint8_t* bytes
    ) {
        return (static_cast<std::uint32_t>(bytes[0]) << 24U) |
            (static_cast<std::uint32_t>(bytes[1]) << 16U) |
            (static_cast<std::uint32_t>(bytes[2]) << 8U) |
            static_cast<std::uint32_t>(bytes[3]);
    }

    [[nodiscard]] std::vector<Candidate> ReadCandidates(
        std::size_t index,
        std::size_t limit,
        MatchKind kind,
        std::string canonical,
        float score_adjustment
    ) const {
        std::size_t cursor = offsets_[index];
        const std::size_t code_length = data_[cursor++];
        cursor += code_length;
        const std::size_t candidate_count = data_[cursor++];
        std::vector<Candidate> result;
        result.reserve((std::min)(limit, candidate_count));
        for (std::size_t candidate_index = 0;
             candidate_index < candidate_count;
             ++candidate_index) {
            const std::size_t text_length = data_[cursor++];
            const auto text_bytes =
                std::span<const std::uint8_t>(data_ + cursor, text_length);
            cursor += text_length;
            const std::uint32_t weight = ReadUInt32(data_ + cursor);
            cursor += 4;
            const std::size_t initials_length = data_[cursor++];
            const std::string initials(
                reinterpret_cast<const char*>(data_ + cursor),
                initials_length
            );
            cursor += initials_length;
            const std::uint8_t source_tier = data_[cursor++];

            if (candidate_index >= limit) {
                continue;
            }
            std::wstring text = Utf8ToWide(text_bytes);
            if (text.empty()) {
                continue;
            }
            Candidate candidate;
            candidate.text = std::move(text);
            candidate.score =
                static_cast<float>(std::log(static_cast<double>(weight) + 1.0)) +
                score_adjustment -
                (source_tier == 1 ? kFallbackSourcePenalty : 0.0F);
            candidate.canonical_pinyin = canonical;
            candidate.canonical_initials = initials;
            candidate.match_kind = kind;
            result.push_back(std::move(candidate));
        }
        return result;
    }

    [[nodiscard]] std::vector<Candidate> ReadPrefix(
        std::string_view query,
        std::size_t limit
    ) const {
        std::unordered_map<std::wstring, Candidate> best_by_text;
        std::size_t index = LowerBound(query);
        std::size_t scanned = 0;
        while (index < offsets_.size() && scanned < kPrefixScanLimit) {
            const std::string_view code = Code(index);
            if (!code.starts_with(query)) {
                break;
            }
            if (!code.empty() && code.front() >= 'a' && code.front() <= 'z' &&
                code.size() <= kMaximumSegmentCodeLength) {
                const float completion_penalty =
                    static_cast<float>(code.size() - query.size()) * 0.08F;
                auto candidates = ReadCandidates(
                    index,
                    2,
                    MatchKind::Prefix,
                    std::string(code),
                    -completion_penalty
                );
                for (auto& candidate : candidates) {
                    auto found = best_by_text.find(candidate.text);
                    if (found == best_by_text.end() ||
                        candidate.score > found->second.score) {
                        best_by_text[candidate.text] = std::move(candidate);
                    }
                }
            }
            ++index;
            ++scanned;
        }
        std::vector<Candidate> result;
        result.reserve(best_by_text.size());
        for (auto& [_, candidate] : best_by_text) {
            result.push_back(std::move(candidate));
        }
        std::sort(
            result.begin(),
            result.end(),
            [](const Candidate& left, const Candidate& right) {
                if (left.score != right.score) {
                    return left.score > right.score;
                }
                return left.text < right.text;
            }
        );
        if (result.size() > limit) {
            result.resize(limit);
        }
        return result;
    }

    [[nodiscard]] std::vector<Candidate> ReadHybrid(
        std::string_view query,
        std::size_t limit
    ) const {
        const std::string prefix = "}" + std::string(query) + "|";
        std::size_t index = LowerBound(prefix);
        std::size_t scanned = 0;
        std::vector<Candidate> result;
        while (index < offsets_.size() && scanned < kHybridScanLimit) {
            const std::string_view code = Code(index);
            if (!code.starts_with(prefix)) {
                break;
            }
            const std::string canonical(code.substr(prefix.size()));
            Append(
                result,
                ReadCandidates(
                    index,
                    (std::min)(limit, std::size_t{16}),
                    MatchKind::Hybrid,
                    canonical,
                    0.0F
                )
            );
            ++index;
            ++scanned;
        }
        return result;
    }

    static float CompositionScore(const CompositionPath& path) {
        if (path.segments == 0) {
            return -std::numeric_limits<float>::infinity();
        }
        return (
            path.raw_score -
            static_cast<float>(path.segments - 1) * kWordBoundaryCost
        ) / static_cast<float>(path.segments);
    }

    static void PruneCompositionBeam(std::vector<CompositionPath>& beam) {
        if (beam.size() <= kCompositionBeamWidth) {
            return;
        }
        std::sort(
            beam.begin(),
            beam.end(),
            [](const CompositionPath& left, const CompositionPath& right) {
                const float left_score = CompositionScore(left);
                const float right_score = CompositionScore(right);
                if (left_score != right_score) {
                    return left_score > right_score;
                }
                if (left.segments != right.segments) {
                    return left.segments < right.segments;
                }
                return left.text < right.text;
            }
        );
        std::unordered_map<std::wstring, std::size_t> seen;
        std::vector<CompositionPath> retained;
        retained.reserve(kCompositionBeamWidth);
        for (auto& path : beam) {
            const std::wstring identity =
                path.text + L"\x1f" + std::to_wstring(path.segments);
            if (seen.emplace(identity, retained.size()).second) {
                retained.push_back(std::move(path));
                if (retained.size() == kCompositionBeamWidth) {
                    break;
                }
            }
        }
        beam = std::move(retained);
    }

    [[nodiscard]] std::vector<Candidate> Compose(
        std::string_view query,
        const std::vector<bool>& forced_boundaries,
        std::size_t limit
    ) const {
        std::vector<std::vector<CompositionPath>> beams(query.size() + 1);
        beams[0].push_back(CompositionPath{});
        for (std::size_t start = 0; start < query.size(); ++start) {
            auto& source = beams[start];
            if (source.empty()) {
                continue;
            }
            PruneCompositionBeam(source);
            const std::size_t maximum_end =
                (std::min)(query.size(), start + kMaximumSegmentCodeLength);
            for (std::size_t end = start + 1; end <= maximum_end; ++end) {
                if (!IsCompositionEdgeAllowed(
                        query,
                        start,
                        end,
                        forced_boundaries
                    )) {
                    continue;
                }
                const std::string_view segment = query.substr(start, end - start);
                const std::size_t record = FindExact(segment);
                if (record == kNotFound) {
                    continue;
                }
                const auto options = ReadCandidates(
                    record,
                    kCandidatesPerSegment,
                    MatchKind::Composed,
                    std::string(segment),
                    0.0F
                );
                auto& target = beams[end];
                for (const auto& path : source) {
                    for (const auto& option : options) {
                        CompositionPath next;
                        next.text.reserve(path.text.size() + option.text.size());
                        next.text = path.text;
                        next.text += option.text;
                        next.initials.reserve(
                            path.initials.size() +
                            option.canonical_initials.size()
                        );
                        next.initials = path.initials;
                        next.initials += option.canonical_initials;
                        next.segments = path.segments + 1;
                        next.raw_score = path.raw_score + option.score;
                        target.push_back(std::move(next));
                    }
                }
                if (target.size() >= kCompositionBeamWidth * 4) {
                    PruneCompositionBeam(target);
                }
            }
        }
        auto& final_beam = beams.back();
        PruneCompositionBeam(final_beam);
        std::vector<Candidate> result;
        result.reserve((std::min)(limit, final_beam.size()));
        for (auto& path : final_beam) {
            if (path.segments < 2) {
                continue;
            }
            Candidate candidate;
            candidate.text = std::move(path.text);
            candidate.score = CompositionScore(path);
            candidate.canonical_pinyin = std::string(query);
            candidate.canonical_initials = std::move(path.initials);
            candidate.match_kind = MatchKind::Composed;
            result.push_back(std::move(candidate));
        }
        return result;
    }

    static std::vector<Candidate> Rank(
        std::vector<Candidate> values,
        std::size_t limit,
        bool has_exact,
        bool has_composition
    ) {
        struct Scored {
            Candidate candidate;
            float total = 0.0F;
        };
        std::unordered_map<std::wstring, Scored> best_by_text;
        for (auto& candidate : values) {
            if (candidate.text.empty() || !std::isfinite(candidate.score)) {
                continue;
            }
            Scored scored{
                std::move(candidate),
                0.0F,
            };
            scored.total =
                scored.candidate.score +
                SourcePrior(
                    scored.candidate.match_kind,
                    has_exact,
                    has_composition
                );
            auto found = best_by_text.find(scored.candidate.text);
            if (found == best_by_text.end() ||
                scored.total > found->second.total ||
                (scored.total == found->second.total &&
                 MatchPriority(scored.candidate.match_kind) <
                     MatchPriority(found->second.candidate.match_kind))) {
                best_by_text[scored.candidate.text] = std::move(scored);
            }
        }
        std::vector<Scored> scored;
        scored.reserve(best_by_text.size());
        for (auto& [_, candidate] : best_by_text) {
            scored.push_back(std::move(candidate));
        }
        std::sort(
            scored.begin(),
            scored.end(),
            [](const Scored& left, const Scored& right) {
                if (left.total != right.total) {
                    return left.total > right.total;
                }
                const int left_priority =
                    MatchPriority(left.candidate.match_kind);
                const int right_priority =
                    MatchPriority(right.candidate.match_kind);
                if (left_priority != right_priority) {
                    return left_priority < right_priority;
                }
                const std::size_t left_length =
                    CodePointCount(left.candidate.text);
                const std::size_t right_length =
                    CodePointCount(right.candidate.text);
                if (left_length != right_length) {
                    return left_length < right_length;
                }
                return left.candidate.text < right.candidate.text;
            }
        );
        if (scored.size() > limit) {
            scored.resize(limit);
        }
        std::vector<Candidate> result;
        result.reserve(scored.size());
        for (auto& value : scored) {
            result.push_back(std::move(value.candidate));
        }
        return result;
    }

    static void Append(
        std::vector<Candidate>& target,
        std::vector<Candidate> source
    ) {
        target.reserve(target.size() + source.size());
        std::move(source.begin(), source.end(), std::back_inserter(target));
    }

    HANDLE file_ = INVALID_HANDLE_VALUE;
    HANDLE mapping_ = nullptr;
    const std::uint8_t* data_ = nullptr;
    std::size_t size_ = 0;
    std::vector<std::uint32_t> offsets_;
    std::filesystem::path path_;
};

PinyinLexicon::PinyinLexicon() : impl_(std::make_unique<Impl>()) {}

PinyinLexicon::~PinyinLexicon() = default;

PinyinLexicon::PinyinLexicon(PinyinLexicon&&) noexcept = default;

PinyinLexicon& PinyinLexicon::operator=(PinyinLexicon&&) noexcept = default;

bool PinyinLexicon::Open(
    const std::filesystem::path& path,
    std::wstring* error
) {
    return impl_->Open(path, error);
}

void PinyinLexicon::Close() noexcept {
    impl_->Close();
}

bool PinyinLexicon::is_open() const noexcept {
    return impl_->is_open();
}

std::size_t PinyinLexicon::record_count() const noexcept {
    return impl_->record_count();
}

const std::filesystem::path& PinyinLexicon::path() const noexcept {
    return impl_->path();
}

std::vector<Candidate> PinyinLexicon::Decode(
    std::string_view composing,
    std::size_t limit
) const {
    return impl_->Decode(composing, limit);
}

}  // namespace sense
