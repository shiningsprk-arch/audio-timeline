package com.shiningsprk.audiotimeline.history

import android.content.Context
import android.util.Log
import com.shiningsprk.audiotimeline.align.AlignedLine
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One finished alignment, kept so the result can be reopened without recomputing. */
data class HistoryRecord(
    val id: Long,
    val audioName: String,
    val audioUri: String?,
    val durationSec: Double,
    val createdAt: Long,
    val lines: List<AlignedLine>,
    val matchedChars: Int = lines.size,
    val totalChars: Int = lines.size,
    val elapsedSec: Double = 0.0,
) {
    val lineCount: Int get() = lines.size
}

/**
 * Tiny JSON backed store. The whole list is rewritten on every change, which is fine for
 * a few dozen records and avoids pulling in a database for a personal tool.
 */
class HistoryStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "history.json"))

    private val mutex = Mutex()

    suspend fun load(): List<HistoryRecord> = mutex.withLock { loadLocked() }

    suspend fun save(records: List<HistoryRecord>): Boolean = mutex.withLock {
        runCatching {
            val array = JSONArray()
            for (record in records) {
                val lines = JSONArray()
                for (line in record.lines) {
                    lines.put(
                        JSONObject()
                            .put("t", line.text)
                            .put("s", line.start)
                            .put("e", line.end)
                    )
                }
                array.put(
                    JSONObject()
                        .put("id", record.id)
                        .put("name", record.audioName)
                        .put("uri", record.audioUri ?: "")
                        .put("duration", record.durationSec)
                        .put("created", record.createdAt)
                        .put("matched", record.matchedChars)
                        .put("total", record.totalChars)
                        .put("elapsed", record.elapsedSec)
                        .put("lines", lines)
                )
            }
            file.parentFile?.mkdirs()
            // write to a temp file first: a half written history.json would be unreadable
            // and the old content would be gone
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrElse { error ->
            Log.w(TAG, "cannot persist history", error)
            false
        }
    }

    private fun loadLocked(): List<HistoryRecord> {
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrElse {
            Log.w(TAG, "history file is not valid json, ignoring it")
            return emptyList()
        }
        val out = ArrayList<HistoryRecord>(array.length())
        for (i in 0 until array.length()) {
            // one broken entry must not throw away the rest of the history
            runCatching { parseRecord(array.optJSONObject(i)) }.getOrNull()?.let { out.add(it) }
        }
        return out.distinctBy { it.id }
    }

    private fun parseRecord(obj: JSONObject?): HistoryRecord? {
        if (obj == null) return null
        val lines = obj.optJSONArray("lines") ?: JSONArray()
        val parsed = ArrayList<AlignedLine>(lines.length())
        for (j in 0 until lines.length()) {
            val line = lines.optJSONObject(j) ?: continue
            parsed.add(
                AlignedLine(
                    text = line.optString("t"),
                    start = line.optDouble("s", 0.0),
                    end = line.optDouble("e", 0.0),
                )
            )
        }
        return HistoryRecord(
            id = obj.optLong("id"),
            audioName = obj.optString("name"),
            audioUri = obj.optString("uri").takeIf { it.isNotBlank() },
            durationSec = obj.optDouble("duration", 0.0),
            createdAt = obj.optLong("created"),
            lines = parsed,
            matchedChars = obj.optInt("matched", parsed.size),
            totalChars = obj.optInt("total", parsed.size),
            elapsedSec = obj.optDouble("elapsed", 0.0),
        )
    }

    companion object {
        private const val TAG = "HistoryStore"
        const val MAX_RECORDS = 30
    }
}
