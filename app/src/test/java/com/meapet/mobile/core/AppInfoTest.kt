package com.meapet.mobile.core

import org.junit.Test
import kotlin.test.assertEquals

class AppInfoTest {

    @Test
    fun `baseVersion strips automatic build short hash suffix`() {
        assertEquals("1.9.0", AppInfo.baseVersion("1.9.0-abc123def"))
        // 短哈希可能以数字开头，也必须整体剥离，而不是被当成版本段
        assertEquals("1.9.0", AppInfo.baseVersion("1.9.0-1a2b3c4d5"))
        assertEquals("1.9.0", AppInfo.baseVersion("1.9.0-123456789"))
    }

    @Test
    fun `baseVersion handles v prefix and surrounding whitespace`() {
        assertEquals("v1.9.0", AppInfo.baseVersion(" v1.9.0-1a2b3c4d5 "))
    }

    @Test
    fun `baseVersion keeps plain versions and prerelease identifiers`() {
        assertEquals("1.9.0", AppInfo.baseVersion("1.9.0"))
        // 预发布标识不是构建哈希，保留给版本比较逻辑处理
        assertEquals("1.9.0-beta1", AppInfo.baseVersion("1.9.0-beta1"))
        assertEquals("1.9.0-rc.1", AppInfo.baseVersion("1.9.0-rc.1"))
    }

    @Test
    fun `baseVersion tolerates full 40 char commit hash`() {
        val sha = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b"
        assertEquals("1.9.0", AppInfo.baseVersion("1.9.0-$sha"))
    }
}
