package com.example

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.*
import com.example.ui.BillViewModel
import com.example.ui.theme.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

fun compressAndResizeImage(context: Context, file: File) {
    try {
        if (!file.exists()) return
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) return
        
        val maxDimension = 1600
        var sampleSize = 1
        if (width > maxDimension || height > maxDimension) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / sampleSize) >= maxDimension && (halfWidth / sampleSize) >= maxDimension) {
                sampleSize *= 2
            }
        }
        
        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        var bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return
        
        if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val newWidth: Int
            val newHeight: Int
            if (bitmap.width > bitmap.height) {
                newWidth = maxDimension
                newHeight = (maxDimension / ratio).toInt()
            } else {
                newHeight = maxDimension
                newWidth = (maxDimension * ratio).toInt()
            }
            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            if (scaledBitmap != bitmap) {
                bitmap.recycle()
                bitmap = scaledBitmap
            }
        }
        
        try {
            val exifInterface = android.media.ExifInterface(file.absolutePath)
            val orientation = exifInterface.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = android.graphics.Matrix()
            var rotated = false
            when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> {
                    matrix.postRotate(90f)
                    rotated = true
                }
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> {
                    matrix.postRotate(180f)
                    rotated = true
                }
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> {
                    matrix.postRotate(270f)
                    rotated = true
                }
            }
            if (rotated) {
                val rotatedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = rotatedBitmap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        file.outputStream().use { fos ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos)
        }
        bitmap.recycle()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// Internal Screen state navigation mapping
sealed class Screen {
    object Dashboard : Screen()
    data class CategoryList(val categoryId: String) : Screen()
    object Search : Screen()
    data class Detail(val billId: String, val fromScreen: Screen) : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(applicationContext)
        val repository = BillRepository(database.billDao())
        
        val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return BillViewModel(repository) as T
            }
        })[BillViewModel::class.java]

        viewModel.loadCloudSyncPreference(applicationContext)
        viewModel.loadCategories(applicationContext)

        setContent {
            MyApplicationTheme {
                MainContent(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(viewModel: BillViewModel) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }

    // Dialog state controllers
    var showAddBillDialog by remember { mutableStateOf(false) }
    var showCloudSyncDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Read streams from view model
    val bills by viewModel.allBills.collectAsStateWithLifecycle()
    val categoriesList by viewModel.categoriesList.collectAsStateWithLifecycle()
    val isCloudSyncEnabled by viewModel.isCloudSyncEnabled.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()

    // Setup native back navigation handler
    BackHandler(enabled = currentScreen != Screen.Dashboard) {
        currentScreen = when (val screen = currentScreen) {
            is Screen.CategoryList -> Screen.Dashboard
            is Screen.Search -> Screen.Dashboard
            is Screen.Detail -> screen.fromScreen
            else -> Screen.Dashboard
        }
    }

    // Capture Toast events from viewmodel sharedFlow
    LaunchedEffect(key1 = true) {
        viewModel.eventFlow.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentScreen != Screen.Dashboard) {
                                IconButton(
                                    onClick = {
                                        currentScreen = when (val screen = currentScreen) {
                                            is Screen.CategoryList -> Screen.Dashboard
                                            is Screen.Search -> Screen.Dashboard
                                            is Screen.Detail -> screen.fromScreen
                                            else -> Screen.Dashboard
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Go back",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text(
                                text = "📁 Document Folder",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (currentScreen == Screen.Dashboard) {
                            Text(
                                text = "Snap it. File it. Find it later.",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier.padding(start = if (currentScreen != Screen.Dashboard) 48.dp else 0.dp)
                            )
                        } else if (currentScreen is Screen.CategoryList) {
                            val catId = (currentScreen as Screen.CategoryList).categoryId
                            val category = categoriesList.find { it.id == catId }
                            Text(
                                text = category?.name ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                                modifier = Modifier.padding(start = 48.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAboutDialog = true },
                        modifier = Modifier.testTag("guide_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Help/About User Guide",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(
                        onClick = { currentScreen = Screen.Search },
                        modifier = Modifier.testTag("search_toggle_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Open Global Search",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    IconButton(
                        onClick = { showCloudSyncDialog = true },
                        modifier = Modifier.testTag("sync_config_button")
                    ) {
                        Icon(
                            imageVector = if (isCloudSyncEnabled) Icons.Default.CloudQueue else Icons.Default.CloudOff,
                            contentDescription = "Cloud Sync & Backups",
                            tint = if (isCloudSyncEnabled) Color(0xFF81C784) else MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            if (currentScreen !is Screen.Search && currentScreen !is Screen.Detail) {
                FloatingActionButton(
                    onClick = { showAddBillDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = CircleShape,
                    modifier = Modifier
                        .testTag("add_bill_fab")
                        .size(56.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add new bill photo",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "ScreenTransition"
            ) { target ->
                when (target) {
                    is Screen.Dashboard -> DashboardScreen(
                        bills = bills,
                        categories = categoriesList,
                        onCategorySelected = { catId ->
                            currentScreen = Screen.CategoryList(catId)
                        }
                    )
                    is Screen.CategoryList -> CategoryListScreen(
                        categoryId = target.categoryId,
                        bills = bills,
                        categories = categoriesList,
                        onBillSelected = { id ->
                            currentScreen = Screen.Detail(id, target)
                        }
                    )
                    is Screen.Search -> SearchScreen(
                        viewModel = viewModel,
                        categories = categoriesList,
                        onBillSelected = { id ->
                            currentScreen = Screen.Detail(id, target)
                        }
                    )
                    is Screen.Detail -> DetailScreen(
                        billId = target.billId,
                        viewModel = viewModel,
                        onNavigateBack = {
                            currentScreen = target.fromScreen
                        }
                    )
                }
            }
        }
    }

    // Rendering bottomsheets/dialogs
    if (showAddBillDialog) {
        AddBillDialog(
            viewModel = viewModel,
            onDismiss = { showAddBillDialog = false }
        )
    }

    if (showCloudSyncDialog) {
        CloudSyncDialog(
            viewModel = viewModel,
            onDismiss = { showCloudSyncDialog = false }
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }
}

// ============================================================
// SCREEN A: MAIN DASHBOARD SCREEN
// ============================================================
@Composable
fun DashboardScreen(
    bills: List<Bill>,
    categories: List<CategoryInfo>,
    onCategorySelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcoming card summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Dashboard Summary Icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Filing Cabinet Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${bills.size} total bills saved completely offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        val rows = (categories.size + 1) / 2
        val gridHeight = (rows * 125).dp

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight) // Dynamically calculate height to fit all custom categories
        ) {
            items(categories) { cat ->
                val count = bills.count { it.category == cat.id }
                CategoryCard(
                    category = cat,
                    savedCount = count,
                    onClick = { onCategorySelected(cat.id) }
                )
            }
        }
    }
}

@Composable
fun CategoryCard(
    category: CategoryInfo,
    savedCount: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("category_card_${category.id}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 24.dp,
            bottomEnd = 12.dp,
            bottomStart = 12.dp
        ), // Custom file folder asymmetric design
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Folder tab colorful accent line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(category.color)
            )

            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = category.icon,
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$savedCount saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

// ============================================================
// SCREEN B: CATEGORY BILL LIST SCREEN
// ============================================================
@Composable
fun CategoryListScreen(
    categoryId: String,
    bills: List<Bill>,
    categories: List<CategoryInfo>,
    onBillSelected: (String) -> Unit
) {
    val category = categories.find { it.id == categoryId } ?: categories.last()
    val categoryBills = remember(bills, categoryId) {
        bills.filter { it.category == categoryId }
            .sortedByDescending { it.date }
    }

    if (categoryBills.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = category.icon,
                    fontSize = 64.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No saved bills here yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap the '+' button below to snapshot your first ${category.name} receipt.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categoryBills, key = { it.id }) { bill ->
                BillRow(bill = bill, category = category, onClick = { onBillSelected(bill.id) })
            }
        }
    }
}

@Composable
fun BillRow(
    bill: Bill,
    category: CategoryInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rounded photo thumbnail using Coil AsyncImage
            AsyncImage(
                model = if (bill.photoPath.isNotEmpty()) File(bill.photoPath) else null,
                contentDescription = "Bill preview thumbnail",
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.outline),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    text = if (bill.refNumber.isBlank()) "No Ref. Number" else bill.refNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = bill.date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (bill.notes.isNotBlank()) {
                    Text(
                        text = bill.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "View detail",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

// ============================================================
// SCREEN C: GLOBAL SEARCH SCREEN
// ============================================================
@Composable
fun SearchScreen(
    viewModel: BillViewModel,
    categories: List<CategoryInfo>,
    onBillSelected: (String) -> Unit
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateSearchQuery(it) },
            placeholder = { Text("Search number, date, notes, scanned photo text...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search query")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_input_field"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (query.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Placeholder",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Type to search across everything.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (searchResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "🤷",
                        fontSize = 48.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No bills match \"$query\"",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(searchResults, key = { it.id }) { bill ->
                    val cat = categories.find { it.id == bill.category } ?: categories.last()
                    
                    // Identify where the matches were made for explicit high-fidelity UI tags
                    val matchedLocations = remember(bill, query) {
                        val list = mutableListOf<String>()
                        val q = query.lowercase().trim()
                        if (bill.refNumber.lowercase().contains(q)) list.add("Reference")
                        if (bill.notes.lowercase().contains(q)) list.add("Notes")
                        if (bill.ocrText.lowercase().contains(q)) list.add("Photo text")
                        if (bill.date.lowercase().contains(q)) list.add("Date")
                        if (cat.name.lowercase().contains(q)) list.add("Category")
                        list
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onBillSelected(bill.id) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = if (bill.photoPath.isNotEmpty()) File(bill.photoPath) else null,
                                contentDescription = "Thumbnail",
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.outline),
                                contentScale = ContentScale.Crop
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${cat.icon} ${cat.name} — ${if (bill.refNumber.isBlank()) "No Ref" else bill.refNumber}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = bill.date,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                if (matchedLocations.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Matched: ${matchedLocations.joinToString(", ")}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                                    shape = RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// SCREEN D: BILL DETAIL VIEW SCREEN
// ============================================================
@Composable
fun DetailScreen(
    billId: String,
    viewModel: BillViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val bills by viewModel.allBills.collectAsStateWithLifecycle()
    val categories by viewModel.categoriesList.collectAsStateWithLifecycle()
    val bill = remember(bills, billId) { bills.find { it.id == billId } }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (bill == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Bill details not found or deleted.", style = MaterialTheme.typography.titleMedium)
        }
        return
    }

    val category = categories.find { it.id == bill.category } ?: categories.last()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // High resolution photo card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = if (bill.photoPath.isNotEmpty()) File(bill.photoPath) else null,
                    contentDescription = "High-res bill scan",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Details grid container
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetailItem(label = "Category", value = "${category.icon} ${category.name}")
                DetailItem(label = "Date Filed", value = bill.date)
                DetailItem(
                    label = "Reference / Consumer / Thandaper Number",
                    value = if (bill.refNumber.isBlank()) "None declared" else bill.refNumber
                )
                DetailItem(
                    label = "My Personal Notes",
                    value = if (bill.notes.isBlank()) "None added" else bill.notes
                )
            }
        }

        // Offline scanned text
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF3E0)), // Parchment yellow box
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "📄 Automatically Detected Text (Local OCR)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = if (bill.ocrText.isBlank()) "No printed text was detected on this photo receipt." else bill.ocrText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Action Buttons
        Button(
            onClick = {
                val file = File(bill.photoPath)
                if (file.exists()) {
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "com.aistudio.billfolder.vintag.fileprovider",
                            file
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share/Save Photo"))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Sharing failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Local image file was deleted or not found.", Toast.LENGTH_SHORT).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("share_photo_button")
                .height(48.dp)
        ) {
            Icon(Icons.Default.Share, contentDescription = "Share")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Photo Scan")
        }

        OutlinedButton(
            onClick = { showDeleteConfirmation = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("delete_bill_button")
                .height(48.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Delete Bill Receipt")
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Receipt permanently?") },
            text = { Text("This will delete the local database entry and the photo permanently. This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteBill(billId) {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DetailItem(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ============================================================
// MODAL: ADD BILL MODAL (BOTTOMSHEET-LIKE FULL SCREEN DIALOG)
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBillDialog(
    viewModel: BillViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val categories by viewModel.categoriesList.collectAsStateWithLifecycle()
    
    // Form fields
    var selectedCategory by remember { mutableStateOf("electricity") }
    var refNumber by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedDate by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-DD", Locale.US).format(Date())) }

    // OCR scanning results from Viewmodel
    val ocrLoading by viewModel.ocrLoading.collectAsStateWithLifecycle()
    val detectedOcrText by viewModel.detectedOcrText.collectAsStateWithLifecycle()

    // Temporary captured image file
    var capturedPhotoFile by remember { mutableStateOf<File?>(null) }
    var photoUriString by remember { mutableStateOf("") }

    // Image capture system launcher setup
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && capturedPhotoFile != null) {
            val file = capturedPhotoFile!!
            // Compress and resize the image for efficient storage/sync
            compressAndResizeImage(context, file)
            photoUriString = file.absolutePath
            
            // Run background OCR locally using ML Kit
            viewModel.setOcrLoading(true)
            viewModel.setOcrText("")
            
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val inputImage = InputImage.fromFilePath(context, Uri.fromFile(file))
                recognizer.process(inputImage)
                    .addOnSuccessListener { textResult ->
                        viewModel.setOcrLoading(false)
                        viewModel.setOcrText(textResult.text)
                    }
                    .addOnFailureListener {
                        viewModel.setOcrLoading(false)
                        viewModel.setOcrText("")
                    }
            } catch (e: Exception) {
                viewModel.setOcrLoading(false)
                viewModel.setOcrText("")
                e.printStackTrace()
            }
        }
    }

    // Photo selection from gallery launcher setup
    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.setOcrLoading(true)
            viewModel.setOcrText("")
            
            // Save the Uri to our internal application directories
            val dir = File(context.filesDir, "bill_images")
            if (!dir.exists()) dir.mkdirs()
            val destFile = File(dir, "bill_${System.currentTimeMillis()}.jpg")
            
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Compress and resize the image for efficient storage/sync
                compressAndResizeImage(context, destFile)
                capturedPhotoFile = destFile
                photoUriString = destFile.absolutePath

                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromFilePath(context, Uri.fromFile(destFile))
                recognizer.process(inputImage)
                    .addOnSuccessListener { textResult ->
                        viewModel.setOcrLoading(false)
                        viewModel.setOcrText(textResult.text)
                    }
                    .addOnFailureListener {
                        viewModel.setOcrLoading(false)
                        viewModel.setOcrText("")
                    }
            } catch (e: Exception) {
                viewModel.setOcrLoading(false)
                viewModel.setOcrText("")
                e.printStackTrace()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(16.dp, 16.dp, 0.dp, 0.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Add New Bill Scan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Dialog")
                    }
                }

                // Category selector dropdown
                var dropdownExpanded by remember { mutableStateOf(false) }
                val currentCategoryInfo = categories.find { it.id == selectedCategory } ?: categories.last()

                Column {
                    Text(
                        text = "Select Category",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { dropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${currentCategoryInfo.icon} ${currentCategoryInfo.name}",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Dropdown expand", tint = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text("${cat.icon} ${cat.name}") },
                                    onClick = {
                                        selectedCategory = cat.id
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Image Capture buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val file = File(context.filesDir, "bill_images").apply { if (!exists()) mkdirs() }
                            val captureFile = File(file, "capture_${System.currentTimeMillis()}.jpg")
                            capturedPhotoFile = captureFile
                            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "com.aistudio.billfolder.vintag.fileprovider",
                                captureFile
                            )
                            takePictureLauncher.launch(photoUri)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Camera")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("📷 Take Photo")
                    }

                    Button(
                        onClick = {
                            pickPhotoLauncher.launch("image/*")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        Icon(Icons.Default.Photo, contentDescription = "Gallery")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🖼️ From Gallery")
                    }
                }

                // Image preview and offline OCR scroll box
                if (photoUriString.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.outline)
                    ) {
                        AsyncImage(
                            model = File(photoUriString),
                            contentDescription = "Captured bill receipt",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }

                    if (ocrLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reading printed text from photo locally...", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (detectedOcrText.isNotEmpty()) {
                        // Parchment yellow box for dynamic local OCR scroll
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF3E0)),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "Text Scanned Locally:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 80.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = detectedOcrText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Date selection field
                val calendar = Calendar.getInstance()
                val datePickerDialog = DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        // Format to ISO
                        val mStr = if (month + 1 < 10) "0${month + 1}" else "${month + 1}"
                        val dStr = if (dayOfMonth < 10) "0$dayOfMonth" else "$dayOfMonth"
                        selectedDate = "$year-$mStr-$dStr"
                    },
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)
                )

                Column {
                    Text(
                        text = "Receipt Date",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { datePickerDialog.show() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = selectedDate, color = MaterialTheme.colorScheme.onSurface)
                            Icon(Icons.Default.CalendarToday, contentDescription = "Pick Date", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

                // Reference field
                OutlinedTextField(
                    value = refNumber,
                    onValueChange = { refNumber = it },
                    label = { Text("Consumer ID / Land No / Ref Number") },
                    placeholder = { Text("e.g. Consumer No. 1024513") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Personal Notes field
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Personal notes (Optional)") },
                    placeholder = { Text("Anything worth remembering about this payment...") },
                    maxLines = 4,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        viewModel.addBill(
                            category = selectedCategory,
                            date = selectedDate,
                            refNumber = refNumber,
                            notes = notes,
                            photoPath = photoUriString,
                            ocrText = detectedOcrText
                        ) {
                            onDismiss()
                        }
                    },
                    enabled = photoUriString.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_bill_button")
                        .height(48.dp)
                ) {
                    Text("Save Bill Receipt")
                }
            }
        }
    }
}

// ============================================================
// MODAL: CLOUD SYNC & BACKUPS DIALOG
// ============================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncDialog(
    viewModel: BillViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val isConnected by viewModel.isCloudSyncEnabled.collectAsStateWithLifecycle()

    // Backup file save launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            // Write exported cache backup file into user target folder uri
            viewModel.exportBackup(context) { cacheUri ->
                if (cacheUri != null) {
                    try {
                        context.contentResolver.openInputStream(cacheUri)?.use { input ->
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        Toast.makeText(context, "✅ Backup JSON exported!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Document Picker restore launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.restoreBackup(context, uri) { restoredCount ->
                if (restoredCount >= 0) {
                    Toast.makeText(context, "Successfully restored $restoredCount bills!", Toast.LENGTH_LONG).show()
                    onDismiss()
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💾 Local Backups",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Dialog")
                    }
                }

                Text(
                    text = "Save self-contained backups with embedded offline base64 photo scans. Works without cloud configuration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Button(
                    onClick = {
                        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        exportLauncher.launch("bill_folder_backup_$timestamp.json")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download JSON backup")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download Backup (JSON)")
                }

                Button(
                    onClick = { importLauncher.launch("application/json") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Upload, contentDescription = "Upload JSON backup")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restore from Backup File")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline)

                Text(
                    text = "🏷️ Custom Categories",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Create custom category labels for your bills. You can choose a custom name, emoji icon, and color.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                val categoriesList by viewModel.categoriesList.collectAsStateWithLifecycle()

                // List of existing categories with delete button for custom ones
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Existing Categories:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        categoriesList.forEach { cat ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .background(cat.color, shape = CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = "${cat.icon} ${cat.name}", style = MaterialTheme.typography.bodyMedium)
                                }
                                
                                val isDefault = com.example.data.CATEGORIES.any { it.id == cat.id }
                                if (!isDefault) {
                                    IconButton(
                                        onClick = { viewModel.deleteCustomCategory(context, cat.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete custom category",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "System",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Add Category Form
                var newCatName by remember { mutableStateOf("") }
                var selectedEmoji by remember { mutableStateOf("🛒") }
                val emojiList = listOf("🛒", "🍔", "🚗", "🏠", "🎁", "✈️", "👔", "🎓", "🩺", "📈", "🛠️", "🎮", "🍿", "🐾")
                var emojiDropdownExpanded by remember { mutableStateOf(false) }

                val colorsMap = listOf(
                    "Blue" to "#1E88E5",
                    "Green" to "#43A047",
                    "Red" to "#E53935",
                    "Orange" to "#FB8C00",
                    "Purple" to "#8E24AA",
                    "Pink" to "#D81B60",
                    "Teal" to "#00897B",
                    "Cyan" to "#00ACC1",
                    "Grey" to "#5A6270"
                )
                var selectedColorPair by remember { mutableStateOf(colorsMap[0]) }
                var colorDropdownExpanded by remember { mutableStateOf(false) }

                OutlinedTextField(
                    value = newCatName,
                    onValueChange = { newCatName = it },
                    label = { Text("Category Name") },
                    placeholder = { Text("e.g. Groceries, Subscriptions") },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Emoji Selector
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { emojiDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Icon: $selectedEmoji")
                        }
                        DropdownMenu(
                            expanded = emojiDropdownExpanded,
                            onDismissRequest = { emojiDropdownExpanded = false }
                        ) {
                            emojiList.forEach { emoji ->
                                DropdownMenuItem(
                                    text = { Text(emoji) },
                                    onClick = {
                                        selectedEmoji = emoji
                                        emojiDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Color Selector
                    Box(modifier = Modifier.weight(1.5f)) {
                        OutlinedButton(
                            onClick = { colorDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(Color(android.graphics.Color.parseColor(selectedColorPair.second)), shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(selectedColorPair.first)
                            }
                        }
                        DropdownMenu(
                            expanded = colorDropdownExpanded,
                            onDismissRequest = { colorDropdownExpanded = false }
                        ) {
                            colorsMap.forEach { pair ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .background(Color(android.graphics.Color.parseColor(pair.second)), shape = CircleShape)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(pair.first)
                                        }
                                    },
                                    onClick = {
                                        selectedColorPair = pair
                                        colorDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        viewModel.addCustomCategory(context, newCatName, selectedEmoji, selectedColorPair.second)
                        newCatName = "" // Reset
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add custom category icon")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Add Custom Category")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline)

                Text(
                    text = "☁️ Cloud Sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Synchronize receipt metadata + photos across your devices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isConnected) "Enabled" else "Disabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Switch(
                        checked = isConnected,
                        onCheckedChange = { enabled -> viewModel.setCloudSyncEnabled(context, enabled) }
                    )
                }

                Button(
                    onClick = { viewModel.syncWithCloud(context) },
                    enabled = isConnected,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = "Sync Now")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync Now (Bidirectional)")
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Sync Log Status:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = syncStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ============================================================
// MODAL: USAGE GUIDE / ABOUT DIALOG
// ============================================================
@Composable
fun AboutDialog(
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📖 About Bill Folder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close Dialog")
                    }
                }

                Text(
                    text = "Most homes deal with a steady pile of paper: electricity bills, water bills, land tax and building tax receipts, cable/subscription invoices — each with its own account number, due date, and payment history buried somewhere in fine print. They get lost, faded, or thrown out before you need them again — usually right when a payment dispute or renewal comes up.\n\n" +
                            "Bill Folder turns your phone into a secure, elegant filing cabinet for all of it. Photograph a bill once, sort it into a category, and it's saved permanently on your device — searchable by any number or word on it, even ones you never typed in yourself.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = "💡 Adding a bill",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Tap the green '+' button → choose a category → tap 'Take Photo' (opens camera) or 'Choose from Gallery'. Fill in the date and a reference/ID number if you have one, add notes, then Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Text(
                    text = "🏷️ Custom Categories",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Need more folders beyond the default 5? Tap the Cloud/Backup settings button on the top right, scroll to 'Custom Categories', enter a name, choose an emoji icon and color, and tap 'Add Custom Category'. It will instantly appear on your dashboard! You can also delete custom categories you no longer need.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Text(
                    text = "⚡ Automatic text reading (OCR)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Right after you take or pick a photo, the app automatically scans it for any printed text — bill numbers, names, addresses — and shows you what it found. This happens entirely on your device; nothing is sent anywhere.\n\n" +
                            "Printed Latin/English characters work well. Handwriting is not supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Text(
                    text = "🔒 Offline & Private",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "All your bills live only on this device unless you back them up or connect cloud sync. Uninstalling the app deletes everything that hasn't been backed up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Text(
                    text = "🖼️ Smart Image Compression",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "No manual button is needed! To keep your device storage clean and fast, the app automatically compresses and downscales images immediately upon capture or selection from the gallery. Large, heavy photos are resized to a maximum of 1600px and saved as optimized JPEGs (typically 100-200 KB) while perfectly preserving text legibility.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Developed by Muraly",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "📧 Contact: 7012451340",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Version 1.0.0 • Pure Native Android",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
