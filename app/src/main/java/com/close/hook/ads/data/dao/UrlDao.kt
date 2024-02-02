package com.close.hook.ads.data.dao

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.close.hook.ads.data.model.Url

@Dao
interface UrlDao {

    @Insert
    fun insert(url: Url): Long

    @Query("SELECT * FROM url_info")
    fun findAll(): Cursor

    @Query("DELETE FROM url_info WHERE id = :id ")
    fun delete(id: Long): Int

    @Update
    fun update(url: Url): Int

    @Query("select * from url_info ORDER BY id DESC")
    fun loadAllList(): List<Url>

    @Query("SELECT 1 FROM url_info WHERE url = :url LIMIT 1")
    fun isExist(url: String): Boolean

    @Query("DELETE FROM url_info WHERE url = :url")
    fun delete(url: String)

    @Query("DELETE FROM url_info")
    fun deleteAll()

}
