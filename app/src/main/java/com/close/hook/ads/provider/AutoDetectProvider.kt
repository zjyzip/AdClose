package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.close.hook.ads.data.model.CustomHookInfo
import com.close.hook.ads.data.repository.CustomHookRepository
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class AutoDetectProvider : ContentProvider() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "sendResult" && extras != null) {
            extras.getString("detected_hooks_result")?.let { jsonString ->
                if (jsonString.length > MAX_PAYLOAD_BYTES) return null
                try {
                    val hooks = json.decodeFromString<List<CustomHookInfo>>(jsonString)
                    if (hooks.size > MAX_HOOKS_COUNT) return null
                    CustomHookRepository.publishAutoDetectResult(hooks)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return null
    }

    companion object {
        private const val MAX_PAYLOAD_BYTES = 512 * 1024
        private const val MAX_HOOKS_COUNT = 1000
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
