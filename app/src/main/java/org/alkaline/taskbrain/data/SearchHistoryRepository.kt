package org.alkaline.taskbrain.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SearchHistoryEntry(
    val query: String,
    val criteria: Map<String, Boolean>,
    val timestamp: Long,
)

class SearchHistoryRepository(
    private val context: Context,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val prefs = context.getSharedPreferences("taskbrain_prefs", Context.MODE_PRIVATE)

    private fun historyCollection(userId: String) =
        db.collection("users").document(userId).collection("searchHistory")

    fun getHistory(): List<SearchHistoryEntry> {
        return loadLocal().sortedByDescending { it.timestamp }
    }

    fun saveEntry(entry: SearchHistoryEntry) {
        val local = loadLocal().toMutableList()
        local.removeAll { it.query == entry.query && it.criteria == entry.criteria }
        local.add(0, entry)
        val trimmed = local.take(MAX_ENTRIES)
        saveLocal(trimmed)

        val userId = auth.currentUser?.uid ?: return
        val col = historyCollection(userId)
        val data = hashMapOf(
            "query" to entry.query,
            "criteria" to entry.criteria,
            "timestamp" to entry.timestamp,
        )
        col.add(data)
            .addOnSuccessListener {
                FirestoreUsage.recordWrite("saveSearchHistory", FirestoreUsage.WriteType.SET)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to save search history to Firebase", e)
                NoteStore.raiseWarning(
                    "Search history sync failed. Your local search history is still " +
                    "available, but recent searches may not appear on other devices."
                )
            }
    }

    suspend fun syncFromFirebase() {
        val userId = auth.currentUser?.uid ?: return
        try {
            val snapshot = withContext(Dispatchers.IO) {
                historyCollection(userId)
                    .orderBy("timestamp", Query.Direction.DESCENDING)
                    .limit(MAX_ENTRIES.toLong())
                    .get()
                    .await()
            }
            FirestoreUsage.recordRead(
                "syncSearchHistory",
                FirestoreUsage.ReadType.GET_DOCS,
                snapshot.documents.size,
            )

            val remote = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                val query = data["query"] as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val criteria = (data["criteria"] as? Map<String, Boolean>) ?: emptyMap()
                val timestamp = (data["timestamp"] as? Long) ?: return@mapNotNull null
                SearchHistoryEntry(query, criteria, timestamp)
            }

            val local = loadLocal()
            val merged = mergeHistories(local, remote)
            saveLocal(merged)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync search history from Firebase", e)
            NoteStore.raiseWarning(
                "Search history sync failed. Recent searches from other devices may not appear."
            )
        }
    }

    private fun mergeHistories(
        local: List<SearchHistoryEntry>,
        remote: List<SearchHistoryEntry>,
    ): List<SearchHistoryEntry> {
        val byKey = mutableMapOf<String, SearchHistoryEntry>()
        for (entry in local + remote) {
            val key = "${entry.query}|${entry.criteria}"
            val existing = byKey[key]
            if (existing == null || entry.timestamp > existing.timestamp) {
                byKey[key] = entry
            }
        }
        return byKey.values.sortedByDescending { it.timestamp }.take(MAX_ENTRIES)
    }

    private fun loadLocal(): List<SearchHistoryEntry> {
        val json = prefs.getString(PREF_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val criteriaObj = obj.getJSONObject("criteria")
                val criteria = mutableMapOf<String, Boolean>()
                criteriaObj.keys().forEach { key ->
                    criteria[key] = criteriaObj.getBoolean(key)
                }
                SearchHistoryEntry(
                    query = obj.getString("query"),
                    criteria = criteria,
                    timestamp = obj.getLong("timestamp"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse local search history", e)
            emptyList()
        }
    }

    private fun saveLocal(entries: List<SearchHistoryEntry>) {
        val arr = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("query", entry.query)
            val criteriaObj = JSONObject()
            entry.criteria.forEach { (k, v) -> criteriaObj.put(k, v) }
            obj.put("criteria", criteriaObj)
            obj.put("timestamp", entry.timestamp)
            arr.put(obj)
        }
        prefs.edit().putString(PREF_KEY, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "SearchHistoryRepo"
        private const val MAX_ENTRIES = 10
        private const val PREF_KEY = "search_history"
    }
}
