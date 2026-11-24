package com.meapet.mobile.update

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateCheckerTest {

    @Test
    fun `compareVersions treats v prefix as equal`() {
        assertEquals(0, UpdateChecker.compareVersions("v1.0.2", "1.0.2"))
        assertEquals(0, UpdateChecker.compareVersions("1.0.0", "v1.0.0"))
    }

    @Test
    fun `isNewer detects patch and minor bumps`() {
        assertTrue(UpdateChecker.isNewer("1.0.3", "1.0.2"))
        assertTrue(UpdateChecker.isNewer("v1.1.0", "1.0.9"))
        assertFalse(UpdateChecker.isNewer("1.0.2", "1.0.2"))
        assertFalse(UpdateChecker.isNewer("1.0.1", "1.0.2"))
    }

    @Test
    fun `compareVersions handles uneven segment counts`() {
        assertTrue(UpdateChecker.compareVersions("1.0.0.1", "1.0.0") > 0)
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.1") < 0)
    }

    // 自动构建会通过 -PversionSuffix 把当前提交短哈希拼到 versionName 末尾
    //（9 位，如 1.9.0-1a2b3c4d5）。更新检查比较远端 tag 与本机 versionName 时，
    // 必须把「基础版本 + 短哈希」视为与基础版本相同，不能把哈希当成新的版本段。
    @Test
    fun `compareVersions treats short hash build suffix as the same version`() {
        assertEquals(0, UpdateChecker.compareVersions("1.9.0-abc123def", "1.9.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.9.0", "1.9.0-abc123def"))
    }

    @Test
    fun `compareVersions ignores short hash beginning with a digit`() {
        // 旧实现按 . - _ 切段并取数字前缀，1.9.0-1a2b3c4d5 会被解析成 1.9.0.1。
        assertEquals(0, UpdateChecker.compareVersions("1.9.0-1a2b3c4d5", "1.9.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.9.0", "1.9.0-1a2b3c4d5"))
    }

    @Test
    fun `isNewer does not misjudge short hash suffix as a newer version`() {
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.9.0-abc123def"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.9.0-1a2b3c4d5"))
        assertFalse(UpdateChecker.isNewer("v1.9.0", "1.9.0-1a2b3c4d5"))
    }

    @Test
    fun `isNewer still detects real upgrades when local version has short hash suffix`() {
        assertTrue(UpdateChecker.isNewer("1.9.1", "1.9.0-abc123def"))
        assertTrue(UpdateChecker.isNewer("v2.0.0", "1.9.0-1a2b3c4d5"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.9.0-1a2b3c4d5"))
    }
}
