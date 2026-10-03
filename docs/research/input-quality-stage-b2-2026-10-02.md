# 输入质量 B2：字符语言模型进入真实词格

## 阶段结论

**已完成可选择启用的解码器接线，尚未默认启用模型或发布。**
不是再对保存的前三候选重排：M11 调用实际 `AdaptivePinyinDecoder.decodeProgressively`，
LM 在词边截断和 beam 剪枝之前参与搜索，最终返回 255 个候选限额内的真实结果。

同一 M8 开发诊断集：权重 1.0 首选从 **28/40 → 32/40**，5 个改善、1 个退步。
权重 2.0 退回 28/40、前三十以外的候选也有变化，显示当前小语料模型存在明显领域偏差。
这些都是开发证据，不是独立 P2C 测试结论，更不是 Android 输入体验验收结论。

## 实现

### 不同候选来源共享校准

保留原来的无 LM 路径；`PinyinDecoder.withLanguageModel(model, weight)` 返回新实例，
共享只读词库/模型，独立缓存和个人词绑定。`EMPTY` 或权重 0 返回原始评分行为。

启用后，对同一个原始拼音查询固定：

```text
D = max(1, raw_query_letters / 6)
L = sum(ln(word_weight + 1) - ln(Z) + dictionary_boundary_feature - split_cost)
C = weight * sum(clip(log P(character | previous_two) + 6, -6, +6))
candidate_score = ln(Z) + (L + C) / D + external_boundary_feature
```

候选源优先级和纠错惩罚在公共候选域中沿用；exact 的 `L` 是单个词的先验。
词内、跨词、纠正拼写共享相同的 LM 特征及原始查询分母。
beam 内携带两字状态和可累加的 C；导出时先转换 L，最终合流只加一次 C。
词内显式分隔符不会重复计分，已有回归测试。

这是 **log-linear 解码特征**，不是句子概率。
`+6` 是显式字符插入先验，裁剪及分母是待独立评测校准的工程参数，非语言学常数。
不对仍在编辑的片段添加 EOS。纠错探测预算仍有启发式限额，不宣称无剪枝最优。
无声调全拼、简拼、mixed 的既有召回策略没有被声称全部重写。

### 上下文与生命周期

- 新增可选 `TextContextualInputDecoder`，不要求已有适配器改变接口行为。
- progressive 整句与前缀探测都传递前文：外部 composing 前文 + 已接受片段。
- 只保留末尾 UTF-16 小片段，LM 消费最后两个完整 Han 码点；标点/非汉字边界重置。
- 前缀缓存 key 加入两字状态：`古代 + shi` 和 `时代 + shi` 不共享排序。
- LM 的词边缓存只存活于单次 decode，最多 4,096 项；无全局编辑器文本缓存。
- T9 的廉价 lexical probe 仍保留原词频召回；本轮重点是 26 键全拼，不声称 T9 LM 调优完成。

### 个人词保护

第一轮实测发现，通用语料会让“程彻”退到“乘车/澄澈”后，强权重下甚至掉出候选。
原始结果保留在 `benchmarks/results/baselines/m11-lattice-language-unprotected.json`。

当前采用明确的保守规则：查询包含**正向学习、两个字及以上、基础词库未收录同一文本的个人词**时，
该次完整查询回到已验证的个人词格路径。普通已收录词的学习不会让所有句子关闭 LM。
这不是个性化 LM 已训练完成；名称类别建模及更细粒度混合仍待开发。
学习/忘记立即生效，忘记后恢复原 LM 结果；缓存不冻结个人排序。

字级 OOV 发射暂取中性特征，避免把 UNK 总概率直接当作每一个未知字的概率。
高权重仍可能偏爱异常词形，已保留负例，尚不作为生产默认配置。

## M11 开发消融

输入：固定词库、词库 bigram、相同 40 条 M8 拼音/前文，最终一次 progressive decode。
包含全候选生成和 prefix 调用，不是逐键性能，也不是 Android 帧耗时。
模型沿用 B1 的 3,090,930 字节 SCNG/1；没有重新训练或用测试分片挑参数。

| 配置 | 首选 | 前三 | 前十 | 有候选 | 首选改善 / 退步 |
|---|---:|---:|---:|---:|---:|
| 无语料 LM，已有词库 bigram | 28 | 34 | 39 | 40 | 0 / 0 |
| 仅新校准、零 LM 特征 | 28 | 34 | 39 | 40 | 0 / 0 |
| 语料 LM × 0.25 | 30 | 36 | 39 | 40 | 3 / 1 |
| 语料 LM × 0.5 | 30 | 34 | 39 | 40 | 3 / 1 |
| 语料 LM × 1.0 | 32 | 35 | 39 | 40 | 5 / 1 |
| 语料 LM × 2.0 | 28 | 34 | 35 | 40 | 3 / 3 |

权重 1.0 的改善：今天有一点累、请检查一下邮件、这家饭店的菜很好、请重新登录、一起去公园。
退步：**我等会儿回复 → 我等会儿恢复**。高权重还出现“再聊/再撩”“优化/有话”等退步。
所有逐条候选、源码指纹、输入 SHA-256、参数和耗时写入 `benchmarks/results/m11-lattice-language.json`。

4 个个人词冒烟句在所有本轮配置下恢复首选：程彻喜欢智能体、我喜欢程彻、智能体可以帮忙、我的智能体。
这些只是保护规则的回归，不代替更广泛个人词测试。

## 性能修复

最初实现对每条路径排序词边，每次 comparator 又查询 LM，最终解码 p95 约 111 ms。
改为：**同一输入偏移/词边、相同两字状态共享候选选择，先计算一次数值再排序**。
本机同一任务复测，权重 1.0 p95 约 27–29 ms，基线约 29–36 ms；排名结果保持一致。
计时有 JIT、主机负载和固定测量顺序影响，这只说明消除了明显重复开销，不宣称 Android 已达到同样延迟。

## 回归与构建

最终结果：JVM/Robolectric **729 项通过**（core 256、UI 224、service 249），0 失败、0 跳过；
Python 专项 **14 项通过**。M2–M9 现有门禁通过，M10 的 579 条 Python/Kotlin 分数互操作通过。
无 LM 的 M8 仍为 28/40，M9 仍为 4/16 → 15/16，默认路径保持本轮前的质量。
Debug APK 构建成功，SHA-256：`4e7b44124029a5b189f62cfb1651ee5976986c5ae8369b2f74532f47f10b2aae`。
M11 源码 SHA-256 与最终文件逐项核对一致。没有在本轮重新运行 Android 仪器测试。

本轮新增 9 项测试，覆盖：截断前救回深层同音词、词格状态、两字上下文和前缀缓存隔离、
已接受单字与外部前文拼接、模型关闭等价性、显式分隔符不重复计分、纠错路径、
个人词保护及忘记、补充平面汉字、标点重置、OOV。

完整验收命令：

```powershell
$env:JAVA_HOME = 'F:\Android\Jdk\jdk-17'
$env:ANDROID_HOME = 'F:\Android\Sdk'
.\gradlew.bat --offline --no-parallel --console=plain `
  :core-input:test :ime-ui:testDebugUnitTest :ime-service:testDebugUnitTest `
  :core-input:m2AdaptiveBenchmark :core-input:m3SentenceBenchmark `
  :core-input:m4CoreBenchmark :core-input:m5MixedInputBenchmark `
  :core-input:m6InputPolishBenchmark :core-input:m7ChineseSchemeBenchmark `
  :core-input:m8DailyInputBenchmark :core-input:m9PersonalSentenceBenchmark `
  :core-input:m10CharacterLmBenchmark :core-input:m11LatticeLanguageBenchmark `
  :app:assembleDebug
```

M10/M11 需要先按 `benchmarks/corpus/README-character-lm.md` 重建本地模型；
没有将它们塞进不带模型资产的默认 release 门禁。

## 后续验收缺口

1. 新的独立 P2C 集：冻结选句规则、拼音/多音字审计、保留多种自然答案，再决定模型及权重。
2. 个人名字类别和领域语料：减少通用短句语料对技术词、聊天表达、姓名的偏差。
3. Android 模型资源接线、损坏/缺失加载回退、低端设备逐键内存/取消预算。
4. 真正系统输入法服务 → 外部 EditText 的键入、选择、学习、重建、跨字段/隐私路径验收。

目标保持进行中。本轮没有发布 release，没有更换签名证书。
