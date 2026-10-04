#include "word_lm_graph.h"
#include <functional>
#include <iostream>

struct ToyModel {
    using State = std::array<unsigned, 2>;
    unsigned order() const { return 3; }
    State start() const { return {0, 0}; }
    sense::WordId unknown() const { return 0; }
    std::map<std::string, unsigned> vocab{{"甲",1},{"乙",2},{"丙",3},{"甲乙",4},{"乙甲",5},{"甲丙",6},{"乙丙甲",7}};
    sense::WordId index(const std::string &word) const { auto it = vocab.find(word); return it == vocab.end() ? 0 : it->second; }
    double step(const State &in, const std::string &, sense::WordId id, State &out) const {
        out = {in[1], id};
        return -.125 * (1 + ((in[0]*37 + in[1]*13 + id*11) % 23));
    }
};

void require(bool result, const char *message) { if (!result) throw std::runtime_error(message); }

sense::GraphResult exhaustive(ToyModel &model, const std::string &text) {
    auto offsets = sense::han_offsets(text);
    sense::GraphResult best; best.score = -std::numeric_limits<double>::infinity();
    std::function<void(size_t,ToyModel::State,double,std::vector<std::string>,size_t)> visit;
    visit = [&](size_t at, ToyModel::State state, double score, std::vector<std::string> words, size_t unknown) {
        if (at + 1 == offsets.size()) {
            if (sense::better(score, words, best)) best = {score, words, unknown, 0, 0};
            return;
        }
        for (size_t end = at + 1; end < offsets.size() && end <= at + 8; ++end) {
            auto word = text.substr(offsets[at], offsets[end] - offsets[at]); auto id = model.index(word);
            if (id == model.unknown() && end > at + 1) continue;
            ToyModel::State next{}; const auto step = model.step(state, word, id, next);
            auto path = words; path.push_back(word); visit(end, next, score+step, std::move(path), unknown+(id==0));
        }
    };
    visit(0, model.start(), 0., {}, 0); return best;
}

int main() {
    try {
        ToyModel model; size_t cases = 0;
        std::function<void(std::string,unsigned)> visit = [&](std::string text, unsigned left) {
            const auto expected = exhaustive(model,text); const auto actual = sense::word_graph(model,text);
            require(expected.score==actual.score && expected.words==actual.words && expected.unknowns==actual.unknowns,
                    "DP disagrees with exhaustive enumeration");
            ++cases;
            if (!left) return;
            for (const auto *word : {"甲","乙","丙"}) visit(text+word, left-1);
        };
        visit("", 7);
        require(sense::word_graph(model,"甲𠀀乙").unknowns==1,"Supplementary unknown singleton lost");
        for (const auto &bad : std::vector<std::string>{"a", "甲。", "\xc0\x80", "\xed\xa0\x80", "\xf4\x90\x80\x80", "\xe7\x94"}) {
            bool rejected = false; try { sense::han_offsets(bad); } catch (const std::runtime_error &) { rejected = true; }
            require(rejected,"Invalid text accepted");
        }
        bool rejected = false; std::string longText;
        for (int i=0;i<27;++i) longText += "甲";
        try { sense::word_graph(model,longText); } catch (const std::runtime_error &) { rejected = true; }
        require(rejected,"Unbounded graph accepted");
        std::cout << "{\"passed\":true,\"exhaustiveCases\":" << cases
                  << ",\"supplementaryUnknown\":true,\"malformedInputs\":6,\"lengthBound\":true}\n";
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
