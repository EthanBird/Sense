package io.github.ethanbird.senseime.service.ai.editor

import java.util.Locale

/**
 * Android-independent interpretation of the stable InputType/EditorInfo security bits.
 *
 * Keeping this conversion pure avoids duplicating subtly different password checks at the UI,
 * capture, and persistence call sites. [oneTimeCodeSignal] is supplied by the Android adapter from
 * the host metadata/OTP heuristic because EditorInfo has no universal OTP bit.
 */
object AndroidEditorSecurityClassifier {
    fun hasOneTimeCodeSignal(vararg metadata: CharSequence?): Boolean {
        val normalized = metadata.joinToString(" ") { it?.toString().orEmpty() }
            .lowercase(Locale.ROOT)
        return OTP_METADATA_HINTS.any(normalized::contains)
    }

    fun classify(
        inputType: Int,
        imeOptions: Int,
        oneTimeCodeSignal: Boolean = false,
        classificationComplete: Boolean = true,
    ): EditorSecurityContext {
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return EditorSecurityContext(
            isTextPassword =
                inputClass == TYPE_CLASS_TEXT &&
                    variation == TYPE_TEXT_VARIATION_PASSWORD,
            isVisibleTextPassword =
                inputClass == TYPE_CLASS_TEXT &&
                    variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            isWebPassword =
                inputClass == TYPE_CLASS_TEXT &&
                    variation == TYPE_TEXT_VARIATION_WEB_PASSWORD,
            isNumberPassword =
                inputClass == TYPE_CLASS_NUMBER &&
                    variation == TYPE_NUMBER_VARIATION_PASSWORD,
            isOneTimeCode = oneTimeCodeSignal,
            noPersonalizedLearning =
                imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
            classificationComplete = classificationComplete,
        )
    }

    // Stable public Android constants copied to keep this source free of android.* dependencies.
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    private const val IME_FLAG_NO_PERSONALIZED_LEARNING = 0x01000000
    private val OTP_METADATA_HINTS = listOf(
        "otp",
        "one-time",
        "one_time",
        "one time",
        "verification code",
        "verification_code",
        "verification-code",
        "sms code",
        "sms_code",
        "sms-code",
        "2fa",
        "two-factor",
        "two_factor",
        "authentication code",
        "auth code",
        "security code",
        "验证码",
        "动态码",
        "校验码",
    )
}
