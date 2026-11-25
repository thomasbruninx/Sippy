package org.sippy

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.text.Collator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal enum class Source(val label: String) { PHONE("Phone"), LOCAL("Local"), CSV("Imported"), SYNC("Synced") }
internal enum class Sort(val label: String) {
    NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"), NUMBER_ASC("Number ↑"), NUMBER_DESC("Number ↓")
}
internal data class Entry(val id: String, val name: String, val number: String, val source: Source)
internal data class DirectoryConfig(val url: String, val minutes: Int, val revision: Int)

internal fun localContactsCsv(entries: List<Entry>): String = buildString {
    append("name,number\r\n")
    fun quoted(value: String) = "\"${value.replace("\"", "\"\"")}\""
    entries.filter { it.source == Source.LOCAL }.forEach {
        append(quoted(it.name)).append(',').append(quoted(it.number)).append("\r\n")
    }
}

internal val directoryExecutor = Executors.newSingleThreadExecutor()

internal class Directory(private val context: Context) {
    private val data = Preferences(context).data

    fun config() = DirectoryConfig(data.getString("csvUrl", "").orEmpty(),
        data.getInt("csvInterval", 60), data.getInt("csvRevision", 0))

    fun configure(url: String, interval: Int) {
        if (url.isNotBlank()) checkedUrl(url)
        require(interval in 15..10080) { "Enter a sync interval between 15 and 10080 minutes." }
        val current = config()
        val edit = data.edit().putString("csvUrl", url).putInt("csvInterval", interval)
        if (current.url != url) edit.remove("entries_SYNC").remove("lastSync").remove("syncError")
        edit.putInt("csvRevision", current.revision + 1).apply()
    }

    fun schedule() {
        val value = config()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (value.url.isBlank()) {
            scheduler.cancel(JOB_ID)
            return
        }
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing?.intervalMillis == TimeUnit.MINUTES.toMillis(value.minutes.toLong())) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DirectoryJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(TimeUnit.MINUTES.toMillis(value.minutes.toLong()))
            .build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android could not schedule CSV sync." }
    }

    fun stored(source: Source): List<Entry> {
        val records = JSONArray(data.getString("entries_$source", "[]"))
        return (0 until records.length()).map {
            val item = records.getJSONObject(it)
            Entry(item.getString("id"), item.getString("name"), item.getString("number"), source)
        }
    }

    private fun records(items: List<Entry>) = JSONArray(items.map {
        JSONObject().put("id", it.id).put("name", it.name).put("number", it.number)
    }).toString()

    fun phone(): List<Entry> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            val columns = arrayOf(Phone._ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER)
            context.contentResolver.query(Phone.CONTENT_URI, columns, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(2).orEmpty().trim()
                        if (number.isNotEmpty()) {
                            add(Entry(cursor.getString(0), cursor.getString(1).orEmpty(), number, Source.PHONE))
                        }
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun saveLocal(id: String?, name: String, number: String) {
        require(dialTarget(number) != null) { "Enter a valid number or SIP address." }
        val entry = Entry(id ?: UUID.randomUUID().toString(), name.trim(), number.trim(), Source.LOCAL)
        data.edit().putString("entries_LOCAL", records(stored(Source.LOCAL).filterNot { it.id == id } + entry)).apply()
    }

    fun removeLocal(id: String) {
        data.edit().putString("entries_LOCAL", records(stored(Source.LOCAL).filterNot { it.id == id })).apply()
    }

    fun import(input: InputStream): Int {
        val items = parseCsv(input, Source.CSV)
        data.edit().putString("entries_CSV", records(items)).apply()
        return items.size
    }

    fun fetch(config: DirectoryConfig): List<Entry> {
        var uri = checkedUrl(config.url)
        repeat(6) {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 12000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "text/csv, text/plain, application/octet-stream")
                when (val status = connection.responseCode) {
                    in 200..299 -> return connection.inputStream.use { parseCsv(it, Source.SYNC) }
                    301, 302, 303, 307, 308 -> {
                        val next = checkedUrl(uri.resolve(checkNotNull(connection.getHeaderField("Location"))).toString())
                        require(uri.scheme != "https" || next.scheme == "https") { "CSV server redirected HTTPS to HTTP." }
                        uri = next
                    }
                    else -> error("CSV server returned HTTP $status.")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many CSV redirects.")
    }

    fun complete(config: DirectoryConfig, result: Result<List<Entry>>) {
        if (config != config()) return
        val edit = data.edit()
        result.fold(
            onSuccess = { edit.putString("entries_SYNC", records(it)).putLong("lastSync", System.currentTimeMillis()).remove("syncError") },
            onFailure = { edit.putString("syncError", it.exceptionOrNullMessage()) },
        )
        edit.apply()
    }

    fun syncStatus(): String {
        val date = data.getLong("lastSync", 0)
        val last = if (date == 0L) "No successful sync yet" else
            "Last synced ${java.text.DateFormat.getDateTimeInstance().format(date)}"
        return listOfNotNull(last, data.getString("syncError", null)).joinToString("\n")
    }

    companion object { private const val JOB_ID = 9321 }
}

private fun Throwable.exceptionOrNullMessage(): String = when (this) {
    is java.net.UnknownHostException -> "CSV server could not be found."
    is java.net.SocketTimeoutException -> "CSV server timed out."
    is javax.net.ssl.SSLException -> "CSV server TLS verification failed."
    else -> localizedMessage ?: "CSV sync failed."
}

class DirectoryJob : JobService() {
    private var work: Future<*>? = null
    private var activeParams: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val directory = Directory(this)
        val config = directory.config()
        if (config.url.isBlank()) return false
        activeParams = params
        work = directoryExecutor.submit {
            val result = runCatching { directory.fetch(config) }
            mainExecutor.execute {
                if (activeParams === params) {
                    directory.complete(config, result)
                    jobFinished(params, false)
                    work = null
                    activeParams = null
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        activeParams = null
        work?.cancel(true)
        work = null
        return true
    }
}

internal fun checkedUrl(value: String): URI {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme in listOf("http", "https") && uri.host != null &&
        uri.rawUserInfo == null && uri.rawFragment == null) { "Enter a direct HTTP or HTTPS CSV URL." }
    return uri
}

internal fun dialTarget(raw: String): String? {
    val text = raw.trim()
    if (text.any { it.isISOControl() || it in "<>\"" }) return null
    if (text.startsWith("sip:", true) || text.startsWith("sips:", true) || '@' in text) {
        return text.takeIf { '@' in it && it.none(Char::isWhitespace) }
    }
    return text.filterNot { it.isWhitespace() || it in "().-" }
        .takeIf { it.matches(Regex("[+*#0-9][0-9*#]{0,63}")) && it != "+" }
}

internal fun searchEntries(items: List<Entry>, query: String, sort: Sort): List<Entry> {
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    val numberQuery = query.filter { it.isDigit() || it in "+*#" }
    val filtered = items.filter { item ->
        item.name.contains(query, true) || item.number.contains(query, true) ||
            (numberQuery.isNotEmpty() && query.none(Char::isLetter) &&
                item.number.filter { it.isDigit() || it in "+*#" }.contains(numberQuery))
    }
    val names = Comparator<Entry> { a, b ->
        collator.compare(a.name.ifBlank { a.number }, b.name.ifBlank { b.number })
    }
    val numbers = compareBy<Entry> {
        val digits = it.number.filter(Char::isDigit).trimStart('0')
        digits.length
    }.thenBy { it.number.filter(Char::isDigit).trimStart('0') }.thenBy { it.number }
    val order = when (sort) {
        Sort.NAME_ASC -> names.then(numbers)
        Sort.NAME_DESC -> names.then(numbers).reversed()
        Sort.NUMBER_ASC -> numbers.then(names)
        Sort.NUMBER_DESC -> numbers.then(names).reversed()
    }
    return filtered.sortedWith(order)
}

internal fun parseCsv(input: InputStream, source: Source): List<Entry> {
    val maxBytes = 2_000_000
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = input.read(chunk)
        if (count < 0) break
        require(buffer.size() + count <= maxBytes) { "CSV files must be 2 MB or smaller." }
        buffer.write(chunk, 0, count)
    }
    val text = buffer.toByteArray().decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
    val delimiter = csvDelimiter(text)
    val rows = csvRows(text, delimiter)
    require(rows.isNotEmpty()) { "The CSV is empty." }
    val header = rows.first().map { it.lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "") }
    val numberColumn = header.indexOfFirst { it in listOf("number", "phone", "phonenumber", "extension", "telephone", "tel", "mobile", "sip") }
    val nameColumn = header.indexOfFirst { it in listOf("name", "displayname", "fullname", "contact") }
    val dataRows = if (numberColumn >= 0) rows.drop(1) else rows
    return dataRows.mapIndexed { index, row ->
        val inferred = if (row.size > 1 && dialTarget(row[0]) == null) 1 else 0
        val numberIndex = numberColumn.takeIf { it >= 0 } ?: inferred
        val nameIndex = if (numberColumn >= 0) nameColumn else 1 - inferred
        val number = row.getOrNull(numberIndex).orEmpty()
        require(dialTarget(number) != null) { "CSV record ${index + 1 + if (numberColumn >= 0) 1 else 0} has an invalid number." }
        Entry(index.toString(), row.getOrNull(nameIndex).orEmpty(), number, source)
    }.distinctBy { it.name to (dialTarget(it.number) ?: it.number) }
}

private fun csvDelimiter(text: String): Char {
    var quoted = false
    val counts = linkedMapOf(',' to 0, ';' to 0, '\t' to 0)
    for (char in text) {
        if (char == '"') quoted = !quoted
        if (quoted) continue
        if (char in "\r\n") {
            if (counts.values.any { it > 0 }) break
            continue
        }
        if (char in counts) counts[char] = counts.getValue(char) + 1
    }
    return counts.maxBy { it.value }.key
}

private fun csvRows(text: String, separator: Char): List<List<String>> {
    val result = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closedQuote = false
    var index = 0
    fun finishField() {
        row.add(field.toString().trim())
        field.clear()
        closedQuote = false
    }
    fun finishRow() {
        finishField()
        if (row.any(String::isNotEmpty)) result.add(row.toList())
        row.clear()
    }
    while (index < text.length) {
        val char = text[index++]
        when {
            quoted && char == '"' && text.getOrNull(index) == '"' -> { field.append('"'); index++ }
            quoted && char == '"' -> { quoted = false; closedQuote = true }
            quoted -> field.append(char)
            char == separator -> finishField()
            char == '\n' || char == '\r' -> {
                if (char == '\r' && text.getOrNull(index) == '\n') index++
                finishRow()
            }
            char == '"' && field.isBlank() && !closedQuote -> { field.clear(); quoted = true }
            char == '"' || (closedQuote && !char.isWhitespace()) -> error("Invalid quoted CSV field.")
            else -> field.append(char)
        }
    }
    require(!quoted) { "CSV has an unterminated quoted field." }
    finishRow()
    return result
}
