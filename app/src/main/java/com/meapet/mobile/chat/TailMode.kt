package com.meapet.mobile.chat

/**
 * 每轮变化的附加上下文（tail，目前只有当前时间）在请求中的发送方式。
 *
 * tail 只承载动态内容，**不写入会话历史**：每轮重新生成，只作用于发出去的副本。
 * 因此上一轮含 tail 的那条 user 在下一轮不再含 tail，缓存分叉点就在该条 user，
 * 它之前的前缀保持稳定（该缓存影响为推断，需用 usage.prompt_cache_hit_tokens 实测验证）。
 *
 * - [MERGE_USER]：并入最后一条 user 消息（默认）。兼容性最好，不引入新角色、
 *   不破坏 user/assistant 交替，所有聊天模板通用。
 * - [SEPARATE_USER]：作为带标签的独立 user 消息（备用）。会产生连续两条 user，
 *   对 Gemma / Mistral 等严格交替模板会报错，仅在确认端点接受连续 user 时才可启用。
 *
 * 已弃用尾部 system：llama.cpp 模板拒绝返回 500，vLLM / SGLang 返回 400，
 * Gemma 2 / Mistral 旧模板抛异常，Anthropic 兼容层与 DeepSeek-V3 模板会把它提升到最前。
 *
 * 修改默认值会使所有用户的前缀缓存失效一次，应视为版本事件。
 */
enum class TailMode { MERGE_USER, SEPARATE_USER }

/** 默认方式：并入最后一条 user。 */
internal val DEFAULT_TAIL_MODE: TailMode = TailMode.MERGE_USER

/** 备用方式：独立 user 消息（仅确认端点接受连续 user 时可用）。 */
internal val FALLBACK_TAIL_MODE: TailMode = TailMode.SEPARATE_USER

/** tail 的固定标签前缀（固定字符串，保证缓存前缀稳定）。 */
internal const val TAIL_LABEL: String = "[系统提示]"

/**
 * 把有序片段列表拼成 tail 文本：标签 + 每个非空片段一行，按传入顺序。
 * 新增动态内容只需追加片段，无需改动调用方。全部为空时返回空串。
 */
internal fun composeTail(fragments: List<String>): String {
    val parts = fragments.map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ""
    return buildString {
        append(TAIL_LABEL)
        parts.forEach {
            append('\n')
            append(it)
        }
    }
}

/**
 * 把 [tail] 按 [mode] 应用到即将发送的消息列表（返回新列表，不改动入参与会话历史）。
 * [tail] 为空时原样返回。
 *
 * **只在最后一条消息是 user 时附加**：工具循环的消息序列里，assistant(tool_calls) 与
 * tool 结果必须紧邻。若在末尾不是 user 时硬插 tail，会把一条 user 消息夹进工具调用与
 * 结果之间，服务端直接 400。正常调用点（请求前才组装）最后一条一定是刚入史的 user；
 * 万一不是（如被窗口裁到 turn 中间），宁可不发 tail 也不能破坏序列。
 *
 * 注意这里**不**去历史里找更早的 user 拼接：tail 是「本轮动态内容」，
 * 贴到历史中间会永久改变那段前缀，破坏 prefix cache，也容易让模型误以为那是旧对话。
 */
internal fun applyTail(messages: List<ChatMessage>, tail: String, mode: TailMode): List<ChatMessage> {
    if (tail.isBlank()) return messages
    val last = messages.lastOrNull()
    if (last == null || last.role != ChatRole.user) {
        android.util.Log.w(
            "TailMode",
            "tail skipped: last message role=${last?.role}, not user (would break tool-call sequence)"
        )
        return messages
    }
    return when (mode) {
        TailMode.MERGE_USER -> messages.dropLast(1) + last.copy(content = "${last.content}\n\n$tail")
        TailMode.SEPARATE_USER -> messages + ChatMessage(role = ChatRole.user, content = tail)
    }
}
