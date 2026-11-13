package cn.com.omnimind.androidgui.fixture

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * 只读合成验收证据 ContentProvider
 * URI: content://cn.com.omnimind.androidgui.test.evidence/state
 * 仅支持 query 读取单行单列 JSON，严格拒绝所有写入、修改、调用及非指定列操作。
 */
class GuiFixtureEvidenceProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "cn.com.omnimind.androidgui.test.evidence"
        private const val MATCH_STATE = 1
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "state", MATCH_STATE)
        }
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (uriMatcher.match(uri) != MATCH_STATE) {
            throw IllegalArgumentException("不支持的URI路径: $uri，仅支持 content://$AUTHORITY/state")
        }

        if (!selection.isNullOrBlank() || !selectionArgs.isNullOrEmpty()) {
            throw IllegalArgumentException("证据查询不支持过滤条件")
        }

        if (!sortOrder.isNullOrBlank()) {
            throw IllegalArgumentException("证据查询不支持排序条件")
        }

        if (projection != null && projection.isNotEmpty()) {
            if (projection.size != 1 || projection[0] != "json") {
                throw IllegalArgumentException("证据查询仅支持单列 json 投影")
            }
        }

        val jsonPayload = FixtureStateHolder.toJsonString()
        val cursor = MatrixCursor(arrayOf("json"))
        cursor.addRow(arrayOf(jsonPayload))
        return cursor
    }

    override fun getType(uri: Uri): String {
        return "application/json"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("只读合成证据Provider不支持插入操作")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("只读合成证据Provider不支持删除操作")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("只读合成证据Provider不支持更新操作")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        throw UnsupportedOperationException("只读合成证据Provider不支持调用操作")
    }
}
