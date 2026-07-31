package io.github.ethanbird.senseime.speech

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

internal data class SogouAsrHandshake(
    val encryptedConfigBase64: String,
    val headers: Map<String, String>,
    val sliceId: String,
)

/** Wire-format helpers for Sogou's SRSS streaming-recognition endpoint. */
internal object SogouAsrProtocol {
    const val ENDPOINT_URL =
        "wss://srss.speech.sogou.com/srss/v1/speech/streaming_recognize"

    private const val PUBLIC_KEY_DER_BASE64 =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA042we0tp1Qf9oJ5HPTtDbevvl883q/" +
            "e3FXXwnQbE7b4p6OqtVjQxprCusNKCiPPctzWUzOmLCnozWp/7j3sROdTDPK7ZtElf6fLL+l2" +
            "bbdHijfSr0Z98yLwQOumOOPWtcxT34Ssrq5G3Sqaw8/RC9ZluoONqouzEl2ausPo21W+yctm" +
            "IzQ8otMKOkTunNSg+f5bM7phhsYoNy4nkCiISuXjlVpEnvb9V0t4ih2sAAvqCmGim4PQODJq" +
            "DDz68Iz0a32kMUIR1ydMqjoHokRdOk/VXgjE7OmJsVVe7Fn4ezdg7hYfnKVCPJxvzh0cgdbC" +
            "MUUSXOP8foKnJEEoGIQcV+lYgKqNUSJJRrfzG33i58aRwJ29UOVHuhGJ/SqFXqNmTvYQR5/Y" +
            "8kvMCoTdxQG6c5bWy9jTesrc/OliezEMS9GlepeiNdlHh3couDyn2zyYwE6aBpqp7k3uVQr/7" +
            "PiAJ6ZDBQkzVX2PGyqFeAoPFE4xg6LLTYH4EydbpZXIDg4zzAgMBAAE="

    private val secureRandom = SecureRandom()
    private val publicKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_DER_BASE64)),
        )
    }

    fun prepareHandshake(
        languageTag: String,
        interimResults: Boolean,
    ): SogouAsrHandshake {
        val ids = RequestIds.create()
        val config = buildConfig(languageTag, interimResults, ids)
        val aesKey = ByteArray(AES_KEY_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(AES_BLOCK_BYTES).also(secureRandom::nextBytes)
        val encryptedConfig: ByteArray
        val encryptedKey: ByteArray
        try {
            val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
            aes.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                IvParameterSpec(iv),
            )
            encryptedConfig = aes.doFinal(config.toByteArray(StandardCharsets.UTF_8))

            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            rsa.init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    MGF1ParameterSpec.SHA256,
                    PSource.PSpecified.DEFAULT,
                ),
            )
            encryptedKey = rsa.doFinal(aesKey)
        } finally {
            aesKey.fill(0)
        }

        return try {
            SogouAsrHandshake(
                encryptedConfigBase64 = Base64.getEncoder().encodeToString(encryptedConfig),
                headers = mapOf(
                    "X-Srss-Cipher-Key-Type" to "1",
                    "X-Srss-Cipher-Key-Sec" to
                        Base64.getEncoder().encodeToString(encryptedKey),
                    "X-Srss-Cipher-Key-Vec" to Base64.getEncoder().encodeToString(iv),
                    "User-Agent" to "okhttp/4.9.3",
                ),
                sliceId = ids.sliceId,
            )
        } finally {
            encryptedConfig.fill(0)
            encryptedKey.fill(0)
            iv.fill(0)
        }
    }

    internal fun languageCode(languageTag: String): String =
        when (languageTag.lowercase(Locale.ROOT).replace('_', '-')) {
            "en", "en-us", "en-gb", "en-au" -> "en-US"
            "yue", "zh-yue" -> "zh-cmnxyue-Hans-CN"
            "zh-hk" -> "zh-yue-Hant-HK"
            "zh-tw" -> "zh-cmn-Hans-CN"
            else -> "zh-cmn-Hans-CN"
        }

    internal fun buildConfig(
        languageTag: String,
        interimResults: Boolean,
        ids: RequestIds = RequestIds.create(),
    ): String = buildString(1_024) {
        append("{\"config\":{")
        append("\"convert_number\":false,")
        append("\"enable_ambient_sound_event\":false,")
        append("\"enable_word_time_offsets\":false,")
        append("\"encoding\":\"OPUS_WITH_HEADER\",")
        append("\"first_input_time\":0,")
        append("\"ime_install_time\":0,")
        append("\"language_code\":\"")
        append(languageCode(languageTag))
        append("\",")
        append("\"metadata\":{")
        append("\"audio_info\":{")
        append("\"audio_id\":\"").append(ids.audioId).append("\",")
        append("\"audio_slice_id\":\"").append(ids.sliceId).append("\"},")
        append("\"client_info\":{")
        append("\"product_category\":\"sogou_ime\",")
        append("\"product_id\":\"android_trunk\",")
        append("\"product_version\":\"10.8.0\"},")
        append("\"host_device_info\":{")
        append("\"device_aid\":\"").append(ids.deviceAid).append("\",")
        append("\"device_category\":\"phone\",")
        append("\"device_qid\":\"").append(ids.deviceQid).append("\",")
        append("\"device_uuid\":\"").append(ids.deviceUuid).append("\"},")
        append("\"host_os_info\":{")
        append("\"os_category\":\"Android\",")
        append("\"os_id\":\"android\",")
        append("\"os_version\":\"13\"},")
        append("\"network_info\":{\"network_type\":\"WIFI\"},")
        append("\"runtime_info\":{},")
        append("\"sdk_info\":{")
        append("\"sdk_category\":\"sogou_ime\",")
        append("\"sdk_id\":\"android_ime\",")
        append("\"sdk_version\":\"46.6.0\"},")
        append("\"user_info\":{")
        append("\"user_category\":\"anonymous\",")
        append("\"user_id\":\"anonymous\"}},")
        append("\"model\":\"\",")
        append("\"original_audio\":false,")
        append("\"punctuation_mode\":\"NORMAL_PUNCTUATION\",")
        append("\"result_form\":\"WBEST\",")
        append("\"speech_contexts\":[],")
        append("\"unit_symbol_type\":0,")
        append("\"user_feature\":{},")
        append("\"custom_info\":{},")
        append("\"client_itn_switch\":true,")
        append("\"functions_switch\":{}},")
        append("\"interim_results\":").append(interimResults).append(',')
        append("\"single_utterance\":false}")
    }

    internal data class RequestIds(
        val sliceId: String,
        val audioId: String,
        val deviceAid: String,
        val deviceQid: String,
        val deviceUuid: String,
    ) {
        companion object {
            fun create() = RequestIds(
                sliceId = UUID.randomUUID().toString(),
                audioId = UUID.randomUUID().toString(),
                deviceAid = UUID.randomUUID().toString(),
                deviceQid = UUID.randomUUID().toString(),
                deviceUuid = UUID.randomUUID().toString(),
            )
        }
    }

    private const val AES_KEY_BYTES = 32
    private const val AES_BLOCK_BYTES = 16
}

/** Converts fixed 16 kHz mono PCM16 into SRSS length-prefixed 20 ms Opus packets. */
internal object SogouOpusPacketEncoder {
    const val FRAME_SAMPLES = 320
    const val FRAME_PCM_BYTES = FRAME_SAMPLES * Pcm16AudioFormat.BYTES_PER_SAMPLE

    fun encode(pcm: ByteArray): List<ByteArray> {
        require(pcm.isNotEmpty()) { "PCM audio is empty" }
        require(pcm.size % Pcm16AudioFormat.BYTES_PER_SAMPLE == 0) {
            "PCM16 audio must be sample-aligned"
        }
        require(
            pcm.size <= Pcm16AudioFormat.maxPcmBytes(
                Pcm16AudioFormat.ABSOLUTE_MAX_DURATION_MILLIS,
            ),
        ) { "PCM audio exceeds the recording ceiling" }

        val encoder = OpusEncoder(
            Pcm16AudioFormat.SAMPLE_RATE_HZ,
            Pcm16AudioFormat.CHANNEL_COUNT,
            OpusApplication.OPUS_APPLICATION_VOIP,
        )
        val samples = ShortArray(FRAME_SAMPLES)
        val encoded = ByteArray(MAX_OPUS_PACKET_BYTES)
        val packets = ArrayList<ByteArray>((pcm.size + FRAME_PCM_BYTES - 1) / FRAME_PCM_BYTES)
        try {
            var pcmOffset = 0
            while (pcmOffset < pcm.size) {
                samples.fill(0)
                val byteCount = minOf(FRAME_PCM_BYTES, pcm.size - pcmOffset)
                var source = pcmOffset
                var sample = 0
                while (source < pcmOffset + byteCount) {
                    val low = pcm[source].toInt() and 0xff
                    val high = pcm[source + 1].toInt()
                    samples[sample++] = ((high shl 8) or low).toShort()
                    source += Pcm16AudioFormat.BYTES_PER_SAMPLE
                }
                val encodedBytes = encoder.encode(
                    samples,
                    0,
                    FRAME_SAMPLES,
                    encoded,
                    0,
                    encoded.size,
                )
                require(encodedBytes in 1..MAX_UNSIGNED_SHORT) {
                    "Opus encoder produced an invalid packet length"
                }
                val packet = ByteArray(LENGTH_PREFIX_BYTES + encodedBytes)
                packet[0] = (encodedBytes ushr 8).toByte()
                packet[1] = encodedBytes.toByte()
                encoded.copyInto(
                    packet,
                    destinationOffset = LENGTH_PREFIX_BYTES,
                    endIndex = encodedBytes,
                )
                packets += packet
                pcmOffset += byteCount
            }
            return packets
        } catch (error: Exception) {
            packets.forEach { it.fill(0) }
            throw error
        } finally {
            samples.fill(0)
            encoded.fill(0)
        }
    }

    private const val LENGTH_PREFIX_BYTES = 2
    private const val MAX_OPUS_PACKET_BYTES = 1_275
    private const val MAX_UNSIGNED_SHORT = 65_535
}
