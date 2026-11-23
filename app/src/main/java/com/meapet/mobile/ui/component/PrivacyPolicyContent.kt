package com.meapet.mobile.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meapet.mobile.BuildConfig
import com.meapet.mobile.core.AppInfo

// ═══════════════════════════════════════════════════
//  隐私政策版本与日期（两份政策共享同一套）
// ═══════════════════════════════════════════════════
// 硬编码在此而非 local.properties：修订政策必然改动本文件文案，版本号/日期随代码一起
// 提交，避免放配置里时漏改。修订时必须同时递增版本号并更新日期（与 MainActivity 的
// 版本比对逻辑配合：版本号变化 → 含统计 SDK 的构建会重新弹窗确认）。
const val PRIVACY_POLICY_VERSION = "1.3"
const val PRIVACY_POLICY_EFFECTIVE_DATE = "2026-07-29"
const val PRIVACY_POLICY_UPDATE_DATE = "2026-11-23"

/**
 * 共享的隐私政策正文内容（纯内容，不含任何外壳组件）。
 *
 * 供 [PrivacyDialog] 和设置页的隐私政策子页复用，确保两处文案一致且由同一模块维护。
 *
 * ## 通用数据流向（两版政策都必须覆盖）
 * - 会话、记忆、设置与 API Key 只保存在本机应用私有存储；
 * - AI 对话请求（含系统提示词、记忆检索/写入结果、记忆摘要、上下文压缩摘要与容量
 *   探测请求）发往**用户自己配置**的 API 地址，应用不运营服务器、不中转、不保存；
 * - 联网搜索的查询词与网页地址发往 DeepSeek / AnySearch，自动模式可能切换服务商；
 * - 备份与导出由用户主动发起，写到用户自己选择的位置，导出包强制 AES-256 密码加密，
 *   应用不上传、也不保留任何备份副本。
 *
 * ## 两份政策分开维护（同一文件）
 * 按构建是否包含统计 SDK（[BuildConfig.UMENG_ENABLED]）分派到两套完整政策：
 * - [PrivacyPolicyWithUmeng]：集成友盟+ 统计 SDK 的构建，在通用数据流向之外另披露统计
 *   SDK 的采集范围、用途、授权管理与数据安全；
 * - [PrivacyPolicyWithoutSdk]：无统计 SDK 的构建，只保留通用数据流向，声明不采集统计数据。
 * 两份政策各自成篇、章节独立编号，修订时分别维护；版本号/日期由上方共享常量统一提供。
 */
@Composable
fun PrivacyPolicyContent() {
    if (BuildConfig.UMENG_ENABLED) {
        PrivacyPolicyWithUmeng()
    } else {
        PrivacyPolicyWithoutSdk()
    }
}

/** 含友盟+ 统计 SDK 构建的隐私政策全文（与无 SDK 版分开维护）。 */
@Composable
private fun PrivacyPolicyWithUmeng() {
    PrivacyHeader()

    PrivacySection("一、概述") {
        Text(
            "本隐私政策由 ${AppInfo.devName}（下称「开发者」）制定并生效，适用于 MeaPet（梅尔桌宠）应用。" +
                "MeaPet 是本地优先的 AI 聊天应用：会话、记忆与 API 配置保存在你的设备上；" +
                "AI 对话与联网搜索需要联网，相关内容会直接发送给你自行配置的第三方服务，" +
                "开发者不运营服务器，也不接收、存储或中转这些内容。" +
                "集成友盟+（Umeng）统计 SDK 的构建还会采集去标识化的使用数据。" +
                "本政策说明上述数据流向、采集范围、用途与你的权利。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("二、保存在本机的数据") {
        PrivacyBullet("会话记录、记忆内容、设置项与 API 配置保存在应用私有存储中；卸载应用或在系统中清除应用数据即会删除")
        PrivacyBullet("API Key（含「API 配置」中的聊天 Key 与联网搜索的独立 Key）以明文形式保存在应用私有存储中，未做额外加密；请在可信设备上使用并妥善保管")
        PrivacyBullet("「导出日志」只包含设备与版本信息、崩溃记录和运行日志，不包含对话正文、记忆内容或 API Key")
    }

    PrivacySection("三、备份与导出") {
        PrivacyBullet("备份与导出由你主动发起，应用不会自动创建备份；导出时由系统文件选择器指定保存位置（本地存储或你信任的云盘），应用不保留备份副本，也不会上传任何备份")
        PrivacyBullet("备份包包含你勾选的数据：设置、对话历史、记忆库、上下文压缩状态与背景壁纸；文件写入你选择的位置后，即由该位置（含云盘服务商）负责存储与保护")
        PrivacyBullet("导出包强制使用 AES-256 密码加密，密码由你设置且应用不会保存，遗忘后无法找回")
        PrivacyBullet("API Key（「API 配置」中的聊天 Key 与 DeepSeek / AnySearch 的独立 Key）默认不写入备份包，只有你显式勾选「API Key」才会写入")
        PrivacyBullet("导入时按类别整体覆盖本机对应数据（例如「对话历史」会替换本机全部对话），导入前需在确认弹窗中逐项确认；导入用的暂存文件在进入「数据与备份」页面时清扫")
    }

    PrivacySection("四、AI 对话的数据流向") {
        Text(
            "对话由你自行配置的 API 地址提供服务。以下内容会随请求发送给该地址对应的第三方服务商：" +
                "你发送的消息与会话历史；记忆检索与记忆写入工具的执行结果；" +
                "系统提示词（含人设与工具说明）；自动记忆摘要请求中的短期记忆内容；" +
                "上下文压缩摘要请求中被折叠的历史对话；上下文容量探测请求（仅用于读取模型列表，只带 API Key，不含对话内容）。" +
                "请求由应用直接从你的设备发往你配置的地址，开发者不运营服务器、不中转、不存储请求与回复。" +
                "该服务商对数据的处理受其自身隐私政策约束，请只填写你信任的 API 地址。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("五、联网搜索的数据流向") {
        PrivacyBullet("联网搜索默认关闭，仅在「服务商 → 联网搜索」中开启且聊天端点支持工具调用时生效")
        PrivacyBullet("web_search 的查询词与 web_fetch 要求读取的网页地址，会发送给实际使用的搜索服务商（DeepSeek 或 AnySearch）")
        PrivacyBullet("自动模式下首选服务失败或超时会切换到下一家，同一请求可能被发送给多家服务商")
        PrivacyBullet("AnySearch 未填写 Key 时使用匿名模式：请求不带鉴权信息，但查询词与网页地址仍会发送给 AnySearch；独立 Key 保存在本机应用私有存储中，仅随发往 AnySearch 的请求发送")
        PrivacyBullet("DeepSeek 独立 Key 留空、且「API 配置」使用 DeepSeek 官方地址、并且已开启「复用聊天 Key」开关（默认开启）时，才会复用其中的聊天 Key；该 Key 会随搜索请求发往 DeepSeek。关闭该开关后不再复用，需单独填写 DeepSeek Key")
        PrivacyBullet("应用不记录查询词、网页地址、网页正文或 API Key；服务商可能对调用设置额度、限速或收费，相关费用与限制由服务商决定")
    }

    PrivacySection("六、友盟+ 统计 SDK") {
        Text(
            "SDK 名称：友盟+ 统计 SDK",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "服务类型：应用统计分析",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        PrivacyBullet("采集的信息：设备标识符（OAID、Android ID、设备型号、操作系统版本等）、网络信息（网络类型、IP 地址）、应用使用信息（启动次数、使用时长、版本号、渠道来源）、统计基础库运行信息")
        PrivacyBullet("用途：生成脱敏的终端用户设备唯一性标识以提供统计服务、统计活跃用户数与使用时长、帮助发现并修复崩溃与性能问题")
        PrivacyBullet("应用每次启动会预初始化统计 SDK（该阶段仅完成初始化准备，不采集、不上报任何数据）；正式初始化并开始采集仅在您同意后进行")
        PrivacyBullet("采集的数据经去标识化处理，仅用于统计与分析目的，不用于识别具体个人；数据传输使用加密通道")
        Text(
            "隐私权政策链接：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinkItem(
            text = AppInfo.umengPolicyUrl,
            url = AppInfo.umengPolicyUrl,
            uriHandler = LocalUriHandler.current
        )
    }

    PrivacySection("七、授权管理") {
        PrivacyBullet("首次启动时，你会在弹窗中选择是否同意统计 SDK 的数据采集；该选择只影响统计 SDK，不影响你正常使用 AI 对话与联网搜索")
        PrivacyBullet("不同意：App 正常使用，但不会初始化统计 SDK，不采集任何统计数据")
        PrivacyBullet("同意后可随时在「设置 → 关于 → 隐私与数据」中取消授权。为确保撤回后立即、彻底停止数据采集，取消授权后 App 将自动退出；重新打开即可正常使用，且不会再进行任何统计采集")
        PrivacyBullet("AI 对话与联网搜索由你自行配置的第三方服务提供，可随时在设置中关闭或更换")
    }

    PrivacySection("八、数据安全") {
        Text(
            "统计 SDK 采集的数据均经去标识化处理，仅用于统计与分析目的，不用于识别具体个人；友盟+ 遵循相关法律法规对数据进行安全管理。" +
                "对话、记忆与 API Key 不上传到开发者服务器，其安全取决于你配置的第三方服务与设备本身的安全防护。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("九、你的权利") {
        PrivacyBullet("你可以随时在「设置」中取消数据采集授权")
        PrivacyBullet("你可以在应用内清除对话与记忆，或修改、删除 API 配置与联网搜索 Key")
        PrivacyBullet("卸载应用或在系统中清除应用数据，可删除本机保存的全部数据")
        PrivacyBullet("如果你不希望某些内容离开设备，请不要在对话中发送，或关闭联网搜索、避免使用需要联网的功能")
    }

    PrivacySection("十、联系我们") {
        Text(
            "本隐私政策主体为 ${AppInfo.devName}。如有任何关于隐私与数据采集的疑问，可通过 GitHub 仓库（${AppInfo.gitRepoUrl}）Issues 联系开发者。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 无统计 SDK 构建的隐私政策全文（与含 SDK 版分开维护）。 */
@Composable
private fun PrivacyPolicyWithoutSdk() {
    PrivacyHeader()

    PrivacySection("一、概述") {
        Text(
            "本隐私政策由 ${AppInfo.devName}（下称「开发者」）制定并生效，适用于 MeaPet（梅尔桌宠）应用。" +
                "本构建未包含任何统计 SDK，不采集统计数据。" +
                "MeaPet 是本地优先的 AI 聊天应用：会话、记忆与 API 配置保存在你的设备上；" +
                "AI 对话与联网搜索需要联网，相关内容会直接发送给你自行配置的第三方服务，" +
                "开发者不运营服务器，也不接收、存储或中转这些内容。本政策说明应用的数据处理情况与你的权利。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("二、保存在本机的数据") {
        PrivacyBullet("本构建未包含统计 SDK，应用不采集、不上报任何统计数据")
        PrivacyBullet("会话记录、记忆内容、设置项与 API 配置保存在应用私有存储中；卸载应用或在系统中清除应用数据即会删除")
        PrivacyBullet("API Key（含「API 配置」中的聊天 Key 与联网搜索的独立 Key）以明文形式保存在应用私有存储中，未做额外加密；请在可信设备上使用并妥善保管")
        PrivacyBullet("「导出日志」只包含设备与版本信息、崩溃记录和运行日志，不包含对话正文、记忆内容或 API Key")
    }

    PrivacySection("三、备份与导出") {
        PrivacyBullet("备份与导出由你主动发起，应用不会自动创建备份；导出时由系统文件选择器指定保存位置（本地存储或你信任的云盘），应用不保留备份副本，也不会上传任何备份")
        PrivacyBullet("备份包包含你勾选的数据：设置、对话历史、记忆库、上下文压缩状态与背景壁纸；文件写入你选择的位置后，即由该位置（含云盘服务商）负责存储与保护")
        PrivacyBullet("导出包强制使用 AES-256 密码加密，密码由你设置且应用不会保存，遗忘后无法找回")
        PrivacyBullet("API Key（「API 配置」中的聊天 Key 与 DeepSeek / AnySearch 的独立 Key）默认不写入备份包，只有你显式勾选「API Key」才会写入")
        PrivacyBullet("导入时按类别整体覆盖本机对应数据（例如「对话历史」会替换本机全部对话），导入前需在确认弹窗中逐项确认；导入用的暂存文件在进入「数据与备份」页面时清扫")
    }

    PrivacySection("四、AI 对话的数据流向") {
        Text(
            "对话由你自行配置的 API 地址提供服务。以下内容会随请求发送给该地址对应的第三方服务商：" +
                "你发送的消息与会话历史；记忆检索与记忆写入工具的执行结果；" +
                "系统提示词（含人设与工具说明）；自动记忆摘要请求中的短期记忆内容；" +
                "上下文压缩摘要请求中被折叠的历史对话；上下文容量探测请求（仅用于读取模型列表，只带 API Key，不含对话内容）。" +
                "请求由应用直接从你的设备发往你配置的地址，开发者不运营服务器、不中转、不存储请求与回复。" +
                "该服务商对数据的处理受其自身隐私政策约束，请只填写你信任的 API 地址。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("五、联网搜索的数据流向") {
        PrivacyBullet("联网搜索默认关闭，仅在「服务商 → 联网搜索」中开启且聊天端点支持工具调用时生效")
        PrivacyBullet("web_search 的查询词与 web_fetch 要求读取的网页地址，会发送给实际使用的搜索服务商（DeepSeek 或 AnySearch）")
        PrivacyBullet("自动模式下首选服务失败或超时会切换到下一家，同一请求可能被发送给多家服务商")
        PrivacyBullet("AnySearch 未填写 Key 时使用匿名模式：请求不带鉴权信息，但查询词与网页地址仍会发送给 AnySearch；独立 Key 保存在本机应用私有存储中，仅随发往 AnySearch 的请求发送")
        PrivacyBullet("DeepSeek 独立 Key 留空、且「API 配置」使用 DeepSeek 官方地址、并且已开启「复用聊天 Key」开关（默认开启）时，才会复用其中的聊天 Key；该 Key 会随搜索请求发往 DeepSeek。关闭该开关后不再复用，需单独填写 DeepSeek Key")
        PrivacyBullet("应用不记录查询词、网页地址、网页正文或 API Key；服务商可能对调用设置额度、限速或收费，相关费用与限制由服务商决定")
    }

    PrivacySection("六、数据安全") {
        Text(
            "本构建不采集统计数据。对话、记忆与 API Key 不上传到开发者服务器，其安全取决于你配置的第三方服务与设备本身的安全防护。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    PrivacySection("七、你的权利") {
        PrivacyBullet("你可以在应用内清除对话与记忆，或修改、删除 API 配置与联网搜索 Key")
        PrivacyBullet("卸载应用或在系统中清除应用数据，可删除本机保存的全部数据")
        PrivacyBullet("如果你不希望某些内容离开设备，请不要在对话中发送，或关闭联网搜索、避免使用需要联网的功能")
    }

    PrivacySection("八、联系我们") {
        Text(
            "本隐私政策主体为 ${AppInfo.devName}。如有任何关于隐私与数据处理的疑问，可通过 GitHub 仓库（${AppInfo.gitRepoUrl}）Issues 联系开发者。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 隐私政策头部：版本、生效时间与修订时间（两份政策共享 [PRIVACY_POLICY_VERSION] 等常量）。
 */
@Composable
private fun PrivacyHeader() {
    Column(
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Text(
            "版本：${PRIVACY_POLICY_VERSION}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "生效时间：${PRIVACY_POLICY_EFFECTIVE_DATE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "修订时间：${PRIVACY_POLICY_UPDATE_DATE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 隐私政策小节标题。
 */
@Composable
private fun PrivacySection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
    content()
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(top = 12.dp)
    )
}

/**
 * 隐私政策 bullet 条目。
 */
@Composable
private fun PrivacyBullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
