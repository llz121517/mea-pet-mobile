# 贡献者

感谢每一位为 MeaPet 付出时间与精力的人，以下名单按加入贡献的时间排序。


|                                                         头像                                                          | GitHub                                     | 贡献                                                                                                       |
|:-------------------------------------------------------------------------------------------------------------------:|--------------------------------------------|----------------------------------------------------------------------------------------------------------|
| <img src="https://github.com/llz121517.png?s=48" style="width:48px;height:48px;border-radius:50%;" alt="llz121517"> | [@llz121517](https://github.com/llz121517) | 项目作者，整体架构、功能开发与维护 |
| <img src="https://github.com/starmiaoa.png?s=48" style="width:48px;height:48px;border-radius:50%;" alt="starmiaoa"> | [@starmiaoa](https://github.com/starmiaoa) | 记忆 / 聊天 / 悬浮窗 / 设置链路关键缺陷修复，获取模型列表，`/v1` API 路径自动拼接（PR #5 等）                                              |
| <img src="https://github.com/FlexiAtom.png?s=48" style="width:48px;height:48px;border-radius:50%;" alt="FlexiAtom"> | [@FlexiAtom](https://github.com/FlexiAtom) | System Prompt 恢复默认（PR #7）                                                                                |
| <img src="https://github.com/furina315.png?s=48" style="width:48px;height:48px;border-radius:50%;" alt="furina315"> | [@furina315](https://github.com/furina315) | 助手消息 Markdown / LaTeX 渲染 + 气泡文字选择复制（PR #11）；设置界面二级导航重构、ErrorBubble 错误卡片、浅色配色 WCAG 对比度修复、21 个矢量图标（PR #12）；Temperature 参数说明气泡（PR #16）；48 项代码审查的问题复核与修复（PR #18、#20）：系统气泡寿命反向延长、失败消息回滚与重试重复、网关错误详情可见性、宽表格分段横向滚动等；代码审查进度与待办文档（PR #21） |

---

## 参考来源

部分实现参考了社区公开的成熟做法，出处注明如下。

| 来源 | 参考内容 |
|------|----------|
| [@atifmahmood29](https://github.com/atifmahmood29) — [`FloatingWindow.java`](https://github.com/atifmahmood29/overlays-floating-window-like-facebook-messenger/blob/master/app/src/main/java/innovativepocket/com/systemalertwindow/FloatingWindow.java)（[完整仓库](https://github.com/atifmahmood29/overlays-floating-window-like-facebook-messenger)） | 悬浮输入条「按需聚焦」的窗口 flag 组合：带 `FLAG_WATCH_OUTSIDE_TOUCH` 以收取窗外触摸的 `ACTION_OUTSIDE`，再按需切换 `FLAG_NOT_FOCUSABLE` 并 `updateViewLayout` —— 窗口既能弹软键盘，又不长期占着焦点挡住下层应用。本项目只借鉴该思路，代码为自行实现（`app/src/main/java/com/meapet/mobile/live2d/overlay/OverlayInputFocus.kt`） |

---

## 如何贡献

欢迎通过 [Issues](https://github.com/llz121517/mea-pet-mobile/issues) 反馈问题、提出建议，或直接提交 [Pull Request](https://github.com/llz121517/mea-pet-mobile/pulls)。被合并的贡献会出现在上面的名单中。
