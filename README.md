# MeaPet —— 梅尔桌宠

Android 上的 Live2D AI 桌宠：在主页与系统悬浮窗里渲染 Live2D 模型，接入 OpenAI 兼容 API 做多轮聊天，并带本地记忆与 Material You 主题。

本项目由 [suan-11/mea-pet-public](https://github.com/suan-11/mea-pet-public) 衍生。

- 本项目由 LLM 驱动的 Agent 辅助开发

---

## 功能

- **Live2D 模型** — Cubism SDK 渲染，支持触摸交互、视角跟随与分区语音反馈
- **AI 聊天** — OpenAI 兼容 API（可自建中转），多轮对话、System Prompt、记忆上下文注入；助手回复支持 Markdown / LaTeX 渲染，气泡文字可长按选择复制；聊天记录本地持久化，重启不丢
- **本地记忆** — 由大模型在对话中自主判断该记什么（事实 / 特质 / 短期），并按设定轮次自动摘要为长期记忆；事实与特质永久保留，可在首页菜单「查看记忆」中查看与删除
- **本地语音合成（TTS）** — 梅尔音色端侧离线朗读，完全不走云端；模型按需下载或从本地 zip 资源包导入，主界面 / 悬浮窗可独立开关，支持语速调节与喇叭快捷静音
- **背景壁纸** — 主界面聊天背景支持相册选图与模糊调节（仅主界面生效，悬浮窗保持透明）
- **聊天气泡透明度** — 主页气泡透明度可调（20%–100%）
- **多主题配色** — Material You 动态取色 + 多套预设色板，支持浅色 / 深色 / 跟随系统；浅色配色已通过 WCAG 对比度校验
- **悬浮窗模式** — 前台 Service 浮窗常驻，拖拽 / 捏合缩放；双击唤起悬浮菜单（关闭悬浮窗 / 唤起输入 / 锁定 / 透明度），快速三击直接关闭；悬浮窗内可直接输入聊天，AI 回复以带尾巴的气泡显示在人物旁，配色跟随主题
- **检测更新** — 启动静默检查 GitHub Releases；关于页可手动检测
- **隐私合规** — 首次启动授权弹窗；可查看隐私政策、导出日志、取消数据采集授权

## 开始使用

### 前置要求

| 项目 | 要求 |
|------|------|
| **Android 版本** | 8.0（API 26）或更高 |
| **API 端点** | 一个 OpenAI 兼容的 API 端点（可自部署或使用第三方服务） |
| **Live2D Cubism Core** | 需自行下载（见下方说明） |
| **构建工具**（手动编译需要） | JDK 21+、Android SDK 36+、Gradle（可使用 wrapper） |

### 获取方式

#### 方式一：下载发行版（推荐）

从 [Releases](https://github.com/llz121517/mea-pet-mobile/releases) 页面下载最新的 APK 直接安装，无需自行编译。

#### 方式二：手动编译

**1. 克隆仓库**

```bash
git clone https://github.com/llz121517/mea-pet-mobile.git
cd mea-pet-mobile
```

**2. 下载 Live2D Cubism Core**

MeaPet 依赖 Live2D Cubism Core 原生库，受 Live2D 专有软件许可协议保护，**不随仓库分发**。你需要手动下载并放入项目：

1. 前往 [Live2D 官方下载页](https://www.live2d.com/download/cubism-sdk/download-java/) 下载 **Cubism 5 Java SDK**
2. 解压后找到 `Core/android/Live2DCubismCore.aar`
3. 将 `Live2DCubismCore.aar` 复制到本项目的 `app/libs/` 目录

```
MeaPet/
└── app/
    └── libs/
        └── Live2DCubismCore.aar   ← 手动放入
```

**3. 编译 APK**

```bash
./gradlew assembleDebug
```

**4. 安装**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 配置

应用安装后，在设置页按分组配置。设置页采用二级导航：根页为「提供商 / 对话 / 外观 / 语音 / 关于」五个功能域入口（每项显示当前状态摘要，如模型名、主题、发声开关、版本号），点入对应子页进行详细设置；隐私政策全文在「关于」再往里一层。

#### 提供商

**API 配置**：

| 字段 | 说明 |
|------|------|
| **API Key** | API 密钥。**可留空**——本地模型（Ollama / LM Studio 等）无需鉴权；云端服务缺 Key 会在请求时提示填写 |
| **API 地址** | OpenAI 兼容的 API 基础 URL（含版本路径，如 `/v1`、`/v4`；地址原样使用，不自动附加版本号） |

**模型参数**：

| 选项 | 说明 |
|------|------|
| **模型** | 使用的模型名称（如 `gpt-4o-mini`）；也可点「获取模型列表」从端点拉取后点选 |
| **Temperature** | 生成温度 (0.0–2.0) |
| **最大 Token** | 单次响应最大 Token 数 |

#### 对话

**System Prompt**：编辑人设提示词；「恢复默认」一键还原内置人设（二次确认）。

**记忆系统**：

| 选项 | 说明 |
|------|------|
| **启用记忆** | 总开关。关闭后不再记录、不注入记忆上下文，对话也不会外发给摘要模型 |
| **自动摘要** | 是否定期把短期记忆压缩为长期记忆 |
| **摘要轮次** | 每隔多少轮对话触发一次摘要（3–30，默认 10） |

> 记忆由大模型在回复时自行判断并创建，会额外占用少量输出 Token。使用指令遵循能力较弱的小模型时，可能出现偶尔不创建记忆的情况——这不影响正常聊天。

#### 外观

**背景壁纸**：从相册选一张图片作为主界面聊天背景（实时生效；仅主界面生效，悬浮窗保持透明）；「恢复默认」回到主题纯色。同一小节提供 **背景模糊** 滑杆（0–100%），GL 内实时模糊，缩略图预览同步。
**聊天气泡透明度**：滑杆调节用户 / 助手 / 系统气泡透明度（20%–100%，默认不透明）。
**主题**：

| 选项 | 说明 |
|------|------|
| **主题模式** | 跟随系统 / 浅色 / 深色 |
| **使用系统动态颜色** | Android 12+ 可用 Material You 动态取色；关闭后可选预设主题色 |
| **颜色预设** | 多套预设主题色板 |

#### 语音（TTS）

本地 VITS 语音合成，回复以梅尔音色端侧朗读（离线、不走云端）。模型不打包进 APK，首次使用需先就绪语音模型。

**语音模型**（状态卡）：

| 操作 | 说明 |
|------|------|
| **下载模型** | 从 GitHub Releases 拉取约 73MB（4 个 ONNX 模型；ONNX Runtime 原生库随 APK 打包，无需下载），支持断点续传 |
| **从本地导入** | 网络受限无法访问 GitHub 时，从本地 zip 资源包导入（详见下文「资源包格式」）。导入完成后自动切换为就绪状态 |
| **删除模型** | 已就绪时显示，删除模型释放空间（会同时关闭语音开关） |

**发声**（模型就绪后开放，未就绪置灰）：

| 选项 | 说明 |
|------|------|
| **主界面语音** | 对话回复在主界面朗读 |
| **悬浮窗语音** | 悬浮窗回复朗读 |
| **语速** | 滑杆调节朗读速度 0.5x–2.0x（半速 / 原速 / 双倍速） |

**资源包获取**：从 [Releases · tts-resource-pack](https://github.com/llz121517/mea-pet-mobile/releases/tag/tts-resource-pack) 下载 `TTS-Resource-Pack.zip` 到手机后选择导入。

资源包 zip 约定格式（平铺、无目录）：

```
enc_p.onnx  dp.onnx  flow.onnx  dec.onnx
```

导入规则：**4 个 ONNX 全量导入**（ONNX Runtime 原生库随 APK 分发，资源包无需携带；旧资源包里多余的 so 会被跳过）。兼容打包时误嵌套子文件夹的情况（自动在子目录中查找，最多 2 层）。

#### 关于

「关于」子页集中了应用信息、更新与隐私入口：

- **应用信息** — 版本号、技术栈等。
- **更新** — 启动时静默检测新版本，发现更新才提示（可在子页关闭）；此处也可手动「检查更新」（按钮 + 进度 + 结果文案与发布页链接）。
- **隐私与数据** — 查看隐私政策全文（再往里一层）；导出日志（含设备 / 版本头信息、native 崩溃 tombstone、logcat），便于反馈问题；友盟统计 SDK 授权管理（可查看授权状态，取消授权后立即退出，确保停止上报）。

## 技术栈

```
Live2D Cubism  ·  Jetpack Compose  ·  Ktor  ·  Markwon  ·  Coroutines  ·  GLSurfaceView
```

## Live2D 模型来源

应用内展示的梅尔 Live2D 模型资源来自社区作品，原始出处：

- [Bilibili — [Live2D模型分享 - 梅娅]](https://www.bilibili.com/video/BV1AoX7BXEaN)

使用该模型时请遵循原作者的发布说明与授权要求。模型版权归原作者所有，与本仓库 MIT 许可证无关。

## 贡献

感谢所有为 MeaPet 做出贡献的开发者，名单见 [CONTRIBUTORS.md](CONTRIBUTORS.md)。欢迎通过 [Issues](https://github.com/llz121517/mea-pet-mobile/issues) 或 [Pull Request](https://github.com/llz121517/mea-pet-mobile/pulls) 参与贡献。

## 许可证

本项目基于 [MIT](LICENSE) 许可证开源。

本项目包含 Live2D 等第三方组件，其许可证条款详见 [NOTICE.md](NOTICE.md)。使用 Live2D Cubism Core 需要单独下载并接受 Live2D 专有软件许可协议。应用内 Live2D 角色模型来源见上文「Live2D 模型来源」。
