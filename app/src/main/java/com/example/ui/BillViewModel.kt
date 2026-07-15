package com.example.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.Bill
import com.example.data.BillRepository
import com.example.data.CATEGORIES
import com.example.data.CategoryInfo
import com.example.data.CloudSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BillViewModel(
    private val repository: BillRepository,
    private val cloudSync: CloudSyncRepository = CloudSyncRepository()
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _categoriesList = MutableStateFlow<List<CategoryInfo>>(CATEGORIES)
    val categoriesList = _categoriesList.asStateFlow()

    // Reactive list of bills sorted chronologically
    val allBills: StateFlow<List<Bill>> = repository.allBills
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Global Search results matching across all specified bill fields
    val searchResults: StateFlow<List<Bill>> = combine(allBills, _searchQuery) { bills, query ->
        if (query.isBlank()) {
            emptyList()
        } else {
            val lower = query.lowercase().trim()
            bills.filter { bill ->
                val categoryName = _categoriesList.value.find { it.id == bill.category }?.name ?: bill.category
                bill.refNumber.lowercase().contains(lower) ||
                        bill.notes.lowercase().contains(lower) ||
                        bill.ocrText.lowercase().contains(lower) ||
                        bill.date.lowercase().contains(lower) ||
                        categoryName.lowercase().contains(lower)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun loadCategories(context: Context) {
        val sharedPrefs = context.getSharedPreferences("BillFolderPrefs", Context.MODE_PRIVATE)
        val jsonString = sharedPrefs.getString("custom_categories", null)
        if (!jsonString.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonString)
                val newList = ArrayList<CategoryInfo>()
                newList.addAll(CATEGORIES)
                
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    val name = obj.getString("name")
                    val icon = obj.getString("icon")
                    val colorHex = obj.getString("colorHex")
                    val color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorHex))
                    newList.add(CategoryInfo(id, name, icon, color))
                }
                _categoriesList.value = newList
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addCustomCategory(context: Context, name: String, icon: String, colorHex: String) {
        viewModelScope.launch {
            if (name.isBlank()) {
                _eventFlow.emit("❌ Category name cannot be empty")
                return@launch
            }
            val id = "custom_" + name.lowercase().replace(" ", "_") + "_" + System.currentTimeMillis()
            
            val exists = _categoriesList.value.any { it.name.lowercase() == name.lowercase() }
            if (exists) {
                _eventFlow.emit("❌ Category already exists!")
                return@launch
            }

            val color = try {
                androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(colorHex))
            } catch (e: Exception) {
                androidx.compose.ui.graphics.Color(0xFF5A6270)
            }

            val newCategory = CategoryInfo(id, name, icon, color)
            val updatedList = _categoriesList.value.toMutableList()
            updatedList.add(newCategory)
            _categoriesList.value = updatedList

            saveCustomCategoriesToPrefs(context, updatedList)
            _eventFlow.emit("✅ Category '$name' added!")
        }
    }

    fun deleteCustomCategory(context: Context, categoryId: String) {
        viewModelScope.launch {
            if (CATEGORIES.any { it.id == categoryId }) {
                _eventFlow.emit("❌ Cannot delete default categories")
                return@launch
            }

            val updatedList = _categoriesList.value.filter { it.id != categoryId }
            _categoriesList.value = updatedList
            saveCustomCategoriesToPrefs(context, updatedList)
            _eventFlow.emit("🗑️ Custom category removed!")
        }
    }

    private fun saveCustomCategoriesToPrefs(context: Context, list: List<CategoryInfo>) {
        val sharedPrefs = context.getSharedPreferences("BillFolderPrefs", Context.MODE_PRIVATE)
        val customList = list.filter { item -> !CATEGORIES.any { it.id == item.id } }
        val array = JSONArray()
        for (item in customList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("icon", item.icon)
                val colorHex = String.format("#%08X", item.color.value.toLong() and 0xFFFFFFFFL)
                put("colorHex", colorHex)
            }
            array.put(obj)
        }
        sharedPrefs.edit().putString("custom_categories", array.toString()).apply()
    }

    // Sync state
    private val _syncStatus = MutableStateFlow("Idle")
    val syncStatus = _syncStatus.asStateFlow()

    private val _isCloudSyncEnabled = MutableStateFlow(false)
    val isCloudSyncEnabled = _isCloudSyncEnabled.asStateFlow()

    // OCR scanning feedback
    private val _ocrLoading = MutableStateFlow(false)
    val ocrLoading = _ocrLoading.asStateFlow()

    private val _detectedOcrText = MutableStateFlow("")
    val detectedOcrText = _detectedOcrText.asStateFlow()

    // Shared Flow for toast notifications
    private val _eventFlow = MutableSharedFlow<String>()
    val eventFlow = _eventFlow.asSharedFlow()

    init {
        // Cloud sync preference is loaded explicitly via loadCloudSyncPreference(context),
        // called from MainActivity.onCreate (needs a Context we don't have yet here).
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setOcrText(text: String) {
        _detectedOcrText.value = text
    }

    fun setOcrLoading(loading: Boolean) {
        _ocrLoading.value = loading
    }

    // Turn Supabase cloud sync on/off. Unlike the old Firebase pattern, there's
    // no per-user config to paste in — the Supabase URL/key are fixed at build
    // time via BuildConfig, so this is just a persisted on/off preference.
    fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
        viewModelScope.launch {
            context.getSharedPreferences("BillFolderPrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("cloud_sync_enabled", enabled)
                .apply()
            _isCloudSyncEnabled.value = enabled
            _eventFlow.emit(if (enabled) "✅ Cloud Sync enabled!" else "☁️ Cloud Sync disabled")
        }
    }

    fun loadCloudSyncPreference(context: Context) {
        val saved = context.getSharedPreferences("BillFolderPrefs", Context.MODE_PRIVATE)
            .getBoolean("cloud_sync_enabled", false)
        _isCloudSyncEnabled.value = saved
    }

    // Insert a new bill
    fun addBill(
        category: String,
        date: String,
        refNumber: String,
        notes: String,
        photoPath: String,
        ocrText: String,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val id = "bill_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(6)
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
            val bill = Bill(
                id = id,
                category = category,
                date = date,
                refNumber = refNumber,
                notes = notes,
                photoPath = photoPath,
                ocrText = ocrText,
                createdAt = timestamp
            )
            withContext(Dispatchers.IO) {
                repository.insertBill(bill)
            }
            _eventFlow.emit("✅ Bill saved locally!")
            onComplete()

            // Best effort background sync to Supabase
            if (_isCloudSyncEnabled.value) {
                viewModelScope.launch(Dispatchers.IO) {
                    uploadSingleBill(bill)
                }
            }
        }
    }

    // Delete bill
    fun deleteBill(billId: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Also clean up local file to free space
                repository.getBillById(billId)?.let { bill ->
                    if (bill.photoPath.isNotEmpty()) {
                        val file = File(bill.photoPath)
                        if (file.exists()) file.delete()
                    }
                }
                val deletedImagePath = "$billId.jpg"
                repository.deleteBillById(billId)

                // Remove from Supabase + leave a tombstone so the OTHER
                // device knows to delete its local copy on next sync.
                if (_isCloudSyncEnabled.value) {
                    cloudSync.deleteBillRemote(billId, deletedImagePath)
                }
            }
            _eventFlow.emit("🗑️ Bill deleted permanently")
            onComplete()
        }
    }

    // JSON export with built-in Base64 photo encoding
    fun exportBackup(context: Context, onResult: (Uri?) -> Unit) {
        viewModelScope.launch {
            _syncStatus.value = "Exporting backup..."
            val resultUri = withContext(Dispatchers.IO) {
                try {
                    val bills = allBills.value
                    val backupObj = JSONObject().apply {
                        put("app", "BillFolder")
                        put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
                        put("billCount", bills.size)
                    }

                    // Save custom categories as well
                    val customCatsArray = JSONArray()
                    val customCats = _categoriesList.value.filter { cat -> !CATEGORIES.any { it.id == cat.id } }
                    for (cat in customCats) {
                        val catObj = JSONObject().apply {
                            put("id", cat.id)
                            put("name", cat.name)
                            put("icon", cat.icon)
                            val colorHex = String.format("#%08X", cat.color.value.toLong() and 0xFFFFFFFFL)
                            put("colorHex", colorHex)
                        }
                        customCatsArray.put(catObj)
                    }
                    backupObj.put("custom_categories", customCatsArray)

                    val billsArray = JSONArray()
                    for (bill in bills) {
                        val billObj = JSONObject().apply {
                            put("id", bill.id)
                            put("category", bill.category)
                            put("date", bill.date)
                            put("refNumber", bill.refNumber)
                            put("notes", bill.notes)
                            put("ocrText", bill.ocrText)
                            put("createdAt", bill.createdAt)
                        }

                        // Encode photo path into base64
                        val file = File(bill.photoPath)
                        if (file.exists()) {
                            val bytes = file.readBytes()
                            val base64Str = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            billObj.put("photoBase64", base64Str)
                        } else {
                            billObj.put("photoBase64", "")
                        }
                        billsArray.put(billObj)
                    }
                    backupObj.put("bills", billsArray)

                    // Write JSON string to standard cache folder for sharing
                    val cacheDir = File(context.cacheDir, "backups")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val backupFile = File(cacheDir, "bill_folder_backup_$timestamp.json")
                    backupFile.writeText(backupObj.toString(2))

                    // Use FileProvider to get shareable Uri
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "com.aistudio.billfolder.vintag.fileprovider",
                        backupFile
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            _syncStatus.value = "Idle"
            onResult(resultUri)
        }
    }

    // JSON import and restore
    fun restoreBackup(context: Context, jsonUri: Uri, onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            _syncStatus.value = "Restoring backup..."
            val restoredCount = withContext(Dispatchers.IO) {
                try {
                    val jsonStr = context.contentResolver.openInputStream(jsonUri)?.use { stream ->
                        stream.bufferedReader().use { it.readText() }
                    } ?: return@withContext 0

                    val backupObj = JSONObject(jsonStr)
                    val app = backupObj.optString("app")
                    if (app != "BillFolder") {
                        throw IllegalArgumentException("Not a valid Bill Folder backup file")
                    }

                    // Restore custom categories if present
                    val customCatsArray = backupObj.optJSONArray("custom_categories")
                    if (customCatsArray != null && customCatsArray.length() > 0) {
                        val currentList = _categoriesList.value.toMutableList()
                        var modified = false
                        for (i in 0 until customCatsArray.length()) {
                            val catObj = customCatsArray.getJSONObject(i)
                            val catId = catObj.getString("id")
                            val catName = catObj.getString("name")
                            val catIcon = catObj.getString("icon")
                            val catColorHex = catObj.getString("colorHex")
                            
                            // Only add if it doesn't exist yet
                            if (!currentList.any { it.id == catId || it.name.lowercase() == catName.lowercase() }) {
                                val catColor = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(catColorHex))
                                currentList.add(CategoryInfo(catId, catName, catIcon, catColor))
                                modified = true
                            }
                        }
                        if (modified) {
                            _categoriesList.value = currentList
                            saveCustomCategoriesToPrefs(context, currentList)
                        }
                    }

                    val billsArray = backupObj.optJSONArray("bills") ?: return@withContext 0
                    var count = 0

                    for (i in 0 until billsArray.length()) {
                        val billObj = billsArray.getJSONObject(i)
                        val id = billObj.getString("id")

                        // Avoid duplicate records
                        if (repository.getBillById(id) != null) continue

                        val category = billObj.getString("category")
                        val date = billObj.getString("date")
                        val refNumber = billObj.optString("refNumber", "")
                        val notes = billObj.optString("notes", "")
                        val ocrText = billObj.optString("ocrText", "")
                        val createdAt = billObj.optString("createdAt", "")
                        val photoBase64 = billObj.optString("photoBase64", "")

                        var localPath = ""
                        if (photoBase64.isNotEmpty()) {
                            try {
                                val bytes = Base64.decode(photoBase64, Base64.DEFAULT)
                                val imagesDir = File(context.filesDir, "bill_images")
                                if (!imagesDir.exists()) imagesDir.mkdirs()
                                val photoFile = File(imagesDir, "bill_${id}.jpg")
                                photoFile.writeBytes(bytes)
                                localPath = photoFile.absolutePath
                            } catch (err: Exception) {
                                err.printStackTrace()
                            }
                        }

                        val bill = Bill(
                            id = id,
                            category = category,
                            date = date,
                            refNumber = refNumber,
                            notes = notes,
                            photoPath = localPath,
                            ocrText = ocrText,
                            createdAt = createdAt
                        )
                        repository.insertBill(bill)
                        count++
                    }
                    count
                } catch (e: Exception) {
                    e.printStackTrace()
                    -1
                }
            }

            _syncStatus.value = "Idle"
            if (restoredCount >= 0) {
                _eventFlow.emit("✅ Restored $restoredCount bills successfully!")
            } else {
                _eventFlow.emit("❌ Failed to restore backup. File may be corrupted.")
            }
            onComplete(restoredCount)
        }
    }

    // Active full-effort Cloud Sync
    fun syncWithCloud(context: Context) {
        if (!_isCloudSyncEnabled.value) {
            viewModelScope.launch {
                _eventFlow.emit("❌ Enable Cloud Sync in settings first")
            }
            return
        }

        viewModelScope.launch {
            _syncStatus.value = "Syncing with cloud..."
            val success = withContext(Dispatchers.IO) {
                try {
                    val localBills = allBills.value

                    // 1. Upload local bill metadata + photo to Supabase
                    for (bill in localBills) {
                        uploadSingleBill(bill)
                    }

                    // 2. Pull tombstones first, and delete any matching local bills
                    // (this is how deletions propagate to the OTHER device).
                    val deletedIds = cloudSync.fetchTombstoneClientIds()
                    for (deletedId in deletedIds) {
                        repository.getBillById(deletedId)?.let { bill ->
                            if (bill.photoPath.isNotEmpty()) {
                                val file = File(bill.photoPath)
                                if (file.exists()) file.delete()
                            }
                            repository.deleteBillById(deletedId)
                        }
                    }

                    // Clean up tombstones older than 90 days so the table
                    // doesn't grow forever. Safe to do here since we've already
                    // applied all tombstones to the local DB above.
                    cloudSync.purgeOldTombstones()

                    // 3. Fetch remote documents not seen locally and not tombstoned
                    val remoteDocuments = cloudSync.fetchAllDocuments()
                    val localIds = localBills.map { it.id }.toSet()

                    var restoredCount = 0
                    for (doc in remoteDocuments) {
                        val id = doc.clientId
                        if (id.isBlank()) continue
                        if (localIds.contains(id)) continue
                        if (deletedIds.contains(id)) continue   // it was deleted, don't resurrect it

                        var localPath = ""
                        if (doc.imagePath.isNotEmpty()) {
                            val bytes = cloudSync.downloadPhotoBytes(doc.imagePath)
                            if (bytes != null) {
                                val imagesDir = File(context.filesDir, "bill_images")
                                if (!imagesDir.exists()) imagesDir.mkdirs()
                                val photoFile = File(imagesDir, "bill_${id}.jpg")
                                photoFile.writeBytes(bytes)
                                localPath = photoFile.absolutePath
                            }
                        }

                        val remoteBill = Bill(
                            id = id,
                            category = doc.category,
                            date = doc.date,
                            refNumber = doc.refNumber,
                            notes = doc.notes,
                            photoPath = localPath,
                            ocrText = doc.ocrText,
                            createdAt = doc.createdAt
                        )
                        repository.insertBill(remoteBill)
                        restoredCount++
                    }
                    true
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }

            _syncStatus.value = "Idle"
            if (success) {
                _eventFlow.emit("✅ Cloud Sync complete!")
            } else {
                _eventFlow.emit("❌ Sync failed. Please check network connection.")
            }
        }
    }

    private suspend fun uploadSingleBill(bill: Bill) {
        try {
            cloudSync.upsertBillRow(bill)

            if (bill.photoPath.isNotEmpty()) {
                val file = File(bill.photoPath)
                if (file.exists()) {
                    val path = cloudSync.uploadPhoto(bill.id, file.readBytes())
                    if (path != null) {
                        cloudSync.setImagePath(bill.id, path)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
