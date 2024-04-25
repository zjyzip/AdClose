package com.close.hook.ads.data.model

import android.os.Parcel
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "url_info",
    indices = [
        Index(value = ["url"]),
        Index(value = ["type"])
    ]
)
data class Url(

    @ColumnInfo(name = "type") // domain url keyword
    var type: String,

    @ColumnInfo(name = "url")
    var url: String

) : Parcelable {

    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    companion object {
        var URL_TYPE = "type"
        var URL_ADDRESS = "url"
    }

    override fun describeContents(): Int {
        TODO("Not yet implemented")
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        TODO("Not yet implemented")
    }
}
