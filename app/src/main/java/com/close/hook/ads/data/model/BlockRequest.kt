package com.close.hook.ads.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class BlockRequest(
    var url: String
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0
}