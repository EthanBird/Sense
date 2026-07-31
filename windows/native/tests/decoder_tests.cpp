#include "sense/agent_protocol.h"
#include "sense/input_session.h"
#include "sense/pinyin_lexicon.h"

#include <algorithm>
#include <filesystem>
#include <iostream>
#include <string>
#include <vector>

namespace {

int failures = 0;

void Expect(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

bool Contains(
    const std::vector<sense::Candidate>& candidates,
    std::wstring_view text
) {
    return std::any_of(
        candidates.begin(),
        candidates.end(),
        [text](const sense::Candidate& candidate) {
            return candidate.text == text;
        }
    );
}

void TestDecoder(const std::filesystem::path& asset) {
    sense::PinyinLexicon lexicon;
    std::wstring error;
    Expect(lexicon.Open(asset, &error), "SPLX v3 lexicon opens");
    if (!lexicon.is_open()) {
        std::wcerr << L"decoder error: " << error << L'\n';
        return;
    }
    Expect(
        lexicon.record_count() > 800'000,
        "production lexicon index contains the Frost-derived records"
    );

    const auto nihao = lexicon.Decode("nihao", 50);
    Expect(!nihao.empty(), "nihao returns candidates");
    Expect(Contains(nihao, L"你好"), "nihao contains 你好");

    const auto zhongguo = lexicon.Decode("zhongguo", 50);
    Expect(Contains(zhongguo, L"中国"), "zhongguo contains 中国");

    const auto sentence = lexicon.Decode("nihaoshijie", 50);
    Expect(Contains(sentence, L"你好世界"), "sentence beam composes 你好世界");

    const auto forcedBoundary = lexicon.Decode("xi'an", 50);
    Expect(Contains(forcedBoundary, L"西安"), "apostrophe forces a syllable boundary");

    const auto initials = lexicon.Decode("zgr", 50);
    Expect(!initials.empty(), "initials namespace returns candidates");

    Expect(
        lexicon.Decode("ni hao", 10).empty(),
        "invalid characters do not enter the decoder"
    );
    Expect(
        lexicon.Decode(std::string(97, 'a'), 10).empty(),
        "composition length is bounded"
    );

    sense::InputSession session(&lexicon);
    Expect(session.Type('N'), "session accepts uppercase ASCII letters");
    Expect(session.Type('i'), "session accepts a second letter");
    Expect(session.composing() == "ni", "session normalizes composing text");
    Expect(!session.candidates().empty(), "session refreshes candidates");
    Expect(session.MoveSelection(1), "session selection moves");
    Expect(session.Backspace(), "session backspace mutates composition");
    session.Reset();
    Expect(session.empty(), "session reset is atomic");
}

void TestAgentProtocol() {
    sense::agent::SkillInvocation invocation;
    invocation.skill_id = "rewrite.concise";
    invocation.revision = 3;
    invocation.instruction = L"保持语气，缩短到两句话。";
    invocation.snapshot.request_id = "request-42";
    invocation.snapshot.generation = 9;
    invocation.snapshot.capability =
        sense::agent::SnapshotCapability::SurroundingWindow;
    invocation.snapshot.before_cursor = L"前文";
    invocation.snapshot.selected_text = L"待处理文本";
    invocation.snapshot.after_cursor = L"后文";
    invocation.snapshot.document_hash = std::string(64, 'a');

    const auto validation = sense::agent::Validate(invocation);
    Expect(validation.ok, "bounded agent invocation validates");
    const std::string json = sense::agent::Serialize(invocation);
    Expect(
        json.find("\"protocol\":\"sense.agent.bridge.v1\"") !=
            std::string::npos,
        "agent bridge emits protocol version"
    );
    Expect(
        json.find("\"type\":\"skill.invoke\"") != std::string::npos,
        "agent bridge emits invocation type"
    );
    Expect(!json.empty() && json.back() == '\n', "bridge uses NDJSON framing");

    invocation.snapshot.before_cursor.assign(
        sense::agent::kMaximumSnapshotChars + 1,
        L'x'
    );
    Expect(
        !sense::agent::Validate(invocation).ok,
        "agent snapshot upper bound is enforced"
    );
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    if (argc != 2) {
        std::wcerr << L"usage: SenseCoreTests <pinyin_lexicon.bin>\n";
        return 2;
    }
    TestDecoder(argv[1]);
    TestAgentProtocol();
    if (failures != 0) {
        std::cerr << failures << " test(s) failed\n";
        return 1;
    }
    std::cout << "SenseCoreTests passed\n";
    return 0;
}
