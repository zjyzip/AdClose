package com.close.hook.ads

import android.os.Parcel
import android.os.Parcelable

data class BlockedBean(
    val isBlocked: Boolean,
    val type: String?,
    val value: String?
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readString(),
        parcel.readString()
    ) {
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(if (isBlocked) 1 else 0)
        parcel.writeString(type)
        parcel.writeString(value)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<BlockedBean> {
        override fun createFromParcel(parcel: Parcel): BlockedBean {
            return BlockedBean(parcel)
        }

        override fun newArray(size: Int): Array<BlockedBean?> {
            return arrayOfNulls(size)
        }
    }
}
