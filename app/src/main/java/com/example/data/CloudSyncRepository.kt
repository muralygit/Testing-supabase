package com.example.data

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/** A row pulled back from the `documents` table. */
data class RemoteDocument(
    val clientId: String,
    val category: String,
    val date: String,
    val refNumber: String,
    val notes: String,
    val ocrText: String,
    val createdAt: String,
    val imagePath: String
)

/**
 * Talks to Supabase Postgrest (`documents`, `documents_tombstones`) and
 * Storage (`documents` bucket). Replaces the old FirebaseFirestore /
 * FirebaseStorage calls, keeping the same tombstone-based 90-day delete-sync
 * shape as before.
 */
class CloudSyncRepository {

    private val client = SupabaseClientProvider.client
    private val okHttp = OkHttpClient()
    private val timestampFormat get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    /** Insert-or-update a bill's metadata row, keyed by client_id = Bill.id. */
    suspend fun upsertBillRow(bill: Bill) = withContext(Dispatchers.IO) {
        val title = bill.refNumber.ifBlank { "${bill.category} ${bill.date}".trim() }
        val row: JsonObject = buildJsonObject {
            put("client_id", bill.id)
            put("title", title)
            put("category", bill.category)
            put("date", bill.date)
            put("ref_number", bill.refNumber)
            put("notes", bill.notes)
            put("ocr_text", bill.ocrText)
        }
        client.postgrest[SupabaseClientProvider.DOCUMENTS_TABLE]
            .upsert(row) {
                onConflict = "client_id"
            }
    }

    /** Upload the local photo file's bytes to Storage, returns the stored object path. */
    suspend fun uploadPhoto(clientId: String, photoBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        try {
            val path = "$clientId.jpg"
            client.storage.from(SupabaseClientProvider.DOCUMENTS_BUCKET)
                .upload(path, photoBytes) { upsert = true }
            path
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Set image_path on an existing document row once the photo upload succeeds. */
    suspend fun setImagePath(clientId: String, imagePath: String) = withContext(Dispatchers.IO) {
        val row: JsonObject = buildJsonObject {
            put("image_path", imagePath)
        }
        client.postgrest[SupabaseClientProvider.DOCUMENTS_TABLE]
            .update(row) {
                filter { eq("client_id", clientId) }
            }
    }

    /** Signed, time-limited URL to view/download a private-bucket photo. */
    suspend fun getSignedUrl(imagePath: String, expiresInMinutes: Long = 60): String? = withContext(Dispatchers.IO) {
        try {
            client.storage.from(SupabaseClientProvider.DOCUMENTS_BUCKET)
                .createSignedUrl(imagePath, expiresInMinutes.minutes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Download photo bytes via a freshly-signed URL. */
    suspend fun downloadPhotoBytes(imagePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = getSignedUrl(imagePath) ?: return@withContext null
        try {
            val request = Request.Builder().url(url).build()
            okHttp.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** All document rows currently in the cloud. */
    suspend fun fetchAllDocuments(): List<RemoteDocument> = withContext(Dispatchers.IO) {
        val result = client.postgrest[SupabaseClientProvider.DOCUMENTS_TABLE]
            .select(columns = Columns.ALL)
        val array = JSONArray(result.data)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            RemoteDocument(
                clientId = obj.optString("client_id"),
                category = obj.optString("category", "other"),
                date = obj.optString("date", ""),
                refNumber = obj.optString("ref_number", ""),
                notes = obj.optString("notes", ""),
                ocrText = obj.optString("ocr_text", ""),
                createdAt = obj.optString("created_at", ""),
                imagePath = obj.optString("image_path", "")
            )
        }
    }

    /** client_id values that have been tombstoned (deleted on some device). */
    suspend fun fetchTombstoneClientIds(): Set<String> = withContext(Dispatchers.IO) {
        val result = client.postgrest[SupabaseClientProvider.TOMBSTONES_TABLE]
            .select(columns = Columns.list("client_id"))
        val array = JSONArray(result.data)
        (0 until array.length())
            .mapNotNull { i -> array.getJSONObject(i).optString("client_id").takeIf { it.isNotBlank() } }
            .toSet()
    }

    /** Delete the cloud row + storage object for a bill, and leave a tombstone. */
    suspend fun deleteBillRemote(clientId: String, imagePath: String) = withContext(Dispatchers.IO) {
        try {
            if (imagePath.isNotBlank()) {
                try {
                    client.storage.from(SupabaseClientProvider.DOCUMENTS_BUCKET).delete(imagePath)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            client.postgrest[SupabaseClientProvider.DOCUMENTS_TABLE].delete {
                filter { eq("client_id", clientId) }
            }
            val tombstone: JsonObject = buildJsonObject {
                put("client_id", clientId)
                put("deleted_at", timestampFormat.format(java.util.Date()))
            }
            client.postgrest[SupabaseClientProvider.TOMBSTONES_TABLE].insert(tombstone)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** File count + total bytes currently stored in the `documents` bucket
     *  (used for a storage-usage check). Returning the count too makes it
     *  possible to tell "bucket is genuinely empty" apart from "list()
     *  returned files but their size metadata came back empty/null".
     *
     *  Note: FileObject.metadata is a raw JsonObject (basically a Map), so
     *  `metadata?.size` looks like it reads a file size but actually calls
     *  Map.size (the number of metadata keys) — that was the bug that made
     *  this always report 0. The real byte size lives at metadata["size"]
     *  and has to be read out explicitly as JSON. */
    suspend fun getStorageUsageInfo(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val files = client.storage.from(SupabaseClientProvider.DOCUMENTS_BUCKET).list()
        var totalBytes = 0L
        for (file in files) {
            val sizeBytes = file.metadata?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
            totalBytes += sizeBytes
        }
        Pair(files.size, totalBytes)
    }

    /** Clean up tombstones older than 90 days so the table doesn't grow forever. */
    suspend fun purgeOldTombstones() = withContext(Dispatchers.IO) {
        try {
            val cutoff = timestampFormat.format(java.util.Date(System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000))
            client.postgrest[SupabaseClientProvider.TOMBSTONES_TABLE].delete {
                filter { lt("deleted_at", cutoff) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
