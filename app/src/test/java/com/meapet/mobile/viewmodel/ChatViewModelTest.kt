package com.meapet.mobile.viewmodel

import com.meapet.mobile.chat.ChatMessage
import com.meapet.mobile.chat.ChatRole
import com.meapet.mobile.chat.ChatService
import com.meapet.mobile.app.AppContainer
import com.meapet.mobile.app.MeaPetApplication
import com.meapet.mobile.memory.MemoryItem
import com.meapet.mobile.memory.MemoryManager
import com.meapet.mobile.memory.MemoryStats
import com.meapet.mobile.settings.SettingsManager
import com.meapet.mobile.tts.TtsManager
import com.meapet.mobile.update.UpdateCheckResult
import com.meapet.mobile.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * [ChatViewModel] 交互测试。
 *
 * 通过 mockStatic 注入 `MeaPetApplication.from` → 假 [AppContainer]，验证
 * UI 事件 → 服务调用 → 状态更新的分发链路。测试协程与 ViewModel 共享
 * [dispatcher]（Main dispatcher），用 `advance()` 推进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    private lateinit var application: MeaPetApplication
    private lateinit var container: AppContainer
    private lateinit var chatService: ChatService
    private lateinit var memoryManager: MemoryManager
    private lateinit var updateChecker: UpdateChecker
    private lateinit var ttsManager: TtsManager
    private lateinit var settingsManager: SettingsManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = mock()
        container = mock()
        chatService = mock()
        memoryManager = mock()
        updateChecker = mock()
        ttsManager = mock()
        settingsManager = mock()
        // AppContainer 属性为 by lazy，whenever(getter) 会拿到 null；用 doReturn 直接设定
        Mockito.doReturn(chatService).`when`(container).chatService
        Mockito.doReturn(memoryManager).`when`(container).memoryManager
        Mockito.doReturn(updateChecker).`when`(container).updateChecker
        Mockito.doReturn(ttsManager).`when`(container).ttsManager
        Mockito.doReturn(settingsManager).`when`(container).settingsManager
        Mockito.doReturn(Job().apply { complete() }).`when`(container).warmUpJob
        wheneverBlocking { updateChecker.check() }.thenReturn(UpdateCheckResult.UpToDate("1.0.0"))
        wheneverBlocking { memoryManager.getStats() }.thenReturn(MemoryStats())
        wheneverBlocking { memoryManager.getAllMemories() }.thenReturn(emptyList())

        // from(application) 真实执行：application 是 MeaPetApplication 的 mock，container 经 doReturn 注入
        Mockito.doReturn(container).`when`(application).container
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 推进 ViewModel 的主线程协程（共享 scheduler）。 */
    private fun ChatViewModel.advance() {
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun reply(user: ChatMessage): ChatMessage =
        ChatMessage(role = ChatRole.assistant, content = "收到")

    @Test
    fun `初始化时加载已有会话历史`() = runTest(dispatcher.scheduler) {
        val msg = ChatMessage(role = ChatRole.user, content = "你好")
        whenever(chatService.getHistory()).thenReturn(listOf(msg))

        val vm = ChatViewModel(application)
        vm.advance()

        assertEquals(listOf(msg), vm.state.value.messages)
    }

    @Test
    fun `发送消息会调用 ChatService`() = runTest(dispatcher.scheduler) {
        // ViewModel 传入自己的乐观消息（同 id 入史），因此 stub/verify 的是 ChatMessage 重载
        wheneverBlocking { chatService.sendMessage(any<ChatMessage>()) }
            .thenReturn(Result.success(ChatMessage(ChatRole.user, "hi") to reply(ChatMessage(ChatRole.user, "hi"))))

        val vm = ChatViewModel(application)
        vm.onEvent(ChatEvent.SendMessage("hi"))
        vm.advance()

        verify(chatService).sendMessage(argThat<ChatMessage> { content == "hi" && role == ChatRole.user })
    }

    @Test
    fun `清空会话调用服务并清空消息`() = runTest(dispatcher.scheduler) {
        whenever(chatService.getHistory())
            .thenReturn(listOf(ChatMessage(role = ChatRole.user, content = "旧消息")))
        val vm = ChatViewModel(application)
        vm.advance()

        vm.onEvent(ChatEvent.ClearConversation)
        vm.advance()

        verify(chatService).clearHistory()
        assertTrue(vm.state.value.messages.isEmpty())
    }

    @Test
    fun `reloadHistory 丢弃被窗口裁剪的旧消息避免拼到末尾`() = runTest(dispatcher.scheduler) {
        // 模拟长对话：当前 UI 内存里已有 6 条（5 条旧 + 1 条最新）
        val old1 = ChatMessage(role = ChatRole.user, content = "旧1")
        val old2 = ChatMessage(role = ChatRole.assistant, content = "旧2")
        val old3 = ChatMessage(role = ChatRole.user, content = "旧3")
        val old4 = ChatMessage(role = ChatRole.assistant, content = "旧4")
        val latestUser = ChatMessage(role = ChatRole.user, content = "最新")
        val latestReply = ChatMessage(role = ChatRole.assistant, content = "回复")

        // 启动时加载全量
        whenever(chatService.getHistory()).thenReturn(
            listOf(old1, old2, old3, old4, latestUser, latestReply)
        )
        val vm = ChatViewModel(application)
        vm.advance()

        // 悬浮窗期间会话被 ConversationManager 裁剪：只剩最近的 3 条（old3/old4 起）
        whenever(chatService.getHistory()).thenReturn(
            listOf(old3, old4, latestUser, latestReply)
        )

        vm.reloadHistory()

        // 被裁剪的 old1/old2 不应被拼到末尾；结果 = 新历史（有序）+ 无多余尾巴
        assertEquals(
            listOf(old3, old4, latestUser, latestReply),
            vm.state.value.messages
        )
    }

    @Test
    fun `reloadHistory 保留历史之后新追加的尾部消息`() = runTest(dispatcher.scheduler) {
        // 历史：msg1/msg2 已落库；当前 UI 里在它们之后有一条未落库的系统气泡
        val msg1 = ChatMessage(role = ChatRole.user, content = "a")
        val msg2 = ChatMessage(role = ChatRole.assistant, content = "b")
        val bubble = ChatMessage(role = ChatRole.system, content = "触摸提示")
        whenever(chatService.getHistory()).thenReturn(listOf(msg1, msg2))
        val vm = ChatViewModel(application)
        vm.advance()

        // 构造 current = [msg1, msg2, bubble]（模拟触摸气泡 append 到末尾）
        val current = listOf(msg1, msg2, bubble)

        // merge：history 尾部 msg2 之后的新消息（bubble）应保留，旧历史以 history 为准
        val merged = vm.mergeWithHistory(listOf(msg1, msg2), current)
        assertEquals(listOf(msg1, msg2, bubble), merged)
    }

    @Test
    fun `有用户消息时重试调用服务`() = runTest(dispatcher.scheduler) {
        val user = ChatMessage(role = ChatRole.user, content = "hi")
        whenever(chatService.getHistory()).thenReturn(listOf(user))
        wheneverBlocking { chatService.retryMessage(any<ChatMessage>()) }
            .thenReturn(Result.success(user to reply(user)))

        val vm = ChatViewModel(application)
        vm.advance()

        vm.onEvent(ChatEvent.RetryLastMessage)
        vm.advance()

        verify(chatService).retryMessage(argThat<ChatMessage> { id == user.id })
    }

    /**
     * 失败后重试必须重发**界面上那条**消息。
     *
     * 失败的消息已被 `ChatService.sendMessage` 回滚出历史，若服务端侧再去取"历史里
     * 最后一条 user"，拿到的是上一条早已成功的消息：它会被重发一遍，并因为原副本还在
     * UI 列表里而造出同一个 id 的两份 → LazyColumn 重复 key → 整个应用崩溃。
     */
    @Test
    fun `失败后重试传的是界面最后一条用户消息且不产生重复 id`() = runTest(dispatcher.scheduler) {
        val oldUser = ChatMessage(role = ChatRole.user, content = "老消息")
        val oldReply = ChatMessage(role = ChatRole.assistant, content = "老回复")
        whenever(chatService.getHistory()).thenReturn(listOf(oldUser, oldReply))
        val vm = ChatViewModel(application)
        vm.advance()

        wheneverBlocking { chatService.sendMessage(any<ChatMessage>()) }
            .thenReturn(Result.failure(RuntimeException("请求过于频繁")))
        vm.onEvent(ChatEvent.SendMessage("新消息"))
        vm.advance()

        val newUser = vm.state.value.messages.last { it.isUser }
        assertEquals("新消息", newUser.content)

        wheneverBlocking { chatService.retryMessage(any<ChatMessage>()) }
            .thenReturn(Result.success(newUser to ChatMessage(ChatRole.assistant, "新回复")))
        vm.onEvent(ChatEvent.RetryLastMessage)
        vm.advance()

        verify(chatService).retryMessage(argThat<ChatMessage> { id == newUser.id })

        val ids = vm.state.value.messages.map { it.id }
        assertEquals(
            "消息列表存在重复 id: " + vm.state.value.messages.map { it.content },
            ids.size,
            ids.distinct().size
        )
        assertEquals(1, vm.state.value.messages.count { it.content == "老消息" })
    }

    @Test
    fun `打开记忆对话框拉取列表与统计`() = runTest(dispatcher.scheduler) {
        wheneverBlocking { memoryManager.getAllMemories() }
            .thenReturn(listOf(mock<MemoryItem>()))

        val vm = ChatViewModel(application)
        vm.onEvent(ChatEvent.ShowMemories)
        vm.advance()

        val dialog = vm.state.value.memoryDialog
        assertNotNull(dialog)
        assertEquals(1, dialog?.memories?.size)
    }

    /**
     * 发送失败后的消息重复（偶发 bug 的确定性复现）。
     *
     * 失败时 [ChatService.sendMessage] 不回滚，历史里残留一条与 ViewModel 乐观消息
     * 同内容、不同 id 的"幽灵消息"。此后任意一次 `ON_RESUME`（息屏亮屏 / 切后台 /
     * 悬浮窗返回）触发 [ChatViewModel.reloadHistory]，`mergeWithHistory` 因分界消息
     * 不在 UI 列表里（`idxInCurrent == -1`）落入 `takeLast(5)` 兜底分支，乐观消息
     * 因 id 不在历史里被当作"新追加"保留 → 同一句话并列成两条。
     */
    @Test
    fun `发送失败后 resume 不应把同一条消息并列成两条`() = runTest(dispatcher.scheduler) {
        whenever(chatService.getHistory()).thenReturn(emptyList())
        val vm = ChatViewModel(application)
        vm.advance()

        wheneverBlocking { chatService.sendMessage(any<ChatMessage>()) }
            .thenReturn(Result.failure(RuntimeException("请求过于频繁")))
        vm.onEvent(ChatEvent.SendMessage("hello"))
        vm.advance()
        assertEquals(1, vm.state.value.messages.count { it.isUser })

        // 真实 ChatService 的副作用：失败但历史里留下了幽灵消息（id 与乐观消息不同）。
        // 时间戳取与乐观消息相同的值——这是最严苛的情形（服务端副本只比它晚几微秒），
        // 合并时必须靠「严格晚于历史末条」把它判成旧副本而丢弃。
        val optimisticTs = vm.state.value.messages.first { it.isUser }.timestamp
        whenever(chatService.getHistory()).thenReturn(
            listOf(ChatMessage(role = ChatRole.user, content = "hello", timestamp = optimisticTs))
        )

        vm.reloadHistory()

        assertEquals(
            "resume 后同一条消息被并列成两条: " + vm.state.value.messages.map { it.content },
            1,
            vm.state.value.messages.count { it.isUser }
        )
    }

    @Test
    fun `无用户消息时重试不调用服务`() = runTest(dispatcher.scheduler) {
        val vm = ChatViewModel(application)

        vm.onEvent(ChatEvent.RetryLastMessage)
        vm.advance()

        org.mockito.kotlin.verify(chatService, org.mockito.kotlin.never())
            .retryMessage(any<ChatMessage>())
    }
}
