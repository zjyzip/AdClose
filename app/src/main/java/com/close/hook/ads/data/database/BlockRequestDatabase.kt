package com.close.hook.ads.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.close.hook.ads.data.dao.BlockRequestDao
import com.close.hook.ads.data.model.BlockRequest

@Database(version = 1, entities = [BlockRequest::class])
abstract class BlockRequestDatabase : RoomDatabase() {
    abstract fun blockRequestDao(): BlockRequestDao

    companion object {
        private var instance: BlockRequestDatabase? = null

        @Synchronized
        fun getDatabase(context: Context): BlockRequestDatabase {
            instance?.let {
                return it
            }
            return Room.databaseBuilder(
                context.applicationContext,
                BlockRequestDatabase::class.java, "block_request_database"
            ).allowMainThreadQueries()
                .build().apply {
                    instance = this
                }
        }
    }
}