# 离线词级联想 v1（F1）

用于空闲候选栏，不替换正在输入的拼音到汉字解码器。无需网络请求或模型 token。

## 数据与训练

- 复用 `README-character-lm.md` 的固定普通话语料：Tatoeba 2026-09-26，CC BY 2.0 FR，
  NFKC/OpenCC 繁简规范化、分组去重和 train/dev/test 隔离保持原样。
- train 76,736 条、94,195 个汉字片段、776,701 字。模型只使用 train 计数。
- 使用已发布的 SPLX/3 规范词条及频率做 unigram Viterbi 分词，最长 8 字；未知字保留单字。
  该依赖来自 Rime Frost，保留 GPL-3.0 和对应 NOTICE。分词不是人工金标准。
- 每个字后产生一次观测：上下文取末尾 1～3 汉字，目标是下个词，或当前词尚未输入的后缀；
  片段末尾为 STOP。绝对折扣 0.75、unigram 加 0.1、每个上下文至少 3 次观测、最多 32 条直接边。
- 固定 9 个开发候选：长度奖励 0/0.5/1 × STOP 比例 0/1/2；最低首项概率 0.02。
  最终配置为 **无长度奖励、首项概率超过 STOP 才出现**。

## 选择与证据边界

`benchmarks/results/f1-association-development-freeze.json` 在本项任务打开测试集前保存。
选择标准是在查看开发结果及 21 个示例后制定的，不冒充预注册：片段末尾出提示比例 ≤15%，
在满足条件的配置中取 exact next-unit Top3 最大者。

测试分片没有用于本项联想训练或调参；它来自此前字符 LM 研究使用过的语料分片，
不是新收集的自然键入盲测。逐字截断回放也不是实际提交词边界分布。
“前缀命中”和“精确后续单元命中”分别统计，另报真实词边界的指标、多字命中及片段末尾出提示。
一句只有一个观测续写，不表示其他续写错误；语段结束出提示只是打扰倾向的代理指标。

## 重建

从仓库根目录运行；语料准备命令和原始快照见字符 LM 文档。所有实验输出使用新目录。

```powershell
# E7 之后运行时词库为 SPLX/4；训练仍使用原 SPLX/3，不用补充词重新训练旧模型。
python tools/project_pinyin_base.py ime-service/src/main/assets/pinyin_lexicon.bin .artifacts/input-quality/f1-training-assets/pinyin_lexicon.bin
Copy-Item ime-service/src/main/assets/pinyin_bigrams.bin, ime-service/src/main/assets/pinyin_character_lm.scng .artifacts/input-quality/f1-training-assets/
python tools/train_association_model.py .artifacts/input-quality/lm-corpus-v1 .artifacts/input-quality/f1-training-assets .artifacts/input-quality/f1-development
python tools/evaluate_association_model.py .artifacts/input-quality/lm-corpus-v1 .artifacts/input-quality/f1-training-assets .artifacts/input-quality/f1-development benchmarks/results/f1-association-development-freeze.json .artifacts/input-quality/f1-evaluation
python tools/package_association_model.py .artifacts/input-quality/f1-development benchmarks/results/f1-association-development-freeze.json .artifacts/input-quality/f1-evaluation ime-service/src/main/assets
python tools/audit_association_asset.py app/build/outputs/apk/debug/app-debug.apk benchmarks/results/f1-apk-asset-audit.json
```

完整 dev/test 观测压缩保存于 `benchmarks/results/f1-raw/`。开发集 28,703 个唯一上下文的
Python/Kotlin/生产引擎排序对照放在 `benchmarks/association/dev-parity.tsv.gz`；它不进入 APK。
这些预测与上下文继承相同来源署名链，不当作独立训练资料或新的盲测。

## 格式与运行预算

SNWP/1：大端 `magic(4) + version(u16) + rows(u32)`；每行 `key(u64) + count(u8)`，
随后每候选 `UTF-8字节数(u8) + 文本 + log概率(f32)`。上下文按 Unicode 标量每字 21 bit 编码，
最多 3 字，key 递增；每行最多 8 个、每个最多 8 汉字。空行显式阻止短上下文回退。

装载时固定文件长度和 SHA-256，校验 UTF-8、键、排序、分数、重复和资源上限；
运行时只保留字节表、LongArray 和 IntArray。文件损坏/缺失使用旧二元模型；
**有效模型主动返回空不回退到单字噪声**。个人历史仍可覆盖 STOP，隐私场景跳过个人历史读取。

查询转移到串行 `sense-associations` 线程，至多一个运行和一个待处理请求；
主线程持有编辑框/上下文快照，结果回投时检查显示票据、编辑会话、连接对象、上下文、模型与隐私状态。
不跨编辑框引用内容，不把输入文字写到诊断日志。

## 研究参考

[Moon IME, ACL 2018](https://aclanthology.org/P18-4024/) 将输入转换与联想结合；
这里只借鉴任务划分，当前实现是小型统计检索表，不是 Moon 的神经网络/检索架构复现。
