package com.meapet.mobile.core

import android.util.Log
import kotlinx.serialization.SerializationException

/**
 * 日志脱敏工具。
 *
 * 背景：`kotlinx.serialization` 的解码异常（[SerializationException] 及其子类
 * `JsonDecodingException`）会把**原始 JSON 片段**拼进 `message`，格式形如：
 *
 * ```
 * Unexpected JSON token at offset 43: Expected quotation mark ...
 * JSON input: {"choices":[{"message":{"content":"<用户对话正文>"}}]}
 * ```
 *
 * 因此凡是对「对话正文 / 记忆内容 / API 响应体」做解码、并在 catch 里打印
 * `e.message` 或直接用 `Log.e(tag, msg, e)` 打异常对象的位置，都会把正文写进
 * logcat；而「导出日志」会抓全量 logcat 随用户分享外发 —— 这与隐私政策
 * 「运行日志不含对话正文」的承诺冲突。
 *
 * 这里的做法：截掉 `JSON input:` 之后的内容（保留 offset / path 等排障信息），
 * 并对超长消息限长。
 */

/** 日志里 `JSON input:` 标记，其后即原始输入内容。 */
private const val JSON_INPUT_MARKER = "JSON input:"

/** 脱敏后的消息默认最大长度。 */
private const val DEFAULT_MAX_LEN = 200

/**
 * 取异常消息的**安全子串**用于日志。
 *
 * - 去掉 `JSON input: ...` 之后的部分（那才是可能含正文的原始输入）；
 * - 截断到 [maxLen]；
 * - `message` 为空时退回异常类名。
 */
fun Throwable.safeLogMessage(maxLen: Int = DEFAULT_MAX_LEN): String {
    val raw = message?.trim().orEmpty()
    if (raw.isEmpty()) return this::class.java.simpleName
    val cut = raw.indexOf(JSON_INPUT_MARKER)
    val head = if (cut >= 0) raw.substring(0, cut).trimEnd() else raw
    return if (head.length > maxLen) head.take(maxLen) + "…" else head
}

/** 该异常的消息里是否含被拼进去的原始 JSON 输入。 */
private fun Throwable.carriesRawInput(): Boolean =
    this is SerializationException || message?.contains(JSON_INPUT_MARKER) == true

/**
 * 打警告日志，但对「会夹带原始 JSON 输入」的异常做脱敏：
 * 这种情况下不传异常对象（否则堆栈里仍带完整 message），只打类名 + 安全消息，
 * 足够定位（含 offset / path）。其余异常照常带堆栈，便于排查真实 bug。
 */
fun Throwable.logWarnSanitized(tag: String, prefix: String) {
    if (carriesRawInput()) {
        Log.w(tag, "$prefix: ${this::class.java.simpleName}: ${safeLogMessage()}")
    } else {
        Log.w(tag, prefix, this)
    }
}

/** [logWarnSanitized] 的错误级别版本。 */
fun Throwable.logErrorSanitized(tag: String, prefix: String) {
    if (carriesRawInput()) {
        Log.e(tag, "$prefix: ${this::class.java.simpleName}: ${safeLogMessage()}")
    } else {
        Log.e(tag, prefix, this)
    }
}
