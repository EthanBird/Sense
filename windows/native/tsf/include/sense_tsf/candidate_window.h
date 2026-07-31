#pragma once

#include <Windows.h>

#include <cstddef>
#include <functional>
#include <string>
#include <vector>

namespace sense::tsf {

class CandidateWindow final {
public:
    using CommitHandler = std::function<void(std::size_t)>;

    CandidateWindow();
    ~CandidateWindow();

    CandidateWindow(const CandidateWindow&) = delete;
    CandidateWindow& operator=(const CandidateWindow&) = delete;

    [[nodiscard]] bool Create(CommitHandler on_commit);
    void Destroy();
    void Show(bool visible);

    void Update(
        std::wstring composition,
        std::vector<std::wstring> candidates,
        std::size_t selection,
        const RECT& anchor
    );

    [[nodiscard]] bool is_visible() const noexcept;

    static LRESULT CALLBACK WindowProcedure(
        HWND window,
        UINT message,
        WPARAM wparam,
        LPARAM lparam
    );

private:
    LRESULT HandleMessage(UINT message, WPARAM wparam, LPARAM lparam);
    void Paint();
    void ResetFonts();
    void RecalculateLayout();
    void MoveToAnchor(const RECT& anchor);
    [[nodiscard]] UINT Dpi() const;

    HWND window_ = nullptr;
    HFONT composition_font_ = nullptr;
    HFONT candidate_font_ = nullptr;
    std::wstring composition_;
    std::vector<std::wstring> candidates_;
    std::vector<RECT> item_rectangles_;
    std::size_t selection_ = 0;
    std::size_t page_start_ = 0;
    int desired_width_ = 320;
    int desired_height_ = 76;
    bool requested_visible_ = false;
    CommitHandler on_commit_;
};

}  // namespace sense::tsf
