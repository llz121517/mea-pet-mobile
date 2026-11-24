package com.meapet.mobile.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * 仅测试源集使用的 androidx.sqlite 驱动适配器：用 sqlite-jdbc 在 JVM 上跑真实 SQLite。
 *
 * 工程开了 isReturnDefaultValues=true，android.database.sqlite 在 JVM 单测里会静默返回默认值，
 * 测试会空转；这里绕开框架 SQLite，让存储层测试真正执行 SQL。
 *
 * 约定：绑定参数下标从 1 开始，结果列下标从 0 开始（与 androidx.sqlite 一致）。
 * 事务由调用方用 BEGIN/COMMIT/ROLLBACK 语句显式控制（连接保持 autoCommit）。
 */
class JdbcSQLiteDriver : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$fileName")
        return JdbcConnection(conn)
    }
}

private class JdbcConnection(private val conn: Connection) : SQLiteConnection {
    override fun prepare(sql: String): SQLiteStatement = JdbcStatement(conn.prepareStatement(sql))
    override fun close() = conn.close()
}

private class JdbcStatement(private val ps: PreparedStatement) : SQLiteStatement {
    private var rs: ResultSet? = null
    private var executed = false

    private fun row(): ResultSet = checkNotNull(rs) { "no current row" }

    override fun bindBlob(index: Int, value: ByteArray) = ps.setBytes(index, value)
    override fun bindDouble(index: Int, value: Double) = ps.setDouble(index, value)
    override fun bindLong(index: Int, value: Long) = ps.setLong(index, value)
    override fun bindText(index: Int, value: String) = ps.setString(index, value)
    override fun bindNull(index: Int) = ps.setNull(index, java.sql.Types.NULL)

    override fun getBlob(index: Int): ByteArray = row().getBytes(index + 1)
    override fun getDouble(index: Int): Double = row().getDouble(index + 1)
    override fun getLong(index: Int): Long = row().getLong(index + 1)
    override fun getText(index: Int): String = row().getString(index + 1)
    override fun isNull(index: Int): Boolean = row().getObject(index + 1) == null

    override fun getColumnCount(): Int = ps.metaData?.columnCount ?: 0
    override fun getColumnName(index: Int): String = ps.metaData.getColumnName(index + 1)

    // 1=INTEGER 2=FLOAT 3=TEXT 4=BLOB 5=NULL（与 SQLITE_DATA_* 常量一致）
    override fun getColumnType(index: Int): Int = when (row().getObject(index + 1)) {
        null -> 5
        is Long, is Int, is Short, is Byte -> 1
        is Double, is Float -> 2
        is ByteArray -> 4
        else -> 3
    }

    override fun step(): Boolean {
        if (!executed) {
            executed = true
            if (!ps.execute()) return false
            rs = ps.resultSet
        }
        val r = rs ?: return false
        return r.next()
    }

    override fun reset() {
        rs?.close()
        rs = null
        executed = false
    }

    override fun clearBindings() = ps.clearParameters()

    override fun close() {
        rs?.close()
        rs = null
        ps.close()
    }
}
