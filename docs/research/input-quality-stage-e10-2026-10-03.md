# E10：全拼上下文传递、同音歧义基线与模型负面试验

日期：2026-10-03。范围：26 键全拼；工作区 `codex/input-quality-v2`。

## 结论

1. 修复真实服务层的 Unicode 上下文截断：原来向 `InputConnection` 请求 2 个 UTF-16 单元，
   不等于 LM 所需的两个完整汉字。现在最多读取 4 个单元，再只保留最后两个码点。
2. 主机测试先复现 8 项中 2 项失败，再全部通过；新增边界测试共 11 项。
   专用 Android 模拟器新增的 8 个外部输入框上下文场景全部通过，最终包总计 50 项系统场景通过。
3. 为已有 64 句带审核拼音的样本建立 540 个后缀、4 种模式、2,160 次完整解码的上下文对照。
   **有上下文的 397/540 首选，对比无上下文的 323/540，是现有模型的消融结果，不是 E9→E10 的提升。**
4. 保留出现一次的三元组虽然降低开发困惑度，却在人工诊断中产生首选损失；该模型未进入 APK。
5. 找到可操作的下一问题：“价格 + shiliangnianqiandeliangbei”受到旧词库“格式”加分干扰。
   本阶段没有据这些测试答案继续调参、加特例词或改核心排序。

整个专业化目标继续；这一阶段不宣称总体输入质量已经完成，也未发布正式版。

## 1. 服务到解码器的实际路径

`SenseInputMethodService.handlePinyinCharacter` 在第一次建立 composition、且修改编辑框之前，
读取左侧上下文；编辑框拒绝 `setComposingText` 时不推进本地状态。
后续按键复用这个 composition 的前文，避免把正在输入的拉丁字母当作上下文。

`CandidateDecodeRequest` 把前文传给 `AdaptivePinyinDecoder.decodeProgressively`。
适配层把外部前文与已经选择、仍在组合区内的汉字接起来；字符 LM 使用最后两个汉字码点，
标点/非汉字切断状态。前文只作暂态解码状态，本次没有新增日志、数据库列或用户原文持久化。

### 修复前

- `旧文𠀀好` 读取最后两个 UTF-16 单元，会从 `𠀀` 的低代理项开始。
- `前文𠀀𠀁` 只能读到最后一个完整扩展汉字。
- LM 因此丢失本应可用的前一个字。普通 BMP 汉字的“两字”路径未受此问题影响。

### 修复后

- 新 `EditorDecodeContext.MAX_UTF16_UNITS = 4`。
- 从尾部至多反向走两个码点，再转换为 String；即使宿主多返回文字，也不复制整段正文。
- 只保留两个码点，包括必要的标点边界，不把读取窗口扩大为更长记忆。
- 外部编辑框与 Agent 草稿的前文读取使用同一窗口规则；本轮未重新执行 Agent 前台 UI 验收。
- 禁止读取的会话先行返回；宿主读取异常得到空上下文；拒绝组合更新不改变本地状态。

改动仅在服务的前文读取与新辅助类。核心解码源文件与 E9 的固定哈希一致，
APK 中 25 项 assets 和已封存 E8/E9 的资产一致；不涉及 LM 权重、个人词、搜索预算或键盘布局变更。

## 2. 先诊断而非凭感觉调词频

先固定 40 组人工同音词诊断，再分别运行无前文、给定前文、前文后加句号三种模式。
这是作者选择的诊断，不是用户自然输入抽样，也不是独立人工金标准。

| 模式 | 目标首选数 / 40 |
|---|---:|
| 无前文 | 23 |
| 给定前文 | 28 |
| 前文后加句号 | 23 |

例如，“我很 + lei”从“类”转为“累”；“想象 + li”从“里”转为“力”。
但“学习 + zhishi”仍以“只是”为首选，“拔掉 + zhichi”仍有明显缺口。
实际模型概率和词库搭配得分保存在 `scores-before-utf8.txt`，没有把调试输出写入 Android 服务。

排查假设的结果：

- **前文完全漏传**：被主机直接解码和新增 8 项外部编辑框对照否定；但发现并修复了 UTF-16 窗口问题。
- **语料证据不足**：多个日常搭配概率很低；训练资料仅 776,701 个汉字，覆盖仍有限。
- **旧排序特征干扰**：后面的整句后缀对照找到“格式”搭配压过目标的具体证据；仍需独立修复与验证。

## 3. 负面结果：保留 singleton trigram 不进入默认包

用原来的 train/dev、同一词表/折扣/平滑参数，只把 `min_trigram_count` 从 2 调为 1。
训练仍使用已署名、按族分离的 Tatoeba 数据；未读取 test 来拟合。

| 指标 | 当前生产模型 | singleton 试验 |
|---|---:|---:|
| 模型字节数 | 3,090,930 | 7,185,498 |
| 开发集 3-gram 困惑度 | 52.5431 | 49.1288 |
| 40 组上下文诊断目标首选 | 28 | 27 |

“听从 + zhishi → 指示”名次 5→2，但“会议 + jilu → 记录”名次 1→2。
“记录/纪录”存在语义差异，困惑度与产品首选质量需要分别判断。
试验模型哈希 `e59c58fdaa0f949b52f65aceea9caad2f75fdf2fb5346728bfdbf8c4c0aa7e6a`。
它只在研究产物中留存，生产仍为 `39ea5d90…45a7d`；没有按诊断结果追加例外词。

## 4. 可复用的上下文后缀评测

来源：既有 `benchmarks/corpus/p2c-layered-v4/test.tsv`，64 句，已经用于此前评测。
句子、署名、拼音审核和冻结元数据一并保留；这里不把旧样本变换冒充新的盲测。

每句在每个汉字后切开，给定正确的前缀，输入剩余部分的审核全拼，得到 540 个非空后缀。
不根据解码输出挑选切点；没有额外筛除失败记录。生产绑定为 LM 0.5、OOV -4、255 候选、空个人词库。

四种模式：

- `empty`：无前文。
- `editor`：给出正确前缀的最后两个码点，模拟外部编辑框已有文字。
- `boundary`：前文后加句号，应与无前文一致。
- `accepted`：将前文最后一个字置于未提交的已选组合段，其前一个字作为外部前文，应与 `editor` 一致。

| 模式 | 首选 | 前五 | 255 内召回 |
|---|---:|---:|---:|
| empty | 323 | 437 | 511 |
| editor | 397 | 469 | 520 |
| boundary | 323 | 437 | 511 |
| accepted | 397 | 469 | 520 |

540 组 `editor == accepted` 和 540 组 `empty == boundary` 的**完整结果指纹**均一致。
不是仅比较目标名次；工具也检查模式缺失、重复、标签变化与 rank/top1 自相矛盾。

上下文总体更有用，但仍保留 4 条目标首选损失和 18 条目标名次下降。
这些是现有上下文开关之间的差异，不是本次 Unicode 修复引入的退步。

| 前文 | 目标后缀 | 有上下文首选 | 目标名次 |
|---|---|---|---:|
| 价格 | 是两年前的两倍 | 式两年前的两倍 | 1→4 |
| 可以 | 叫我鲍勃 | 教我鲍勃 | 1→2 |
| 能想 | 像一辆蓝色的法拉利吗 | 象一辆蓝色的法拉利吗 | 1→4 |
| 个月 | 以来一直在读古兰经 | 一来一直在读古兰经 | 1→8 |

“想像/想象”包含文字变体与标注选择，目标名次损失不等于每项都是同样严重的语言错误。
完整列表在 `benchmarks/results/e10-context-p2c.json`，不是只展示变好的例子。

### 具体下一修复线索

“价格”后的 `式两年前的两倍` 最终分 16.191150，其中旧 bigram 的“格式”贡献 3.0；
目标 `是两年前的两倍` 最终分 14.072844，旧 bigram 贡献 0。
完整句子的 LM 特征按 query normalizer 缩放，而外部单字搭配仍按原值加入，
可能让长输入被这一个搭配主导。这里是有得分证据的待验证假设，尚未据此改变生产配置。

下一步应统一外部搭配与整句特征的分数域，并用新的开发/冻结评测验证，
同时保留短词、个人词、T9、标点边界和 prefix beam 的既有行为；不为“价格”写特判。

## 5. 构建与系统验收

主机：**866** 项通过：core 318、UI 235、service 310、About 3。
Python：**347** 项运行，346 通过，1 项 Windows 符号链接权限相关跳过。

同一个最终 APK、同一个测试 APK，在专用 API 37 x86_64 模拟器 `emulator-5580` 上：

| 场景组 | 通过数量 |
|---|---:|
| 新增上下文传递/生命周期 | 8 |
| 普通外部输入 | 10 |
| 显式拼音分词 | 6 |
| 混合拼写召回 | 3 |
| 纠错 | 8 |
| 联想生命周期 | 9 |
| 冷启动 | 4 |
| 实际个人学习与重启 | 2 |
| 合计 | **50** |

新上下文组关闭个性化学习：前文来自外部 EditText，候选和 Space 上屏走真实系统 IME。
覆盖两个同音词上下文、光标中间插入、选区替换、句号边界、切换输入框、删除整段组合后重捕获、
上一段刚提交的文字成为下一段前文。已检查选区替换后的截图：外部编辑框为“我很累”，工具栏/键盘正常。

最终学习组在可丢弃模拟器配置内重新验证“程彻”“智能体”的显式学习、再次接受、进程重启与隐私隔离。
SQLite 只读审计及每组 APK 哈希保留。本轮未接触实体手机，未重新测量 ABBA 延迟，不借用上一版 P95 充当本版实测。

APK：`app/build/outputs/apk/debug/app-debug.apk`，85,826,196 字节。
SHA-256：`5ecf199435663ef1cbb6436b0bbdb0354612a7ca4cb8998cf6cf89ef8dee6f81`。
证书：**Android Debug**，v2 校验通过；这是开发验收包，不是沿用正式发布证书的 release 包。

## 6. 复现与证据

```powershell
$env:JAVA_HOME='F:/Android/Jdk/jdk-17'
$env:ANDROID_HOME='F:/Android/Sdk'
$env:PYTHONUTF8='1'
$env:PYTHONPATH='tools'
$Py='C:/Users/syc/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'

./gradlew.bat --offline --no-parallel --console=plain `
  :core-input:test :ime-ui:testDebugUnitTest :ime-service:testDebugUnitTest `
  :app:testDebugUnitTest --tests '*AboutNoticeControllerTest' `
  :app:assembleDebug :input-quality-device:assembleDebug :input-quality-device:assembleDebugAndroidTest
& $Py -m unittest discover -s tools -p 'test_*.py' -v
./tools/test_external_editor.ps1 -SkipBuild -Context -RunName NEW-UNIQUE-RUN

$Stdlib=Get-ChildItem C:/Users/syc/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.2.0 `
  -Filter '*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
& "$env:JAVA_HOME/bin/java.exe" '-Dfile.encoding=UTF-8' `
  --class-path "core-input/build/classes/kotlin/main;$Stdlib" tools/ContextReplay.java `
  benchmarks/corpus/p2c-layered-v4/test.tsv NEW-context.tsv
& $Py tools/summarize_context_replay.py NEW-context.tsv NEW-report.json
```

原始记录：`.artifacts/input-quality/e10`、`external-editor-e10-*-final`。
封存脚本：`tools/collect_context_stage.py`；先验证前阶段压缩/原文哈希、核心源代码、资产和全部验收，再生成一次性证据集。
最终索引：`benchmarks/results/e10-evidence-manifest.json` 与 `e10-acceptance-gate.json`。
封存后改源代码，应开启新阶段，不覆盖本次结果或重复运行封存器。
