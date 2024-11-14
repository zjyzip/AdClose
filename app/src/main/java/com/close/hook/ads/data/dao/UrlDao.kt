package com.close.hook.ads.data.dao

import android.database.Cursor
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.close.hook.ads.data.model.Url
import kotlinx.coroutines.flow.Flow

@Dao
interface UrlDao {

    @Insert
    fun insert(url: Url): Long

    @Query("SELECT * FROM url_info")
    fun findAll(): Cursor

    @Query("DELETE FROM url_info WHERE id = :id")
    fun deleteById(id: Long): Int

    @Update
    fun update(url: Url): Int

    @Insert
    fun insertAll(urls: List<Url>): List<Long>

    @Query("SELECT * FROM url_info ORDER BY id DESC")
    fun loadAllList(): Flow<List<Url>>

    @Query("SELECT * FROM url_info WHERE url LIKE '%' || :searchText || '%' ORDER BY id DESC")
    fun searchUrls(searchText: String): Flow<List<Url>>

    @Query("SELECT * FROM url_info WHERE url = :url LIMIT 1")
    fun findExactMatchCursor(url: String): Cursor

    @Query("SELECT * FROM url_info WHERE :url LIKE '%' || url || '%' LIMIT 1")
    fun findPartialMatchCursor(url: String): Cursor

    @Query("SELECT COUNT(*) FROM url_info WHERE url = :url LIMIT 1")
    fun isExist(url: String): Boolean

    @Query("SELECT COUNT(*) FROM url_info WHERE type = :type AND url = :url LIMIT 1")
    fun isExist(type: String, url: String): Boolean

    @Delete
    fun deleteList(list: List<Url>): Int

    @Delete
    fun deleteUrl(url: Url): Int

    @Query("DELETE FROM url_info WHERE type = :type AND url = :url")
    fun deleteUrlString(type: String, url: String): Int

    @Query("DELETE FROM url_info")
    fun deleteAll(): Int
}
