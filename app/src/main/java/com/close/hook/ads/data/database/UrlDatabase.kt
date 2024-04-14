package com.close.hook.ads.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.model.Url

@Database(entities = [Url::class], version = 3, exportSchema = false)
abstract class UrlDatabase : RoomDatabase() {
    abstract val urlDao: UrlDao

    companion object {
        @Volatile
        private var instance: UrlDatabase? = null

        private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE url_info_new (id INTEGER NOT NULL, url TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("INSERT INTO url_info_new (id, url) SELECT id, url FROM url_info")
                db.execSQL("DROP TABLE url_info")
                db.execSQL("ALTER TABLE url_info_new RENAME TO url_info")
            }
        }

        private val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE url_info ADD COLUMN type TEXT NOT NULL DEFAULT 'url'")
            }
        }

        fun getDatabase(context: Context): UrlDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UrlDatabase::class.java,
                    "url_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also {
                    instance = it
                }
            }
    }
}
