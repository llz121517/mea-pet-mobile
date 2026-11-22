package com.meapet.mobile.live2d

import android.util.Log

/**
 * Live2D 原生核心可用性判定。
 *
 * ## 为什么需要
 * Live2D Cubism Core 的 AAR 只提供 `arm64-v8a` / `x86` / `x86_64` 三种 ABI，
 * **没有 `armeabi-v7a`**。App 的 `abiFilters` 仍保留 v7a（minSdk 26 的设备里仍有约三成
 * 只有 32 位），这些设备上 `System.loadLibrary("Live2DCubismCoreJNI")` 必然失败，
 * 若直接在 `Live2dDelegate.<init>` 里调用 `CubismFramework.startUp()` 会在启动瞬间
 * 抛 `UnsatisfiedLinkError` 闪退。
 *
 * 因此启动前先探测一次：失败就整体降级为「无 Live2D」模式（背景退回纯色/壁纸、
 * 无模型、无触摸语音、不提供悬浮窗），聊天等其余功能完全不受影响。
 *
 * ## 生命周期
 * [isAvailable] 只探测一次并缓存（`lazy`），探测本身不产生副作用；成功时后续
 * `CubismFramework.startUp()` 会复用系统已加载的库。
 *
 * 决策背景与后续移除计划见 `docs/adr/0003-live2d-32bit-fallback.md`。
 */
object Live2dSupport {

    private const val TAG = "Live2dSupport"

    /** Live2D Cubism Core 的 JNI 库名（随 `app/libs/Live2DCubismCore.aar` 打包）。 */
    internal const val CORE_LIBRARY = "Live2DCubismCoreJNI"

    /**
     * 尝试加载 Live2D 原生库。
     *
     * 抽成带注入参数的纯函数，便于在 JVM 单测里验证「加载失败 → 返回 false」这条分支
     * （真机之外没法让 `System.loadLibrary` 可控地失败）。
     *
     * @param load 实际加载动作；抛 [LinkageError] 视为不可用
     * @return 加载成功为 true
     */
    internal fun probe(load: () -> Unit): Boolean = try {
        load()
        true
    } catch (e: LinkageError) {
        // UnsatisfiedLinkError（本 ABI 无 .so）及其它链接错误：按不可用处理，不上抛
        false
    } catch (e: SecurityException) {
        // 少数 ROM 会以安全异常拒绝 dlopen；同样只降级，不让启动失败
        false
    }

    /**
     * 本设备/本构建是否可用 Live2D。
     *
     * false 时所有 Live2D 入口都应跳过：不启动 Cubism SDK、不创建模型视图、
     * 不提供悬浮窗，但背景壁纸与聊天功能保持可用。
     */
    val isAvailable: Boolean by lazy {
        val loaded = probe { System.loadLibrary(CORE_LIBRARY) }
        if (loaded) {
            Log.d(TAG, "Live2D native core available")
        } else {
            Log.w(
                TAG,
                "Live2D native core NOT available for this ABI ($CORE_LIBRARY); " +
                    "degrading to wallpaper-only background"
            )
        }
        loaded
    }

    /** 是否因缺少原生库而处于降级模式（供 UI 提示与悬浮窗入口判断）。 */
    val isDegraded: Boolean get() = !isAvailable
}
