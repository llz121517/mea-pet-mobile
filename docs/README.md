# 文档总览

> 本页是 `docs/` 的索引：说明每份文档**写什么、是否仍在维护、和代码的对应关系**。
> 先读本页，再读具体文件。
>
> - 面向使用者 / 构建者的入口是根目录 [`README.md`](../README.md)
> - 发布记录是根目录 [`CHANGELOG.md`](../CHANGELOG.md)
> - 待办只在 [`TODO.md`](TODO.md) 一处维护，本页不重复跟踪

## 1. 按需求找文档

| 我想… | 看这里 |
|---|---|
| 安装 / 编译 / 配置 App，了解功能 | [`README.md`](../README.md) |
| 知道某个版本改了什么 | [`CHANGELOG.md`](../CHANGELOG.md)；1.6.0 及更早见 [`archive/changelog/`](archive/changelog/) |
| 接手续做代码审查遗留项 | [`TODO.md`](TODO.md) ← **唯一在维护的待办清单** |
| **1.9.0 发版前收尾与真机验收** | [`2026-11-22-release-checklist.md`](2026-11-22-release-checklist.md)（勾选状态 + 判据）；已执行的 adb 自动化记录见 [`2026-11-24-adb-automation-plan.md`](2026-11-24-adb-automation-plan.md) |
| 了解记忆工具、只读回退、自动摘要与数据兼容 | [`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) |
| 导出 / 导入自己的设置与数据（数据与备份） | [`2026-11-22-backup-export-import-prd.md`](2026-11-22-backup-export-import-prd.md)、[`2026-11-22-backup-export-import-lld.md`](2026-11-22-backup-export-import-lld.md) |
| 了解备份包与社区分支的格式互通边界 | [`2026-11-23-backup-format-compat.md`](2026-11-23-backup-format-compat.md) |
| 了解 TTS 的设计、发音规则与已知边界 | [`tts/architecture.md`](tts/architecture.md) |
| 复现 / 重新打包 TTS 模型包与词表 | [`tts/model-package.md`](tts/model-package.md)、[`tts/g2p-pipeline.md`](tts/g2p-pipeline.md)、[`tools/tts/README.md`](../tools/tts/README.md) |
| 查 1.8.0 真机验收记录（已通过，归档） | [`archive/checklists/2026-11-15-tts-device-test-1.8.0.md`](archive/checklists/2026-11-15-tts-device-test-1.8.0.md) |
| 了解 1.8.0 语音迁移的全过程与最终结论（已归档） | [`archive/reports/2026-11-15-migration-report-1.8.0.md`](archive/reports/2026-11-15-migration-report-1.8.0.md) |
| 改 / 删旧版本迁移代码 | [`legacy-compat.md`](legacy-compat.md)、[`legacy/README.md`](../app/src/main/java/com/meapet/mobile/legacy/README.md) |
| 了解本项目的二创性质与语音/Live2D 资源使用边界 | [`FAN-WORK-NOTICE.md`](FAN-WORK-NOTICE.md)
| 查 1.6.0 及更早的发布记录 | [`archive/changelog/`](archive/changelog/) |
| 看 1.8.0 TTS 的实施计划（历史） | [`archive/plans/`](archive/plans/) |

## 2. 活跃文档（在维护）

### 2.1 待办与迁移

| 文件 | 内容 | 更新时机 |
|---|---|---|
| [`TODO.md`](TODO.md) | 代码审查遗留项、1.9 发布前验收与真机验证、架构和工程待办。**全项目唯一待办清单**，用户可见改动同步 `CHANGELOG.md` | 每次修复或发现一个问题 |
| [`legacy-compat.md`](legacy-compat.md) | 版本迁移框架引用点、当前迁移步骤、升级行为与验证方式 | 新增 / 删除迁移 step 时 |
| [`FAN-WORK-NOTICE.md`](FAN-WORK-NOTICE.md) | 二创性质声明：语音素材（GPT-SoVITS）与 TTS 模型（VITS）的权利归属、可做/不可做清单、fork 时须移除的资源 | 语音/TTS 模型来源或授权口径变化时 |

### 2.2 TTS（语音合成）文档组

| 文件 | 内容 | 权威性 |
|---|---|---|
| [`tts/architecture.md`](tts/architecture.md) | 最终方案：统一模型 + 统一 lexicon、日文上下文改写规则、英文 OOV 逐字母兜底、tokens 约定、已验证结论与边界 | **设计权威**（与 App 代码 `TtsTextNormalizer` / `EnglishBloomIndex` 对齐） |
| [`tts/model-package.md`](tts/model-package.md) | 模型包 6 个文件的大小 / sha256、两种分发方式（在线下载 / 本地 zip）、重新生成命令 | **产物清单权威** |
| [`tts/g2p-pipeline.md`](tts/g2p-pipeline.md) | 一条命令复现 G2P 资产、产物指纹与断言级别、lexicon 行序差异结论 | 索引页；细节在 [`tools/tts/README.md`](../tools/tts/README.md) |

> TTS 生成管线的代码与完整说明在 [`tools/tts/`](../tools/tts/README.md)（`README.md` + `build_model.md`），
> `model.onnx` 不在管线内（需外部 checkpoint），见 [`tools/tts/build_model.md`](../tools/tts/build_model.md)。

### 2.3 项目规则与决策记录

| 文件 | 内容 | 更新时机 |
|---|---|---|
| [`rules.md`](rules.md) | 项目规则（通用逻辑优先 / 童子军规则优先 / 过时文档直接删除 / 提交规范） | 新增 / 修改规则时 |
| [`adr/0001-tool-round-limit-interception.md`](adr/0001-tool-round-limit-interception.md) | ADR：工具轮次用尽时在工具执行端拦截，不发送 `tool_choice="none"` | 新决策按编号追加 |
| [`adr/0002-context-window-fallback.md`](adr/0002-context-window-fallback.md) | ADR：兜底上下文容量取 32K 小窗口而非 128K，把「估大被 400 拒绝」换成「提前压缩」 | 新决策按编号追加 |
| [`adr/0003-live2d-32bit-fallback.md`](adr/0003-live2d-32bit-fallback.md) | ADR：32 位 ARM 无 Live2D 原生核心，降级为无模型背景；保留 v7a 兼容，后续考虑改 v8a-only | 新决策按编号追加 |

### 2.4 活跃实现参考

| 文件 | 内容 |
|---|---|
| [`2026-11-18-memory-tools-plan.md`](2026-11-18-memory-tools-plan.md) | 1.9 记忆系统当前实现：工具写入、只读关键词注入、自动摘要边界、历史与旧数据兼容 |
| [`2026-11-18-sqlite-context-plan.md`](2026-11-18-sqlite-context-plan.md) | 1.9 SQLite 存储、上下文压缩、容量探测及遗留决策 / 待验收项 |
| [`2026-11-19-web-search-plan.md`](2026-11-19-web-search-plan.md) | 联网搜索与抓取的已接入行为、适配器配置与待真机验收项 |
| [`2026-11-22-backup-export-import-prd.md`](2026-11-22-backup-export-import-prd.md) | 1.9 数据与备份：需求决策、包格式、交互流程与测试清单（**已实现**，状态见文首） |
| [`2026-11-22-backup-export-import-lld.md`](2026-11-22-backup-export-import-lld.md) | 1.9 数据与备份详细设计：模块划分、条目结构、接口、错误文案与测试设计（实现状态见 §3 开头） |
| [`2026-11-23-backup-format-compat.md`](2026-11-23-backup-format-compat.md) | 备份包与社区参考分支的逐字段互通性审计（**一次性结论**，已实施部分见文首状态头） |
| [`2026-11-22-release-checklist.md`](2026-11-22-release-checklist.md) | 1.9.0 发版收尾与真机验收：勾选状态、判据、已知问题与发版步骤（**一次性清单**，发版后归档） |
| [`2026-11-24-adb-automation-plan.md`](2026-11-24-adb-automation-plan.md) | 上述清单的 adb 自动化分拣结果与实测证据（**一次性执行记录**） |

## 3. 历史归档（只读，不再维护）

| 文件 | 内容 |
|---|---|
| [`archive/changelog/CHANGELOG-1.6-and-earlier.md`](archive/changelog/CHANGELOG-1.6-and-earlier.md) | 1.6.0 及更早的发布记录（2026-09-07 从 `CHANGELOG.md` 归档） |
| [`archive/checklists/2026-11-15-tts-device-test-1.8.0.md`](archive/checklists/2026-11-15-tts-device-test-1.8.0.md) | 1.8.0 真机验收清单（**已执行完成、全部通过**，2026-11-15；含自动化记录 §A 与结论 §3） |
| [`archive/reports/2026-11-15-migration-report-1.8.0.md`](archive/reports/2026-11-15-migration-report-1.8.0.md) | 1.8.0 迁移报告：架构对比、决策、验证记录、升级与回滚、已知限制、提交记录、最终审查结论（历史结论参考） |
| [`archive/plans/2026-10-31-sherpa-onnx-tts-integration.md`](archive/plans/2026-10-31-sherpa-onnx-tts-integration.md) | 1.8.0 TTS 迁移的逐任务实施计划（**已执行完成**，勾选框未回填；结论见 `archive/reports/2026-11-15-migration-report-1.8.0.md`） |

归档规则：只有**长期有价值**的版本化资料才归档（发布记录、已完成计划的实施过程）；进来后**不再更新**，新结论写回活跃文档。
**过时且会误导的文档直接删除，不进归档**——2026-08/09 的 48 项代码审查报告、逐条判定表、旧批次清单已于 2026-11-15 删除（描述 1.7.2 之前的代码，结论已并入 `TODO.md`；需要追溯查 git 历史）。
新增计划文档先按 `YYYY-MM-DD-<主题>` 命名，执行完成后加状态头再移入 `archive/plans/`。

## 4. 仓库内其他文档（不在 `docs/`）

| 文件 | 内容 |
|---|---|
| [`../README.md`](../README.md) | 项目介绍、功能、构建、配置、发布流程 |
| [`../CHANGELOG.md`](../CHANGELOG.md) | 发布记录（Keep a Changelog） |
| [`../NOTICE.md`](../NOTICE.md) | 第三方组件许可与署名（Live2D、图标、友盟、TTS 组件等） |
| [`../tools/tts/README.md`](../tools/tts/README.md) | G2P / 词表生成管线：范围、复现命令、脚本职责、数据源 pin、产物断言、格式约定、排查 |
| [`../tools/tts/build_model.md`](../tools/tts/build_model.md) | `model.onnx` 的来源、指纹与契约约束（不在管线内） |
| [`../tools/icons.txt`](../tools/icons.txt) | 设置界面图标清单（MDI → VectorDrawable 的对应关系与生成方式） |
| [`../app/src/main/java/com/meapet/mobile/client/README.md`](../app/src/main/java/com/meapet/mobile/client/README.md) | OpenAI 兼容客户端包（HTTP + JSON，无业务逻辑）的用法与设计原则 |
| [`../app/src/main/java/com/meapet/mobile/legacy/README.md`](../app/src/main/java/com/meapet/mobile/legacy/README.md) | 版本迁移模块的纪律 |
| [`../app/src/main/java/com/live2d/LICENSE.md`](../app/src/main/java/com/live2d/LICENSE.md)、[`../app/src/main/assets/com/live2d/LICENSE.md`](../app/src/main/assets/com/live2d/LICENSE.md) | Live2D Cubism 框架 / 着色器资源的许可原文 |

## 5. 维护规则

1. **单一权威**：同一主题只留一份权威文档，其余要么归档、要么显式标注「历史 / 已取代」。当前分工见 §2。
2. **状态头**：一次性文档（计划、迁移报告、真机清单）在标题下写明状态（进行中 / 已完成 / 待执行）与对应版本。
3. **过时即删**：描述旧代码状态、会误导人的文档直接删除（git 历史可追溯），不进归档；只有长期有价值的版本化资料才归档（见 §3）。
4. **TODO 与 CHANGELOG 联动**：`TODO.md` 勾掉 / 新增条目时，用户可见改动同步写进 `CHANGELOG.md`（见 TODO 页首约定）。
5. **改代码要顺手改文档**：涉及 TTS 发音规则、模型包清单、迁移 step、发布流程的改动，必须同步更新对应活跃文档；每个版本的真机验收**新写清单**，不复用旧清单（1.8.0 的记录已归档，只读不改）。
6. **发版清单格式统一**：条目一律写成**表格行**（`检查项 | 步骤 | 预期 | 状态`），状态只用 `✅ 已通过` / `🟡 部分完成` / `⬜ 未执行` / `❌ 失败` 四档；部分完成不算通过，证据写在该节「已验证明细」。这样可用一条正则统计进度，也便于与 `TODO.md` 对账。
7. **提交前查链接**：`docs/` 内相对链接必须可达，可跑 `node tools/check_doc_links.mjs` 自查；路径写法见本页示例（同目录直接写文件名，跨目录用相对路径）。
8. **冲突处理**：文档与代码不一致时以代码为准并及时修正（行号会漂移，引用优先用符号名）。整理文档时若发现数字 / 行号漂移，**当场改对并注明复核日期**，不要只留「待确认」让人二次核对。
