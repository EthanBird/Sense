# M8：26 键全拼日常输入回放

`m8-daily-full-pinyin.tsv` 是 2026-10-02 在修改评分前固定的 40 条人工诊断集，
覆盖聊天、工作、出行、生活、软件、技术和切分歧义。仅用于评测，不参与生产词库/模型训练。
原始拼音中的空格只是人工审阅辅助，执行时去掉；无声调、无自动注入分词边界。

## 执行

在仓库根目录、JDK 17 与 Android SDK 环境下：

```powershell
.\gradlew.bat --offline --console=plain --max-workers=1 :core-input:m8DailyInputBenchmark
```

可用 `'-PdailyInputReport=.artifacts/input-quality/experiment.json'` 指定实验报告文件。
报告先完整写出，再检查回归门槛；失败时仍可检查每条候选。
当前门槛是本阶段回归下限，不是“好用”的最终验收标准。

## 测量与解释

- 每条从空 composition 逐字母输入，调用生产 `AdaptivePinyinDecoder.decodeProgressively`，255 个候选预算。
- 包含未完成拼音、英文混排和前缀探测；770 次按键为一轮，同进程测首次回放与热身后回放。
- 使用空的用户词库，避免学习掩盖基础模型质量。个性化学习、持久化契约另由单元测试验证。
- 核对语料和四个词库资产的 SHA-256；包含 Unicode 码点编辑距离、Top-1/3/10、MRR、覆盖和逐条 Top-3。
- 语料检查检出字段/重复/音节数问题，不宣称完成逐字读音或语义的自动审校。
- 这是 **解码 API** 测量，不含 service 的 curated/semantic overlay、Canvas 帧、InputConnection 或真实人的输入停顿。
- 40 条手工集不是生产准确率抽样。后续模型调参已经能看到此集的错误，应新增独立审计分片。
- 同音句可能有多个合理答案，先维护显式 alias，再谈语义错误率；不为提高分数放宽非合理答案。

## 版本对照

- `benchmarks/results/baselines/m8-daily-input-d457eec.json`：修改前真实执行得到的报告。
- `benchmarks/results/m8-daily-input.json`：当前代码执行结果。
- 比较时先核对 `assets` 中语料哈希、观察行和候选限额。模型资产变更应明确列出。
- M3 旧报告中的 `baseline/contextual` 指**同一版本无/有字 bigram**，不代表修改前后。
- M3 原始基线保留一条已发现的错误标注；应对比共同的 125 条或另跑修正后的基线，勿将标注修复计为算法收益。

## 回归范围

`M8DailyInputBenchmarkTest` 检查评测器本身；`PinyinDecoderTest` 检查搜索与上下文；
`ProductionLearningQualityTest` 检查实际词库上的“程彻”“智能体”、重建词库恢复、缓存与忘记。
Android View 的点选/连续滑动/联想关闭由 `SenseKeyboardViewLayoutDeviceTest` 仪器测试验证。
`tools/local_release.ps1` 已纳入 M8 门槛和报告归档；本专项开发期间未运行发布脚本。
