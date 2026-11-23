import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读取友盟 AppKey 与独特性标识信息，与代码隔离。
// 必须按 UTF-8 读取：Properties.load(InputStream) 固定按 ISO-8859-1 解码，
// 文件中含中文（如证书路径）时会被解成乱码，导致 key 读不到（本地构建曾因此静默跳过 release 签名）。
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.reader(Charsets.UTF_8).use { load(it) }
    }
}
val umengAppKey: String = localProperties.getProperty("umeng.appKey", "") ?: ""
// 友盟分发渠道名（按分发来源命名）
val umengChannel: String = localProperties.getProperty("umeng.channel", "GitHub") ?: "GitHub"
// 友盟统计 SDK 门控：false 时 SDK 不打包进 APK（依赖退化为 compileOnly），
// 统计接入代码、首启隐私弹窗与关于页授权管理经 BuildConfig.UMENG_ENABLED 全部失效。
// 优先级：gradle -Pumeng.enabled > local.properties umeng.enabled > 默认 true。
// 命令行切换（CI/无统计构建）：./gradlew assembleDebug -Pumeng.enabled=false
val umengEnabled: Boolean =
    (project.findProperty("umeng.enabled") ?: localProperties.getProperty("umeng.enabled", "true")) == "true"
// 开发者标识 / 仓库地址 / 交流群链接 / 友盟隐私政策链接（开源分叉时按需替换）
val devName: String = localProperties.getProperty("app.devName", "") ?: ""
val gitRepoUrl: String = localProperties.getProperty("app.gitRepoUrl", "") ?: ""
val qqGroupUrl: String = localProperties.getProperty("app.qqGroupUrl", "") ?: ""
val umengPolicyUrl: String = localProperties.getProperty("app.umengPolicyUrl", "") ?: ""
// TTS 模型下载地址（开源分叉时按需替换；缺省为空表示未配置，设置里下载入口将提示）
val ttsModelBaseUrl: String = localProperties.getProperty("app.ttsModelBaseUrl", "") ?: ""

// 打包的原生库 ABI 过滤：gradle -PappAbi=v7a|v8a|both（缺省 both = v8a + v7a）。
// 供 tools/build-release.* 依次产出分 ABI 的 release APK；v7a/v8a 各自只带对应 so。
val appAbi: String = (project.findProperty("appAbi") as String?) ?: "both"

// ── 测试版共存开关 ──
// gradle -PtestApp=true 时产出「测试版」：applicationId 加 .test 后缀，可与正式版同时安装，
// 用来双版本对照（例如验证备份包在两个版本间能否互相导入）。
// 只改 applicationId：namespace（R 类包名）保持不变，源码无需任何改动。
// 注意两者数据互相独立（各自有私有目录），备份包才是它们之间传递数据的通道。
val testApp: Boolean = (project.findProperty("testApp") as String?)?.lowercase() == "true"
val applicationIdBase = "com.meapet.mobile"
val applicationIdEffective = if (testApp) "$applicationIdBase.test" else applicationIdBase

// 自动构建脚本（tools/build-release.*）用 `git rev-parse --short=9 HEAD` 取当前提交的
// 9 位短哈希，再通过 -PversionSuffix 传入；拼到 versionName 末尾后形如 1.9.0-1a2b3c4d5，
// 便于从安装包直接识别构建来源（git 短哈希冲突时 git 会自动加长，最多 40 位）。
// 手动构建（Android Studio / gradlew assembleDebug）没有该属性，versionName 保持原样。
// 只接受十六进制短哈希：属性写错时直接报错，避免把非法字符串拼进版本号。
val versionSuffix: String? = (project.findProperty("versionSuffix") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also { suffix ->
        require(suffix.matches(Regex("[0-9a-fA-F]{7,40}"))) {
            "versionSuffix 必须是 7–40 位十六进制提交短哈希（如 1a2b3c4d5），实际：$suffix"
        }
    }

// ── 构建时间 ──
// **只给 release 注入**，debug 固定 0（关于页据此隐藏该行）。
// 为什么不一刀切注入：时间戳每次配置阶段都变，会让 generate*BuildConfig → compile*Kotlin
// 每次都要重跑，增量构建缓存基本失效；而构建时间只有发布产物才需要（排障时反查是哪次
// 构建），开发调试包不需要这行信息。
// 这里只存时间戳本身（epoch 毫秒，Long 常量），不预先格式化、不固定时区；
// 关于页展示时用设备当前时区解析成可读文本，格式与语言跟随系统。
// 实现说明：
//  - 用 providers.provider 生成时间戳，configuration cache 可缓存该值，不引入不可序列化状态；
//  - 自动构建每次传入不同的 -PversionSuffix（不同提交哈希会改变配置缓存键），
//    因此发布构建总能拿到当次构建时间戳。
val buildTimeMillis: Long = providers.provider<Long> { System.currentTimeMillis() }.get()

// ── Release 签名 ──
// 证书信息优先读环境变量，其次读根目录 keystore.properties（gitignored，模板见 keystore.properties.example）。
// 四者齐备且 keystore 文件存在时才给 release 配置签名；否则保持未签名（无证书也能 assembleRelease）。
// 同 local.properties：必须按 UTF-8 读取，否则证书路径中的中文会乱码 → 误判为未配置 → 静默产出 unsigned。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.reader(Charsets.UTF_8).use { load(it) }
    }
}
fun signingValue(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProperties.getProperty(propKey)
val releaseStoreFile: File? = signingValue("KEYSTORE_STORE_FILE", "storeFile")?.let { path ->
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
}
val releaseStorePassword: String? = signingValue("KEYSTORE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? = signingValue("KEYSTORE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? = signingValue("KEYSTORE_KEY_PASSWORD", "keyPassword")
val canSignRelease: Boolean =
    releaseStoreFile != null && releaseStoreFile!!.isFile &&
        !releaseStorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

android {
    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStoreFile!!
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    namespace = "com.meapet.mobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = applicationIdEffective
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        // 自动构建追加 -<提交短哈希>；手动构建保持 "1.9.0"。
        // 注意：tools/build-release.* 与 tools/release_meta.py 用正则在 "1.9.0" 字面量上
        // 解析基础版本号，因此保留引号内的字面量，不要改成变量引用。
        // 测试版（-PtestApp=true）额外加 `.test` 后缀，便于在「关于」页区分两个共存版本。
        versionName = "1.9.0" + versionSuffix?.let { "-$it" }.orEmpty() +
            if (testApp) ".test" else ""

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 按 -PappAbi 过滤原生库（v7a / v8a / both），供分 ABI 构建；注释见文件顶 appAbi
            abiFilters += when (appAbi) {
                "v7a" -> listOf("armeabi-v7a")
                "v8a" -> listOf("arm64-v8a")
                else -> listOf("arm64-v8a", "armeabi-v7a")
            }
        }

        // 友盟 AppKey 通过 BuildConfig 注入，源码中不硬编码
        buildConfigField("String", "UMENG_APP_KEY", "\"$umengAppKey\"")
        // 友盟分发渠道名同样通过 BuildConfig 注入（分叉时可替换为自己的渠道）
        buildConfigField("String", "UMENG_CHANNEL", "\"$umengChannel\"")
        // 友盟统计 SDK 是否打包进本构建（false = 无统计 SDK 构建，接入代码与授权 UI 全部失效）
        buildConfigField("boolean", "UMENG_ENABLED", umengEnabled.toString())
        // 独特性标识信息同样通过 BuildConfig 注入，缺失时保持 _unset 占位（运行时回退默认）
        buildConfigField("String", "DEV_NAME", "\"$devName\"")
        buildConfigField("String", "GIT_REPO_URL", "\"$gitRepoUrl\"")
        buildConfigField("String", "QQ_GROUP_URL", "\"$qqGroupUrl\"")
        buildConfigField("String", "UMENG_POLICY_URL", "\"$umengPolicyUrl\"")
        buildConfigField("String", "TTS_MODEL_BASE_URL", "\"$ttsModelBaseUrl\"")
    }

    buildTypes {
        release {
            // 构建时间戳（epoch 毫秒，Long）：只有发布产物注入真实值，关于页按设备时区展示。
            // 放在 release 而不是 defaultConfig：debug 不注入时间戳，避免每次配置阶段生成
            // 新常量导致 generate*BuildConfig / compile*Kotlin 无法增量复用。
            buildConfigField("long", "BUILD_TIME", "${buildTimeMillis}L")
            // 开启 R8（代码收缩/混淆 + 资源收缩）；keep 规则见 proguard-rules.pro
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 证书齐备（keystore.properties / 环境变量）时签名，否则保持未签名
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // 固定 0（= 无构建时间）：字段必须存在以保持源码一致，但取值恒定，
            // 不会因时间戳变化触发重编译，关于页遇到 0 时隐藏该行。
            buildConfigField("long", "BUILD_TIME", "0L")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // sherpa-onnx AAR 内嵌 4 个 .so（jni / c-api / cxx-api / onnxruntime），由上方 abiFilters
    // 限制为仅 arm64-v8a / armeabi-v7a 两个 ABI；ONNX 模型仍走运行时下载 / 本地导入。
    //
    // 剔除 c-api / cxx-api 两个 .so：该 AAR 的 Kotlin API 只 System.loadLibrary("sherpa-onnx-jni")，
    // jni.so 已静态链接 C/C++ API（DT_NEEDED 仅 libonnxruntime/liblog/libm/libdl），
    // c-api 只被 cxx-api 依赖，二者在 App 里没有任何加载路径，v8a+v7a 合计省约 7.6MB。
    packaging {
        jniLibs {
            excludes += listOf(
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so",
            )
        }
    }
    // 触摸语音 assets/voice/**/*.ogg 必须不压缩存储：VoicePlayer 用 AssetManager.openFd
    // 直接拿 fd 喂 MediaPlayer，压缩过的 asset 不能 openFd（.ogg 本就在 aapt 默认不压缩
    // 列表里，这里显式声明防回归）。
    androidResources {
        noCompress += listOf("ogg")
    }
    testOptions {
        unitTests {
            // JVM 单测中 android.util.Log 等桩方法返回默认值而非抛异常
            isReturnDefaultValues = true
        }
    }
}

// Kotlin JVM target 显式对齐 Java 11（与上方 compileOptions 保持一致，
// 防止依赖某侧默认值漂移导致 bytecode 版本不一致）
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    // SQLite 存储（会话历史 + 记忆）：androidx.sqlite 驱动 API + 框架实现
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.framework)

    // Live2D Cubism Core
    implementation(files("libs/Live2DCubismCore.aar"))

    // Markwon：AI 回复 Markdown 渲染（代码块/公式/表格/删除线/链接）
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.table)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.inline.parser)

    // Ktor Client (for OpenAI client)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // zip4j：备份包 AES-256 加密 zip（导出/导入），Apache-2.0，见 NOTICE.md
    implementation(libs.zip4j)

    // Kotlinx Serialization (for OpenAI client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 友盟+ 统计 SDK (U-APP)
    // umeng.enabled=false 时退化为 compileOnly：不打包进 APK，仅保留编译期符号。
    // 运行时调用已被 BuildConfig.UMENG_ENABLED 短路（release 经 R8 常量折叠彻底移除），
    // debug 未走该分支也不会触发类解析，安全。
    if (umengEnabled) {
        implementation(libs.umeng.umsdk.common)  // 必选：统计核心
        implementation(libs.umeng.umsdk.asms)    // 必选：重要组件
    } else {
        compileOnly(libs.umeng.umsdk.common)
        compileOnly(libs.umeng.umsdk.asms)
    }

    // 本地 VITS TTS：sherpa-onnx AAR（内含 JNI + libonnxruntime.so，仅 v8a/v7a 会被 abiFilters 打包进 APK；
    // 模型仍走运行时下载，见 TtsModelManager）。
    implementation(libs.sherpa.onnx)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 测试所需
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
    // JVM 单测的真实 SQLite 引擎（仅测试期，通过自写 SQLiteDriver 适配器接入）
    testImplementation(libs.sqlite.jdbc)
}