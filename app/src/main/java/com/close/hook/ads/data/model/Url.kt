package com.close.hook.ads.data.model

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "url_info",
    indices = [
        Index(value = ["url"]),
        Index(value = ["type"])
    ]
)
data class Url(
    @ColumnInfo(name = "type") // domain, url, keyword
    var type: String,

    @ColumnInfo(name = "url")
    var url: String

) : Parcelable {

    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    ) {
        id = parcel.readLong()
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(type)
        dest.writeString(url)
        dest.writeLong(id)
    }

    companion object {
        const val URL_TYPE = "type"
        const val URL_ADDRESS = "url"

        @JvmField
        val CREATOR = object : Parcelable.Creator<Url> {
            override fun createFromParcel(parcel: Parcel): Url {
                return Url(parcel)
            }

            override fun newArray(size: Int): Array<Url?> {
                return arrayOfNulls(size)
            }
        }
    }
}
