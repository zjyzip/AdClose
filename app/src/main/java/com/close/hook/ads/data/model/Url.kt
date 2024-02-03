package com.close.hook.ads.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "url_info")
data class Url(

    @ColumnInfo(name = "type") // host url keyword
    var type: String,

    @ColumnInfo(name = "url")
    var url: String

) {

    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    companion object {
        var URL_TYPE = "type"
        var URL_ADDRESS = "url"
    }
}
