package org.sippy

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal enum class EventKind(val label: String) {
    INFO("Info"), WARNING("Warning"), ERROR("Error"), SIP("SIP")
}

internal enum class CallOutcome(val label: String) {
    ANSWERED("Answered"), MISSED("Missed"), DECLINED("Declined"), UNANSWERED("Unanswered"),
    FAILED("Failed"), ELSEWHERE("Answered elsewhere"), DECLINED_ELSEWHERE("Declined elsewhere")
}

internal data class EventRecord(val time: Long, val kind: EventKind, val message: String)
internal data class HistoryRecord(
    val id: String, val time: Long, val incoming: Boolean, val outcome: CallOutcome,
    val name: String, val address: String, val seconds: Int
)

internal class ActivityStore(private val context: Context, databaseName: String = "activity.db") {
    private val db = object : SQLiteOpenHelper(context, databaseName, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL)")
            db.execSQL("CREATE INDEX event_time ON events(time)")
            db.execSQL("CREATE INDEX event_kind_time ON events(kind, time)")
            db.execSQL("""CREATE TABLE history (id TEXT PRIMARY KEY, time INTEGER NOT NULL, incoming INTEGER NOT NULL,
                outcome TEXT NOT NULL, name TEXT NOT NULL, address TEXT NOT NULL, seconds INTEGER NOT NULL)""")
            db.execSQL("CREATE INDEX history_time ON history(time)")
            db.execSQL("CREATE TABLE cleared_history (id TEXT PRIMARY KEY)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS cleared_history (id TEXT PRIMARY KEY)")
        }
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = Preferences(context).data

    val retentionDays get() = prefs.getInt("eventRetentionDays", 3).coerceIn(1, 30)

    fun setRetention(days: Int) {
        require(days in 1..30)
        prefs.edit().putInt("eventRetentionDays", days).apply()
        prune()
    }

    fun event(kind: EventKind, message: String, secret: String = "") {
        val record = EventRecord(System.currentTimeMillis(), kind, sanitizeLog(message, secret))
        submit {
            val writable = db.writableDatabase
            trim(writable)
            writable.insertOrThrow("events", null, ContentValues().apply {
                put("time", record.time)
                put("kind", record.kind.name)
                put("message", record.message)
            })
        }
    }

    fun record(call: HistoryRecord) = submit {
        // Linphone can replay its saved call logs after reconnecting. Never restore
        // records the user has explicitly cleared from Sippy's history.
        db.readableDatabase.rawQuery("SELECT 1 FROM cleared_history WHERE id = ?", arrayOf(call.id)).use {
            if (it.moveToFirst()) return@submit
        }
        db.writableDatabase.replaceOrThrow("history", null, ContentValues().apply {
            put("id", call.id)
            put("time", call.time)
            put("incoming", if (call.incoming) 1 else 0)
            put("outcome", call.outcome.name)
            put("name", call.name)
            put("address", call.address)
            put("seconds", call.seconds)
        })
    }

    fun clearHistory(result: (Result<Unit>) -> Unit) = read(result) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL("INSERT OR IGNORE INTO cleared_history (id) SELECT id FROM history")
            database.delete("history", null, null)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    fun events(filter: EventKind?, limit: Int, result: (Result<List<EventRecord>>) -> Unit) = read(result) {
        val writable = db.writableDatabase
        trim(writable)
        writable.query("events", arrayOf("time", "kind", "message"),
            filter?.let { "kind = ?" }, filter?.let { arrayOf(it.name) }, null, null, "time DESC, id DESC", limit.toString()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(EventRecord(cursor.getLong(0), EventKind.valueOf(cursor.getString(1)), cursor.getString(2)))
            }
        }
    }

    fun history(limit: Int, result: (Result<List<HistoryRecord>>) -> Unit) = read(result) {
        db.readableDatabase.query("history", arrayOf("id", "time", "incoming", "outcome", "name", "address", "seconds"),
            null, null, null, null, "time DESC, rowid DESC", limit.toString()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(HistoryRecord(cursor.getString(0), cursor.getLong(1), cursor.getInt(2) == 1,
                    CallOutcome.valueOf(cursor.getString(3)), cursor.getString(4), cursor.getString(5), cursor.getInt(6)))
            }
        }
    }

    fun prune(done: (() -> Unit)? = null) = submit {
        try { trim(db.writableDatabase) }
        finally { if (done != null) context.mainExecutor.execute(done) }
    }

    fun schedulePruning() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(9392) != null) return
        scheduler.schedule(JobInfo.Builder(9392, ComponentName(context, EventPruneJob::class.java))
            .setPersisted(true).setPeriodic(TimeUnit.DAYS.toMillis(1)).build())
    }

    private fun trim(database: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        database.delete("events", "time < ?", arrayOf(cutoff.toString()))
    }

    private fun submit(action: () -> Unit) = executor.execute {
        runCatching(action).onFailure { android.util.Log.w("Sippy", "Activity storage update failed.") }
    }

    private fun <T> read(result: (Result<T>) -> Unit, query: () -> T) = executor.execute {
        val records = runCatching(query)
        context.mainExecutor.execute { result(records) }
    }

    companion object {
        private var instance: ActivityStore? = null
        @Synchronized fun get(context: Context): ActivityStore =
            instance ?: ActivityStore(context.applicationContext).also { instance = it }
    }
}

class EventPruneJob : JobService() {
    private var active: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        active = params
        ActivityStore.get(this).prune {
            if (active === params) {
                active = null
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) active = null
        return true
    }
}

private val sipRequest = Regex("^[A-Z][A-Z0-9-]*\\s+\\S+\\s+SIP/2\\.0$")
private val sipResponse = Regex("^SIP/2\\.0\\s+[0-9]{3}(?:\\s+.*)?$")
private val uriPassword = Regex("(?i)(sips?:[^\\s<>:@]+):[^\\s<>@]+@")
private val sensitiveText = Regex("(?i)\\b(?:proxy-authorization|authorization|proxy-authenticate|www-authenticate|password|passwd|ha1|bearer|token)\\b")
private val safeHeaders = setOf("via", "v", "route", "record-route", "service-route", "contact", "m", "to", "t",
    "from", "f", "call-id", "i", "cseq", "expires", "min-expires", "reason", "retry-after", "content-type", "supported", "allow")

internal fun sipLogMessage(message: String): String? {
    val lines = message.lineSequence().map { it.trimEnd() }.toList()
    val start = lines.indexOfFirst { sipRequest.matches(it.trim()) || sipResponse.matches(it.trim()) }
    if (start == -1) return null
    val prefix = lines.take(start).joinToString(" ").lowercase(Locale.ROOT)
    val direction = when {
        "received" in prefix || "receiving" in prefix -> "Received"
        "sent" in prefix || "sending" in prefix -> "Sent"
        else -> "SIP"
    }
    return buildList {
        add("$direction · ${lines[start].trim()}")
        lines.drop(start + 1).takeWhile(String::isNotBlank).forEach { header ->
            if (header.substringBefore(':').lowercase(Locale.ROOT) in safeHeaders) add(header)
        }
    }.joinToString("\n")
}

internal fun sanitizeLog(message: String, secret: String = ""): String {
    val scrubbed = if (secret.isEmpty()) message else message.replace(secret, "[redacted]")
    return scrubbed.lineSequence().filterNot { sensitiveText.containsMatchIn(it) }
        .joinToString("\n") { line ->
            uriPassword.replace(line, "$1@").replace(Regex("(?i)(sips?:[^\\s<>?]+)\\?[^\\s<>]+"), "$1")
        }.trim().take(8192).ifBlank { "Sensitive diagnostic text omitted." }
}

internal fun safeCallAddress(address: String) = uriPassword.replace(address.substringBefore('?'), "$1@")
