package org.alkaline.taskbrain.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Bucketed counters for Firestore reads and writes, segmented by operation
 * name and event type. Used to find hotspots — call [recordRead] /
 * [recordWrite] at every Firestore call site, then tap the Usage button on
 * the note list to view the report.
 *
 * Data model:
 * - Hourly buckets: last 24 hours, one bucket per local-clock hour.
 * - Daily buckets: last 7 days, one bucket per local-clock day.
 * - Each bucket holds (operation, type) → {ops, docs} for both reads and writes.
 *
 * Persistence: buckets are stored in SharedPreferences and survive app
 * restarts. Writes are debounced to once per 30s to keep the hot path cheap;
 * the latest unsaved increment is lost on a hard kill (acceptable — this is
 * diagnostic data, not user data). Call [attach] once from
 * `Application.onCreate` so reads/writes have somewhere to persist.
 */
object FirestoreUsage {
    private const val TAG = "FirestoreUsage"
    private const val PREFS_NAME = "firestore_usage"
    private const val KEY_HOURLY = "hourly"
    private const val KEY_DAILY = "daily"
    private val HOUR_MS = TimeUnit.HOURS.toMillis(1)
    private val DAY_MS = TimeUnit.DAYS.toMillis(1)
    private const val MAX_HOURLY = 24
    private const val MAX_DAILY = 7
    private const val PERSIST_DEBOUNCE_MS = 30_000L

    enum class ReadType {
        DOC_GET,
        GET_DOCS,
        LISTENER_INITIAL_FRESH,
        LISTENER_INITIAL_CACHED,
        LISTENER_UPDATE_FRESH,
        LISTENER_UPDATE_CACHED,
        LISTENER_LOCAL_ECHO,
    }

    enum class WriteType {
        SET, UPDATE, DELETE, BATCH_COMMIT, TRANSACTION,
    }

    private data class Counter(var ops: Long = 0, var docs: Long = 0)

    private data class Bucket(
        val start: Long,
        val reads: HashMap<String, Counter> = HashMap(),
        val writes: HashMap<String, Counter> = HashMap(),
    )

    private val hourly = ArrayList<Bucket>()
    private val daily = ArrayList<Bucket>()
    private val lock = Any()

    private var prefs: SharedPreferences? = null
    private val persistHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var persistScheduled = false

    /** Hook up persistence. Idempotent; safe to call from Application.onCreate. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            load()
        }
    }

    fun recordRead(operation: String, type: ReadType, docCount: Int = 1) {
        record(isRead = true, operation = operation, type = type.name, docCount = docCount)
    }

    fun recordWrite(operation: String, type: WriteType, docCount: Int = 1) {
        record(isRead = false, operation = operation, type = type.name, docCount = docCount)
    }

    private fun record(isRead: Boolean, operation: String, type: String, docCount: Int) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val hour = currentBucket(hourly, hourStart(now), MAX_HOURLY)
            val day = currentBucket(daily, dayStart(now), MAX_DAILY)
            val key = "$operation|$type"
            increment(if (isRead) hour.reads else hour.writes, key, docCount)
            increment(if (isRead) day.reads else day.writes, key, docCount)
            schedulePersist()
        }
    }

    private fun currentBucket(buckets: ArrayList<Bucket>, bucketStart: Long, max: Int): Bucket {
        val last = buckets.lastOrNull()
        if (last != null && last.start == bucketStart) return last
        val next = Bucket(bucketStart)
        buckets.add(next)
        while (buckets.size > max) buckets.removeAt(0)
        return next
    }

    fun getReport(): String = synchronized(lock) {
        buildString {
            appendLine("=== Firestore Usage Report ===")
            val now = System.currentTimeMillis()
            val current = hourly.lastOrNull()
            if (current != null) {
                appendLine()
                appendLine("Current hour (${formatHour(current.start)}):")
                append(formatBucket(current))
            }
            val last24 = summarize(hourly.filter { it.start >= now - 24 * HOUR_MS })
            appendLine()
            appendLine()
            appendLine("Last 24 hours:")
            append(formatBucket(last24))
            val last7 = summarize(daily.filter { it.start >= now - 7 * DAY_MS })
            appendLine()
            appendLine()
            appendLine("Last 7 days:")
            append(formatBucket(last7))
            appendLine()
            appendLine()
            append("=== End ===")
        }
    }

    fun reset() {
        synchronized(lock) {
            hourly.clear()
            daily.clear()
            persist()
        }
    }

    private fun summarize(buckets: List<Bucket>): Bucket {
        val merged = Bucket(0)
        for (b in buckets) {
            for ((k, c) in b.reads) accumulate(merged.reads, k, c)
            for ((k, c) in b.writes) accumulate(merged.writes, k, c)
        }
        return merged
    }

    private fun increment(map: HashMap<String, Counter>, key: String, docCount: Int) {
        val c = map.getOrPut(key) { Counter() }
        c.ops += 1
        c.docs += docCount
    }

    private fun accumulate(map: HashMap<String, Counter>, key: String, c: Counter) {
        val existing = map.getOrPut(key) { Counter() }
        existing.ops += c.ops
        existing.docs += c.docs
    }

    private fun hourStart(t: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return cal.timeInMillis
    }

    private fun dayStart(t: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return cal.timeInMillis
    }

    private fun formatHour(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        return "%tb %td %02d:00".format(cal, cal, cal.get(Calendar.HOUR_OF_DAY))
    }

    private fun formatBucket(b: Bucket): String = buildString {
        var totalReadOps = 0L
        var totalReadDocs = 0L
        var totalWriteOps = 0L
        var totalWriteDocs = 0L
        appendLine("  Reads:")
        for ((key, c) in b.reads.entries.sortedByDescending { it.value.docs }) {
            val parts = key.split("|", limit = 2)
            val op = parts.getOrNull(0) ?: ""
            val type = parts.getOrNull(1) ?: ""
            appendLine("    ${op.padEnd(34)} ${type.padEnd(28)} ${"%5d".format(c.ops)} ops × ${"%6d".format(c.docs)} docs")
            totalReadOps += c.ops
            totalReadDocs += c.docs
        }
        appendLine("    TOTAL: $totalReadOps ops × $totalReadDocs docs")
        appendLine("  Writes:")
        for ((key, c) in b.writes.entries.sortedByDescending { it.value.docs }) {
            val parts = key.split("|", limit = 2)
            val op = parts.getOrNull(0) ?: ""
            val type = parts.getOrNull(1) ?: ""
            appendLine("    ${op.padEnd(34)} ${type.padEnd(28)} ${"%5d".format(c.ops)} ops × ${"%6d".format(c.docs)} docs")
            totalWriteOps += c.ops
            totalWriteDocs += c.docs
        }
        append("    TOTAL: $totalWriteOps ops × $totalWriteDocs docs")
        if (totalWriteDocs > 0) {
            appendLine()
            append("    R:W RATIO: %.1fx".format(totalReadDocs.toDouble() / totalWriteDocs))
        }
    }

    private fun load() {
        val p = prefs ?: return
        try {
            p.getString(KEY_HOURLY, null)?.let { hourly.addAll(parseBucketList(it)) }
            p.getString(KEY_DAILY, null)?.let { daily.addAll(parseBucketList(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load buckets from prefs; resetting", e)
            hourly.clear()
            daily.clear()
        }
    }

    private fun schedulePersist() {
        if (persistScheduled) return
        persistScheduled = true
        persistHandler.postDelayed({
            synchronized(lock) {
                persistScheduled = false
                persist()
            }
        }, PERSIST_DEBOUNCE_MS)
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            p.edit()
                .putString(KEY_HOURLY, serializeBucketList(hourly))
                .putString(KEY_DAILY, serializeBucketList(daily))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist buckets", e)
        }
    }

    private fun serializeBucketList(buckets: List<Bucket>): String {
        val arr = JSONArray()
        for (b in buckets) {
            val obj = JSONObject()
            obj.put("start", b.start)
            obj.put("reads", serializeCounters(b.reads))
            obj.put("writes", serializeCounters(b.writes))
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun serializeCounters(map: Map<String, Counter>): JSONObject {
        val obj = JSONObject()
        for ((k, c) in map) {
            obj.put(k, JSONArray().apply { put(c.ops); put(c.docs) })
        }
        return obj
    }

    private fun parseBucketList(json: String): List<Bucket> {
        val arr = JSONArray(json)
        val out = ArrayList<Bucket>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val b = Bucket(obj.getLong("start"))
            b.reads.putAll(parseCounters(obj.getJSONObject("reads")))
            b.writes.putAll(parseCounters(obj.getJSONObject("writes")))
            out.add(b)
        }
        return out
    }

    private fun parseCounters(obj: JSONObject): Map<String, Counter> {
        val out = HashMap<String, Counter>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = obj.getJSONArray(k)
            out[k] = Counter(ops = arr.getLong(0), docs = arr.getLong(1))
        }
        return out
    }
}
