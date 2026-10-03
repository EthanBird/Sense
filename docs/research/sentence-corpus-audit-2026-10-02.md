# Sense 句子语料候选审计

## 结论

**下载页列出普通话，并不意味着 CC0 子集有足够中文可训练。**
实际读取 2026-09-26 的全语言 CC0 包后发现：562,200 行、62 个语言标签，其中 `cmn` 仅 **1 行**，
只有 4 个汉字，且夹有数字。放弃把该快照当作中文训练主体。

普通话 detailed 导出包有 **89,102 行**，适合继续做小规模语言模型试验；当前只是候选来源，
没有训练模型，没有加入 APK，也没有把“许可明确”当作“质量合格”。

## 来源与可重现性

- [Tatoeba 下载说明](https://tatoeba.org/ar/downloads)：普通文本为 CC BY 2.0 FR，CC0 文件单独列出，detailed 包保留贡献者。
- [全语言导出目录](https://downloads.tatoeba.org/exports/)：CC0 包 7,961,039 字节。
- [普通话导出目录](https://downloads.tatoeba.org/exports/per_language/cmn/)：detailed 包 1,722,601 字节。
- 快照记录：`benchmarks/corpus/sentence-sources-v1.json`，包含 URL、日期、SHA-256、许可链接及署名规则。
- 审计脚本：`tools/audit_sentence_corpus.py`；原始下载仅留在忽略目录 `.artifacts/input-quality/corpus/`。
- 机器报告：`benchmarks/results/sentence-corpus-audit.json`；同时包含脚本和 manifest 的哈希。

```powershell
python tools/audit_sentence_corpus.py `
  benchmarks/corpus/sentence-sources-v1.json `
  .artifacts/input-quality/corpus `
  benchmarks/results/sentence-corpus-audit.json `
  --replays benchmarks/replay
```

脚本无网络访问，先验证哈希、许可与格式对应关系，再读取压缩包；不把 tar 内容解压至文件系统。
导出文件每周变化，哈希不同需要单独冻结新快照，禁止悄悄更新训练集。

## 普通话 detailed 包统计

| 项目 | 结果 |
|---|---:|
| 普通话句子 / 原文唯一句子 | 89,102 / 89,102 |
| NFKC 后去标点汉字骨架数量，尚未繁简转换 | 88,711 |
| 汉字总量（Unicode 码点） | 914,296 |
| 仅含汉字、空白、标点的行 | 86,419 |
| 有署名贡献者 | 971 |
| 缺少署名的行 | 0 |
| 最大贡献者占比 | 12.67% |
| 缺失/零值最后修改时间 | 57,288 |
| 与现有 replay 汉字骨架完全重叠 | 24 |

重叠数量是对 replay 的所有汉字字段/别名做的保守扫描，包括单字词、种子词及句子，
并非声称 24 条整句泄漏。尚未做繁简归一化，因此也不是充分的防泄漏检测。
日期大量缺失，下一步使用文本族内容哈希分组，而不是据修改时间冒称进行了时间外测试。

## 下一步数据治理

1. 保留原文、句子 ID、贡献者、句子 URL、许可和转换说明，另外写训练清洗文本及署名 sidecar。
2. 固定繁简转换器及词表版本；去重在规范化之后进行。
3. 同一原句的标点变体、繁简变体、近重复模板进入同一分片；训练/开发/冻结审计分片互斥。
4. 在训练前排除已有 replay 答案及近重复文本；另留新的冻结 P2C 审计分片。
5. 自动拼音只作候选标注，重点复核多音字、人名和儿化；不用未复核转写当零误差标准答案。
6. 先训练有平滑和回退的字 2/3-gram，对照纯词频与词库 bigram。记录改善、退步及模型体积。
7. Tatoeba 是语言例句库，有翻译风格和作者偏差；它只作为起点，不等同于手机聊天语料。

不通过固定评测和端侧预算的模型保持实验态；现有词频路径继续作为缺模型回退。
