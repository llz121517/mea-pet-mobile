package com.meapet.mobile.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * 隐私授权管理器。
 *
 * 管理用户是否已同意《隐私政策》中关于友盟统计 SDK 数据采集的授权。
 * - 首次启动：未同意，App 正常使用但不初始化友盟 SDK。
 * - 同意后：友盟 SDK 正式初始化，开始采集并上报数据。
 * - 取消授权后：停止上报（后续冷启动不再 init），App 其余功能不受影响。
 *
 * 是否需要弹窗由隐私政策版本号（DataStore `privacy_version_shown`）决定，
 * 见 SettingsManager / MainActivity；此处只负责授权状态本身的读写。
 *
 * **唯一存储源是 SharedPreferences**（`privacy_consent` 文件）：[isAgreed] 可在
 * `Application.onCreate` 中同步判断，[agreedFlow] 也由同一份数据派生，不再另写
 * DataStore——双存储存在两份状态不一致的隐患（旧实现曾在主线程用 runBlocking 写
 * DataStore，超时/失败后两份数据就分叉了）。
 */
object PrivacyConsentManager {

    private const val TAG = "PrivacyConsentManager"
    private const val PREFS_NAME = "privacy_consent"
    private const val KEY_AGREED = "umeng_privacy_agreed"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户是否已同意隐私授权（同步读取，可在主线程调用）。 */
    fun isAgreed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGREED, false)

    /**
     * 授权状态 Flow（响应式订阅）。
     *
     * 由 [isAgreed] 的同一份 SharedPreferences 派生：注册监听后先发当前值，
     * 之后每次 `umeng_privacy_agreed` 变化再发一次；订阅取消时注销监听。
     */
    fun agreedFlow(context: Context): Flow<Boolean> = booleanPreferenceFlow(
        read = { isAgreed(context) },
        register = { onChange ->
            val prefs = prefs(context)
            // SharedPreferences 以弱引用持有监听器：listener 由本闭包/awaitClose 强引用，
            // 保证订阅期间不被 GC 回收。
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_AGREED) onChange()
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    )

    /**
     * 标记用户已做出选择并记录授权状态。
     *
     * 写盘在 [Dispatchers.IO] 上同步 `commit()`：取消授权后调用方会立即 killProcess，
     * `apply()` 的异步落盘可能来不及，导致重启后仍读到旧的授权状态；因此本函数要等
     * 写盘完成才返回，调用方必须在本函数返回后再退出进程（见 AboutSettings）。
     */
    @Suppress("ApplySharedPref")
    suspend fun setAgreed(context: Context, agreed: Boolean) {
        val committed = withContext(Dispatchers.IO) {
            prefs(context).edit()
                .putBoolean(KEY_AGREED, agreed)
                .commit()
        }
        if (!committed) {
            Log.w(TAG, "授权状态写盘失败（agreed=$agreed），重启后可能仍为旧值")
        }
    }
}

/**
 * 把「读取当前值 + 注册变化回调」桥接成 Flow 的纯逻辑（不依赖 Android，可 JVM 单测）。
 *
 * 先注册监听再读初值：若两步之间发生变化，读到的已是最新值，随后 [distinctUntilChanged]
 * 会丢弃重复项，不会漏事件。订阅取消时调用 [AutoCloseable.close] 注销监听。
 */
internal fun booleanPreferenceFlow(
    read: () -> Boolean,
    register: (onChange: () -> Unit) -> AutoCloseable
): Flow<Boolean> = callbackFlow {
    val registration = register { trySend(read()) }
    trySend(read())
    awaitClose { registration.close() }
}.distinctUntilChanged()
