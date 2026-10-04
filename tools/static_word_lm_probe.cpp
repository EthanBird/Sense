// Static word LM only. Never reads a reference label, pinyin dictionary or user history.
#include "word_lm_graph.h"
#include <libime/core/languagemodel.h>
#include <libime/core/lattice.h>
#include <chrono>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <set>
#include <sstream>

struct StaticModel {
    using State = libime::State;
    libime::LanguageModel model;
    explicit StaticModel(const char *path) : model(path) { model.setUnknownPenalty(0.); }
    // Caller pins the E14 model and its verified trigram ARPA identity before execution.
    unsigned order() const { return 3; }
    State start() const { return model.nullState(); }
    sense::WordId unknown() const { return model.unknown(); }
    sense::WordId index(const std::string &word) const { return model.index(word); }
    double step(const State &state, const std::string &word, sense::WordId id, State &out) const {
        return model.score(state,libime::WordNode(word,id),out);
    }
};

std::string quote(const std::string &value) {
    std::ostringstream out; out << '"';
    for (unsigned char c : value) {
        if (c=='"' || c=='\\') out << '\\' << c;
        else if (c<0x20) out << "\\u00" << std::hex << std::setw(2) << std::setfill('0') << int(c);
        else out << c;
    }
    out << '"'; return out.str();
}

int main(int argc,char **argv) {
    try {
        if(argc!=3) throw std::runtime_error("Usage: static-word-lm MODEL THREE_COLUMN_ID_CONTEXT_TEXT.tsv");
        StaticModel model(argv[1]); std::ifstream input(argv[2]);
        if(!input) throw std::runtime_error("Input unavailable");
        std::cout << std::setprecision(17) << "{\"type\":\"header\",\"schemaVersion\":1,\"modelOrder\":3,"
            "\"reader\":\"libime-1.0.11-static\",\"maxWordLength\":8,\"additionalUnknownPenalty\":0,"
            "\"start\":\"null\",\"eos\":false,\"beamPruning\":false}\n";
        std::set<std::string> ids; std::string line; size_t count=0;
        while(std::getline(input,line)) {
            if(!line.empty() && line.back()=='\r')line.pop_back();
            if(line.empty())continue;
            const auto a=line.find('\t'), b=line.find('\t',a==std::string::npos?line.size():a+1);
            if(a==std::string::npos || b==std::string::npos || line.find('\t',b+1)!=std::string::npos)
                throw std::runtime_error("Expected exactly three fields");
            auto id=line.substr(0,a), context=line.substr(a+1,b-a-1), text=line.substr(b+1);
            if(id.empty() || !ids.insert(id).second || sense::han_offsets(context).size()>3 ||
                sense::han_offsets(text).size()<3 || sense::han_offsets(text).size()>25)
                throw std::runtime_error("Invalid or duplicate bounded request");
            auto begin=std::chrono::steady_clock::now();
            const auto result=sense::word_graph(model,context+text);
            const auto nanos=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-begin).count();
            // Check each chosen edge directly against the model, independently of chart merging.
            double direct=0.; auto state=model.start();
            for(const auto &word:result.words) { StaticModel::State next{};
                direct+=model.step(state,word,model.index(word),next); state=next; }
            if(direct!=result.score)throw std::runtime_error("Chosen path differs from direct LM score");
            std::cout << "{\"type\":\"row\",\"id\":"<<quote(id)<<",\"context\":"<<quote(context)<<",\"text\":"<<quote(text)
                <<",\"log10Score\":"<<result.score<<",\"unknowns\":"<<result.unknowns<<",\"transitions\":"<<result.transitions
                <<",\"states\":"<<result.states<<",\"nanos\":"<<nanos<<",\"directExact\":true,\"words\":[";
            bool comma=false; for(const auto &word:result.words) { if(comma)std::cout<<',';comma=true;std::cout<<quote(word); }
            std::cout<<"]}\n"; if(!std::cout)throw std::runtime_error("Output failed"); ++count;
        }
        if(!input.eof() || !count)throw std::runtime_error("Incomplete or empty request file");
        std::cout<<"{\"type\":\"summary\",\"rows\":"<<count<<"}\n";
        return std::cout?0:2;
    } catch(const std::exception &e) {std::cerr<<e.what()<<'\n';return 1;}
}
