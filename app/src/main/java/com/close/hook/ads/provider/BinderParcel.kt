package com.close.hook.ads.provider

import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable

class BinderParcel(var mProxy: IBinder? = null) : Parcelable {

    private constructor(parcel: Parcel) : this(parcel.readStrongBinder())

    override fun describeContents(): Int = 0

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeStrongBinder(mProxy)
    }

    companion object CREATOR : Parcelable.Creator<BinderParcel> {
        override fun createFromParcel(parcel: Parcel): BinderParcel = BinderParcel(parcel)

        override fun newArray(size: Int): Array<BinderParcel?> = arrayOfNulls(size)
    }
}
