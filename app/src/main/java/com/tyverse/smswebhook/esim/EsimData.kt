package com.tyverse.smswebhook.esim

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "esim_cards")
data class EsimCard(
    @PrimaryKey val id: String,
    val name: String,
    val infoCipher: String,
    val expiresAt: Long,
    val zoneId: String,
    val periodCount: Int,
    val periodUnit: String,
    val reminderEnabled: Boolean,
    val leadMinutes: Long,
    val repeatMinutes: Long,
    val localEnabled: Boolean,
    val wecomEnabled: Boolean,
    val nextReminderAt: Long?,
    val revision: Long,
    val ruleVersion: Long,
    val cycleStartedAt: Long?,
    val lastRenewedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "esim_reminder_events", indices = [Index(value = ["cardId", "ruleVersion", "scheduledAt"], unique = true)])
data class EsimReminderEvent(
    @PrimaryKey val id: String,
    val cardId: String,
    val ruleVersion: Long,
    val scheduledAt: Long,
    val createdAt: Long,
    val localState: String,
    val wecomEnabled: Boolean,
)

data class EsimInfo(val provider: String = "", val identifier: String = "", val notes: String = "")
data class EsimCardView(val card: EsimCard, val info: EsimInfo)
data class EsimDeliveryView(@Embedded val event: EsimReminderEvent, val remoteState: String?, val remoteError: String?)
data class EsimDraft(
    val id: String? = null,
    val revision: Long? = null,
    val name: String = "",
    val provider: String = "",
    val identifier: String = "",
    val notes: String = "",
    val expiresAt: Long = System.currentTimeMillis() / 60_000 * 60_000 + 30 * 86_400_000L,
    val zoneId: String = java.time.ZoneId.systemDefault().id,
    val periodCount: Int = 30,
    val periodUnit: String = "DAYS",
    val reminderEnabled: Boolean = true,
    val leadMinutes: Long = 1440,
    val repeatMinutes: Long = 0,
    val localEnabled: Boolean = true,
    val wecomEnabled: Boolean = false,
) {
    companion object {
        fun from(view: EsimCardView) = with(view.card) {
            EsimDraft(id, revision, name, view.info.provider, view.info.identifier, view.info.notes,
                expiresAt, zoneId, periodCount, periodUnit, reminderEnabled, leadMinutes,
                repeatMinutes, localEnabled, wecomEnabled)
        }
    }
}

@Dao
interface EsimDao {
    @Query("SELECT * FROM esim_cards ORDER BY expiresAt, createdAt, id")
    fun observeCards(): Flow<List<EsimCard>>
    @Query("SELECT * FROM esim_cards ORDER BY expiresAt, createdAt, id")
    suspend fun all(): List<EsimCard>
    @Query("SELECT * FROM esim_cards WHERE id=:id")
    suspend fun get(id: String): EsimCard?
    @Insert suspend fun insert(card: EsimCard)
    @Update suspend fun update(card: EsimCard): Int
    @Query("DELETE FROM esim_cards WHERE id=:id") suspend fun delete(id: String)
    @Insert suspend fun insertEvent(event: EsimReminderEvent)
    @Query("SELECT * FROM esim_reminder_events WHERE cardId=:cardId AND ruleVersion=:version ORDER BY scheduledAt DESC LIMIT 1")
    suspend fun latest(cardId: String, version: Long): EsimReminderEvent?
    @Query("SELECT * FROM esim_reminder_events ORDER BY createdAt DESC")
    fun observeEvents(): Flow<List<EsimReminderEvent>>
    @Query("""SELECT e.*, m.state AS remoteState, m.lastError AS remoteError FROM esim_reminder_events e
        LEFT JOIN messages m ON m.id=e.id
        WHERE NOT EXISTS (SELECT 1 FROM esim_reminder_events newer WHERE newer.cardId=e.cardId
            AND (newer.ruleVersion>e.ruleVersion OR (newer.ruleVersion=e.ruleVersion AND newer.scheduledAt>e.scheduledAt)))""")
    fun observeResults(): Flow<List<EsimDeliveryView>>
    @Query("UPDATE esim_reminder_events SET localState=:state WHERE id=:id")
    suspend fun setLocalState(id: String, state: String)
    @Query("UPDATE esim_reminder_events SET localState='CANCELLED' WHERE cardId=:id AND localState IN ('PENDING','BLOCKED')")
    suspend fun cancelEvents(id: String)
    @Query("DELETE FROM esim_reminder_events WHERE cardId=:id") suspend fun deleteEvents(id: String)
}
