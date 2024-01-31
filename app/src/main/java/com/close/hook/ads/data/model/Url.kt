package com.close.hook.ads.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "url_info")
data class Url(

    @PrimaryKey(autoGenerate = false)
    var id: Long,

    @ColumnInfo(name = "url")
    var url: String,

    ) {
    companion object {
        var URL_ID = "id"
        var URL_ADDRESS = "url"
    }
}
