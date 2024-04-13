package com.close.hook.ads.provider

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

/**
 * Author wangyu1
 * Data 2018/12/24
 * Description
 */
class BinderParcel() : Parcelable {
    @JvmField
    var mProxy: IBinder? = null

    constructor(parcel: Parcel) : this() {
        mProxy = parcel.readStrongBinder()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(arg0: Parcel, arg1: Int) {
        arg0.writeStrongBinder(mProxy)
    }

    companion object {
        val CREATOR: Parcelable.Creator<BinderParcel> = object : Parcelable.Creator<BinderParcel> {
            override fun createFromParcel(arg0: Parcel): BinderParcel {
                return readFromParcel(arg0)
            }

            override fun newArray(arg0: Int): Array<BinderParcel?> {
                return arrayOfNulls(arg0)
            }
        }

        fun readFromParcel(arg0: Parcel): BinderParcel {
            val bp = BinderParcel()
            bp.mProxy = arg0.readStrongBinder()
            return bp
        }
    }

    object CREATOR : Parcelable.Creator<BinderParcel> {
        override fun createFromParcel(parcel: Parcel): BinderParcel {
            return BinderParcel(parcel)
        }

        override fun newArray(size: Int): Array<BinderParcel?> {
            return arrayOfNulls(size)
        }
    }
}