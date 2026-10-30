package com.tyverse.smswebhook

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyverse.smswebhook.data.*
import com.tyverse.smswebhook.delivery.DeliveryScheduler
import com.tyverse.smswebhook.esim.*
import com.tyverse.smswebhook.security.CryptoStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EsimRepositoryTest {
    private fun repositoryTest(block: suspend (EsimRepository, RelayDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, RelayDatabase::class.java).build()
        val file = File(context.cacheDir, "esim-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val preferences = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        val crypto = CryptoStore()
        val notifications = RelayNotifications(context).apply { createChannel() }
        val scheduler = EsimScheduler(context)
        val repository = EsimRepository(db, crypto, SettingsStore(context, crypto, preferences), scheduler,
            DeliveryScheduler(context), notifications)
        try { block(repository, db) }
        finally {
            for (card in db.cards().all()) { scheduler.cancel(card.id); notifications.cancelCard(card.id) }
            job.cancelAndJoin(); db.close(); file.delete()
        }
    }

    @Test fun cardSettingsAreIndependentAndNotesAreEncrypted() = repositoryTest { repo, db ->
        repo.save(EsimDraft(name = "A", notes = "私密备注\n第二行😀", periodCount = 30, reminderEnabled = false))
        repo.save(EsimDraft(name = "B", periodCount = 3, periodUnit = "MONTHS", leadMinutes = 4320,
            repeatMinutes = 1440, reminderEnabled = false))
        val cards = repo.cards.first()
        val a = cards.first { it.card.name == "A" }; val b = cards.first { it.card.name == "B" }
        assertFalse(a.card.infoCipher.contains("私密备注"))
        assertEquals("私密备注\n第二行😀", a.info.notes)
        val before = System.currentTimeMillis()
        val renewed = repo.renew(a.card.id, a.card.revision)
        val after = System.currentTimeMillis()
        assertTrue(renewed.expiresAt in EsimPolicy.renewAt(before, 30, "DAYS", a.card.zoneId)..EsimPolicy.renewAt(after, 30, "DAYS", a.card.zoneId))
        assertFalse(renewed.reminderEnabled)
        assertEquals(b.card, db.cards().get(b.card.id))
        assertEquals(a.info, repo.cards.first().first { it.card.id == a.card.id }.info)
    }

    @Test fun staleOrConcurrentRenewalCannotApplyTwice() = repositoryTest { repo, db ->
        repo.save(EsimDraft(name = "A", reminderEnabled = false))
        val card = db.cards().all().single()
        val attempts = coroutineScope { (1..8).map { async(Dispatchers.IO) { runCatching { repo.renew(card.id, card.revision) }.isSuccess } }.awaitAll() }
        assertEquals(1, attempts.count { it })
        assertEquals(card.revision + 1, db.cards().get(card.id)!!.revision)
    }

    @Test fun repeatedRecoveryAndNotesEditDoNotDuplicateReminder() = repositoryTest { repo, db ->
        repo.save(EsimDraft(name = "到期卡", expiresAt = System.currentTimeMillis() - 86_400_000, leadMinutes = 0))
        val card = repo.cards.first().single()
        repeat(5) { repo.recover() }
        assertEquals(1, db.cards().observeEvents().first().size)
        val original = db.cards().latest(card.card.id, card.card.ruleVersion)!!
        repo.save(EsimDraft.from(card).copy(notes = "仅修改备注"))
        assertEquals(card.card.ruleVersion, db.cards().get(card.card.id)!!.ruleVersion)
        assertEquals(original.id, db.cards().observeEvents().first().single().id)
    }

    @Test fun missedRepeatIntervalsCoalesceAndRenewalInvalidatesOldEvent() = repositoryTest { repo, db ->
        repo.save(EsimDraft(name = "过期卡", expiresAt = System.currentTimeMillis() - 10 * 86_400_000L,
            leadMinutes = 0, repeatMinutes = 1440))
        val card = db.cards().all().single()
        assertEquals(1, db.cards().observeEvents().first().size)
        assertTrue(card.nextReminderAt!! > System.currentTimeMillis())
        val event = db.cards().latest(card.id, card.ruleVersion)!!
        val message = RelayMessage(event.id, "test-esim-${event.id}", "eSIM", "", "", "", 1, 1, null,
            EsimPolicy.SOURCE, cardId = card.id, cardRuleVersion = card.ruleVersion)
        db.messages().insert(message)
        repo.renew(card.id, card.revision)
        assertEquals(DeliveryState.CANCELLED, db.messages().get(event.id)!!.state)
        assertFalse(repo.isActive(message))
        assertEquals(card.ruleVersion + 1, db.cards().get(card.id)!!.ruleVersion)
    }

    @Test fun smsPauseDoesNotPauseCardDeliveries() = repositoryTest { _, db ->
        val base = RelayMessage("sms", "sms", "sender", "", "", "", 1, 1, null, "sms_broadcast")
        db.messages().insert(base)
        db.messages().insert(base.copy(id = "esim", fingerprint = "esim", source = EsimPolicy.SOURCE))
        db.messages().pauseSms()
        assertEquals(DeliveryState.PAUSED, db.messages().get("sms")!!.state)
        assertEquals(DeliveryState.PENDING, db.messages().get("esim")!!.state)
    }

    @Test fun invalidInputAndStaleEditPreserveStoredCard() = repositoryTest { repo, db ->
        assertTrue(runCatching { repo.save(EsimDraft(name = " ")) }.isFailure)
        assertTrue(db.cards().all().isEmpty())
        repo.save(EsimDraft(name = "A", reminderEnabled = false))
        val view = repo.cards.first().single()
        repo.save(EsimDraft.from(view).copy(notes = "新备注"))
        assertTrue(runCatching { repo.save(EsimDraft.from(view).copy(notes = "过时备注")) }.isFailure)
        assertEquals("新备注", repo.cards.first().single().info.notes)
    }
}
