package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils

class BinderProvider : ContentProvider() {

    private val mBinderParcel = BinderParcel()

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            "getBinder" -> {
                mBinderParcel.mProxy = extras?.getBinder("binder")
                null
            }
            "getData" -> {
                val bundle = Bundle()
                bundle.putBinder("binder", mBinderParcel.mProxy)
                bundle
            }
            else -> super.call(method, arg, extras)
        }
    }
}
