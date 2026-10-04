// Real-model exhaustive check. No calibration or evaluation labels are read.
#include "word_lm_graph.h"
#include <libime/core/languagemodel.h>
#include <libime/core/lattice.h>
#include <fstream>
#include <functional>
#include <iostream>

struct Model {
    using State=libime::State;
    libime::LanguageModel lm;
    explicit Model(const char *file):lm(file){lm.setUnknownPenalty(0.);}
    unsigned order()const{return 3;}
    State start()const{return lm.nullState();}
    sense::WordId unknown()const{return lm.unknown();}
    sense::WordId index(const std::string &word)const{return lm.index(word);}
    double step(const State &state,const std::string &word,sense::WordId id,State &out)const{
        return lm.score(state,libime::WordNode(word,id),out);
    }
};

int main(int argc,char **argv){
    try{
        if(argc!=3)throw std::runtime_error("Usage: verify MODEL REQUESTS.tsv");
        Model model(argv[1]);std::ifstream input(argv[2]);
        if(!input)throw std::runtime_error("Input unavailable");
        size_t cases=0,completedPaths=0,multiple=0;std::string line;
        while(cases<256 && std::getline(input,line)){
            const auto a=line.find('\t'),b=line.find('\t',a+1);
            if(a==std::string::npos || b==std::string::npos)throw std::runtime_error("Bad request");
            auto text=line.substr(a+1,b-a-1)+line.substr(b+1);auto offsets=sense::han_offsets(text);
            if(offsets.size()>11)continue;
            double best=-std::numeric_limits<double>::infinity();std::vector<std::string> bestWords;
            size_t paths=0;
            std::function<void(size_t,Model::State,double,std::vector<std::string>)> visit;
            visit=[&](size_t at,Model::State state,double score,std::vector<std::string> words){
                if(at+1==offsets.size()){
                    ++paths;if(score>best || (score==best && words<bestWords)){best=score;bestWords=std::move(words);}return;
                }
                for(size_t end=at+1;end<offsets.size() && end<=at+8;++end){
                    auto word=text.substr(offsets[at],offsets[end]-offsets[at]);auto id=model.index(word);
                    if(id==model.unknown() && end>at+1)continue;
                    Model::State next{};auto probability=model.step(state,word,id,next);
                    auto path=words;path.push_back(word);visit(end,next,score+probability,std::move(path));
                }
            };
            visit(0,model.start(),0.,{});
            auto dynamic=sense::word_graph(model,text);
            if(dynamic.score!=best || dynamic.words!=bestWords)throw std::runtime_error("Real model DP differs from exhaustive paths");
            completedPaths+=paths;multiple+=(paths>1);++cases;
        }
        if(cases!=256 || multiple<128)throw std::runtime_error("Insufficient competing native paths");
        std::cout<<"{\"passed\":true,\"cases\":"<<cases<<",\"completePaths\":"<<completedPaths
                 <<",\"multiplePathCases\":"<<multiple<<",\"maxHan\":10}\n";
    }catch(const std::exception &e){std::cerr<<e.what()<<'\n';return 1;}
}
