#include "sense/agent_protocol.h"

#include <Windows.h>

#include <algorithm>
#include <array>
#include <charconv>
#include <sstream>

namespace sense::agent {
namespace {

std::size_t CharacterCount(const EditorSnapshot& snapshot) {
    return snapshot.before_cursor.size() + snapshot.selected_text.size() +
        snapshot.after_cursor.size();
}

bool IsIdentifier(std::string_view value, std::size_t maximum) {
    if (value.empty() || value.size() > maximum) {
        return false;
    }
    for (const unsigned char character : value) {
        const bool valid =
            (character >= 'a' && character <= 'z') ||
            (character >= 'A' && character <= 'Z') ||
            (character >= '0' && character <= '9') || character == '-' ||
            character == '_' || character == '.';
        if (!valid) {
            return false;
        }
    }
    return true;
}

std::string WideToUtf8(std::wstring_view value) {
    if (value.empty()) {
        return {};
    }
    const int source_size = static_cast<int>(value.size());
    const int size = WideCharToMultiByte(
        CP_UTF8,
        WC_ERR_INVALID_CHARS,
        value.data(),
        source_size,
        nullptr,
        0,
        nullptr,
        nullptr
    );
    if (size <= 0) {
        return {};
    }
    std::string result(static_cast<std::size_t>(size), '\0');
    if (WideCharToMultiByte(
            CP_UTF8,
            WC_ERR_INVALID_CHARS,
            value.data(),
            source_size,
            result.data(),
            size,
            nullptr,
            nullptr
        ) != size) {
        return {};
    }
    return result;
}

void AppendEscaped(std::string& output, std::string_view value) {
    static constexpr std::array<char, 16> kHex = {
        '0',
        '1',
        '2',
        '3',
        '4',
        '5',
        '6',
        '7',
        '8',
        '9',
        'a',
        'b',
        'c',
        'd',
        'e',
        'f',
    };
    output.push_back('"');
    for (const unsigned char character : value) {
        switch (character) {
            case '"':
                output += R"(\")";
                break;
            case '\\':
                output += R"(\\)";
                break;
            case '\b':
                output += R"(\b)";
                break;
            case '\f':
                output += R"(\f)";
                break;
            case '\n':
                output += R"(\n)";
                break;
            case '\r':
                output += R"(\r)";
                break;
            case '\t':
                output += R"(\t)";
                break;
            default:
                if (character < 0x20) {
                    output += "\\u00";
                    output.push_back(kHex[(character >> 4U) & 0x0FU]);
                    output.push_back(kHex[character & 0x0FU]);
                } else {
                    output.push_back(static_cast<char>(character));
                }
                break;
        }
    }
    output.push_back('"');
}

std::string_view CapabilityWireValue(SnapshotCapability capability) {
    switch (capability) {
        case SnapshotCapability::FullDocument:
            return "FULL_DOCUMENT";
        case SnapshotCapability::SelectionOnly:
            return "SELECTION_ONLY";
        case SnapshotCapability::SurroundingWindow:
            return "SURROUNDING_WINDOW";
        case SnapshotCapability::Unavailable:
            return "UNAVAILABLE";
    }
    return "UNAVAILABLE";
}

}  // namespace

ValidationResult Validate(const EditorSnapshot& snapshot) {
    if (!IsIdentifier(snapshot.request_id, 128)) {
        return {false, "INVALID_REQUEST_ID"};
    }
    if (snapshot.generation == 0) {
        return {false, "INVALID_GENERATION"};
    }
    if (CharacterCount(snapshot) > kMaximumSnapshotChars) {
        return {false, "SNAPSHOT_TOO_LARGE"};
    }
    if (!snapshot.document_hash.empty() &&
        (snapshot.document_hash.size() != 64 ||
         !std::all_of(
             snapshot.document_hash.begin(),
             snapshot.document_hash.end(),
             [](const unsigned char value) {
                 return (value >= '0' && value <= '9') ||
                     (value >= 'a' && value <= 'f');
             }
         ))) {
        return {false, "INVALID_DOCUMENT_HASH"};
    }
    return {true, {}};
}

ValidationResult Validate(const SkillInvocation& invocation) {
    if (!IsIdentifier(invocation.skill_id, 128)) {
        return {false, "INVALID_SKILL_ID"};
    }
    if (invocation.revision == 0) {
        return {false, "INVALID_SKILL_REVISION"};
    }
    if (invocation.instruction.empty() ||
        invocation.instruction.size() > kMaximumInstructionChars) {
        return {false, "INVALID_INSTRUCTION"};
    }
    return Validate(invocation.snapshot);
}

std::string Serialize(const SkillInvocation& invocation) {
    const ValidationResult validation = Validate(invocation);
    if (!validation.ok) {
        return {};
    }

    std::string output;
    output.reserve(512 + CharacterCount(invocation.snapshot) * 2);
    output += R"({"protocol":)";
    AppendEscaped(output, kProtocolVersion);
    output += R"(,"type":"skill.invoke","skill_id":)";
    AppendEscaped(output, invocation.skill_id);
    output += R"(,"skill_revision":)";
    output += std::to_string(invocation.revision);
    output += R"(,"instruction":)";
    AppendEscaped(output, WideToUtf8(invocation.instruction));
    output += R"(,"snapshot":{"request_id":)";
    AppendEscaped(output, invocation.snapshot.request_id);
    output += R"(,"generation":)";
    output += std::to_string(invocation.snapshot.generation);
    output += R"(,"capability":)";
    AppendEscaped(
        output,
        CapabilityWireValue(invocation.snapshot.capability)
    );
    output += R"(,"before_cursor":)";
    AppendEscaped(output, WideToUtf8(invocation.snapshot.before_cursor));
    output += R"(,"selected_text":)";
    AppendEscaped(output, WideToUtf8(invocation.snapshot.selected_text));
    output += R"(,"after_cursor":)";
    AppendEscaped(output, WideToUtf8(invocation.snapshot.after_cursor));
    output += R"(,"document_hash":)";
    AppendEscaped(output, invocation.snapshot.document_hash);
    output += "}}\n";
    return output;
}

}  // namespace sense::agent
