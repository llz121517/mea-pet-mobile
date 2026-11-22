package com.meapet.mobile.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私政策版本元数据约束。
 *
 * 政策正文修订必须同时递增 [PRIVACY_POLICY_VERSION] 并更新 [PRIVACY_POLICY_UPDATE_DATE]：
 * `MainActivity` 以「已记录版本号 ≠ 当前版本号」作为重新弹窗条件（含统计 SDK 的构建），
 * 版本号漏改会让老用户永远看不到更新后的政策。这里只锁格式与日期先后，避免手改常量时
 * 写错格式或把修订日期填到生效日期之前；正文内容本身由人工评审保证。
 */
@Suppress("NonAsciiCharacters")
class PrivacyPolicyVersionTest {

    private val versionPattern = Regex("""\d+\.\d+""")
    private val datePattern = Regex("""\d{4}-\d{2}-\d{2}""")

    @Test
    fun `版本号为 x_y 形式`() {
        assertTrue(
            "版本号格式应为 x.y，实际：$PRIVACY_POLICY_VERSION",
            versionPattern.matches(PRIVACY_POLICY_VERSION)
        )
    }

    @Test
    fun `日期为 ISO 格式且修订不早于生效`() {
        assertTrue(
            "生效时间格式应为 yyyy-MM-dd，实际：$PRIVACY_POLICY_EFFECTIVE_DATE",
            datePattern.matches(PRIVACY_POLICY_EFFECTIVE_DATE)
        )
        assertTrue(
            "修订时间格式应为 yyyy-MM-dd，实际：$PRIVACY_POLICY_UPDATE_DATE",
            datePattern.matches(PRIVACY_POLICY_UPDATE_DATE)
        )
        assertTrue(
            "修订时间不应早于生效时间：生效 $PRIVACY_POLICY_EFFECTIVE_DATE，修订 $PRIVACY_POLICY_UPDATE_DATE",
            PRIVACY_POLICY_UPDATE_DATE >= PRIVACY_POLICY_EFFECTIVE_DATE
        )
    }
}
