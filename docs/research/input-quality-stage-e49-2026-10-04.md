# E49：最低支持版本、显示密度与可恢复的系统测试

## 结果

本轮从 rc.7 发布后的范围核对出发，补足此前只有 API37 系统证据的缺口。
**不修改产品源码、不更换模型，也不发布只有测试变化的新 APK。**

同一产品 Debug APK、同一最终仪器测试 APK：

| 环境 | 检查 | 通过数 |
|---|---|---:|
| API29 Google APIs / 420 dpi | 竖/横屏 × 100%/200% 字号，每格三项 | 12 |
| API29 Google APIs / 480 dpi | 相同完整矩阵 | 12 |
| API29 Google APIs | 系统交互时限设为 10 秒，5.5 秒后联想仍可选择 | 1 |
| API37 既有专用模拟器 / 420 dpi | 相同字号/方向矩阵的兼容回归 | 12 |
| Python | 目标绑定、状态解析、严格恢复、异步回写及既有汇总检查 | 15 |

最终 **37 次 Android 执行通过**，其中 API29 为 25 次；不与 E48 的 49 项旧证据相加冒充本轮执行。
观察到的 fixture 应用堆为 API29 **512MiB**、API37 **192MiB**。
两台均为 x86_64、1080×1920、无窗口软件图形模拟器；并行执行的是非计时功能检查，不作性能比较。

每个矩阵格验证真实点击候选及后续输入、展开连续滚动后提交实际可见词、关闭联想后的键盘几何。
每项还核对 Activity 实际密度/字号/方向、外部编辑框最终文本和 composing 结束状态。
SDK/AVD、已安装 APK 的摘要、输入法与字号/旋转/密度恢复均检查，不以请求参数代替实际系统状态。

## 为什么第一轮失败没有被算作通过

### AOSP 默认镜像：词库加载时真实 OOM

首次使用官方 `system-images;android-29;default;x86_64` revision 8。
日志显示 46,495,528 字节分配失败，ART 报 `growth limit 16777216`；输入法降级状态为
`ready=true characterModel=DISABLED`。12 项测试均停在完整运行时就绪条件之前。
这是真实的资源加载失败，不是简单地把 dumpsys 输出字段读错。

该进程的实际增长上限为 16MiB，尽管系统属性报告 heapsize 为 512m；本轮没有进一步判定镜像与模拟器之间的底层原因。
这不足以断言所有 Android 10 或所有 AOSP 设备都有同样问题。
[Android 10 CDD 3.7](https://source.android.com/docs/compatibility/10/android-10-cdd#3_7_runtime_compatibility)
对 small/normal 屏幕的 420/480 dpi 应用内存下限分别是 112/128MiB，因此该失败环境不被用作这一屏幕配置的平台覆盖证明。

改用另一个独立 AVD 的官方 `google_apis;x86_64` revision 13；没有修改产品、字典或质量断言。
新增 fixture 堆下限前置检查和真实堆记录，避免以后把类似环境异常解释成语义算法问题。
最终 Google APIs 镜像的 512MiB 通过，**不等于已经证明在 112/128MiB 下也能运行完整词库**。
初始镜像、属性、OOM、12 项失败和恢复状态完整归档，两个新 AVD 结束后均关闭。

### 字号恢复：输入通过仍不足以判整轮通过

新镜像 420 dpi 首轮 12 项输入均通过，但最初不存在的 `font_scale` 在恢复时变成 `1.0`。
这会改变一次性设备的设置状态，因此严格恢复门槛仍失败。

先把字体恢复放在旋转设置之后，仍观察到同样失败：API29 会异步持久化配置，将默认字号重新写入。
最终处理只作用于测试收尾：最多四次恢复写入，每次要求三次间隔 250ms 的精确读回；所有尝试保留。
原始值和最终一致性断言均未放宽，永久不一致仍报错。
收集后再补一条显式稳定性拒绝断言及单测：即使随后某次单读恰好匹配，也须已经完成三次稳定观察。
已对保存的两组最终观察重新执行该断言，未新增 Android 执行数；测量时/提交时两份工具源码分别归档。

重新建立 `font_scale=null` 的前提后，完整重跑四格矩阵，而非只重复某个失败用例。
最终观测到 `[["1.0"], ["null", "null", "null"]]`，证明实际遇到了回写，并在有界流程中恢复原状。
该轮才进入最终 37 项统计。此前 480 dpi 与时限组已经通过输入和精确恢复，产品/fixture APK 相同，其成功证据明确保留。

## 实现和复现入口

- `tools/test_input_configuration.py`：只接受三个具名的一次性 AVD，核对 API；增加密度、堆前置参数和可审计的恢复流程。
- `ExternalEditorTestFixture.kt`：在选择 Sense 前记录 fixture 堆、memory class 和 SDK；未给产品加入特权测试接口。
- `ExternalEditorConfigurationTest.kt`：断言 Activity 实际 densityDpi，记录 SDK、字号、方向及窗口范围。
- `tools/test_configuration_target.py`：新增独立工具回归，覆盖手机/未知 AVD/错误 API 拒绝、错误密度、异步回写和重试上限。

安装固定 SDK 镜像并启动相应一次性 AVD 后，用全新目录保留每次证据：

```powershell
python -m unittest discover -s tools -p test_configuration_target.py
python tools/test_input_configuration.py E:/e49-new-run --serial emulator-5584 --avd sense-input-quality-api29-google --density 420 --minimum-heap-mib 128
# 下一完整矩阵使用新的目录和 --density 480；时限组使用 --timeout-only。
# --skip-install 仍核对每一个已安装 APK 摘要，仍须通过身份校验。
```

原始冻结、镜像修正和恢复修正分别在 `e49-minimum-api-policy.json`、
`e49-minimum-api-environment-amendment.json`、`e49-restoration-policy.json`，不覆盖早期规则或输出。
构建只重新执行本轮需要的 Debug/fixture 任务；没有把未重跑的 973 项主机结果计入本轮。

## 视觉观察、产物与边界

实际查看 480 dpi、200% 字号的竖屏 composing 和横屏展开截图：候选主体与按键文字可见，
横屏展开视口顶部有滚动后的部分行裁切，这是滚动位置而不是所有词格高度不足。
系统状态栏及外部 fixture 的大字号标题不作为 Sense 自身控件的视觉通过证据。
两张截图保留原始像素，没有重绘。

产品 Debug SHA-256：`a86949bd8c155f719a528849e30e745d37af18c4fc20ac41a5936d01c7a11c22`。
最终 fixture SHA-256：`6a6895f40c03de1da170ebc48983c9ca1872d79eeba34b89e60dd0d700ec5121`。
190 项生产输入源码匹配 E48，25 个输入/许可资产相同；rc.7 正式 APK 和线上四个附件未变。
专用 API37 恢复 Gboard，API29 恢复原 Gboard、字体缺省和物理 420 dpi，debug_app 均为 null。
实体手机未操作。原始日志、环境、截图、失败和恢复记录见 `e49-evidence-manifest.json` 与 `e49-acceptance-gate.json`。

本轮证明上述配置的功能路径，不证明最低内存预算、自然手机延迟、更多厂商 ROM 或语言模型准确率提高。
完整目标仍有自然同音句、最新搜索成本及低内存预算等缺口，见 [范围核对](../development/input-quality-scope-audit-2026-10-04.md)。
