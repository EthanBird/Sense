# 搜狗 SRSS 语音 Provider 接入记录（2026-08-01）

## 输入样本

- 文件：`E:\sogou_asr_reverse.zip`
- SHA-256：`8818e08f7fd43bf7075e6b4da707a77444912f126ca753463d38010c4b8d9945`
- 归档包含协议分析、Python 客户端和 16 kHz mono PCM16 测试语音。

## 已验证协议

- WebSocket：`wss://srss.speech.sogou.com/srss/v1/speech/streaming_recognize`
- 配置：AES-256-CBC/PKCS5Padding 加密 JSON；随机 AES Key 再以
  RSA-OAEP-SHA256 加密；密钥密文与 IV 分别置于 `X-Srss-Cipher-Key-Sec` 和
  `X-Srss-Cipher-Key-Vec`。
- 音频：16 kHz、mono、PCM16 little-endian，经 Opus VOIP 编码；每条 binary message
  为 `2-byte big-endian payload length + Opus packet`。
- 结束：发送文本消息 `{}`。
- 响应：读取 `results[].alternatives[].transcript` 与 `results[].is_final`；中间结果覆盖
  预览，最终结果只提交一次。

归档 Python 示例把 640-byte PCM 块传给 Opus 的 `frame_size=640`。Opus 的
`frame_size` 单位是“每声道采样数”，因此 16 kHz、20 ms 的正确值是 320；Sense
实现固定使用 320 samples / 640 PCM bytes，避免服务端时长翻倍。

## 实现

- `SpeechProviderPresetCatalog.SOGOU`：免 API Key、固定 endpoint/model、运行时可执行。
- `SogouAsrProtocol`：匿名请求 UUID、语言映射、配置构造及加密握手。
- `SogouOpusPacketEncoder`：纯 Java Concentus 编码，20 ms 补零尾帧与 2-byte 长度头。
- `SogouAsrWebSocketClient`：OkHttp WebSocket、8 ms 帧节流、60 秒总超时、响应大小
  上限、取消代际与音频缓冲清零。
- `CloudSpeechRecognitionController`：与既有录音器/会话 gate/PartialResult/FinalResult
  链路统一。
- 设置页与 IME：选择“搜狗在线语音（免配置）”并保存后直接使用；API Key 和连接字段
  均不需要填写。

依赖：

- [OkHttp](https://github.com/square/okhttp) `5.3.0`
- [Concentus](https://central.sonatype.com/artifact/io.github.jaredmdobson/concentus/1.0.2)
  `1.0.2`
- [Opus API](https://github.com/xiph/opus/blob/master/include/opus.h)

## 验证结果

测试样本时长约 7.6 秒。修正 20 ms frame size 后，原始 Python 客户端和 Sense Kotlin
适配器均得到：

> 你好，我是银狼，来自星穹铁道，这是搜狗输入法语音识别接口的测试。

服务端最终消息报告 `end_time=7.600s`、`confidence=0.86`。Kotlin live probe 同时验证了
中间结果、最终结果和完全一致的文本。

离线门禁：

```powershell
.\gradlew.bat --no-daemon --console=plain `
  :ai-runtime:testDebugUnitTest `
  :app:testDebugUnitTest `
  :ime-service:testDebugUnitTest `
  :app:lintDebug `
  :app:assembleDebug
```

实时探针：

```powershell
$env:SENSE_SOGOU_ASR_LIVE_PCM='path\to\test_voice_16k.pcm'
$env:SENSE_SOGOU_ASR_EXPECTED='期望文本'
.\gradlew.bat --no-daemon --console=plain `
  :ai-runtime:testDebugUnitTest `
  --tests 'io.github.ethanbird.senseime.speech.SogouAsrLiveProbeTest'
```
