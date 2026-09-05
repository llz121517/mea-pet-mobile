package com.meapet.mobile.client.exception

/**
 * OpenAI 兼容 API 的统一异常封装。
 *
 * @property statusCode HTTP 状态码
 * @property responseBody 服务端返回的原始响应体（JSON 字符串）
 */
class ApiException(
    val statusCode: Int,
    val responseBody: String,
    message: String
) : RuntimeException(message) {

    companion object {
        /** 拼进用户可见文案的网关错误说明最大长度（超出截断，避免错误卡片被长堆栈撑爆）。 */
        private const val MAX_DETAIL_LENGTH = 200

        /** 常见状态码的面向用户友好提示。 */
        fun friendlyMessage(statusCode: Int): String = when (statusCode) {
            401 -> "API Key 无效或未填写，请在设置中填写正确的 API Key"
            402 -> "API 余额不足，请充值后再试"
            403 -> "API Key 无权限访问，请检查密钥权限"
            404 -> "接口地址不存在，请检查 API 地址是否填写正确"
            429 -> "请求过于频繁，请稍后再试"
            else -> "API 请求失败（HTTP $statusCode）"
        }

        /**
         * 状态码友好提示 + 网关给出的具体原因。
         *
         * 只给状态码文案时，用户看到的永远是「请求过于频繁」这类笼统说法，
         * 无从判断是哪个模型、哪条限额触发的；把网关的 `error.message`
         * 拼进来才有排查价值（原始响应体仍只进日志，不进 UI）。
         *
         * @param detail 网关错误说明，通常来自 [com.meapet.mobile.client.model.ApiResponse.errorMessage]
         */
        fun friendlyMessage(statusCode: Int, detail: String?): String {
            val base = friendlyMessage(statusCode)
            val trimmed = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return base
            val shown = if (trimmed.length > MAX_DETAIL_LENGTH) {
                trimmed.take(MAX_DETAIL_LENGTH) + "…"
            } else {
                trimmed
            }
            return "$base\n（服务端返回：$shown）"
        }
    }
}
