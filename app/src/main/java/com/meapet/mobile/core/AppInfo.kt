package com.meapet.mobile.core

import android.content.Context
import com.meapet.mobile.BuildConfig

/**
 * 应用独特性标识信息的统一访问入口。
 *
 * 开发者名、仓库地址、交流群链接、友盟隐私政策链接等均通过
 * [BuildConfig] 在构建时注入（来源为 local.properties），
 * 源码中不硬编码，便于开源分叉时替换为自己的信息。
 *
 * 注入缺失时回退到 MeaPet 原作者的默认值，保证功能可用。
 */
object AppInfo {

    private const val DEFAULT_VERSION = "1.0.0"

    /**
     * 自动构建追加在 versionName 末尾的提交短哈希后缀（`-1a2b3c4d5`）。
     *
     * 只匹配 7–40 位十六进制串（git 短哈希长度区间），避免把 `1.9.0-beta1`
     * 这类预发布标识误当构建号剥离。`+` 前缀兼容 SemVer build metadata 写法。
     */
    private val BUILD_HASH_SUFFIX = Regex("[-+][0-9a-fA-F]{7,40}$")

    /**
     * 测试版（`-PtestApp=true`）追加在 versionName 末尾的 `.test` 后缀。
     *
     * 测试版与正式版 applicationId 不同、可共存，但版本比较必须把两者视作同一版本，
     * 否则测试版会永远提示「有新版本」（`1.9.0.test` vs 远端 `1.9.0`）。
     */
    private val TEST_SUFFIX = Regex("\\.test$")

    /** 开发者名称。 */
    val devName: String = BuildConfig.DEV_NAME.ifBlank { "llz121517" }

    /** GitHub 仓库主页地址。 */
    val gitRepoUrl: String = BuildConfig.GIT_REPO_URL.ifBlank {
        "https://github.com/llz121517/mea-pet-mobile"
    }

    /** 交流 QQ 群地址。 */
    val qqGroupUrl: String = BuildConfig.QQ_GROUP_URL.ifBlank {
        "https://qm.qq.com/q/pD9vpN6zKg"
    }

    /** 友盟+ 隐私权政策链接。 */
    val umengPolicyUrl: String = BuildConfig.UMENG_POLICY_URL.ifBlank {
        "https://www.umeng.com/page/policy"
    }

    /**
     * 从 PackageManager 读取当前 versionName（失败回退默认）。
     *
     * 自动构建的返回值会带提交短哈希后缀（如 `1.9.0-1a2b3c4d5`）。这里保留完整版本号
     * 供「关于」页与日志展示；需要比较版本时先用 [baseVersion] 剥离后缀。
     */
    fun readVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: DEFAULT_VERSION
    } catch (_: Exception) {
        DEFAULT_VERSION
    }

    /**
     * 去掉自动构建追加的提交短哈希后缀，返回可比较的基础版本号。
     *
     * 例：`1.9.0-1a2b3c4d5` → `1.9.0`。更新检查比较远端 tag 与本机 versionName 前必须
     * 先剥离后缀，否则以数字开头的短哈希会被当成额外的版本段（旧实现会把
     * `1.9.0-1a2b3c4d5` 解析为 `1.9.0.1`，误判需要更新）。
     */
    fun baseVersion(versionName: String): String =
        versionName.trim().replace(BUILD_HASH_SUFFIX, "").replace(TEST_SUFFIX, "")
}
