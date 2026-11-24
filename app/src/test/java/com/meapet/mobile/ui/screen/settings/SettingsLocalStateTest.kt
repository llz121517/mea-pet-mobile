package com.meapet.mobile.ui.screen.settings

import com.meapet.mobile.config.MemoryMode
import com.meapet.mobile.config.WebSearchProviderId
import com.meapet.mobile.settings.SettingsKeys
import com.meapet.mobile.viewmodel.SettingsUiState
import com.meapet.mobile.viewmodel.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * [SettingsLocalState] 的「外部变更 vs 用户编辑」规则。
 *
 * 背景（真机问题）：备份导入会整批替换 DataStore 里的设置，但设置页的本地副本是
 * 进页面时取的快照；离开页面时 `onDispose` 的兜底保存会把旧值写回去 —— 表现为
 * **API Key 明明写进了备份包，导入后却还是旧值**（API Key 没有任何断点会重读）。
 *
 * 规则：用户改过的字段（dirty）保持不动；没改过的字段跟随外部 state 同步，
 * 且 [SettingsLocalState.persist] 只写回 dirty 字段。
 */
@Suppress("NonAsciiCharacters")
class SettingsLocalStateTest {

    private fun state(
        apiKey: String = "",
        apiUrl: String = SettingsKeys.Defaults.API_URL,
        model: String = SettingsKeys.Defaults.MODEL,
        systemPrompt: String = SettingsKeys.Defaults.SYSTEM_PROMPT,
        contextWindow: Int? = null,
    ) = SettingsUiState(
        apiKey = apiKey,
        apiUrl = apiUrl,
        model = model,
        systemPrompt = systemPrompt,
        contextWindow = contextWindow,
        enableMemory = SettingsKeys.Defaults.ENABLE_MEMORY,
        memoryMode = MemoryMode.DEFAULT,
        webSearchProvider = WebSearchProviderId.AUTO,
    )

    private fun localOf(initial: SettingsUiState) =
        SettingsLocalState(initial, initialAnySearchKey = "", initialDeepSeekKey = "")

    @Test
    fun `未编辑字段跟随外部导入的新值`() {
        val local = localOf(state(apiKey = "sk-old"))

        // 模拟导入：DataStore 被整批替换成新值
        local.syncFrom(
            state = state(apiKey = "sk-imported", model = "imported-model"),
            anySearchKey = "as-imported",
            deepSeekKey = "ds-imported",
        )

        assertEquals("sk-imported", local.apiKey)
        assertEquals("imported-model", local.model)
        assertEquals("as-imported", local.webSearchAnySearchKey)
        assertEquals("ds-imported", local.webSearchDeepSeekKey)
    }

    @Test
    fun `用户正在编辑的字段不被外部变更覆盖`() {
        val local = localOf(state(apiKey = "sk-old"))
        // 用户手输了新 Key，但还没失焦保存
        local.updateApiKey("sk-typing")

        // 期间发生导入（外部把 DataStore 改成别的值）
        local.syncFrom(
            state = state(apiKey = "sk-imported"),
            anySearchKey = "",
            deepSeekKey = "",
        )

        assertEquals("正在编辑的内容必须保留", "sk-typing", local.apiKey)
    }

    @Test
    fun `离开页面时只写回用户改过的字段`() {
        val viewModel = mock<SettingsViewModel>()
        val local = localOf(state(apiKey = "sk-old", model = "old-model"))

        // 只有 model 是用户改的；apiKey 是导入进来的新值，不该被本地旧副本覆盖
        local.updateModel("user-model")
        local.syncFrom(state = state(apiKey = "sk-imported"), anySearchKey = "", deepSeekKey = "")

        local.persist(viewModel)

        verify(viewModel).saveModel("user-model")
        verify(viewModel, never()).saveApiKey(org.mockito.kotlin.any())
    }

    @Test
    fun `未编辑任何字段时离开页面不写回任何内容`() {
        val viewModel = mock<SettingsViewModel>()
        val local = localOf(state(apiKey = "sk-old"))

        local.persist(viewModel)

        verify(viewModel, never()).saveApiKey(org.mockito.kotlin.any())
        verify(viewModel, never()).saveApiUrl(org.mockito.kotlin.any())
        verify(viewModel, never()).saveModel(org.mockito.kotlin.any())
        verify(viewModel, never()).saveSystemPrompt(org.mockito.kotlin.any())
        verify(viewModel, never()).saveContextWindow(org.mockito.kotlin.any())
    }

    @Test
    fun `上下文窗口被导入清空时界面同步为空串`() {
        val local = localOf(state(contextWindow = 65536))

        local.syncFrom(state = state(contextWindow = null), anySearchKey = "", deepSeekKey = "")

        assertEquals("", local.contextWindow)
    }

    @Test
    fun `清空输入框也算用户编辑，不会被外部值覆盖`() {
        val local = localOf(state(apiKey = "sk-old"))
        local.updateApiKey("")

        local.syncFrom(state = state(apiKey = "sk-imported"), anySearchKey = "", deepSeekKey = "")

        assertEquals("", local.apiKey)
    }
}
