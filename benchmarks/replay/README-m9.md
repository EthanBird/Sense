# M9：用户词在新句中的复用

```powershell
$env:JAVA_HOME = 'F:\Android\Jdk\jdk-17'
.\gradlew.bat --offline --console=plain :core-input:m9PersonalSentenceBenchmark
```

输出 `benchmarks/results/m9-personal-sentence.json`；可用
`'-PpersonalSentenceReport=.artifacts/input-quality/m9-experiment.json'` 单独保存实验。

## 测的是什么

- 使用实际 SPLX/3 词库、bigram、全拼分词器、英文混排和 `AdaptivePinyinDecoder`。
- 先记录 16 条句子的结果，再将 6 个不同词各明确选择一次，再查同样的**新句子**。
- 只学习词，不学习这些评测整句；不向生产词库注入人名、答案或权重。
- 从 journal 快照恢复后比较整个候选列表；忘记全部种子后与学习前列表完全比较。
- 日常 M8 中没有任何种子词匹配的查询，用真实 progressive API 检查候选/前缀结果一致。
- 10,000 条合成合法全拼三字记录用于容量压力；逐条检查可达性，另测匹配及 770 次逐键调用。

## 门槛与边界

`required_top1=true` 的 4 条锁定已复现问题；其余 12 条是诊断，不把剩余语言歧义藏掉。
恢复、忘记、隔离、10,000 条记录可达性均为硬门槛。报告先落盘，门槛失败再报错。

`beforeTop1 → afterTop1` 是**同一版解码器学习前后**，不是旧提交与新提交的算法对比。
SQLite 落盘/重建由 `PersistentUserSentenceLearningTest` 另测；M9 的 journal 是内存快照。
容量字典是合成数据，不是用户数据或质量评测语料。主机时间不作为 Android 帧延迟结论。
三个字典规模按顺序测试，JIT、调度和 GC 都会影响尾部延迟；不据此声称容量字典比空字典更快。

本轮结果：首选 4/16 → 15/16。保留的失败是“请叫苏苒过来”首选“请教苏苒过来”。
修复后再用这些句子调参时，它们属于开发集；下一阶段的独立审计集应另行冻结。
