package io.github.ethanbird.senseime.brain

/**
 * Request-scoped behavior that must not be persisted as part of a provider profile.
 *
 * A connectivity probe deliberately disables provider thinking and uses a small token ceiling so
 * validating credentials cannot unexpectedly spend the latency or budget of a real editing run.
 */
enum class BrainRequestMode {
    NORMAL,
    CONNECTIVITY_TEST,
}
