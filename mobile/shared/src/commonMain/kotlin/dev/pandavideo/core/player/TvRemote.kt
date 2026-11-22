package dev.pandavideo.core.player

/** Owns the entire confirmation press that reveals controls across a focus change. */
internal class TvConfirmPress {
    private var heldKey: Int? = null

    fun handle(key: Int, down: Boolean, repeatCount: Int, controlsVisible: Boolean,
        revealControls: () -> Unit): Boolean {
        if (heldKey == key) {
            if (!down) heldKey = null
            return true
        }
        if (controlsVisible || !down) return false
        heldKey = key
        if (repeatCount == 0) revealControls()
        return true
    }
}

/** Bounds a remote seek to a known timeline; unknown/live duration retains the position. */
internal fun tvSeekTarget(position: Long, duration: Long, forward: Boolean): Long {
    if (duration <= 0) return position.coerceAtLeast(0)
    val bounded = position.coerceIn(0, duration)
    return if (forward) bounded + minOf(10_000L, duration - bounded)
        else (bounded - 10_000L).coerceAtLeast(0)
}

/**
 * Accumulates remote input against a frozen timeline without issuing decoder commands.
 * Time is supplied by the caller so key-repeat rates cannot change seek distance.
 */
internal class TvSeekGesture {
    var target: Long? = null
        private set
    var forward = true
        private set
    var longPress = false
        private set
    var suppressed = false
        private set
    private var duration = 0L
    private var started = 0L
    private var appliedSteps = 0L

    fun press(now: Long, position: Long, duration: Long, forward: Boolean) {
        if (target == null) {
            if (duration <= 0) return
            this.duration = duration
            this.forward = forward
            started = now
            appliedSteps = 0
            longPress = false
            suppressed = false
            target = tvSeekTarget(position, duration, forward)
        } else if (this.forward != forward) {
            tick(now)
            this.forward = forward
            target = tvSeekTarget(target!!, this.duration, forward)
            // Reversing direction adds one step, then retains the original hold cadence.
        }
    }

    fun tick(now: Long) {
        val current = target ?: return
        val held = (now - started).coerceAtLeast(0)
        if (held <= 600) return
        longPress = true
        val steps = (held - 600) / 120
        val count = (steps - appliedSteps).coerceAtLeast(0)
        appliedSteps = steps
        val distance = if (count >= Long.MAX_VALUE / 10_000) Long.MAX_VALUE else count * 10_000
        target = if (forward) current + minOf(distance, duration - current)
            else current - minOf(distance, current)
    }

    fun dismiss() { if (target != null) suppressed = true }

    /** Returns one final target; duplicate releases and cancelled media return null. */
    fun release(now: Long): Long? {
        tick(now)
        val result = target
        cancel()
        return result
    }

    fun cancel() {
        target = null
        longPress = false
        suppressed = false
    }
}
