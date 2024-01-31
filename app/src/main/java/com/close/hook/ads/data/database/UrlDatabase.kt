package com.close.hook.ads.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room.databaseBuilder
import androidx.room.RoomDatabase
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.model.Url

@Database(entities = [Url::class], version = 1, exportSchema = false)
abstract class UrlDatabase : RoomDatabase() {
    abstract val urlDao: UrlDao

    companion object {
        private var instance: UrlDatabase? = null

        @Synchronized
        fun getDatabase(context: Context): UrlDatabase {
            instance?.let {
                return it
            }
            return databaseBuilder(
                context.applicationContext,
                UrlDatabase::class.java, "url_database"
            ).allowMainThreadQueries()
                .build().apply {
                    instance = this
                }
        }
    }
}
