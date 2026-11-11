package dev.pandavideo.core.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
actual fun rememberAvatarPicker(platform: PlatformServices, onResult: (Result<String?>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) callback.value(Result.success(null))
        else scope.launch {
            var saved: String? = null
            try {
                val filename = withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    val decoded = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    } ?: error("Image cannot be decoded")
                    // Camera photos commonly store rotation in EXIF rather than in their pixels.
                    val orientation = runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        }
                    }.getOrNull()
                    val matrix = Matrix().apply {
                        when (orientation) {
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                        }
                    }
                    val bitmap = try {
                        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    } catch (e: Exception) { decoded.recycle(); throw e }
                    if (bitmap !== decoded) decoded.recycle()
                    try {
                        val scale = minOf(1f, 512f / max(bitmap.width, bitmap.height))
                        val resized = Bitmap.createScaledBitmap(bitmap,
                            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
                        try {
                            val name = "${UUID.randomUUID()}.jpg"
                            val file = File(requireNotNull(platform.avatarDirectory()), name)
                            try {
                                file.outputStream().use { check(resized.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                                saved = name
                                name
                            } catch (e: Exception) {
                                file.delete()
                                throw e
                            }
                        } finally { if (resized !== bitmap) resized.recycle() }
                    } finally { bitmap.recycle() }
                }
                callback.value(Result.success(filename))
                saved = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                callback.value(Result.failure(e))
            } finally { saved?.let(platform::deleteAvatar) }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
