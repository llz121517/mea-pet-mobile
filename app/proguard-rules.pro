# ─────────────────────────────────────────────────────────
#  MeaPet Release 混淆规则（R8）
#  保留反射 / 序列化 / native 依赖，防止 Release 构建运行时崩溃
# ─────────────────────────────────────────────────────────

# ── Live2D Cubism SDK ──────────────────────────────────
# 模型 JSON / moc 解析、native 方法加载依赖反射与类名，禁止混淆移除
-keep class com.live2d.sdk.cubism.** { *; }
-dontwarn com.live2d.sdk.cubism.**

# ── sherpa-onnx（JNI 按注册名回调 Java/Kotlin 类，AAR 自带 proguard.txt 为空） ──
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ── ONNX Runtime（T1–T9 过渡期规则） ──
# 旧 VitsOnnxEngine 仍按 compileOnly(libs.onnxruntime.android) 编译，并被下方
# 「com.meapet.mobile.tts.**」规则全量保留；而 runtime 并无 ai.onnxruntime 类
# （sherpa AAR 只内嵌 libonnxruntime.so，不含 Java API）。此处的唯一作用是让 R8
# 不因这些缺失引用产生 missing-class 警告（严格模式下可能直接中断 shrink）。
# TODO(T9): 删除旧引擎时，与 compileOnly(libs.onnxruntime.android) 一起删除本行。
-dontwarn ai.onnxruntime.**

# ── TTS 语音包 ─────────────────────────────────────────
# 自有 TTS 代码宽保留：G2P / ONNX 引擎 / 播放器 / 模型管理，与 native 层和文件路径交互多，
# 保留全量避免混淆引入隐性问题
-keep class com.meapet.mobile.tts.** { *; }
-dontwarn com.meapet.mobile.tts.**

# native 方法一律保留（ONNX / Live2D 等含 native 声明的类）
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ── 友盟+ 统计 SDK ────────────────────────────────────
# AAR 自带混淆规则，兜底保留，避免上报数据异常。
# umeng.enabled=false 的无 SDK 构建里这些类不存在，规则自然不命中，无需移除。
-keep class com.umeng.** { *; }
-keep class org.repackage.** { *; }
-dontwarn com.umeng.**
-dontwarn org.repackage.**

# ── kotlinx.serialization ──────────────────────────────
# @Serializable 数据类的字段名即序列化名（JSON key），禁止改名；
# 生成的 serializer 伴生类需保留
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
-keep class *$serializer { *; }

# ── 通用反射 / 调试信息 ────────────────────────────────
# 保留行号便于崩溃栈还原；保留运行时注解（友盟/序列化依赖）
-keepattributes SourceFile, LineNumberTable
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
