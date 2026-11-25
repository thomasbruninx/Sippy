package org.sippy

import android.app.Instrumentation
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal class HistoryCsvChecks(private val instrumentation: Instrumentation) {
    private fun <T> await(read: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        read { result = it; latch.countDown() }
        check(latch.await(10, TimeUnit.SECONDS)) { "Storage operation timed out" }
        return checkNotNull(result).getOrThrow()
    }

    fun run() {
        val entries = listOf(
            Entry("1", "Renée, \"Front desk\"\nFloor 2", "+32123456", Source.LOCAL),
            Entry("2", "", "00123", Source.LOCAL),
            Entry("3", "Desk", "desk@example.com", Source.LOCAL),
            Entry("4", "Phone only", "400", Source.PHONE),
            Entry("5", "Imported only", "500", Source.CSV),
            Entry("6", "Synced only", "600", Source.SYNC))
        val csv = localContactsCsv(entries)
        val decoded = parseCsv(csv.byteInputStream(Charsets.UTF_8), Source.CSV)
        check(decoded.map { it.name to it.number } == entries.take(3).map { it.name to it.number })
        check(localContactsCsv(emptyList()) == "name,number\r\n")
        check(!csv.contains("Phone only") && !csv.contains("Imported only") && !csv.contains("Synced only"))

        val context = instrumentation.targetContext
        val name = "history-check-${UUID.randomUUID()}.db"
        // Start from the previous database schema to verify an in-place upgrade.
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE history (id TEXT PRIMARY KEY, time INTEGER NOT NULL, incoming INTEGER NOT NULL, outcome TEXT NOT NULL, name TEXT NOT NULL, address TEXT NOT NULL, seconds INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL)")
            db.execSQL("INSERT INTO history VALUES ('old-call', 1, 1, 'MISSED', 'Test', '101', 0)")
            db.version = 1
        }
        val store = ActivityStore(context, name)
        try {
            check(await<List<HistoryRecord>> { store.history(100, it) }.single().id == "old-call")
            store.event(EventKind.INFO, "Keep this diagnostic event")
            await<Unit> { store.clearHistory(it) }
            check(await<List<HistoryRecord>> { store.history(100, it) }.isEmpty())
            store.record(HistoryRecord("old-call", 1, true, CallOutcome.MISSED, "Test", "101", 0))
            check(await<List<HistoryRecord>> { store.history(100, it) }.isEmpty())
            store.record(HistoryRecord("new-call", 2, false, CallOutcome.ANSWERED, "New call", "102", 20))
            check(await<List<HistoryRecord>> { store.history(100, it) }.single().id == "new-call")
            check(await<List<EventRecord>> { store.events(null, 100, it) }.single().message == "Keep this diagnostic event")
        } finally {
            val helper = ActivityStore::class.java.getDeclaredField("db").apply { isAccessible = true }.get(store) as SQLiteOpenHelper
            val executor = ActivityStore::class.java.getDeclaredField("executor").apply { isAccessible = true }.get(store) as ExecutorService
            executor.submit { helper.close() }.get(5, TimeUnit.SECONDS)
            executor.shutdown()
            context.deleteDatabase(name)
        }
    }
}
