package com.close.hook.ads.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.close.hook.ads.data.model.SubscriptionSource
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionSourceDao {

    @Query("SELECT * FROM subscription_source ORDER BY id DESC")
    fun observeAll(): Flow<List<SubscriptionSource>>

    @Query("SELECT * FROM subscription_source WHERE id = :id")
    suspend fun findById(id: Long): SubscriptionSource?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: SubscriptionSource): Long

    @Update
    suspend fun update(source: SubscriptionSource): Int

    @Delete
    suspend fun delete(source: SubscriptionSource): Int

    @Query(
        "UPDATE subscription_source SET refreshLeaseUntil = :leaseUntil, lastAttemptAt = :now " +
            "WHERE id = :id AND enabled = 1 AND refreshLeaseUntil <= :now"
    )
    suspend fun claimRefresh(id: Long, now: Long, leaseUntil: Long): Int

    @Query(
        "SELECT id FROM subscription_source WHERE enabled = 1 AND nextRefreshAt <= :now " +
            "AND refreshLeaseUntil <= :now"
    )
    suspend fun findDueIds(now: Long): List<Long>

    @Query(
        "UPDATE subscription_source SET etag = :etag, lastModified = :lastModified, " +
            "lastSuccessAt = :now, nextRefreshAt = :nextRefreshAt, refreshLeaseUntil = 0, " +
            "ruleCount = :ruleCount, skippedCount = :skippedCount, lastError = NULL " +
            "WHERE id = :id AND url = :expectedUrl AND enabled = 1"
    )
    suspend fun markRefreshSuccess(
        id: Long,
        etag: String?,
        lastModified: String?,
        now: Long,
        nextRefreshAt: Long,
        ruleCount: Int,
        skippedCount: Int,
        expectedUrl: String
    ): Int

    @Query(
        "UPDATE subscription_source SET lastSuccessAt = :now, nextRefreshAt = :nextRefreshAt, " +
            "refreshLeaseUntil = 0, lastError = NULL " +
            "WHERE id = :id AND url = :expectedUrl AND enabled = 1"
    )
    suspend fun markNotModified(id: Long, now: Long, nextRefreshAt: Long, expectedUrl: String): Int

    @Query(
        "UPDATE subscription_source SET nextRefreshAt = :nextRefreshAt, refreshLeaseUntil = 0, " +
            "lastError = :error WHERE id = :id AND url = :expectedUrl"
    )
    suspend fun markRefreshFailure(id: Long, error: String, nextRefreshAt: Long, expectedUrl: String): Int
}
