package com.close.hook.ads.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils

/**
 * Author wangyu1
 * Data 2019/2/12
 * Description
 */
class MyContentProvider : ContentProvider() {

    private val mBinderParcel = BinderParcel()

    override fun delete(arg0: Uri, arg1: String?, arg2: Array<String>?): Int {
        return 0
    }

    override fun getType(arg0: Uri): String? {
        return null
    }

    override fun insert(arg0: Uri, arg1: ContentValues?): Uri? {
        return null
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
        arg0: Uri,
        arg1: Array<String>?,
        arg2: String?,
        arg3: Array<String>?,
        arg4: String?
    ): Cursor? {
        return null
    }

    override fun update(arg0: Uri, arg1: ContentValues?, arg2: String?, arg3: Array<String>?): Int {
        return 0
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!TextUtils.isEmpty(method) && TextUtils.equals(method, "getBinder")) {
            mBinderParcel.mProxy = extras?.getBinder("binder")
        } else if (!TextUtils.isEmpty(method) && TextUtils.equals(method, "getData")) {
            val bundle = Bundle()
            bundle.putBinder("binder", mBinderParcel.mProxy)
            return bundle
        }
        return null
    }
}
