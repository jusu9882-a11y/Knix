package dev.jusu.knix.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TransferHistoryEntry(
    val fileName: String,
    val direction: String,
    val bytes: Long,
    val timestamp: Long,
    val sha256: String,
    val outcome: String
)

class TransferHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("knix_transfer_history", Context.MODE_PRIVATE)

    @Synchronized
    fun read(): List<TransferHistoryEntry> {
        val raw = preferences.getString(KEY_ENTRIES, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("fileName")
                val direction = item.optString("direction")
                val bytes = item.optLong("bytes", -1)
                val timestamp = item.optLong("timestamp", 0)
                if (name.isBlank() || direction !in setOf("Sent", "Received") || bytes < 0 || timestamp <= 0) {
                    null
                } else {
                    TransferHistoryEntry(
                        fileName = name,
                        direction = direction,
                        bytes = bytes,
                        timestamp = timestamp,
                        sha256 = item.optString("sha256"),
                        outcome = item.optString("outcome", "Complete")
                    )
                }
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun append(entry: TransferHistoryEntry) {
        val entries = (read() + entry).sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
        val array = JSONArray()
        entries.forEach { item ->
            array.put(JSONObject().apply {
                put("fileName", item.fileName)
                put("direction", item.direction)
                put("bytes", item.bytes)
                put("timestamp", item.timestamp)
                put("sha256", item.sha256)
                put("outcome", item.outcome)
            })
        }
        preferences.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    companion object {
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 100
    }
}
