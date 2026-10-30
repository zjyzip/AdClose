package com.close.hook.ads.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.close.hook.ads.data.dao.SubscriptionRuleDao
import com.close.hook.ads.data.dao.SubscriptionSourceDao
import com.close.hook.ads.data.dao.UrlDao
import com.close.hook.ads.data.model.SubscriptionRule
import com.close.hook.ads.data.model.SubscriptionSource
import com.close.hook.ads.data.model.Url

@Database(entities = [Url::class, SubscriptionSource::class, SubscriptionRule::class], version = 7, exportSchema = false)
abstract class UrlDatabase : RoomDatabase() {
    abstract val urlDao: UrlDao
    abstract val subscriptionSourceDao: SubscriptionSourceDao
    abstract val subscriptionRuleDao: SubscriptionRuleDao

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

        private val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_info_url ON url_info(url)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_info_type ON url_info(type)")
            }
        }

        private val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_url_info_type_url ON url_info(type, url)")
            }
        }

        private val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS subscription_source (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "url TEXT NOT NULL, name TEXT NOT NULL, enabled INTEGER NOT NULL, " +
                        "refreshIntervalMinutes INTEGER NOT NULL, etag TEXT, lastModified TEXT, " +
                        "lastAttemptAt INTEGER NOT NULL, lastSuccessAt INTEGER NOT NULL, " +
                        "nextRefreshAt INTEGER NOT NULL, refreshLeaseUntil INTEGER NOT NULL, " +
                        "ruleCount INTEGER NOT NULL, skippedCount INTEGER NOT NULL, lastError TEXT)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_subscription_source_url ON subscription_source(url)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS subscription_rule (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceId INTEGER NOT NULL, " +
                        "type TEXT NOT NULL, value TEXT NOT NULL, " +
                        "FOREIGN KEY(sourceId) REFERENCES subscription_source(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_rule_sourceId ON subscription_rule(sourceId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_subscription_rule_sourceId_type_value " +
                        "ON subscription_rule(sourceId, type, value)"
                )
            }
        }

        private val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove duplicates before creating the unique index required by the entity.
                db.execSQL(
                    "UPDATE url_info SET type = CASE lower(type) " +
                        "WHEN 'url' THEN 'URL' WHEN 'domain' THEN 'Domain' " +
                        "WHEN 'keyword' THEN 'KeyWord' ELSE type END"
                )
                db.execSQL(
                    "DELETE FROM url_info WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM url_info GROUP BY type, url)"
                )
                db.execSQL("DROP INDEX IF EXISTS index_url_info_type_url")
                db.execSQL(
                    "CREATE UNIQUE INDEX index_url_info_type_url " +
                        "ON url_info(type, url)"
                )
            }
        }

        fun getDatabase(context: Context): UrlDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    UrlDatabase::class.java,
                    "url_database"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7
                )
                .build().also {
                    instance = it
                }
            }
    }
}
