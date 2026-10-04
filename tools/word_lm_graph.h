// Host prototype: exact trigram-state Viterbi, reusable independently of libime.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <map>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

namespace sense {
using WordId = uint32_t;
struct History {
    WordId older = 0, newer = 0;
    unsigned length = 0;
    bool operator<(const History &o) const { return std::tie(length, older, newer) < std::tie(o.length, o.older, o.newer); }
    History next(WordId id) const { return {newer, id, std::min(2u, length + 1)}; }
};

struct GraphResult {
    double score = 0.;
    std::vector<std::string> words;
    size_t unknowns = 0, transitions = 0, states = 0;
};

inline bool better(double score, const std::vector<std::string> &words, const GraphResult &other) {
    return score > other.score || (score == other.score && words < other.words);
}

// Strict Unicode scalar decoding; reject malformed UTF-8 before graph construction.
inline std::vector<size_t> han_offsets(const std::string &text) {
    std::vector<size_t> offsets{0};
    for (size_t at = 0; at < text.size();) {
        const auto lead = static_cast<unsigned char>(text[at]);
        unsigned count; uint32_t cp, minimum;
        if (lead < 0x80) { count = 1; cp = lead; minimum = 0; }
        else if (lead >= 0xc2 && lead <= 0xdf) { count = 2; cp = lead & 0x1f; minimum = 0x80; }
        else if (lead >= 0xe0 && lead <= 0xef) { count = 3; cp = lead & 0x0f; minimum = 0x800; }
        else if (lead >= 0xf0 && lead <= 0xf4) { count = 4; cp = lead & 7; minimum = 0x10000; }
        else throw std::runtime_error("Invalid UTF-8 lead");
        if (at + count > text.size()) throw std::runtime_error("Truncated UTF-8");
        for (unsigned i = 1; i < count; ++i) {
            auto c = static_cast<unsigned char>(text[at+i]);
            if ((c & 0xc0) != 0x80) throw std::runtime_error("Invalid UTF-8 continuation");
            cp = (cp << 6) | (c & 0x3f);
        }
        if (cp < minimum || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff))
            throw std::runtime_error("Invalid Unicode scalar");
        const bool han = (cp >= 0x3400 && cp <= 0x4dbf) || (cp >= 0x4e00 && cp <= 0x9fff) ||
            (cp >= 0xf900 && cp <= 0xfaff) || (cp >= 0x20000 && cp <= 0x2ebef) ||
            (cp >= 0x2f800 && cp <= 0x2fa1f) || (cp >= 0x30000 && cp <= 0x323af);
        if (!han) throw std::runtime_error("Expected Han input");
        at += count; offsets.push_back(at);
    }
    if (offsets.size() > 27) throw std::runtime_error("Word graph input exceeds 26 Han");
    return offsets;
}

template<class Model>
GraphResult word_graph(Model &model, const std::string &text) {
    if (model.order() != 3) throw std::runtime_error("Trigram model required");
    const auto offsets = han_offsets(text); const auto n = offsets.size() - 1;
    struct Path { GraphResult result; typename Model::State state; };
    std::vector<std::map<History, Path>> chart(n+1);
    chart[0].emplace(History{}, Path{GraphResult{}, model.start()});
    size_t transitions = 0, states = 0;
    for (size_t start = 0; start < n; ++start) {
        states += chart[start].size();
        for (size_t end = start + 1; end <= std::min(n, start + 8); ++end) {
            auto word = text.substr(offsets[start], offsets[end] - offsets[start]);
            const auto id = model.index(word); const bool unknown = id == model.unknown();
            if (unknown && end != start + 1) continue;
            for (const auto &[history, path] : chart[start]) {
                typename Model::State next{};
                const double step = model.step(path.state, word, id, next);
                if (!std::isfinite(step)) throw std::runtime_error("Nonfinite LM step");
                const double score = path.result.score + step;
                auto words = path.result.words; words.push_back(word);
                auto key = history.next(id); auto previous = chart[end].find(key);
                if (previous == chart[end].end() || better(score, words, previous->second.result)) {
                    chart[end][key] = Path{GraphResult{score, std::move(words), path.result.unknowns + unknown, 0, 0}, next};
                }
                ++transitions;
            }
        }
    }
    states += chart[n].size();
    GraphResult best; best.score = -std::numeric_limits<double>::infinity();
    for (const auto &[_, path] : chart[n]) {
        if (better(path.result.score, path.result.words, best)) best = path.result;
    }
    if (!std::isfinite(best.score)) throw std::runtime_error("Missing complete LM path");
    best.transitions = transitions; best.states = states;
    return best;
}
} // namespace sense
