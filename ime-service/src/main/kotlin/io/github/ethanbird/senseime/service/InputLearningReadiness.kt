package io.github.ethanbird.senseime.service

/** Keeps editor privacy independent from each scheme decoder's installation lifecycle. */
internal fun isPinyinLearningReady(
    localPersistenceAllowed: Boolean,
    productionPinyinDecoderReady: Boolean,
): Boolean = localPersistenceAllowed && productionPinyinDecoderReady

internal fun isWubiLearningReady(
    localPersistenceAllowed: Boolean,
    adaptiveWubiDecoderReady: Boolean,
): Boolean = localPersistenceAllowed && adaptiveWubiDecoderReady
