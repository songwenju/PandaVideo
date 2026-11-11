package dev.pandavideo.core.platform

import android.app.Activity
import android.content.Context
import android.util.Log
import java.lang.reflect.Method

/**
 * Resizes the current PICO spatial window when ordinary Android orientation requests
 * are ignored. Vendor classes are resolved at runtime so phone builds need no PICO SDK.
 */
internal class PicoFullscreenWindow private constructor(
    private val activity: Activity,
    private val manager: Any,
    private val getInfo: Method,
    private val resize: Method,
) {
    private data class WindowSize(val containerId: Int, val width: Int, val height: Int, val depth: Int)
    private var beforeFullscreen: WindowSize? = null

    fun fullscreen(enabled: Boolean) {
        try {
            if (enabled) {
                // Save once, including across repeated Compose fullscreen notifications.
                // Use container pixels, not display metrics: PICO's physical display is
                // stereo and has different dimensions from the application's window.
                val original = beforeFullscreen ?: readSize()?.also { beforeFullscreen = it } ?: return
                if (original.width < original.height) {
                    resize.invoke(manager, original.containerId,
                        intArrayOf(original.height, original.width, original.depth))
                }
            } else {
                beforeFullscreen?.let { original ->
                    resize.invoke(manager, original.containerId,
                        intArrayOf(original.width, original.height, original.depth))
                    beforeFullscreen = null
                }
            }
        } catch (error: ReflectiveOperationException) {
            Log.w("PicoFullscreenWindow", "Spatial window resize failed", error)
        } catch (error: SecurityException) {
            Log.w("PicoFullscreenWindow", "Spatial window resize unavailable", error)
        }
    }

    private fun readSize(): WindowSize? {
        val info = getInfo.invoke(manager, activity) ?: return null
        val type = info.javaClass
        val size = WindowSize(
            type.getField("containerId").getInt(info),
            type.getField("width").getInt(info),
            type.getField("height").getInt(info),
            type.getField("depth").getInt(info),
        )
        return size.takeIf { it.containerId > 0 && it.width > 0 && it.height > 0 && it.depth >= 0 }
    }

    companion object {
        fun create(activity: Activity): PicoFullscreenWindow? {
            return try {
                val type = Class.forName("com.spatial.app.SpatialContainerManager")
                val manager = type.getMethod("getInstance").invoke(null) ?: return null
                PicoFullscreenWindow(
                    activity, manager,
                    type.getMethod("getSpatialContainerInfo", Context::class.java),
                    type.getMethod("resizeSpatialContainer", Int::class.javaPrimitiveType, IntArray::class.java),
                )
            } catch (_: ClassNotFoundException) {
                // Ordinary Android devices use Activity.requestedOrientation alone.
                null
            } catch (error: ReflectiveOperationException) {
                Log.w("PicoFullscreenWindow", "Spatial window API unavailable", error)
                null
            } catch (error: SecurityException) {
                Log.w("PicoFullscreenWindow", "Spatial window API inaccessible", error)
                null
            }
        }
    }
}
