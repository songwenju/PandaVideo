@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.pandavideo.core.platform

import dev.pandavideo.core.model.PlaybackInfo
import dev.pandavideo.data.repository.OfflineAsset
import dev.pandavideo.data.repository.OfflineEngine
import kotlinx.cinterop.CValue
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AVFoundation downloads HLS as a playable asset package; NSURLSession streams MP4 to disk.
 * No full video is held in Kotlin memory. Interrupted iOS attempts restart explicitly.
 */
internal class IosOfflineEngine : OfflineEngine {
    private val files = NSFileManager.defaultManager
    private val root = NSHomeDirectory() + "/Library/Application Support/PandaVideo/offline-media"
    private val delegate = Delegate(this)
    private val hlsSession = AVAssetDownloadURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier("dev.pandavideo.offline.hls"),
        delegate, NSOperationQueue.mainQueue,
    )
    private val fileSession = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.defaultSessionConfiguration, delegate, NSOperationQueue.mainQueue,
    )
    private data class Pending(
        val id: String, val task: NSURLSessionTask, val continuation: CancellableContinuation<String>,
        val progress: (Float?) -> Unit, var location: String? = null,
    )
    private var pending: Pending? = null
    private val recovered = CompletableDeferred<Unit>()

    init {
        files.createDirectoryAtPath(root, true, null, null)
        // Metadata restores interrupted entries as paused. Cancel orphaned system transfers
        // before accepting another attempt, so they cannot publish a stale completion.
        hlsSession.getAllTasksWithCompletionHandler { tasks ->
            tasks.orEmpty().filterIsInstance<NSURLSessionTask>().forEach { it.cancel() }
            recovered.complete(Unit)
        }
    }
    override fun supports(kind: String): Boolean = kind in setOf("hls", "video")
    private fun directory(id: String): String {
        require(id.matches(Regex("[a-z0-9-]+")))
        return "$root/$id"
    }
    override suspend fun download(id: String, info: PlaybackInfo, progress: (Float?) -> Unit): String {
        recovered.await()
        // iOS retries start a fresh asset. Remove a previous attempt's package before replacing
        // its locator so a cancelled publication cannot leave an unreachable large file behind.
        remove(id)
        return suspendCancellableCoroutine { continuation ->
            try {
                require(supports(info.kind))
                check(pending == null)
                val url = requireNotNull(NSURL.URLWithString(info.url))
                val task: NSURLSessionTask = if (info.kind == "hls") {
                    requireNotNull(hlsSession.assetDownloadTaskWithURLAsset(
                        AVURLAsset(uRL = url, options = null), info.title, null, null,
                    ))
                } else fileSession.downloadTaskWithURL(url)
                files.createDirectoryAtPath(directory(id), true, null, null)
                pending = Pending(id, task, continuation, progress)
                continuation.invokeOnCancellation { cancel(id) }
                task.resume()
            } catch (error: Exception) { continuation.resumeWithException(error) }
        }
    }

    override fun cancel(id: String) {
        val current = pending?.takeIf { it.id == id } ?: return
        pending = null
        current.task.cancel()
        current.location?.let { files.removeItemAtPath(it, null) }
        current.continuation.cancel()
    }
    override suspend fun remove(id: String) {
        cancel(id)
        withContext(Dispatchers.Default) {
            storedLocation(id)?.let {
                if (files.fileExistsAtPath(it)) check(files.removeItemAtPath(it, null))
            }
            val dir = directory(id)
            if (files.fileExistsAtPath(dir)) check(files.removeItemAtPath(dir, null))
        }
    }
    private fun storedLocation(id: String): String? {
        val relative = NSString.stringWithContentsOfFile(directory(id) + "/location", NSUTF8StringEncoding, null)
            ?: return null
        if (!relative.startsWith("Library/") || relative.split('/').any { it == ".." }) return null
        return NSHomeDirectory() + "/" + relative
    }
    override fun asset(id: String, locator: String): OfflineAsset? {
        if (id != locator) return null
        val path = storedLocation(id)?.takeIf { files.fileExistsAtPath(it) } ?: return null
        return OfflineAsset(NSURL.fileURLWithPath(path).absoluteString.orEmpty(), "offline")
    }

    fun URLSession(
        session: NSURLSession, assetDownloadTask: AVAssetDownloadTask,
        didFinishDownloadingToURL: NSURL,
    ) {
        val current = pending?.takeIf { it.task == assetDownloadTask }
        val path = didFinishDownloadingToURL.path ?: return
        if (current == null) {
            // A cancelled old task can finish after a new task starts; never publish its package.
            files.removeItemAtPath(path, null)
        } else current.location = path
    }

    fun URLSession(
        session: NSURLSession, assetDownloadTask: AVAssetDownloadTask,
        didLoadTimeRange: CValue<CMTimeRange>, totalTimeRangesLoaded: List<*>,
        timeRangeExpectedToLoad: CValue<CMTimeRange>,
    ) {
        val current = pending?.takeIf { it.task == assetDownloadTask } ?: return
        val total = timeRangeExpectedToLoad.useContents { CMTimeGetSeconds(duration.readValue()) }
        val loaded = totalTimeRangesLoaded.filterIsInstance<NSValue>().sumOf {
            it.CMTimeRangeValue.useContents { CMTimeGetSeconds(duration.readValue()) }
        }
        current.progress(if (total > 0 && loaded.isFinite()) (loaded / total).toFloat() else null)
    }

    fun URLSession(
        session: NSURLSession, downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long, totalBytesWritten: Long, totalBytesExpectedToWrite: Long,
    ) {
        pending?.takeIf { it.task == downloadTask }?.progress?.invoke(
            if (totalBytesExpectedToWrite > 0) totalBytesWritten.toFloat() / totalBytesExpectedToWrite else null,
        )
    }
    fun URLSession(
        session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL,
    ) {
        val current = pending?.takeIf { it.task == downloadTask } ?: return
        val status = (downloadTask.response as? NSHTTPURLResponse)?.statusCode ?: 0
        if (status !in 200L..299L) return
        val destination = directory(current.id) + "/video.mp4"
        files.removeItemAtPath(destination, null)
        if (files.moveItemAtURL(didFinishDownloadingToURL, NSURL.fileURLWithPath(destination), null))
            current.location = destination
    }
    fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val current = pending?.takeIf { it.task == task } ?: return
        pending = null
        val path = current.location
        if (didCompleteWithError != null || path == null || !files.fileExistsAtPath(path)) {
            path?.let { files.removeItemAtPath(it, null) }
            current.continuation.resumeWithException(IllegalStateException("缓存失败"))
            return
        }
        // Save a sandbox-relative path: iOS can change the container UUID between installs.
        val relative = path.removePrefix(NSHomeDirectory() + "/")
        val written = NSString.create(string = relative).writeToFile(
            directory(current.id) + "/location", true, NSUTF8StringEncoding, null,
        )
        if (written) current.continuation.resume(current.id)
        else {
            files.removeItemAtPath(path, null)
            current.continuation.resumeWithException(IllegalStateException("无法保存缓存"))
        }
    }

    // Kotlin/Native cannot mix a Kotlin interface and Objective-C protocols on one NSObject.
    // Keep the native delegate separate from the shared engine boundary.
    private class Delegate(private val owner: IosOfflineEngine) : NSObject(),
        AVAssetDownloadDelegateProtocol, NSURLSessionDownloadDelegateProtocol {
        override fun URLSession(session: NSURLSession, assetDownloadTask: AVAssetDownloadTask,
            didFinishDownloadingToURL: NSURL) =
            owner.URLSession(session, assetDownloadTask, didFinishDownloadingToURL)
        override fun URLSession(session: NSURLSession, assetDownloadTask: AVAssetDownloadTask,
            didLoadTimeRange: CValue<CMTimeRange>, totalTimeRangesLoaded: List<*>,
            timeRangeExpectedToLoad: CValue<CMTimeRange>) =
            owner.URLSession(session, assetDownloadTask, didLoadTimeRange, totalTimeRangesLoaded, timeRangeExpectedToLoad)
        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask,
            didWriteData: Long, totalBytesWritten: Long, totalBytesExpectedToWrite: Long) =
            owner.URLSession(session, downloadTask, didWriteData, totalBytesWritten, totalBytesExpectedToWrite)
        override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask,
            didFinishDownloadingToURL: NSURL) =
            owner.URLSession(session, downloadTask, didFinishDownloadingToURL)
        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) =
            owner.URLSession(session, task, didCompleteWithError)
    }
}
