# ADR 0022：GPL-3.0 与 Rime Frost 可复现词库生产线

- 状态：Accepted
- 日期：2026-07-30
- 决策范围：项目许可、中文词库来源、权重校准、测试数据边界、离线重建门禁
- 取代：ADR 0005 中“不得引入 GPL 资产”的许可约束；早期版本继续按当时随包许可说明归档

## 背景

旧版 `pinyin_lexicon.bin` 以 Rime pinyin-simp 为主，并追加 CC-CEDICT 与
成语表。它只有约 16.8 万 exact 候选，其中超过六成 fallback 记录使用相同
权重 `1`。这使大量同音词在排序时退化为长度和字典序。后续版本又把为了
M4/M5 回归而设置的十亿级权重、Emoji 和人为排序短语写进生产
`sense_custom.dict.tsv`，测试目标开始反向决定真实用户候选顺序。

语燕公开工程采用 Rime 架构和大规模编译词典，但其自定义核心与编译表并未
提供完整、可独立重建的源数据链。Rime Frost 则提供维护中的源格式词表、
频率、出处说明和 GPL-3.0 许可，并以 Rime Ice 的长期词库整理成果为基础。
项目决定后续整体采用 GPL-3.0，因此可以让代码和组合中文资产处于同一个
清晰的 copyleft 边界。

## 决策

### 1. 项目许可统一为 GPL-3.0-only

根目录 `LICENSE`、APK 内 `assets/LICENSE.txt` 与 Rime Frost 许可副本使用
GNU GPL v3 正文。根目录 `NOTICE` 同时保留 ISC 英文词表的独立署名和许可。

### 2. 只从首选源格式构建生产中文词库

生产 manifest 固定在：

`ime-service/src/main/lexicon/sources.json`

Rime Frost 固定 commit：

`69cbcf8937ae03c03792fa285dca7f79f80715bc`

仓库内保存这四个实际参与构建的上游源文件：

- `cn_dicts/8105.dict.yaml`
- `cn_dicts/base.dict.yaml`
- `cn_dicts/ext.dict.yaml`
- `cn_dicts/others.dict.yaml`

每个 source 均声明相对路径、SHA-256、revision、license、weight policy 和
index policy。构建前必须验证路径没有越过 manifest 根目录，且内容哈希逐字节
一致。预编译 `.so` 或缺失首选源的二进制 table 不进入生产构建链。

### 3. 建立 attributed canonical IR

`tools/lexicon_sources.py` 将 Rime 表转换为确定性 IR：

```text
text | syllable-separated pinyin | calibrated weight | tier
     | source id | raw weight | eligible indexes
```

IR 是审计和 diff 界面，不打包进 APK。manifest、源文件和转换器共同构成
可复现的首选修改形式。`pinyin_lexicon.stats.json` 固定产物哈希、namespace
计数、唯一词数以及逐来源接受/拒绝统计。

### 4. 在统一分数域中校准来源

Rime Frost 的单字表与 base 保留原频率尺度；ext 使用固定 `3/4`，others 使用
固定 `4/5`。所有变换采用整数有理数运算，并对零频记录使用很小的来源 floor。
不再把整个补充词典赋为同一个 `weight=1`，也不再用 source tier 硬压另一来源。

源级 index threshold 只影响冗余索引是否物化，不删除 exact 候选：

- 高频记录进入统计前缀；
- 满足阈值的多音节记录进入简拼与 hybrid；
- 低频长尾仍可通过完整拼音召回。

这样在提升 exact 召回率的同时，将 SPLX 控制在约 35 MB，而不是随每个长词的
全部 hybrid 组合线性膨胀。

### 5. 测试 fixture 与生产词表分离

生产 `sense_custom.dict.tsv` 只保留六条产品或人工确认的真实短语，并使用和
上游相同数量级的权重。早期 M5 的十亿级候选顺序、Emoji 等记录移动到：

`ime-service/src/test/fixtures/lexicon/legacy_m5_ranking.dict.tsv`

生产 manifest 不引用任何 `src/test` 路径。测试分别验证 fixture 存在、
生产 overlay 不含这些合成记录、上游哈希固定、IR 确定性和最终资产字节一致。

### 6. 离线门禁从“自举二进制”改为“源到产物”

`tools/offline_verify.sh` 必须从 `sources.json` 和 vendored preferred sources
重新生成 canonical IR、SPLX、syllable inventory、stats 与 bigram，再逐字节
比较仓库内资产。构建过程不访问网络，也不把已编译 SPLX 当成自身的唯一来源。

## 结果

新产物包含：

| Namespace | Keys | Candidates |
|---|---:|---:|
| exact | 524,851 | 610,298 |
| prefix | 9,310 | 71,510 |
| initials | 76,017 | 188,151 |
| hybrid | 213,604 | 236,549 |
| 总计 | 823,782 | 1,106,508 |

唯一 exact 文本从约 16.7 万增加至 608,314。SPLX 为 35,069,585 bytes；
构建机上完整 Python 源构建约 30 秒，运行时格式仍是现有只读 SPLX v3，不增加
网络、数据库或主线程 I/O。

## 取舍

- APK 中文资产增加约 15.6 MB，换取约 3.6 倍 exact 候选覆盖；
- checkout 增加上游首选源文件，但发布者和贡献者可以独立审计、修改与重建；
- hybrid 只覆盖有频率证据的记录，极低频词仍保留 exact 与解码器动态组合路径；
- 当前字符 bigram 仍由词典派生；后续语料语言模型通过独立 ADR 接入，不改变本
  ADR 的源数据、许可和 fixture 边界。

## 门禁

1. manifest schema、许可、路径、revision 与 SHA-256 严格校验；
2. rational weight、非 Han 过滤、长度边界和 index threshold 有独立 Python 测试；
3. canonical IR 两次生成逐字节一致；
4. `pinyin_lexicon.bin`、`pinyin_syllables.txt`、stats 与 bigram 从源重建一致；
5. APK 内 GPL、Rime Frost notice、ISC license 与仓库副本逐字节一致；
6. 生产 overlay 与 legacy fixture 的路径及内容边界有回归测试。
