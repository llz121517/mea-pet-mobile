package com.meapet.mobile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meapet.mobile.app.MeaPetApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机端到端验证：记忆工具调用（create_memory + search_memory）。
 *
 * 说明：这是**开发/验收用**的 instrumented 测试，会真实请求已配置的 API。
 * 为避免在设备上输入中文（adb 注入会被中文输入法合成），这里直接调用 [ChatService]。
 *
 * 运行：`./gradlew :app:connectedDebugAndroidTest --tests '*MemoryToolDeviceTest*'`
 */
@RunWith(AndroidJUnit4::class)
class MemoryToolDeviceTest {

    private val app: MeaPetApplication get() = ApplicationProvider.getApplicationContext()

    @Test
    fun createThenSearchMemoryViaTools() = runBlocking {
        val container = app.container
        container.warmUpJob.join()

        // 清空会话，降低这次真机验证的 token 成本（不影响记忆）
        container.chatService.clearHistory()
        kotlinx.coroutines.delay(500)

        // 1) 让模型记住一件事（应触发 create_memory）
        val remember = container.chatService.sendMessage(
            "Please remember: my favorite food is fried chicken. Reply in one short sentence."
        )
        assertTrue("记住这句话应成功: ${remember.exceptionOrNull()}", remember.isSuccess)

        val memories = container.memoryRepository.getAll()
        assertTrue(
            "应通过 create_memory 工具写入记忆；当前记忆: ${memories.map { it.content }}",
            memories.any { it.content.contains("chicken", ignoreCase = true) }
        )

        // 2) 再问一次（应触发 search_memory 并回答出来）
        val recall = container.chatService.sendMessage(
            "What is my favorite food? Answer in one short sentence."
        )
        assertTrue("检索问题应成功: ${recall.exceptionOrNull()}", recall.isSuccess)
        val reply = recall.getOrNull()?.second?.content.orEmpty()
        assertTrue(
            "回复应提到检索到的炸鸡（实际: $reply）",
            reply.contains("chicken", ignoreCase = true) || reply.contains("炸鸡")
        )
    }
}
