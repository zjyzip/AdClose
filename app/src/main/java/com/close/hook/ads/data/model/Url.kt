package com.close.hook.ads.data.model

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
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
    var url: String,

    @PrimaryKey(autoGenerate = true)
    var id: Long = 0L
) : Parcelable {
    companion object {
        const val URL_TYPE = "type"
        const val URL_ADDRESS = "url"
    }
}
