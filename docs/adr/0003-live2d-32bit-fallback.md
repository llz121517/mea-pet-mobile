# ADR 0003：32 位 ARM 无 Live2D 原生核心，降级为无模型背景

- 状态：已采纳（2026-11-22，1.9.0 dev）
- 相关代码：`Live2dSupport`（能力探测）、`Live2dDelegate`（`cubismReady` 门控）、`FloatingLive2dService`（拒绝启动）、`OverlayMenu`（隐藏入口）、`app/build.gradle.kts`（`abiFilters`）

## 背景

应用最低支持 Android 8（API 26）。在 Android 8 时代的存量设备中，约七成是 64 位、约三成只有 32 位 ARM（`armeabi-v7a`）。因此在 `abiFilters` 里保留 v7a 有现实意义，短期内不能直接砍掉。

但 Live2D Cubism Core 的官方 Android AAR **只提供 `arm64-v8a` / `x86` / `x86_64` 三种 ABI，没有 `armeabi-v7a`**：

```
app/libs/Live2DCubismCore.aar
  jni/arm64-v8a/libLive2DCubismCoreJNI.so
  jni/x86/libLive2DCubismCoreJNI.so
  jni/x86_64/libLive2DCubismCoreJNI.so        ← 无 armeabi-v7a
```

1.9.0 真机验证确认了后果：`-PappAbi=v7a` 的包在 64 位设备上被系统选中 32 位 ABI（`primaryCpuAbi=armeabi-v7a`）后，启动即在 `Live2dDelegate.<init>` → `CubismFramework.startUp()` → `Live2DCubismCoreJNI.<clinit>` 抛 `UnsatisfiedLinkError: dlopen failed: library "libLive2DCubismCoreJNI.so" not found`，直接闪退。

也就是说：**保留 v7a 与使用 Live2D 模型是互斥的**，必须二选一或做降级。

## 决策

**保留 v7a 支持，对缺失原生核心的设备做功能降级。**

- 新增 `Live2dSupport.isAvailable`：启动时用 `System.loadLibrary("Live2DCubismCoreJNI")` 探测一次，捕获 `LinkageError` / `SecurityException` 判定为不可用，结果缓存（`lazy`）。
- `Live2dDelegate` 以 `cubismReady` 门控全部 Cubism 调用：不可用时**不** `startUp` / `initialize` / 加载模型 / 建 shader，只保留背景清屏色与壁纸层（壁纸模糊是普通 GLES20，不依赖 Cubism）。
- 触摸跟随与分区语音在降级模式下整体跳过（没有模型，就没有可点的区域）。
- 悬浮窗的唯一内容是模型，降级时 `FloatingLive2dService` 直接 `stopSelf()` 拒绝启动，主界面菜单也隐藏「悬浮窗」入口，避免用户点了没反应或留下透明僵尸窗。
- 模型资源预热同样跳过，不为不存在的模型占内存。

降级的表现是：**纯聊天 + 背景色/壁纸，无桌宠模型、无触摸语音、无悬浮窗**，其余功能完全不受影响。

## 后果

- v7a 包从「装上必崩」变成「可用但无模型」，32 位存量设备至少能正常聊天。
- 代价是两条代码路径（有/无 Cubism），由 `cubismReady` 单点门控，并通过 `Live2dSupportTest` 覆盖加载失败分支（注入加载动作，模拟真实 `UnsatisfiedLinkError`）。
- 应用在降级模式下体验明显残缺，属预期内的过渡状态，不适合作为长期形态。

## 后续计划

**后续版本考虑改为 `arm64-v8a` only，彻底移除 v7a。**

判断依据：

1. 「Android 8 且仅 32 位」的机型已经很少，且会随存量设备自然淘汰持续下降；
2. Live2D 是应用的核心体验，为了极少数设备保留一条功能残缺的路径，维护成本（双路径门控、两种体验的测试矩阵）不划算；
3. 届时 `abiFilters` 去掉 `armeabi-v7a`、`tools/build-release.*` 去掉 v7a 选项，本 ADR 与 `Live2dSupport` 可一并删除——**能力探测保留与否取决于是否还有其他「部分 ABI 缺原生库」的组件**（当前 `sherpa-onnx` 也只带 arm 系列，但 v7a 是支持的）。

在切换之前，本降级路径是必要的兼容措施，不是临时 hack。
