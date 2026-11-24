# MeaPet 二创声明与资源分发条款

> 本文件适用于 `tts-resource-pack` release 下分发的语音资源包（`tts-model-v1.zip`、`TTS-Resource-Pack.zip` 等），
> 以及随 APK 分发的触摸语音素材。**不适用于**本仓库的源代码（源代码为 MIT）。

## 一、性质

MeaPet（梅尔桌宠）是《霞流宝石心 -壮志凌云振寰宇-》（ジュエリー・ハーツ・アカデミア -We will wing wonder world-）
的**非官方同人二创作品**，仅供学习与个人欣赏，非商业用途。

- 游戏原作与角色版权：**Cabbage Soft**（开发 / 发行）
- 本项目的代码：MIT（见仓库 `LICENSE`）
- **本项目不含、也不分发原作的任何游戏本体资源**

## 二、语音资源

### 触摸语音素材（随 APK 分发的 21 个 Opus 文件）

- 用 **GPT-SoVITS** 微调模型生成，模仿角色梅尔·艾什礼佩克（Mare Ashley-Pecker）的声线
- 属于角色声音的**衍生制品**
- **权利归属：Cabbage Soft**。本素材**不适用 MIT 许可**，也不授权他人复用、再分发或用于训练

### TTS 模型（`tts-model-v1.zip`，约 71 MB）

- 模型：**VITS**；微调框架：**VITS-fast**（[VITS-fast-fine-tuning](https://github.com/Plachtaa/VITS-fast-fine-tuning)）
- 训练数据：角色梅尔的语音样本，属**二创衍生**
- **权利归属：Cabbage Soft**。模型权重**不适用 MIT 许可**，不授权复用、再分发或再训练

## 三、Live2D 模型

`app/src/main/assets/live2d/mea_live2d/` 下的模型资源为社区创作（B 站分享），同样属二创衍生，
权利归 **Cabbage Soft**；不适用 MIT 许可。

## 四、你可以做什么

- ✅ 下载、安装、个人使用本应用
- ✅ 阅读、修改、分发本项目的**源代码**（受 MIT 许可约束）
- ✅ 提交 Issue / PR 参与改进

## 五、你不可以做什么

- ❌ 将本项目的语音素材或 TTS 模型用于**商业用途**
- ❌ 将语音素材或模型**再分发**到其它项目、平台或模型仓库
- ❌ 用这些素材 / 模型**训练其他模型**或做二次微调后分发
- ❌ 声称对角色、角色声音或模型享有权利
- ❌ 将本项目的任何资源用于违法、侵权或违背公序良俗的用途

## 六、分叉与二次开发

如果你要 fork 本项目：

- 源代码可以继续用 MIT，但**必须移除** `app/src/main/assets/voice/`、`app/src/main/assets/live2d/` 下的二创资源，
  并自行准备有合法来源的替代素材；
- TTS 模型包不在仓库内，请勿自行分发；
- 详见 `NOTICE.md` 的「Touch Voice Assets」与「TTS Model Assets」小节。

## 七、免责

本项目为个人兴趣作品，与 Cabbage Soft 无任何隶属或授权关系。
如权利方认为本项目存在不当使用，请通过仓库 Issue 联系，我们将及时下架相关资源。

---

*最后更新：2026-11-24*
