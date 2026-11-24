package com.meapet.mobile.chat

import com.meapet.mobile.storage.ConversationRepository
import com.meapet.mobile.storage.InMemoryConversationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ConversationManager 落盘队列的生命周期测试。
 *
 * 消费协程由外部 scope 驱动；scope 取消后队列不会再有消费者，
 * flush() 必须及时返回，已入队的 Flush 必须被兜底完成，不能永久挂起。
 */
class ConversationManagerPersistTest {

    private fun msg(i: Int) = ChatMessage(
        role = ChatRole.user,
        content = "m$i",
        id = "id$i",
        timestamp = 1_000L + i
    )

    @Test
    fun `落盘 scope 取消后 flush 立即返回而不是永久挂起`() = runBlocking {
        val repo = InMemoryConversationRepository()
        val scopeJob = Job()
        val mgr = ConversationManager(
            repository = repo,
            persistScope = CoroutineScope(scopeJob + Dispatchers.IO)
        )
        mgr.addMessage(msg(1))
        mgr.flush()

        scopeJob.cancelAndJoin()

        // 修复前 Flush 入队后无人完成，这里会超时
        withTimeout(3_000) { mgr.flush() }
    }

    @Test
    fun `单个 op 抛异常不会终止消费协程`() = runBlocking {
        val stored = InMemoryConversationRepository()
        val repo = object : ConversationRepository by stored {
            private var failNextAppend = true

            override suspend fun append(message: ChatMessage) {
                if (failNextAppend) {
                    failNextAppend = false
                    throw IllegalStateException("boom")
                }
                stored.append(message)
            }
        }
        val scopeJob = Job()
        val mgr = ConversationManager(
            repository = repo,
            persistScope = CoroutineScope(scopeJob + Dispatchers.IO)
        )
        try {
            mgr.addMessage(msg(1)) // 第一条落库失败
            mgr.addMessage(msg(2)) // 后续 op 仍应被执行
            withTimeout(3_000) { mgr.flush() }

            assertEquals(listOf("id2"), stored.loadRecent(10).map { it.id })
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun `消费协程取消时已入队的 flush 被兜底完成`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = object : ConversationRepository by InMemoryConversationRepository() {
            override suspend fun append(message: ChatMessage) {
                entered.complete(Unit)
                gate.await() // 一直挂起，直到 scope 取消
            }
        }
        val scopeJob = Job()
        val mgr = ConversationManager(
            repository = repo,
            persistScope = CoroutineScope(scopeJob + Dispatchers.IO)
        )
        try {
            mgr.addMessage(msg(1))
            withTimeout(3_000) { entered.await() } // 消费协程已卡在第一条 op 上

            // UNDISPATCHED：确保 Flush 已入队并停在 done.await() 上，再取消消费者
            val flushJob = launch(start = CoroutineStart.UNDISPATCHED) { mgr.flush() }

            scopeJob.cancelAndJoin()

            // 消费协程退出时必须在 finally 里完成队列中遗留的 Flush
            withTimeout(3_000) { flushJob.join() }
            assertTrue(flushJob.isCompleted)
        } finally {
            scopeJob.cancelAndJoin()
        }
    }
}
