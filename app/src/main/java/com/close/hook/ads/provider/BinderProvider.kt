package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

class BinderProvider : ContentProvider() {
    private val mBinderParcel = BinderParcel()

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        try {
            return when (method) {
                "getBinder" -> {
                    mBinderParcel.mProxy = extras?.getBinder("binder")
                    null
                }
                "getData" -> {
                    val type = extras?.getString("type")
                    val value = extras?.getString("value")
                    val fd = DataManager.getInstance().getData(type!!, value!!)
                    Bundle().apply {
                        putParcelable("fd", fd)
                    }
                }
                else -> super.call(method, arg, extras)
            }
        } catch (e: Exception) {
            Log.e("BinderProvider", "Error handling $method call", e)
            return null
        }
    }
}
