package dev.pandavideo.core.platform

/** Lightweight physical feedback for taps, boundaries and confirmations. */
interface Haptics {
    /** Discrete tick for selection changes, sliders and ordinary button taps. */
    fun tick()

    /** Stronger click for primary playback actions. */
    fun click()

    /** Confirmation pattern for a successful save or toggle action. */
    fun success()

    /** Rejection pattern when an action is unavailable or hits a boundary. */
    fun warning()
}

/**
 * No-op fallback used until a platform wires its tactile actuator. Every call returns
 * immediately, so hosts without haptics hardware (or the JVM test harness) stay silent
 * without each call site needing a capability check.
 */
internal object SilentHaptics : Haptics {
    override fun tick() = Unit
    override fun click() = Unit
    override fun success() = Unit
    override fun warning() = Unit
}
