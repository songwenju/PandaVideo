@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.pandavideo.core.platform

import androidx.compose.runtime.*
import kotlinx.cinterop.useContents
import kotlinx.coroutines.*
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.*
import platform.PhotosUI.*
import platform.UIKit.*
import platform.darwin.NSObject

@Composable
actual fun rememberAvatarPicker(platform: PlatformServices, onResult: (Result<String?>) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val delegate = remember {
        object : NSObject(), PHPickerViewControllerDelegateProtocol {
            override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
                picker.dismissViewControllerAnimated(true, null)
                val selection = didFinishPicking.firstOrNull() as? PHPickerResult
                if (selection == null) {
                    callback.value(Result.success(null))
                    return
                }
                selection.itemProvider.loadDataRepresentationForTypeIdentifier("public.image") { data, error ->
                    scope.launch {
                        if (data == null || error != null) {
                            callback.value(Result.failure(IllegalStateException("Image cannot be read")))
                            return@launch
                        }
                        var saved: String? = null
                        try {
                            val name = withContext(Dispatchers.Default) {
                                val image = UIImage.imageWithData(data) ?: error("Image cannot be decoded")
                                val (width, height) = image.size.useContents { width to height }
                                require(width > 0 && height > 0)
                                val scale = minOf(1.0, 512.0 / maxOf(width, height))
                                UIGraphicsBeginImageContextWithOptions(CGSizeMake(width * scale, height * scale), true, 1.0)
                                val jpeg = try {
                                    image.drawInRect(CGRectMake(0.0, 0.0, width * scale, height * scale))
                                    UIGraphicsGetImageFromCurrentImageContext()?.let { UIImageJPEGRepresentation(it, 0.9) }
                                        ?: error("Image cannot be encoded")
                                } finally { UIGraphicsEndImageContext() }
                                val filename = NSUUID().UUIDString + ".jpg"
                                check(jpeg.writeToFile(requireNotNull(platform.avatarDirectory()) + "/" + filename, true))
                                saved = filename
                                filename
                            }
                            callback.value(Result.success(name))
                            saved = null
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { callback.value(Result.failure(e))
                        } finally { saved?.let(platform::deleteAvatar) }
                    }
                }
            }
        }
    }
    return {
        val configuration = PHPickerConfiguration().apply {
            selectionLimit = 1
            filter = PHPickerFilter.imagesFilter
        }
        val picker = PHPickerViewController(configuration).apply { this.delegate = delegate }
        var presenter = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (presenter?.presentedViewController != null) presenter = presenter.presentedViewController
        if (presenter == null) callback.value(Result.failure(IllegalStateException("No active window")))
        else presenter.presentViewController(picker, true, null)
    }
}
