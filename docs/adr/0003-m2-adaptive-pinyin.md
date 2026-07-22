# ADR 0003：M2 自适应拼音、纠错与用户词库

- 状态：已接受
- 日期：2026-07-22
- 阶段：M2

## 问题

M1 仍要求接近完整且正确的连续拼音；字典构建期每个码最多保留 12 个候选，单字母查询只扫描字典序前 96 条记录；没有用户词库，也无法从一次完整输入学习简拼。这会导致短码候选不符合真实词频、错一个键就无法得到目标词、重复句子仍要完整输入。

## 决策

1. Rime pinyin-simp 保留词频主排序；CC-CEDICT 只补充缺失的 exact `(拼音, 词语)`，固定低权重且不参加短码统计。每个基础码最多保留 128 个候选。
2. 1～4 字母 top-16 统计短码使用独立命名空间，与 exact 键物理隔离；`w → 我` 标记为 `BASE_PREFIX`，不会把 `xiansi/xiang/xianzai` 混进 `xian`。
3. exact 和完整组合优先；低置信度路径再枚举一次有界编辑，覆盖相邻换位、单次插入/删除、邻键替换以及 `n/l`、`f/h` 等低成本模糊音。候选以语言分和编辑成本联合排序。
4. 二进制 v3 为每个候选保存来源音节首字母和来源层级。这样同为连续码 `fangan` 的“方案”可保留 `fa`，“反感”可保留 `fg`；同时主词库始终优先于低权重补充词库。只有缺少边界且 DP 得到唯一简拼时才允许回退学习。
5. SQLite 表保存 `full_pinyin / initials / phrase / use_count / created_at / last_used_at`；全拼和简拼双索引常驻内存，逐键查询绝不访问 SQLite，写入由单线程 WAL 异步完成。关闭任务进入同一写队列尾部，不再使用限时等待或 `shutdownNow`，保证已接收写入先于数据库关闭。
6. 候选提交使用已绘制快照，禁止点击时重新解码，避免异步学习导致“显示 A、提交 B”。
7. 候选槽根据文字宽度从左侧流式布局，文字锚点和触控框同步左对齐。

## 研究依据

- [AOSP PinyinIME](https://android.googlesource.com/platform/packages/inputmethods/PinyinIME/+/7898d76cc005bbe1c5893a9f57439561e0771cc8/) 使用 spelling trie、matrix search、ngram 与 userdict 分层，是本地统计输入与用户词典的直接工程参照。
- [Rime librime](https://github.com/rime/librime) 的 Spelling Algebra 证明以规则衍生模糊拼音和简拼是一条成熟路线。
- [SymSpell](https://github.com/wolfgarbe/SymSpell) 用预计算删除索引缩小编辑距离召回成本；M2 先采用更小的单编辑预算，后续词库继续扩大时再引入 delete index。
- [Android SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper.html) 提供版本化本地数据库生命周期；M2 不引入 Room，减少 IME 进程依赖。

## 学习边界

- 只学习真实上屏且含汉字的完整 exact、组合或纠错候选；
- 前缀补全、原始拉丁回退、剪贴板、Emoji 与工具结果不自动进入用户词库；
- 简拼候选再次使用时，增加其 canonical 全拼记录的频次，不把简拼误当作全拼；
- 第一次完整输入成功后即建立简拼映射，满足一次学习即可召回。

## 性能边界

- 基础词典仍为只读紧凑二进制；
- 统计短码在构建期生成，运行时保持二分查找；
- 纠错最长处理 48 个字母、最大一次字符编辑；短词与长组合句都走同一候选验证路径；
- 用户词库逐键只查内存双索引；
- 基准同时记录 exact、短词 typo、长组合句 typo 和 10,000 用户词条查找延迟。M2 冷启动仍同步装载完整用户词表，必须在后续真机门禁中单独测量并最终替换为有界快照。

## 词典构建与许可边界

- Rime pinyin-simp 固定到 `0c6861ef7420ee780270ca6d993d18d4101049d0`；
- CC-CEDICT 固定镜像 commit `18b4f1e58ba3fec864bb74ee6965c07fa658bdbc`，源文件 SHA-256 为 `18fadc04a78e887e2215f4d4be9b25419a404f5c9f6a56af815c7a93af5fba4b`；
- 生成资产包含 97,536 个 exact 键、7,357 个 namespaced prefix 键和 144,673 条 exact 候选；
- 合并词典资产按 CC BY-SA 4.0 单独提供，代码许可不变；署名、修改说明和无背书声明见根目录 `NOTICE`。

## 后续

M3 将加入 bigram/Viterbi 句级排序、候选分页和真实回放集；当词库继续扩展时，再评估音节 Trie、SymSpell delete index 或 librime JNI。所有排序权重必须通过真实输入回放调整，而不是凭主观常量定稿。
