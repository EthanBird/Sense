# 拼音词库：基础层与补充层

当前运行时格式是 **SPLX/4**，来源、大小、数量和 SHA-256 见 `pinyin_lexicon.stats.json`。

- `sources.json`、`pinyin_base.stats.json`：原白霜基础层及原始统计，保持原频率、候选顺序和简拼索引。
- `layered-sources.json`：独立固定版本的雾凇补充来源，原文件和 GPL 文本保留在 `vendor/rime-ice`。
- 补充层取基础层缺少的 2～8 汉字规范全拼词，306,963 个读音/词条对，统一弱先验 1；
  上游权重不是与基础词库可直接相加的使用频率。没有按评测结果挑选词条。
- tier 2 只进入绑定 LM 的全拼路径。九宫格、LM 未加载的旧路径使用共享字节上的基础索引视图，
  保持原图、前缀遍历预算与候选顺序；不复制两份词库字节。
- 基础层定义 unigram 评分参照和“个人新词”成员关系。补词不使此前学习过的新词失去 LM 回退保护。
- 原二元模型、字符 LM、联想模型没有因为补词重训。移除 tier 2 的投影须逐字节等于原 SPLX/3。

## 离线重建

在仓库根目录运行，输出目录必须是新目录：

```powershell
python tools/rebuild_layered_pinyin.py .artifacts/input-quality/my-layered-rebuild
```

流程为固定来源构建 SPLX/3 → 补词提案 → 分层 SPLX/4；每个输入都有散列校验，
最后核对当前包固定的字节数与散列。中间提案仅用于构建，不作为 Android 资产。

如需重建原二元或联想模型，先恢复其原训练字典，不要将新弱先验词当成旧语料频率：

```powershell
python tools/project_pinyin_base.py ime-service/src/main/assets/pinyin_lexicon.bin .artifacts/input-quality/my-training-assets/pinyin_lexicon.bin
```

投影工具核对原 `71258c…e800` 散列。联想训练的完整命令见 `benchmarks/corpus/README-association.md`。
字库覆盖率、词数和单例改善均不等于总体输入准确率，效果与失败样本见 E7 研究报告。
