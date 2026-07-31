#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace sense::agent {

inline constexpr std::string_view kProtocolVersion = "sense.agent.bridge.v1";
inline constexpr std::wstring_view kPipeName = LR"(\\.\pipe\sense.agent.v1)";
inline constexpr std::size_t kMaximumSnapshotChars = 65'536;
inline constexpr std::size_t kMaximumInstructionChars = 4'096;

enum class SnapshotCapability {
    FullDocument,
    SelectionOnly,
    SurroundingWindow,
    Unavailable,
};

struct EditorSnapshot {
    std::string request_id;
    std::uint64_t generation = 0;
    SnapshotCapability capability = SnapshotCapability::Unavailable;
    std::wstring before_cursor;
    std::wstring selected_text;
    std::wstring after_cursor;
    std::string document_hash;
};

struct SkillInvocation {
    std::string skill_id;
    std::uint64_t revision = 0;
    std::wstring instruction;
    EditorSnapshot snapshot;
};

struct ValidationResult {
    bool ok = false;
    std::string error_code;
};

[[nodiscard]] ValidationResult Validate(const EditorSnapshot& snapshot);
[[nodiscard]] ValidationResult Validate(const SkillInvocation& invocation);

/**
 * Emits the bounded newline-delimited JSON request understood by AgentHost.
 * Text is encoded as JSON escapes without adding any provider credentials.
 */
[[nodiscard]] std::string Serialize(const SkillInvocation& invocation);

}  // namespace sense::agent
