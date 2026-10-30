package com.tyverse.smswebhook.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import com.tyverse.smswebhook.esim.EsimCard
import com.tyverse.smswebhook.esim.EsimDao
import com.tyverse.smswebhook.esim.EsimReminderEvent

object DeliveryState {
    const val PENDING = "PENDING"
    const val IN_FLIGHT = "IN_FLIGHT"
    const val RETRY_WAIT = "RETRY_WAIT"
    const val SUCCEEDED = "SUCCEEDED"
    const val NEEDS_ATTENTION = "NEEDS_ATTENTION"
    const val PAUSED = "PAUSED"
    const val CANCELLED = "CANCELLED"
}

@Entity(tableName = "messages", indices = [Index(value = ["fingerprint"], unique = true),
    Index(value = ["state", "nextAttemptAt"])])
data class RelayMessage(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val sender: String,
    val bodyCipher: String,
    val payloadCipher: String,
    val endpointCipher: String,
    val receivedAt: Long,
    val smsTimestamp: Long,
    val subscriptionId: Int?,
    val source: String,
    val retryWindowStartedAt: Long = receivedAt,
    val state: String = DeliveryState.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val leaseToken: String? = null,
    val leaseUntil: Long = 0,
    val leaseElapsedUntil: Long = 0,
    val leaseBoot: Int = 0,
    val lastAttemptAt: Long = 0,
    val lastHttpStatus: Int? = null,
    val lastError: String = "",
    @ColumnInfo(defaultValue = "0") val nextPartIndex: Int = 0,
    val lastBusinessCode: Int? = null,
    val cardId: String? = null,
    val cardRuleVersion: Long? = null,
)

data class QueueStats(val total: Int = 0, val succeeded: Int = 0, val pending: Int = 0,
    val failed: Int = 0, val paused: Int = 0)
data class ScheduledMessage(val id: String, val nextAttemptAt: Long, val source: String)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: RelayMessage): Long

    @Query("SELECT * FROM messages ORDER BY receivedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<RelayMessage>>

    @Query("""SELECT COUNT(*) AS total,
        COALESCE(SUM(state = 'SUCCEEDED' AND lastBusinessCode = 0), 0) AS succeeded,
        COALESCE(SUM(state IN ('PENDING','RETRY_WAIT','IN_FLIGHT')), 0) AS pending,
        COALESCE(SUM(state = 'NEEDS_ATTENTION'), 0) AS failed,
        COALESCE(SUM(state = 'PAUSED'), 0) AS paused FROM messages""")
    fun observeStats(): Flow<QueueStats>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): RelayMessage?

    @Query("SELECT id FROM messages WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findFingerprint(fingerprint: String): String?

    @Query("SELECT COUNT(*) FROM messages WHERE state NOT IN ('SUCCEEDED','CANCELLED')")
    suspend fun outstandingCount(): Int

    @Query("SELECT id, nextAttemptAt, source FROM messages WHERE state IN ('PENDING','RETRY_WAIT')")
    suspend fun schedulable(): List<ScheduledMessage>

    @Query("""UPDATE messages SET state='IN_FLIGHT', attempts=attempts+1,
        leaseToken=:token, leaseUntil=:until, leaseElapsedUntil=:elapsedUntil,
        leaseBoot=:boot, lastAttemptAt=:now
        WHERE id=:id AND state IN ('PENDING','RETRY_WAIT') AND nextAttemptAt<=:now""")
    suspend fun claim(id: String, token: String, now: Long, until: Long,
        elapsedUntil: Long, boot: Int): Int

    @Query("""UPDATE messages SET state=:state, lastHttpStatus=:httpStatus,
        lastError=:error, nextAttemptAt=:nextAt, leaseToken=NULL, leaseUntil=0,
        leaseElapsedUntil=0 WHERE id=:id AND state='IN_FLIGHT' AND leaseToken=:token""")
    suspend fun finish(id: String, token: String, state: String, httpStatus: Int?,
        error: String, nextAt: Long): Int

    @Query("""UPDATE messages SET nextPartIndex=:nextPart, lastHttpStatus=200, lastBusinessCode=0,
        leaseUntil=:until, leaseElapsedUntil=:elapsedUntil
        WHERE id=:id AND state='IN_FLIGHT' AND leaseToken=:token AND nextPartIndex=:expectedPart""")
    suspend fun acknowledgePart(id: String, token: String, expectedPart: Int, nextPart: Int,
        until: Long, elapsedUntil: Long): Int

    @Query("""UPDATE messages SET lastBusinessCode=:code
        WHERE id=:id AND state='IN_FLIGHT' AND leaseToken=:token""")
    suspend fun setBusinessCode(id: String, token: String, code: Int?): Int

    @Query("UPDATE messages SET payloadCipher=:updated WHERE id=:id AND payloadCipher=:expected")
    suspend fun upgradePayload(id: String, expected: String, updated: String): Int

    @Query("""UPDATE messages SET state='RETRY_WAIT', leaseToken=NULL, leaseUntil=0,
        leaseElapsedUntil=0, nextAttemptAt=:now, lastError='上次执行中断，等待恢复'
        WHERE state='IN_FLIGHT' AND (leaseUntil<=:now OR leaseUntil>:maxUntil
        OR leaseBoot!=:boot OR leaseElapsedUntil<=:elapsed)""")
    suspend fun recoverLeases(now: Long, maxUntil: Long, elapsed: Long, boot: Int): Int

    @Query("""UPDATE messages SET state='PAUSED', leaseToken=NULL, leaseUntil=0,
        leaseElapsedUntil=0 WHERE source='sms_broadcast' AND state IN ('PENDING','RETRY_WAIT','IN_FLIGHT')""")
    suspend fun pauseSms(): Int

    @Query("UPDATE messages SET state='PENDING', nextAttemptAt=0 WHERE state='PAUSED' AND source='sms_broadcast'")
    suspend fun resumeSms(): Int

    @Query("""UPDATE messages SET state='PENDING', attempts=0, nextAttemptAt=0,
        retryWindowStartedAt=:now, lastError='', lastHttpStatus=NULL, lastBusinessCode=NULL
        WHERE id=:id AND state IN ('NEEDS_ATTENTION','RETRY_WAIT')""")
    suspend fun retry(id: String, now: Long): Int

    @Query("""UPDATE messages SET endpointCipher=:endpointCipher,
        nextPartIndex=CASE WHEN :resetProgress THEN 0 ELSE nextPartIndex END WHERE id=:id
        AND state IN ('NEEDS_ATTENTION','RETRY_WAIT')""")
    suspend fun replaceEndpoint(id: String, endpointCipher: String, resetProgress: Boolean): Int

    @Query("""UPDATE messages SET state='NEEDS_ATTENTION', lastError='自动投递期限已到，请手动处理'
        WHERE state IN ('PENDING','RETRY_WAIT') AND retryWindowStartedAt<=:before""")
    suspend fun expireOutstanding(before: Long): Int

    @Query("DELETE FROM messages WHERE (state='SUCCEEDED' AND lastAttemptAt<:before) OR (state='CANCELLED' AND receivedAt<:before)")
    suspend fun deleteOldCompleted(before: Long): Int

    @Query("""UPDATE messages SET bodyCipher='', payloadCipher='', endpointCipher=''
        WHERE state='SUCCEEDED' AND lastAttemptAt<:before AND bodyCipher!=''""")
    suspend fun redactCompleted(before: Long): Int

    @Query("DELETE FROM messages WHERE state='SUCCEEDED'")
    suspend fun clearCompleted(): Int

    @Query("""UPDATE messages SET state='CANCELLED', leaseToken=NULL, leaseUntil=0,
        leaseElapsedUntil=0, lastError='卡片周期已更新或提醒已关闭'
        WHERE cardId=:cardId AND state IN ('PENDING','RETRY_WAIT','IN_FLIGHT','NEEDS_ATTENTION')""")
    suspend fun cancelCard(cardId: String)
}

@Database(entities = [RelayMessage::class, EsimCard::class, EsimReminderEvent::class], version = 3, exportSchema = true)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun cards(): EsimDao
    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN cardId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN cardRuleVersion INTEGER")
                db.execSQL("""CREATE TABLE IF NOT EXISTS esim_cards (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, infoCipher TEXT NOT NULL,
                    expiresAt INTEGER NOT NULL, zoneId TEXT NOT NULL, periodCount INTEGER NOT NULL,
                    periodUnit TEXT NOT NULL, reminderEnabled INTEGER NOT NULL, leadMinutes INTEGER NOT NULL,
                    repeatMinutes INTEGER NOT NULL, localEnabled INTEGER NOT NULL, wecomEnabled INTEGER NOT NULL,
                    nextReminderAt INTEGER, revision INTEGER NOT NULL, ruleVersion INTEGER NOT NULL,
                    cycleStartedAt INTEGER, lastRenewedAt INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS esim_reminder_events (
                    id TEXT NOT NULL PRIMARY KEY, cardId TEXT NOT NULL, ruleVersion INTEGER NOT NULL,
                    scheduledAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, localState TEXT NOT NULL,
                    wecomEnabled INTEGER NOT NULL)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_esim_reminder_events_cardId_ruleVersion_scheduledAt ON esim_reminder_events(cardId, ruleVersion, scheduledAt)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN nextPartIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN lastBusinessCode INTEGER")
            }
        }
    }
}
