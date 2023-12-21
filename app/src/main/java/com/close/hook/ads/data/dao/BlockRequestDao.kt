package com.close.hook.ads.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.close.hook.ads.data.model.BlockRequest


@Dao
interface BlockRequestDao {

    @Insert
    fun insert(url: BlockRequest)

    @Query("select * from BlockRequest ORDER BY id DESC")
    fun loadAllList(): List<BlockRequest>

    @Query("SELECT 1 FROM BlockRequest WHERE url = :url LIMIT 1")
    fun isExist(url: String): Boolean

    @Query("DELETE FROM BlockRequest WHERE url = :url")
    fun delete(url: String)

    @Query("DELETE FROM BlockRequest")
    fun deleteAll()

}