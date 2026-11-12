@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.pandavideo

import dev.pandavideo.data.repository.CacheMaintenance
import dev.pandavideo.data.repository.ContentCacheUsage
import dev.pandavideo.data.repository.formatCacheBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CacheMaintenanceTest {
    @Test fun scanningIsReadOnlyAndCleanupRescansConcurrentNewCacheWrites() = runTest {
        var posters = 1024L
        var content = ContentCacheUsage(2048, 3)
        var cleared = 0
        val manager = CacheMaintenance(backgroundScope, { posters }, { content },
            { cleared++; posters = 128 }, { cleared++; content = ContentCacheUsage() })
        manager.clear()
        runCurrent()
        assertEquals(0, cleared)
        manager.scan()
        runCurrent()
        assertEquals(3072L, manager.state.value.usage?.totalBytes)
        assertEquals(0, cleared)
        manager.clear()
        runCurrent()
        assertEquals(2, cleared)
        assertEquals(128L, manager.state.value.usage?.totalBytes)
        assertEquals(2944L, manager.state.value.cleanedBytes)
        assertNull(manager.state.value.error)
    }

    @Test fun duplicateCleanupAndScanCannotRaceAnActiveCleanup() = runTest {
        val gate = CompletableDeferred<Unit>()
        var clearCalls = 0
        val manager = CacheMaintenance(backgroundScope, { 1024 }, { ContentCacheUsage() },
            { clearCalls++; gate.await() }, {})
        manager.scan()
        runCurrent()
        manager.clear()
        runCurrent()
        assertTrue(manager.state.value.cleaning)
        manager.clear()
        manager.scan()
        runCurrent()
        assertEquals(1, clearCalls)
        gate.complete(Unit)
        runCurrent()
        assertFalse(manager.state.value.busy)
    }

    @Test fun partialFailureStillCleansOtherCategoryAndCanBeRetried() = runTest {
        var fail = true
        var posters = 1024L
        var content = ContentCacheUsage(2048, 1)
        val manager = CacheMaintenance(backgroundScope, { posters }, { content },
            { if (fail) error("Disk cache busy"); posters = 0 },
            { content = ContentCacheUsage() })
        manager.scan()
        runCurrent()
        manager.clear()
        runCurrent()
        assertEquals(1024L, manager.state.value.usage?.totalBytes)
        assertNotNull(manager.state.value.error)
        assertNull(manager.state.value.cleanedBytes)
        fail = false
        manager.clear()
        runCurrent()
        assertEquals(0L, manager.state.value.usage?.totalBytes)
        assertNull(manager.state.value.error)
        assertEquals(1024L, manager.state.value.cleanedBytes)
    }

    @Test fun scanFailureIsUnknownInsteadOfReportingNoCache() = runTest {
        var fail = true
        val manager = CacheMaintenance(backgroundScope,
            { if (fail) error("Unreadable"); 512 }, { ContentCacheUsage() }, {}, {})
        manager.scan()
        runCurrent()
        assertNull(manager.state.value.usage)
        assertNotNull(manager.state.value.error)
        assertFalse(manager.state.value.busy)
        fail = false
        manager.scan()
        runCurrent()
        assertEquals(512L, manager.state.value.usage?.totalBytes)
        assertNull(manager.state.value.error)
    }

    @Test fun scanFailureActionRescansAndCleanupFailureActionCleans() = runTest {
        var scanFails = true
        var clearFails = true
        var bytes = 512L
        var clearAttempts = 0
        var retry: (() -> Unit)? = null
        val manager = CacheMaintenance(backgroundScope,
            { check(!scanFails); bytes }, { ContentCacheUsage() },
            { clearAttempts++; check(!clearFails); bytes = 0 }, {},
            onFailure = { _, action -> retry = action })
        manager.scan(); runCurrent()
        assertEquals(0, clearAttempts)
        scanFails = false
        assertNotNull(retry).invoke(); runCurrent()
        assertEquals(512L, manager.state.value.usage?.totalBytes)
        assertNull(manager.state.value.error)
        manager.clear(); runCurrent()
        assertEquals(1, clearAttempts)
        clearFails = false
        assertNotNull(retry).invoke(); runCurrent()
        assertEquals(2, clearAttempts)
        assertEquals(0L, manager.state.value.usage?.totalBytes)
        assertNull(manager.state.value.error)
    }

    @Test fun byteLabelsHandleSmallValuesUnitBoundariesAndLargeFiles() {
        assertEquals("0 B", formatCacheBytes(-1))
        assertEquals("512 B", formatCacheBytes(512))
        assertEquals("1.0 KB", formatCacheBytes(1024))
        assertEquals("1.5 MB", formatCacheBytes(1536L * 1024))
        assertEquals("1.0 GB", formatCacheBytes(1024L * 1024 * 1024))
        assertTrue(formatCacheBytes(Long.MAX_VALUE).endsWith(" GB"))
    }
}
