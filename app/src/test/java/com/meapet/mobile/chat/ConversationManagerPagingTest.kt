package com.meapet.mobile.chat

import com.meapet.mobile.storage.InMemoryConversationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 完整历史（仓库）与发给模型的内存窗口解耦：窗口裁剪不影响 UI 分页与清除快照。 */
class ConversationManagerPagingTest {

    private fun msg(i: Int) = ChatMessage(
        role = if (i % 2 == 1) ChatRole.user else ChatRole.assistant,
        content = "m$i",
        id = "id$i",
        timestamp = 1000L + i
    )

    @Test
    fun windowTrimDoesNotAffectRepositoryHistory() = runTest {
        val repo = InMemoryConversationRepository()
        val mgr = ConversationManager(maxSize = 10, trimBatch = 4, repository = repo, persistScope = backgroundScope)

        (1..30).forEach { mgr.addMessage(msg(it)) }
        mgr.flush()

        assertTrue(mgr.size < 30, "内存窗口应被裁剪")
        assertEquals(30, mgr.loadFullHistory().size, "仓库保留完整历史")
    }

    @Test
    fun pagingWalksFullHistoryBackwards() = runTest {
        val repo = InMemoryConversationRepository()
        val mgr = ConversationManager(maxSize = 10, trimBatch = 4, repository = repo, persistScope = backgroundScope)
        (1..25).forEach { mgr.addMessage(msg(it)) }

        val first = mgr.loadRecentForUi(10)
        assertEquals((16..25).map { "id$it" }, first.map { it.id })
        val second = mgr.pageBefore(first.first().id, 10)
        assertEquals((6..15).map { "id$it" }, second.map { it.id })
        val third = mgr.pageBefore(second.first().id, 10)
        assertEquals((1..5).map { "id$it" }, third.map { it.id })
    }

    @Test
    fun replaceAllRestoresFullHistoryButKeepsWindowSmall() = runTest {
        val repo = InMemoryConversationRepository()
        val mgr = ConversationManager(maxSize = 10, trimBatch = 4, repository = repo, persistScope = backgroundScope)
        val snapshot = (1..30).map { msg(it) }

        mgr.replaceAll(snapshot)
        mgr.flush()

        assertTrue(mgr.size <= 10)
        assertEquals(30, mgr.loadFullHistory().size)
    }

    @Test
    fun removeAlsoDeletesMessagesAlreadyTrimmedFromWindow() = runTest {
        val repo = InMemoryConversationRepository()
        val mgr = ConversationManager(maxSize = 10, trimBatch = 4, repository = repo, persistScope = backgroundScope)
        (1..30).forEach { mgr.addMessage(msg(it)) }

        mgr.removeMessage("id1")
        mgr.flush()

        assertEquals(29, mgr.loadFullHistory().size)
    }
}
