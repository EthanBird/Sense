#include "sense_tsf/candidate_window.h"

#include "sense_tsf/module.h"

#include <dwmapi.h>
#include <windowsx.h>

#include <algorithm>
#include <array>
#include <utility>

namespace sense::tsf {
namespace {

constexpr wchar_t kWindowClassName[] = L"Sense.CandidateWindow.v1";
constexpr COLORREF kBackground = RGB(17, 24, 39);
constexpr COLORREF kHeaderText = RGB(148, 163, 184);
constexpr COLORREF kCandidateText = RGB(241, 245, 249);
constexpr COLORREF kIndexText = RGB(103, 232, 249);
constexpr COLORREF kSelection = RGB(14, 116, 144);
constexpr COLORREF kDivider = RGB(51, 65, 85);
constexpr std::size_t kPageSize = 5;

int Scale(int value, UINT dpi) {
    return MulDiv(value, static_cast<int>(dpi), 96);
}

ATOM EnsureWindowClass() {
    static ATOM atom = [] {
        WNDCLASSEXW value{};
        value.cbSize = sizeof(value);
        value.style = CS_HREDRAW | CS_VREDRAW | CS_DROPSHADOW;
        value.lpfnWndProc = &CandidateWindow::WindowProcedure;
        value.hInstance = g_module_instance;
        value.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        value.lpszClassName = kWindowClassName;
        return RegisterClassExW(&value);
    }();
    return atom;
}

}  // namespace

CandidateWindow::CandidateWindow() = default;

CandidateWindow::~CandidateWindow() {
    Destroy();
}

bool CandidateWindow::Create(CommitHandler on_commit) {
    if (window_ != nullptr) {
        on_commit_ = std::move(on_commit);
        return true;
    }
    if (EnsureWindowClass() == 0) {
        return false;
    }
    on_commit_ = std::move(on_commit);
    window_ = CreateWindowExW(
        WS_EX_TOOLWINDOW | WS_EX_TOPMOST | WS_EX_NOACTIVATE,
        kWindowClassName,
        L"",
        WS_POPUP,
        0,
        0,
        desired_width_,
        desired_height_,
        nullptr,
        nullptr,
        g_module_instance,
        this
    );
    if (window_ == nullptr) {
        return false;
    }

    // Values are available on supported Windows 10/11 builds. Unknown
    // attributes are simply ignored by older desktop hosts.
    constexpr DWORD kUseImmersiveDarkMode = 20;
    constexpr BOOL enabled = TRUE;
    DwmSetWindowAttribute(
        window_,
        kUseImmersiveDarkMode,
        &enabled,
        sizeof(enabled)
    );
    constexpr DWORD kWindowCornerPreference = 33;
    constexpr DWORD kRoundCorners = 2;
    DwmSetWindowAttribute(
        window_,
        kWindowCornerPreference,
        &kRoundCorners,
        sizeof(kRoundCorners)
    );
    RecalculateLayout();
    return true;
}

void CandidateWindow::Destroy() {
    if (window_ != nullptr) {
        DestroyWindow(window_);
        window_ = nullptr;
    }
    ResetFonts();
    item_rectangles_.clear();
    candidates_.clear();
    on_commit_ = {};
}

void CandidateWindow::ResetFonts() {
    if (composition_font_ != nullptr) {
        DeleteObject(composition_font_);
        composition_font_ = nullptr;
    }
    if (candidate_font_ != nullptr) {
        DeleteObject(candidate_font_);
        candidate_font_ = nullptr;
    }
}

void CandidateWindow::Show(bool visible) {
    requested_visible_ = visible;
    if (window_ != nullptr) {
        ShowWindow(window_, visible ? SW_SHOWNOACTIVATE : SW_HIDE);
    }
}

void CandidateWindow::Update(
    std::wstring composition,
    std::vector<std::wstring> candidates,
    std::size_t selection,
    const RECT& anchor
) {
    composition_ = std::move(composition);
    candidates_ = std::move(candidates);
    selection_ = candidates_.empty()
        ? 0
        : (std::min)(selection, candidates_.size() - 1);
    page_start_ = (selection_ / kPageSize) * kPageSize;
    RecalculateLayout();
    MoveToAnchor(anchor);
    if (window_ != nullptr) {
        InvalidateRect(window_, nullptr, FALSE);
        if (requested_visible_) {
            ShowWindow(window_, SW_SHOWNOACTIVATE);
        }
    }
}

bool CandidateWindow::is_visible() const noexcept {
    return window_ != nullptr && IsWindowVisible(window_) != FALSE;
}

LRESULT CALLBACK CandidateWindow::WindowProcedure(
    HWND window,
    UINT message,
    WPARAM wparam,
    LPARAM lparam
) {
    CandidateWindow* self = reinterpret_cast<CandidateWindow*>(
        GetWindowLongPtrW(window, GWLP_USERDATA)
    );
    if (message == WM_NCCREATE) {
        const auto* create = reinterpret_cast<const CREATESTRUCTW*>(lparam);
        self = static_cast<CandidateWindow*>(create->lpCreateParams);
        self->window_ = window;
        SetWindowLongPtrW(
            window,
            GWLP_USERDATA,
            reinterpret_cast<LONG_PTR>(self)
        );
    }
    if (self != nullptr) {
        return self->HandleMessage(message, wparam, lparam);
    }
    return DefWindowProcW(window, message, wparam, lparam);
}

LRESULT CandidateWindow::HandleMessage(
    UINT message,
    WPARAM wparam,
    LPARAM lparam
) {
    switch (message) {
        case WM_MOUSEACTIVATE:
            return MA_NOACTIVATE;
        case WM_ERASEBKGND:
            return 1;
        case WM_PAINT:
            Paint();
            return 0;
        case WM_LBUTTONUP: {
            const POINT point{
                GET_X_LPARAM(lparam),
                GET_Y_LPARAM(lparam),
            };
            for (std::size_t index = 0; index < item_rectangles_.size();
                 ++index) {
                if (PtInRect(&item_rectangles_[index], point) != FALSE) {
                    if (on_commit_) {
                        on_commit_(page_start_ + index);
                    }
                    break;
                }
            }
            return 0;
        }
        case WM_DPICHANGED: {
            const auto* suggested = reinterpret_cast<const RECT*>(lparam);
            SetWindowPos(
                window_,
                HWND_TOPMOST,
                suggested->left,
                suggested->top,
                suggested->right - suggested->left,
                suggested->bottom - suggested->top,
                SWP_NOACTIVATE
            );
            ResetFonts();
            RecalculateLayout();
            SetWindowPos(
                window_,
                HWND_TOPMOST,
                suggested->left,
                suggested->top,
                desired_width_,
                desired_height_,
                SWP_NOACTIVATE
            );
            return 0;
        }
        case WM_NCDESTROY:
            SetWindowLongPtrW(window_, GWLP_USERDATA, 0);
            return DefWindowProcW(window_, message, wparam, lparam);
        default:
            return DefWindowProcW(window_, message, wparam, lparam);
    }
}

void CandidateWindow::Paint() {
    PAINTSTRUCT paint{};
    HDC dc = BeginPaint(window_, &paint);
    if (dc == nullptr) {
        return;
    }

    RECT client{};
    GetClientRect(window_, &client);
    HBRUSH background = CreateSolidBrush(kBackground);
    FillRect(dc, &client, background);
    DeleteObject(background);
    SetBkMode(dc, TRANSPARENT);

    const UINT dpi = Dpi();
    const int padding = Scale(12, dpi);
    const int header_height = Scale(28, dpi);
    SelectObject(dc, composition_font_);
    SetTextColor(dc, kHeaderText);
    std::wstring header = L"SENSE  ·  ";
    header += composition_;
    RECT header_rect{
        padding,
        0,
        client.right - padding,
        header_height,
    };
    DrawTextW(
        dc,
        header.c_str(),
        static_cast<int>(header.size()),
        &header_rect,
        DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS
    );

    HPEN divider_pen = CreatePen(PS_SOLID, 1, kDivider);
    HGDIOBJ old_pen = SelectObject(dc, divider_pen);
    MoveToEx(dc, padding, header_height, nullptr);
    LineTo(dc, client.right - padding, header_height);
    SelectObject(dc, old_pen);
    DeleteObject(divider_pen);

    SelectObject(dc, candidate_font_);
    for (std::size_t visible = 0; visible < item_rectangles_.size();
         ++visible) {
        const std::size_t absolute = page_start_ + visible;
        RECT item = item_rectangles_[visible];
        if (absolute == selection_) {
            HBRUSH selection_brush = CreateSolidBrush(kSelection);
            FillRect(dc, &item, selection_brush);
            DeleteObject(selection_brush);
        }

        std::wstring index_text = std::to_wstring(visible + 1);
        RECT index_rect = item;
        index_rect.left += Scale(10, dpi);
        index_rect.right = index_rect.left + Scale(18, dpi);
        SetTextColor(dc, kIndexText);
        DrawTextW(
            dc,
            index_text.c_str(),
            static_cast<int>(index_text.size()),
            &index_rect,
            DT_LEFT | DT_VCENTER | DT_SINGLELINE
        );

        RECT text_rect = item;
        text_rect.left += Scale(30, dpi);
        text_rect.right -= Scale(10, dpi);
        SetTextColor(dc, kCandidateText);
        const std::wstring& text = candidates_[absolute];
        DrawTextW(
            dc,
            text.c_str(),
            static_cast<int>(text.size()),
            &text_rect,
            DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS
        );
    }
    EndPaint(window_, &paint);
}

void CandidateWindow::RecalculateLayout() {
    if (window_ == nullptr) {
        return;
    }
    const UINT dpi = Dpi();
    if (composition_font_ == nullptr) {
        composition_font_ = CreateFontW(
            -Scale(11, dpi),
            0,
            0,
            0,
            FW_SEMIBOLD,
            FALSE,
            FALSE,
            FALSE,
            DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY,
            DEFAULT_PITCH | FF_DONTCARE,
            L"Segoe UI Variable Text"
        );
    }
    if (candidate_font_ == nullptr) {
        candidate_font_ = CreateFontW(
            -Scale(18, dpi),
            0,
            0,
            0,
            FW_NORMAL,
            FALSE,
            FALSE,
            FALSE,
            DEFAULT_CHARSET,
            OUT_DEFAULT_PRECIS,
            CLIP_DEFAULT_PRECIS,
            CLEARTYPE_QUALITY,
            DEFAULT_PITCH | FF_DONTCARE,
            L"Microsoft YaHei UI"
        );
    }

    HDC dc = GetDC(window_);
    HGDIOBJ old_font = SelectObject(dc, candidate_font_);
    const int header_height = Scale(29, dpi);
    const int row_height = Scale(44, dpi);
    const int minimum_item = Scale(78, dpi);
    const int maximum_item = Scale(170, dpi);
    const int horizontal_text_padding = Scale(45, dpi);
    int x = Scale(6, dpi);
    item_rectangles_.clear();
    const std::size_t page_end =
        (std::min)(candidates_.size(), page_start_ + kPageSize);
    for (std::size_t index = page_start_; index < page_end; ++index) {
        SIZE measured{};
        GetTextExtentPoint32W(
            dc,
            candidates_[index].c_str(),
            static_cast<int>(candidates_[index].size()),
            &measured
        );
        const int width = (std::clamp)(
            static_cast<int>(measured.cx) + horizontal_text_padding,
            minimum_item,
            maximum_item
        );
        item_rectangles_.push_back(
            RECT{x, header_height + Scale(4, dpi), x + width, header_height + row_height}
        );
        x += width + Scale(3, dpi);
    }
    SelectObject(dc, old_font);
    ReleaseDC(window_, dc);

    desired_width_ = (std::max)(Scale(320, dpi), x + Scale(4, dpi));
    desired_height_ = header_height + row_height + Scale(4, dpi);
    HRGN region = CreateRoundRectRgn(
        0,
        0,
        desired_width_ + 1,
        desired_height_ + 1,
        Scale(12, dpi),
        Scale(12, dpi)
    );
    SetWindowRgn(window_, region, TRUE);
}

void CandidateWindow::MoveToAnchor(const RECT& anchor) {
    if (window_ == nullptr) {
        return;
    }
    POINT point{anchor.left, anchor.bottom + Scale(6, Dpi())};
    HMONITOR monitor = MonitorFromPoint(point, MONITOR_DEFAULTTONEAREST);
    MONITORINFO information{};
    information.cbSize = sizeof(information);
    GetMonitorInfoW(monitor, &information);
    int x = point.x;
    int y = point.y;
    if (x + desired_width_ > information.rcWork.right) {
        x = information.rcWork.right - desired_width_;
    }
    if (x < information.rcWork.left) {
        x = information.rcWork.left;
    }
    if (y + desired_height_ > information.rcWork.bottom) {
        y = anchor.top - desired_height_ - Scale(6, Dpi());
    }
    if (y < information.rcWork.top) {
        y = information.rcWork.top;
    }
    const int bounded_width = (std::min)(
        desired_width_,
        static_cast<int>(
            information.rcWork.right - information.rcWork.left
        )
    );
    const int bounded_height = (std::min)(
        desired_height_,
        static_cast<int>(
            information.rcWork.bottom - information.rcWork.top
        )
    );
    SetWindowPos(
        window_,
        HWND_TOPMOST,
        x,
        y,
        bounded_width,
        bounded_height,
        SWP_NOACTIVATE | SWP_NOOWNERZORDER
    );
}

UINT CandidateWindow::Dpi() const {
    if (window_ != nullptr) {
        const UINT dpi = GetDpiForWindow(window_);
        if (dpi != 0) {
            return dpi;
        }
    }
    return 96;
}

}  // namespace sense::tsf
