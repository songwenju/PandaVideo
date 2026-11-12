package dev.pandavideo

import dev.pandavideo.app.RetryPrompts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetryPromptsTest {
    @Test fun dismissDoesNotRetryAndRepeatedFailureDoesNotDuplicate() {
        val prompts = RetryPrompts()
        var attempts = 0
        repeat(3) { prompts.offer("search", "搜索失败", "重试") { attempts++ } }
        assertEquals(1, prompts.pending.value.size)
        prompts.dismiss(prompts.pending.value.single().id)
        assertEquals(0, attempts)
        assertTrue(prompts.pending.value.isEmpty())
    }

    @Test fun retryRunsOnceAndSynchronousFailureCanOfferAnotherPrompt() {
        val prompts = RetryPrompts()
        var attempts = 0
        prompts.offer("download", "下载失败", "重试") {
            attempts++
            prompts.offer("download", "下载失败", "重试") { attempts++ }
        }
        val id = prompts.pending.value.single().id
        prompts.retry(id)
        prompts.retry(id)
        assertEquals(1, attempts)
        assertEquals(1, prompts.pending.value.size)
        prompts.retry(prompts.pending.value.single().id)
        assertEquals(2, attempts)
        assertTrue(prompts.pending.value.isEmpty())
    }

    @Test fun supersededOrRemovedOperationsCannotBeRetried() {
        val prompts = RetryPrompts()
        var attempts = 0
        prompts.offer("catalog", "失败", "重试") { attempts++ }
        val id = prompts.pending.value.single().id
        prompts.clear("catalog")
        prompts.offer("catalog", "新请求失败", "重试") { attempts++ }
        prompts.retry(id)
        assertEquals(0, attempts)
        var exists = true
        prompts.offer("download", "失败", "重试", available = { exists }) { attempts++ }
        val download = prompts.pending.value.last()
        exists = false
        prompts.retry(download.id)
        assertEquals(0, attempts)
        assertEquals(listOf("catalog"), prompts.pending.value.map { it.key })
    }

    @Test fun differentOperationsRetainTheirOwnActionsAndDestinations() {
        val prompts = RetryPrompts()
        val operations = mutableListOf<String>()
        prompts.offer("catalog", "失败", "重试", "discover") { operations += "catalog" }
        prompts.offer("download", "失败", "重试") { operations += "download" }
        assertEquals(listOf("discover", null), prompts.pending.value.map { it.destination })
        prompts.retry(prompts.pending.value.first().id)
        assertEquals(listOf("catalog"), operations)
        prompts.retry(prompts.pending.value.single().id)
        assertEquals(listOf("catalog", "download"), operations)
    }
}
