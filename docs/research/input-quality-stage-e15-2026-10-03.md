# E15：第二来源语料与平衡字符模型试验

## 结论

**不替换应用模型；开发门槛失败，保留测试集未执行状态。**

新增 AISHELL-NER 文本生产线，训练了一版旧日常语料和新朗读语料按汉字数近 1:1 混合的字符模型。
新领域开发集明显改善，但旧日常回归的字错数和前五命中退步。数据增加有价值，直接混合计数还不适合默认发布。

| 开发领域（每组 64 句、128 状态） | 首选 | 前五 | 前十 | 255 项内目标召回 | 首选字错数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| AISHELL，当前模型 → 平衡模型 | 65 → 84 | 97 → 103 | 105 → 109 | 118 → 121 | 142 → 86 / 1,087 字 |
| Tatoeba 已知日常回归 | 83 → 83 | 113 → 112 | 120 → 119 | 121 → 121 | 70 → 74 / 941 字 |

数字是来源文字重建，不是用户自然输入准确率。每句包括整句和中点后缀，后缀提供前两字的正确上下文；两个状态相关。
整句部分 AISHELL 首选 29→37/64；日常整句 40→40/64。没有把后缀正确上下文当成真实上文识别。

## 来源、许可与预处理

- 作者原库：[Alibaba-NLP/AISHELL-NER](https://github.com/Alibaba-NLP/AISHELL-NER/tree/fb11b6495281bc9bbc2bea7b8bde9eb3c19a4605)，固定提交 `fb11b6495281bc9bbc2bea7b8bde9eb3c19a4605`。
- [论文](https://arxiv.org/abs/2202.08533)研究中文语音命名实体识别；此处只复用转写文字，没有复现其 ASR，也没有借用论文成绩评价拼音输入法。
- 保存仓库 Apache-2.0 LICENSE 原文、README、Git tree 和逐文件 SHA-256/Git blob 校验。
  [底层 AISHELL-1 页面](https://www.openslr.org/33/)的许可字段为 Apache License v.2.0，正文另有 academic-use 描述。
  本轮保留这些来源证据；未来装包前仍需复核数据发布说明与署名。不把仓库许可字段解释成所有后续用途都已审查完毕。
- 只下载 train/dev/test 转写及小型元数据，没有下载音频。语体以朗读、新闻、商业文字为主，不是手机聊天记录。
- 原始 141,600 个 utterance id；严格解析 `() [] <>` 标记，保留原始实体跨度，NFKC + OpenCC 1.4.2 t2s。
- 只保留 2–64 汉字；长度排除 8，非汉字排除 7。原文异字不按评测答案自动纠正。
- 与所有旧 Tatoeba 分片及手写回放文本共同建立编辑距离 ≤1 的近重复族，保护旧来源；新来源去掉触及保护族的 24 条原始记录。
- 精确去重并以近族为单位按原分片 test > dev > train 归属，1,992 条原始行改归属。
  这套 P2C 分片不冒称原 ASR 官方指标分片。

| 新语料分片 | 去重记录 | 汉字 |
| --- | ---: | ---: |
| train | 112,862 | 1,636,310 |
| dev | 13,811 | 198,307 |
| test | 7,161 | 103,508 |

保留 141,561 条逐 utterance 署名记录：原文、转换后文字、原始行号、固定提交 URL、来源 id、实体跨度、分片和 record id。
大数据在 `G:/workspace/sense-input-quality-reference/e15-aishell/`，仓库中的证据清单固定其哈希，没有塞进 APK。

## 先固定规则再看候选

1. `benchmarks/corpus/e15-selection-policy.json`：dev 64 / test 128；6–9 字与 10–18 字各半，固定种子、按族抽样。
2. 192 条 pypinyin 0.55.0 提案在候选运行前逐句由 Codex 复核；修正 6 条语境读音：总行、长宁、都会、证明了、朝阳、曾春蕾。
3. 保留 3 条既有子串重叠排除，额外排除源文“入场劵”读音依据不稳的 1 条；没有补抽。
   最终 dev 64 / test 124；不是独立人工金标，也未听取音频。
4. 事后完整交叉审计所有 192 条提案对旧 train/dev/test 的子串及一字编辑重叠，结果为 0；标签保持原样。
5. `benchmarks/corpus/e15-model-policy.json` 预先声明**仅一个试验**，保留旧 train 全量。
   新 train 以 SHA256(seed + TAB + family) 排序取完整来源族，直到达到旧训练汉字数；不靠候选表现选句。
6. 旧训练 776,701 汉字；选中新训练 53,577 行、53,089 族、776,711 汉字，整族纳入带来 10 字超出。
   train-only 字 1/2/3-gram 绝对折扣插值，discount .75、字频阈值 2、bigram 阈值 1、trigram 阈值 2、alpha .1。
   仍不是 Kneser–Ney、HMM/CRF 或神经模型。

新模型 `balanced.scng` 6,243,366 bytes，SHA-256：
`5a23fe220c5b7d257d8038096ab76cce5f5ec3bde86a1624f5c33edae52c8995`。

## 实际解码链与门槛

新主机入口 `M20CrossDomainBenchmark` 复用真正的 `AdaptivePinyinDecoder.decodeProgressively`，只换 SCNG 模型字节。
权重 .5、OOV −4、纠错组句先验 12、limit 255；空个人词库、无学习。词库、英文混排和分段逻辑不动。
这是整句/后缀状态实验，未测逐键中间态和实际 Android 渲染。

开发与后续测试均要求：**每个领域首选、前五、召回非降，字错非增；新领域首选还须严格增加**。
新模型大小上限 16 MiB。只有开发过关才冻结并开启测试候选；单次试验失败后未扫参数，也未打开测试结果。

- 新域 20 个首选改善、1 个首选退步；另有 11 个目标名次退步。
- 旧域 3 个首选改善、3 个首选退步；另有 13 个目标名次退步。
- 新域短句前五 50→48/64，虽领域聚合门槛通过，分层退步仍记录，不掩盖。
- 两域训练外文本 perplexity：Tatoeba 52.543→52.522，AISHELL 486.087→104.970。
  **困惑度改善没有保证日常拼音候选改善**；它含 EOS，真实输入打分不对未完成文本加 EOS。

## 具体退步与定位

| 正确来源 | 新首选 | 目标名次 |
| --- | --- | ---: |
| 南美大豆也出现了减产 | 南美大都也出现了减产 | 1 → 2 |
| 跟我们一起散步怎么样 | 跟我们一起三不怎么样 | 1 → 2 |
| 散步怎么样（上文“一起”） | 三不怎么样 | 1 → 2 |
| 近露天过夜吗（上文“在附”） | 近路天国也吗 | 1 → 4 |

`inspect_cross_domain_lm_losses.py` 检查真实 SCNG 表以及实际训练子串计数：
旧语料“散步”97 次、“三不”1 次；新增子集为 3 次与 6 次。
“散步怎么样”相对“三不怎么样”的单独 LM 分差从约 .595 降到 .092；
“近露天过夜吗”相对错误首选的分差从约 .638 降到 .018。
这是隔离 LM 特征的 float64 诊断，不是词格完整 Float 得分或单一因果证明。
它支持的下一步假设是：合并计数改变了局部条件分布，削弱原先用来压过词频/切分偏差的语境证据。
不是“旧词条被删除”；两个模型的训练数据和候选数组均保留可查。

后续应检验独立保留日常模型的多域融合或更长上下文证据，先预注册新试验；
不要向词库硬塞这些评测答案，也不要在已经打开的开发片上反复调到全过。
新增独立测试候选仍未运行，标注已冻结可用于后续一次冻结评估。
短词、未完成拼音、自然误触和 UI 时延继续属于总目标，不用本轮整句数据替代。

## 验证与交付边界

- Kotlin core：331 项通过；这轮没有重新执行 UI/service 单元及 Android 仪器测试。
- Python：385 项，384 通过，1 项 Windows 符号链接权限测试跳过。
- 主机主要解码 512 状态；平衡模型两域重复 256 状态，去掉时间字段后完整输出一致。
- M20 旧域基线对 E13 M19 原始导出：128 状态的完整 progressive 结果 SHA、rank、query、expected 全相同。
- 87 项运行前输入锁保持；保留初次未引用 JVM `-Dfile.encoding` 选项的启动失败日志。引用后重试，没有覆盖失败记录。
- 原生产资产及既有 APK 字节未变；未构建新 APK、未做实体手机测量、未发布、未提交。
- 总目标仍在进行。本阶段交付的是可追溯第二来源、可重建候选模型、真实双域诊断和明确的暂缓晋级结果，不是一次已经上线的质量提升。

## 复现入口

在仓库根目录使用带 OpenCC 1.4.2 / pypinyin 0.55.0 的 Python 环境；所有输出用新目录，保留原证据：

```powershell
python tools/fetch_aishell_ner_text.py NEW_SOURCE
python tools/prepare_aishell_corpus.py NEW_SOURCE .artifacts/input-quality/lm-corpus-v1 benchmarks/replay NEW_CORPUS
python tools/prepare_p2c.py prepare NEW_CORPUS benchmarks/corpus/e15-selection-policy.json NEW_LABELS
# 逐句复核 NEW_LABELS/annotations.tsv，随后 freeze；不要自动接受提案
python tools/train_balanced_character_lm.py .artifacts/input-quality/lm-corpus-v1 NEW_CORPUS benchmarks/corpus/e15-model-policy.json ime-service/src/main/assets/pinyin_character_lm.scng NEW_MODEL
./gradlew.bat --offline --no-parallel --console=plain :core-input:compileKotlin
java '-Dfile.encoding=UTF-8' --class-path 'core-input/build/classes/kotlin/main;KOTLIN_STDLIB_JAR' io.github.ethanbird.senseime.core.M20CrossDomainBenchmark . REVIEWED_DEV_TSV MODEL_SCNG NEW_OUTPUT_JSONL
python tools/evaluate_cross_domain.py BASELINE_JSONL PROPOSED_JSONL REVIEWED_DEV_TSV NEW_COMPARISON_JSON
```

完整结果与门槛见 `benchmarks/results/e15-acceptance-gate.json`，证据索引见相邻 `e15-evidence-manifest.json`。
