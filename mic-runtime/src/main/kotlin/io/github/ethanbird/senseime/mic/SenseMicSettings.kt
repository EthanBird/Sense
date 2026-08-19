package io.github.ethanbird.senseime.mic

import android.content.Context
import java.security.SecureRandom

enum class SenseMicQuality(
    val bitrate: Int,
    val label: String,
) {
    ECONOMY(32_000, "省流量 · 32 kbps"),
    BALANCED(64_000, "平衡 · 64 kbps"),
    HIGH(96_000, "高保真语音 · 96 kbps"),
}

enum class SenseMicCaptureProfile {
    VOICE_COMMUNICATION,
    UNPROCESSED,
}

data class SenseMicSettings(
    val enabled: Boolean = false,
    val pairCode: String = generatePairCode(),
    val quality: SenseMicQuality = SenseMicQuality.BALANCED,
    val captureProfile: SenseMicCaptureProfile = SenseMicCaptureProfile.VOICE_COMMUNICATION,
) {
    init {
        require(pairCode.matches(Regex("[0-9]{6}")))
    }

    companion object {
        private val random = SecureRandom()

        fun generatePairCode(): String = "%06d".format(random.nextInt(1_000_000))
    }
}

class SenseMicSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): SenseMicSettings {
        val pairCode = preferences.getString(KEY_PAIR_CODE, null)
            ?.takeIf { it.matches(Regex("[0-9]{6}")) }
            ?: SenseMicSettings.generatePairCode().also { generated ->
                preferences.edit().putString(KEY_PAIR_CODE, generated).apply()
            }
        return SenseMicSettings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            pairCode = pairCode,
            quality = preferences.getString(KEY_QUALITY, null)
                ?.let { stored -> SenseMicQuality.entries.firstOrNull { it.name == stored } }
                ?: SenseMicQuality.BALANCED,
            captureProfile = preferences.getString(KEY_CAPTURE_PROFILE, null)
                ?.let { stored -> SenseMicCaptureProfile.entries.firstOrNull { it.name == stored } }
                ?: SenseMicCaptureProfile.VOICE_COMMUNICATION,
        )
    }

    @Synchronized
    fun save(settings: SenseMicSettings) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_PAIR_CODE, settings.pairCode)
            .putString(KEY_QUALITY, settings.quality.name)
            .putString(KEY_CAPTURE_PROFILE, settings.captureProfile.name)
            .commit()
    }

    @Synchronized
    fun update(transform: (SenseMicSettings) -> SenseMicSettings): SenseMicSettings =
        transform(load()).also(::save)

    @Synchronized
    fun rotatePairCode(): SenseMicSettings = update {
        it.copy(pairCode = SenseMicSettings.generatePairCode())
    }

    private companion object {
        const val FILE_NAME = "sense_mic_settings_v1"
        const val KEY_ENABLED = "enabled"
        const val KEY_PAIR_CODE = "pair_code"
        const val KEY_QUALITY = "quality"
        const val KEY_CAPTURE_PROFILE = "capture_profile"
    }
}
