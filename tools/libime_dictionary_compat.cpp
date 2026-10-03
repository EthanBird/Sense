// Strict old-encoder compatibility filter. Never reads evaluation text or candidate results.
#include <libime/pinyin/pinyinencoder.h>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    std::ifstream source(argv[1]);
    if (!source) return 3;
    size_t count = 0, accepted = 0;
    std::string line;
    while (std::getline(source, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        ++count;
        // Upstream uses both spaces and tabs; score is optional (default zero).
        std::istringstream fields(line);
        std::string word, reading;
        if (!(fields >> word >> reading)) return 4;
        try {
            const auto encoded = libime::PinyinEncoder::encodeFullPinyin(reading);
            if (encoded.empty() || encoded.size() % 2) throw std::invalid_argument("empty or unaligned code");
            std::cout << line << '\n'; ++accepted;
        } catch (const std::invalid_argument &error) {
            std::cerr << count << '\t' << line << '\t' << error.what() << '\n';
        }
    }
    if (!source.eof() || !std::cout || count == 0) return 5;
    std::cerr << "TOTAL\t" << count << "\tACCEPTED\t" << accepted << "\tEXCLUDED\t" << count - accepted << '\n';
}
