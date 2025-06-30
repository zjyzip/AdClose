package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class HookPrefsProvider : ContentProvider() {

    companion object {
        private const val PREF_NAME = "com.close.hook.ads_preferences"
        private const val URI_CODE = 1
        const val AUTHORITY = "com.close.hook.ads.provider.prefs"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PREF_NAME")
    }

    private val uriMatcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PREF_NAME, URI_CODE)
        }
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uriMatcher.match(uri) != URI_CODE) return null
        val prefs = context!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val cursor = MatrixCursor(projection ?: arrayOf("key", "value", "type"))
        val key = selectionArgs?.getOrNull(0)
        val def = selectionArgs?.getOrNull(1)
        val type = selectionArgs?.getOrNull(2)
        if (key != null && type != null) {
            val v = when (type) {
                "boolean" -> prefs.getBoolean(key, def?.toBooleanStrictOrNull() ?: false).toString()
                "int" -> prefs.getInt(key, def?.toIntOrNull() ?: 0).toString()
                "long" -> prefs.getLong(key, def?.toLongOrNull() ?: 0L).toString()
                "float" -> prefs.getFloat(key, def?.toFloatOrNull() ?: 0f).toString()
                "string" -> prefs.getString(key, def)
                else -> null
            }
            if (v != null) cursor.addRow(arrayOf(key, v, type))
        } else if (key == null && selection == "all") {
            prefs.all.forEach { (k, v) ->
                val t = when (v) {
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is String -> "string"
                    else -> "unknown"
                }
                cursor.addRow(arrayOf(k, v?.toString(), t))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.${AUTHORITY}.$PREF_NAME"

    override fun insert(uri: Uri, values: ContentValues?) = throw UnsupportedOperationException("Insert not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (uriMatcher.match(uri) != URI_CODE) return 0
        val prefs = context!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val key = selectionArgs?.getOrNull(0)
        val ok = if (key != null) editor.remove(key).commit() else selection == "clear" && editor.clear().commit()
        if (ok) context!!.contentResolver.notifyChange(uri, null)
        return if (ok) 1 else 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (uriMatcher.match(uri) != URI_CODE) return 0
        val prefs = context!!.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        values?.valueSet()?.forEach {
            when (val v = it.value) {
                is Boolean -> editor.putBoolean(it.key, v)
                is Int -> editor.putInt(it.key, v)
                is Long -> editor.putLong(it.key, v)
                is Float -> editor.putFloat(it.key, v)
                is String -> editor.putString(it.key, v)
            }
        }
        val ok = editor.commit()
        if (ok && values?.size() ?: 0 > 0) context!!.contentResolver.notifyChange(uri, null)
        return if (ok) values?.size() ?: 0 else 0
    }
}
