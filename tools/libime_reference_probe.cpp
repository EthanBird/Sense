// Host reference adapter, not a production engine or a UI timing benchmark.
// Invokes public libime APIs. Each query owns a fresh context; no learn/save calls.
#include <libime/core/userlanguagemodel.h>
#include <libime/pinyin/pinyincontext.h>
#include <libime/pinyin/pinyindictionary.h>
#include <libime/pinyin/pinyinime.h>
#include <chrono>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_set>
#include <vector>

std::string quote(const std::string &value) {
    std::ostringstream out;
    out << '"';
    for (const unsigned char c : value) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c < 0x20) out << "\\u00" << std::hex << std::setw(2) << std::setfill('0') << int(c);
        else out << c;
    }
    out << '"';
    return out.str();
}

std::vector<std::string> fields(const std::string &line) {
    std::vector<std::string> result;
    size_t start = 0;
    do {
        auto end = line.find('\t', start);
        result.push_back(line.substr(start, end - start));
        if (end == std::string::npos) break;
        start = end + 1;
    } while (true);
    return result;
}

int main(int argc, char **argv) {
    try {
        if (argc != 4) throw std::runtime_error("Usage: libime-reference MODEL DICTIONARY FIVE_COLUMN_INPUT.tsv");
        std::ifstream input(argv[3]);
        if (!input) throw std::runtime_error("Input file not readable");
        libime::PinyinIME ime(std::make_unique<libime::PinyinDictionary>(),
                             std::make_unique<libime::UserLanguageModel>(argv[1]));
        ime.dict()->load(libime::PinyinDictionary::SystemDict, argv[2], libime::PinyinDictFormat::Binary);
        // A fixed probe configuration, not selected on this evaluation workload.
        ime.setNBest(10);
        // This older package predates CommonTypo/AdvancedTypo; do not label it as the new engine.
        ime.setFuzzyFlags(libime::PinyinFuzzyFlag::Inner);
        std::cout << std::setprecision(9)
                  << "{\"type\":\"header\",\"schemaVersion\":1,\"engine\":\"libime-1.0.11-ubuntu-jammy\","
                  << "\"mode\":\"whole-sentence-incremental\",\"nbest\":" << ime.nbest()
                  << ",\"beamSize\":" << ime.beamSize() << ",\"frameSize\":" << ime.frameSize()
                  << ",\"learning\":false,\"fuzzy\":[\"Inner\"]}\n";
        std::unordered_set<std::string> ids;
        std::string line;
        size_t count = 0;
        while (std::getline(input, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty() || line[0] == '#') continue;
            const auto row = fields(line);
            if (row.size() != 5 || row[0].empty() || !ids.insert(row[0]).second ||
                row[1].empty() || row[1].size() > 96 ||
                row[1].find_first_not_of("abcdefghijklmnopqrstuvwxyz'") != std::string::npos)
                throw std::runtime_error("Invalid or duplicate five-column input");
            // row[2] (the answer) is deliberately never passed to the engine.
            libime::PinyinContext context(&ime);
            auto start = std::chrono::steady_clock::now();
            for (char c : row[1]) {
                if (!context.type(std::string(1, c))) throw std::runtime_error("Engine rejected a keystroke");
            }
            auto micros = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
            std::cout << "{\"type\":\"query\",\"id\":" << quote(row[0])
                      << ",\"query\":" << quote(row[1]) << ",\"sentence\":" << quote(context.sentence())
                      << ",\"typingMicros\":" << micros << ",\"candidates\":[";
            bool comma = false;
            for (const auto &candidate : context.candidates()) {
                if (comma) std::cout << ',';
                comma = true;
                auto consumed = candidate.sentence().empty() ? 0 : candidate.sentence().back()->to()->index();
                std::cout << "{\"text\":" << quote(candidate.toString()) << ",\"score\":" << candidate.score()
                          << ",\"consumed\":" << consumed << '}';
            }
            std::cout << "]}\n";
            if (!std::cout) throw std::runtime_error("Output write failed");
            ++count;
        }
        if (!input.eof() || count == 0) throw std::runtime_error("Incomplete or empty input");
        std::cout << "{\"type\":\"summary\",\"rows\":" << count << "}\n";
        return std::cout ? 0 : 2;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
