# E50–E51：从未生效的堆配置，到真实的 112MiB 输入回归

## 结论

**完整输入实现通过固定 112MiB 应用堆的 50 次系统输入检查。**
不是只看 AVD 配置，也不是只测外部编辑器：每项均记录并核对外部编辑器与
Sense `:ime` 进程自身的 `Runtime.maxMemory()`，两者均为 112MiB。

| 场景 | 本轮通过数 |
|---|---:|
| 清空专用 Debug 数据后的首次加载、候选点击、外部上屏 | 1 |
| 普通全拼、快速确认、后续输入、英文 Enter、编辑器切换 | 10 |
| 造词、再次接受、重启恢复、隐私隔离、上下文选择及删除反馈 | 6 |
| 上下文 | 8 |
| 纠错 | 10 |
| 显式分词边界 | 6 |
| 联想及工具栏生命周期 | 9 |

额外新执行 317 项服务层单元测试、11 项配置工具测试、3 项堆证据解析测试，全部通过。
Debug/fixture 构建成功。没有把未重跑的 core/UI 测试记成本轮执行。

## E50 为什么没有得到输入结论

此前依次请求普通 `dalvik.vm.heapgrowthlimit` 启动属性、`qemu.dalvik.vm.heapsize`，
以及临时修改 AVD 的 `vm.heapSize`。前两者被忽略或被生成属性覆盖，第三次实际仍为 512m。
三次均没有执行限堆输入测试，不计作通过，也不计作产品故障。
在用户要求及时反馈后结束实验，关闭该模拟器并按原始摘要恢复 AVD 配置。
这些无效预检及收尾记录保留，避免把请求的数字当成实测结果。

## E51 如何取得有效环境

沿用已创建的 `sense-input-quality-api29-google`，API29 Google APIs userdebug、x86_64、
1080×1920、420 dpi、2GiB 虚拟物理内存；没有新建 AVD，也没有改主机 AVD 配置。
对这台专用模拟器执行一次 `adb root`，设置临时 `dalvik.vm.heapgrowthlimit=112m`，
重启 Android framework/zygote，使随后生成的应用进程采用该上限。
不是在旧进程运行时只改一个无效属性。

新增只读 `dumpsys input_method` 字段 `maxHeapMiB`，由真实 IME 进程计算，不包含输入文本。
fixture 同时要求自身和 IME 堆落在声明区间；本轮上下界都设为 112。
预检通过才执行其余 49 项，任一组失败则停止，不通过放大堆或重跑挑选结果。
新工具 `tools/test_input_heap.py` 检查具名 AVD、API、安装 APK 摘要及每项堆记录，
拒绝将实体手机或其他 AVD 当作此测试环境。

复现时，先在同名一次性 userdebug AVD 上配置并重启 zygote，再构建 Debug/fixture：

```powershell
python tools/test_input_heap.py E:/new-sense-heap-run --serial emulator-5584 --heap-mib 112
```

工具本身不替用户改 ART 属性；临时环境控制与最终恢复记录单独留证。
测试结束后恢复原空 growth-limit 属性、原 Gboard、硬键盘设置，确认字体仍为缺省、
密度仍为 420、debug_app 为 null；退出 adbd root 并关闭该模拟器。
主机 AVD 配置摘要与实验前完全一致，原 API37 AVD 和实体手机均未操作。

## 为什么没有修改词库加载算法

代码核对发现，Android 路径的 `BundledPinyinLexicon` 已按固定长度一次分配，
并用进程级 single-flight 缓存共享只读码表。通用 `PinyinDecoder.load(InputStream)`
中的增长缓冲不是当前 Android 主词库路径，修改它不会解决该路径的问题。

112MiB 下没有复现完整加载失败，现有学习和进程重启检查也通过。
因此本轮没有削减词库、切换弱模型或添加 `largeHeap`。
E48 锁定的 190 项生产输入源码中，189 项完全一致，唯一变化是上述只读堆诊断。
APK 中 25 个输入/许可资产摘要全部一致；候选排序、LM、学习及 UI 算法没有改动。

实际查看了上下文学习及进程重启后的截图：外部编辑框为“获得权利”，键盘和工具栏正常呈现。
截图是该模拟器原始输出，不是重绘图。

## 范围和后续

- 本轮测的是明确的 **应用堆预算**，不是 Android low-RAM 标志、整机 112MiB 内存、
  后台内存压力或厂商系统认证。固定内存回归也不是语义准确率提升。
- 功能测试期间执行了主机单元测试，不将这一轮的耗时作为新性能分布。
- 测量 APK 是增加只读诊断的 Debug 构建，不宣称重测了线上正式签名 APK。
- rc.7 及线上附件保持原样；只有诊断和测试变化，不为此增加一个新发布版本。
- 固定 112MiB 完整输入预算已有证据；下一步回到最新查询成本和自然同音句错误，
  不继续扩大模拟器堆参数实验。完整质量目标仍在进行。

机器可读结果、源码/资产身份、原始截图与日志索引见
`benchmarks/results/e51-acceptance-gate.json` 和 `e51-evidence-manifest.json`。
