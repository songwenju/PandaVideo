package dev.pandavideo.core.platform

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/**
 * [Haptics] mapped to UIKit impact and notification feedback generators. Generators are
 * created lazily on first use; all feedback must be triggered from the main thread, which
 * is guaranteed because every call site is a UI gesture.
 */
internal object IosHaptics : Haptics {
    private val lightImpact by lazy {
        UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    }
    private val mediumImpact by lazy {
        UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    }
    private val notification by lazy { UINotificationFeedbackGenerator() }

    override fun tick() {
        lightImpact.impactOccurred()
    }

    override fun click() {
        mediumImpact.impactOccurred()
    }

    override fun success() {
        notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
    }

    override fun warning() {
        notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
    }
}
