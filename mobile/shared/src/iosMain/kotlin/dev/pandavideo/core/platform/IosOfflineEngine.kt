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
 * No full video is held in Kotlin memory. HLS tasks are suspended and recovered; file
 * downloads persist Apple's opaque resume data when the server permits byte-range resume.
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
    private val retained = mutableMapOf<String, Pending>()
    private val restoredTasks = mutableMapOf<String, NSURLSessionTask>()
    private val restoredLocations = mutableMapOf<String, String>()
    private val savingResume = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val recovered = CompletableDeferred<Unit>()

    init {
        files.createDirectoryAtPath(root, true, null, null)
        // Reattach background HLS tasks by opaque ID. The shared index restores them paused,
        // so stop network work until the user resumes and the network gate authorizes it.
        hlsSession.getAllTasksWithCompletionHandler { tasks ->
            tasks.orEmpty().filterIsInstance<NSURLSessionTask>().forEach {
                val id = it.taskDescription
                if (id != null && id.matches(Regex("[a-z0-9-]+"))) {
                    it.suspend()
                    restoredTasks[id] = it
                } else it.cancel()
            }
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
        savingResume[id]?.await()
        if (asset(id, id) != null) return id
        return suspendCancellableCoroutine { continuation ->
            try {
                require(supports(info.kind))
                check(pending == null)
                val url = requireNotNull(NSURL.URLWithString(info.url))
                files.createDirectoryAtPath(directory(id), true, null, null)
                val previous = retained.remove(id)
                val restored = restoredTasks.remove(id)
                if (info.kind != "hls") {
                    previous?.task?.cancel()
                    previous?.location?.let { files.removeItemAtPath(it, null) }
                    restored?.cancel()
                    restoredLocations.remove(id)?.let { files.removeItemAtPath(it, null) }
                }
                val resumed = NSData.dataWithContentsOfFile(directory(id) + "/resume-data")
                val task: NSURLSessionTask = if (info.kind == "hls") {
                    previous?.task ?: restored ?:
                    requireNotNull(hlsSession.assetDownloadTaskWithURLAsset(
                        AVURLAsset(uRL = url, options = null), info.title, null, null,
                    ))
                } else if (resumed != null) {
                    fileSession.downloadTaskWithResumeData(resumed).also {
                        // Consume once; an expired URL or rejected range must permit a fresh
                        // resolved request on the next retry instead of looping on stale data.
                        files.removeItemAtPath(directory(id) + "/resume-data", null)
                    }
                } else if (dev.pandavideo.core.player.BilibiliMedia.owns(info.url)) {
                    val request = NSMutableURLRequest.requestWithURL(url)
                    request.setValue(dev.pandavideo.core.player.BilibiliMedia.userAgent, "User-Agent")
                    fileSession.downloadTaskWithRequest(request)
                } else fileSession.downloadTaskWithURL(url)
                task.taskDescription = id
                pending = Pending(id, task, continuation, progress,
                    if (previous?.task == task) previous.location else restoredLocations.remove(id))
                continuation.invokeOnCancellation { cancel(id) }
                task.resume()
            } catch (error: Exception) { continuation.resumeWithException(error) }
        }
    }

    override fun cancel(id: String) {
        val current = pending?.takeIf { it.id == id } ?: return
        pending = null
        if (current.task is AVAssetDownloadTask) {
            current.task.suspend()
            retained[id] = current
        } else if (current.task is NSURLSessionDownloadTask) {
            // Cancellation finishes asynchronously. A replacement attempt must await this
            // write, otherwise a fast resume could race and restart from byte zero.
            val saved = CompletableDeferred<Unit>()
            savingResume[id] = saved
            current.task.cancelByProducingResumeData { data ->
                data?.writeToFile(directory(id) + "/resume-data", true)
                saved.complete(Unit)
                savingResume.remove(id)
            }
        } else current.task.cancel()
        current.continuation.cancel()
    }
    override suspend fun remove(id: String) {
        recovered.await()
        cancel(id)
        savingResume[id]?.await()
        retained.remove(id)?.let {
            it.task.cancel()
            it.location?.let { path -> files.removeItemAtPath(path, null) }
        }
        restoredTasks.remove(id)?.cancel()
        restoredLocations.remove(id)?.let { files.removeItemAtPath(it, null) }
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
            ?: retained.values.firstOrNull { it.task == assetDownloadTask }
        val path = didFinishDownloadingToURL.path ?: return
        if (current == null) {
            val id = assetDownloadTask.taskDescription
            if (id != null && restoredTasks[id] == assetDownloadTask) {
                restoredLocations[id] = path
                return
            }
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
        val current = pending?.takeIf { it.task == task }
            ?: retained.values.firstOrNull { it.task == task }
        if (current == null) {
            val id = task.taskDescription ?: return
            if (restoredTasks[id] != task) return
            restoredTasks.remove(id)
            val path = restoredLocations.remove(id) ?: return
            // A background task can finish just before suspension is delivered on relaunch.
            // Retain its completed package for explicit resume, without starting network work.
            files.createDirectoryAtPath(directory(id), true, null, null)
            if (didCompleteWithError != null || !NSString.create(
                    string = path.removePrefix(NSHomeDirectory() + "/")).writeToFile(
                    directory(id) + "/location", true, NSUTF8StringEncoding, null))
                files.removeItemAtPath(path, null)
            return
        }
        if (pending?.task == task) pending = null
        retained.remove(current.id)
        val path = current.location
        if (didCompleteWithError != null || path == null || !files.fileExistsAtPath(path)) {
            (didCompleteWithError?.userInfo?.get("NSURLSessionDownloadTaskResumeData") as? NSData)
                ?.writeToFile(directory(current.id) + "/resume-data", true)
            path?.let { files.removeItemAtPath(it, null) }
            if (current.continuation.isActive)
                current.continuation.resumeWithException(IllegalStateException("缓存失败"))
            return
        }
        // Save a sandbox-relative path: iOS can change the container UUID between installs.
        val relative = path.removePrefix(NSHomeDirectory() + "/")
        val written = NSString.create(string = relative).writeToFile(
            directory(current.id) + "/location", true, NSUTF8StringEncoding, null,
        )
        if (written) {
            files.removeItemAtPath(directory(current.id) + "/resume-data", null)
            if (current.continuation.isActive) current.continuation.resume(current.id)
        }
        else {
            files.removeItemAtPath(path, null)
            if (current.continuation.isActive)
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
