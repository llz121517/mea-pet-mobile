package com.meapet.mobile.core

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * 统一异常捕获约定与工具。
 *
 * ## 捕获策略（全项目统一）
 *
 * 1. **协程中必须重抛 [CancellationException]**
 *    取消是协作式的，吞掉会让协程无法被取消（挂起点永不恢复、资源不释放）。
 *    所有协程/suspend 里的 `catch (e: Exception)` 之前必须前置
 *    `catch (e: CancellationException) { throw e }`。
 *
 * 2. **业务路径失败要记录日志并返回可恢复结果**
 *    至少用 `Log.w/e` 记录原因，并返回默认值 / `Result.failure` / `null`，
 *    不裸吞异常导致失败静默无痕。
 *
 * 3. **防御性场景允许静默，但需注释说明**
 *    UI/系统生命周期回调、WindowManager 窗口操作、GL 线程保护等「尽力而为」
 *    的调用，空 catch 可接受，但必须有一行注释说明为何可安全忽略。
 */

/**
 * 执行 [block] 并统一捕获异常：协程取消一律重抛，其余异常记录日志后返回 null。
 *
 * 适用于「失败可降级为 null」的业务操作；若失败需携带具体恢复逻辑，请直接手写
 * `try / catch (e: CancellationException) { throw e } / catch (e: Exception) { ... }`
 * 三件套（本工具已内建取消重抛）。
 *
 * @param tag 日志 TAG
 * @param block 待执行操作
 * @return 成功结果；[block] 抛出非取消异常时为 null
 */
inline fun <T> runCatchingLog(tag: String, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    e.logWarnSanitized(tag, "Operation failed")
    null
}
