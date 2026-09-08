import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 从 local.properties 读取友盟 AppKey 与独特性标识信息，与代码隔离
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
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

// ── Release 签名 ──
// 证书信息优先读环境变量，其次读根目录 keystore.properties（gitignored，模板见 keystore.properties.example）。
// 四者齐备且 keystore 文件存在时才给 release 配置签名；否则保持未签名（无证书也能 assembleRelease）。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
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
        applicationId = "com.meapet.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode =  13
        versionName = "1.7.2"

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // ONNX Runtime 原生库（libonnxruntime.so）随 AAR 打包进 APK，由上方 abiFilters
    // 限制为仅 arm64-v8a / armeabi-v7a 两份；ONNX 模型仍走运行时下载 / 本地导入。
    // OpenJTalk 词典/拼音表等大文本 assets 需禁压缩（否则 aapt 压缩后某些读取路径会失败）
    aaptOptions {
        noCompress += listOf("bin", "dic", "txt")
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

    // 本地 VITS TTS：ONNX Runtime（原生 so 随 AAR 打包进 APK，仅 v8a/v7a；模型仍走运行时下载，见 TtsModelManager）
    implementation(libs.onnxruntime.android)

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
}