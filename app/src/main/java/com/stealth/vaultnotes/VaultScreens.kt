package com.stealth.vaultnotes

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// عقد مخصص يعتمد حصرياً على Storage Access Framework (Intent.ACTION_OPEN_DOCUMENT)
// لضمان صلاحية قراءة وحذف الملف الأصلي من المعرض دون استخدام Photo Picker نهائياً

private class CountingOutputStream(output: java.io.OutputStream) : java.io.FilterOutputStream(output) {
    var bytesWritten: Long = 0L

    override fun write(value: Int) {
        out.write(value)
        bytesWritten++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        out.write(buffer, offset, length)
        bytesWritten += length
    }
}

class SafOpenMultipleDocumentsContract : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (input.size == 1) input[0] else "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val uris = LinkedHashSet<Uri>()
        intent.data?.let { uris.add(it) }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        return uris.toList()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SecretVaultScreen(
    activity: ComponentActivity,
    printContext: Context,
    onExitVault: () -> Unit,
    onPickerStateChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFormatter = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    var currentTab by remember { mutableIntStateOf(0) }
    var vaultFiles by remember { mutableStateOf(StorageRepository.loadVaultFiles(context)) }
    var secretNotes by remember { mutableStateOf(StorageRepository.loadSecretNotes(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(SortOption.DATE_DESC) }
    val vaultViewPrefs = remember { context.getSharedPreferences("vault_view_preferences", Context.MODE_PRIVATE) }
    val restoreLocationPrefs = remember { context.getSharedPreferences("vault_restore_preferences", Context.MODE_PRIVATE) }
    var isGridView by remember { mutableStateOf(vaultViewPrefs.getBoolean("grid_view", false)) }
    var currentSubFolder by remember { mutableStateOf("General") }
    var showSecretTrash by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showRenameFolderDialog by remember { mutableStateOf(false) }
    var folderBeingRenamed by remember { mutableStateOf("") }
    var showMoveFolderDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf("") }

    var savedSubFolders by remember(currentTab) {
        mutableStateOf(
            StorageRepository.loadVaultFolders(
                context,
                when (currentTab) {
                    0 -> VaultMediaType.PHOTO
                    1 -> VaultMediaType.VIDEO
                    else -> VaultMediaType.DOCUMENT
                }
            )
        )
    }

    val selectedIds = remember { mutableStateListOf<String>() }
    var activeViewerIndex by remember { mutableStateOf<Int?>(null) }
    var activePdfItem by remember { mutableStateOf<VaultFileItem?>(null) }
    var editingSecretNote by remember { mutableStateOf<NoteItem?>(null) }
    var pendingRestoreItems by remember { mutableStateOf<List<VaultFileItem>>(emptyList()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                // The service may finish at the same moment the UI is being locked.
                // Never touch the encrypted index from this callback after lock.
                if (!SecurityCrypto.isVaultSessionUnlocked()) return
                val succeeded = intent?.getBooleanExtra("success", false) == true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { VaultTransferManager.recoverPending(context) }
                    }
                    if (!SecurityCrypto.isVaultSessionUnlocked()) return@launch
                    vaultFiles = StorageRepository.loadVaultFiles(context)
                    isBusy = false
                    busyMessage = ""
                    selectedIds.clear()
                    val message = if (AppLanguage.getLanguage(context) == AppLanguage.ARABIC) {
                        if (succeeded) "اكتملت عملية نقل الملفات في الخلفية."
                        else "انتهت عملية النقل مع وجود ملفات لم تكتمل؛ النسخ الأصلية المحفوظة داخل الخزنة لم تُحذف."
                    } else {
                        if (succeeded) "The background file transfer is complete."
                        else "The transfer ended with incomplete items; remaining vault originals were kept."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            android.content.IntentFilter(VaultTransferService.ACTION_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(currentTab) {
        currentSubFolder = savedSubFolders.firstOrNull() ?: "General"
        selectedIds.clear()
    }

    BackHandler(enabled = activeViewerIndex == null && activePdfItem == null && editingSecretNote == null && !isBusy) {
        when {
            selectedIds.isNotEmpty() -> selectedIds.clear()
            currentTab == 4 -> currentTab = 0
            currentTab == 3 && showSecretTrash -> showSecretTrash = false
            currentTab in 0..2 && currentSubFolder != (savedSubFolders.firstOrNull() ?: "General") -> currentSubFolder = savedSubFolders.firstOrNull() ?: "General"
            searchQuery.isNotEmpty() -> searchQuery = ""
            else -> {
                SecurityCrypto.lockVaultSession(context)
                onExitVault()
            }
        }
    }

// Background import is deliberately independent from this Compose screen's lifecycle.
    val safImportLauncher = rememberLauncherForActivityResult(SafOpenMultipleDocumentsContract()) { uris: List<Uri> ->
        onPickerStateChange(false)
        if (uris.isNotEmpty()) {
            val targetType = when (currentTab) {
                0 -> VaultMediaType.PHOTO
                1 -> VaultMediaType.VIDEO
                else -> VaultMediaType.DOCUMENT
            }
            val targetFolder = currentSubFolder
            val persistableUris = uris.filter { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    true
                } catch (_: Exception) {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            if (persistableUris.isNotEmpty()) {
                isBusy = true
                busyMessage = AppLanguage.text(
                    context,
                    "Moving ${persistableUris.size} file(s) into the vault...",
                    "جارٍ نقل ${persistableUris.size} ملفًا إلى الخزنة..."
                )
                if (!VaultTransferManager.startImport(context, persistableUris, targetType, targetFolder)) {
                    isBusy = false
                    busyMessage = ""
                    Toast.makeText(
                        context,
                        AppLanguage.text(context, "Could not start background import. Originals were kept.", "تعذّر بدء الاستيراد في الخلفية. تم الاحتفاظ بالملفات الأصلية."),
                        Toast.LENGTH_LONG
                    ).show()
                } else if (persistableUris.size < uris.size) {
                    Toast.makeText(
                        context,
                        AppLanguage.text(context, "Some selected files could not receive persistent access and were skipped.", "تعذّر منح صلاحية دائمة لبعض الملفات المحددة، لذلك تم تخطيها."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    AppLanguage.text(context, "The selected provider did not grant background file access. No files were moved.", "مصدر الملفات لم يمنح صلاحية الوصول في الخلفية. لم يتم نقل أي ملفات."),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Background export continues independently after the vault UI is locked.
    val safRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        onPickerStateChange(false)
        val itemsToRestore = pendingRestoreItems
        pendingRestoreItems = emptyList()
        if (treeUri != null && itemsToRestore.isNotEmpty()) {
            val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = try {
                context.contentResolver.takePersistableUriPermission(treeUri, grantFlags)
                true
            } catch (_: Exception) {
                false
            }
            if (persisted) {
                val previousUri = restoreLocationPrefs.getString("last_restore_tree_uri", null)
                if (!previousUri.isNullOrBlank() && previousUri != treeUri.toString()) {
                    try {
                        context.contentResolver.releasePersistableUriPermission(Uri.parse(previousUri), grantFlags)
                    } catch (_: Exception) {}
                }
                restoreLocationPrefs.edit().putString("last_restore_tree_uri", treeUri.toString()).apply()
                isBusy = true
                busyMessage = AppLanguage.text(
                    context,
                    "Restoring ${itemsToRestore.size} file(s)...",
                    "جارٍ استعادة ${itemsToRestore.size} ملفًا..."
                )
                if (!VaultTransferManager.startExport(context, itemsToRestore, treeUri)) {
                    isBusy = false
                    busyMessage = ""
                    Toast.makeText(
                        context,
                        AppLanguage.text(context, "Could not start background export. Files remain in the vault.", "تعذّر بدء الإخراج في الخلفية. الملفات ما زالت داخل الخزنة."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    AppLanguage.text(context, "The destination did not grant persistent access. Files remain in the vault.", "المجلد المحدد لم يمنح صلاحية دائمة. الملفات ما زالت داخل الخزنة."),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun launchRestorePicker() {
        val savedUri = restoreLocationPrefs.getString("last_restore_tree_uri", null)
        val initialUri = savedUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        safRestoreLauncher.launch(initialUri)
    }

    editingSecretNote?.let { secretNote ->
        NoteEditorScreen(
            activity = activity,
            printContext = printContext,
            note = secretNote,
            onSave = { saved ->
                val list = secretNotes.toMutableList()
                val idx = list.indexOfFirst { it.id == saved.id }
                val finalNote = saved.copy(isSecret = true)
                if (idx >= 0) list[idx] = finalNote else list.add(0, finalNote)
                if (StorageRepository.saveSecretNotes(context, list)) {
                    secretNotes = list
                } else {
                    Toast.makeText(context, AppLanguage.text(context, "Could not save private note.", "تعذّر حفظ الملاحظة الخاصة."), Toast.LENGTH_LONG).show()
                    throw IllegalStateException("Private note save failed")
                }
            },
            onBack = { editingSecretNote = null },
            onScheduleReminder = { _, _ -> },
            allowReminders = false,
            discardIfEmpty = secretNote.id !in secretNotes.map { it.id }
        )
        return
    }

    activePdfItem?.let { pdfItem ->
        InAppPdfViewer(item = pdfItem, onClose = {
            SecurityCrypto.clearDecryptedTempCache(context)
            activePdfItem = null
        })
        return
    }

    val currentMediaType = when (currentTab) {
        0 -> VaultMediaType.PHOTO
        1 -> VaultMediaType.VIDEO
        else -> VaultMediaType.DOCUMENT
    }

    // Keep swipe navigation inside the currently selected media type and folder.
    // The trash viewer intentionally contains both photos and videos.
    val allVaultMediaList = remember(vaultFiles, currentTab, currentSubFolder, searchQuery, sortOption) {
        val base = if (currentTab == 4) {
            vaultFiles.filter {
                it.isTrashed &&
                    (it.mediaType == VaultMediaType.PHOTO || it.mediaType == VaultMediaType.VIDEO)
            }
        } else {
            vaultFiles.filter {
                !it.isTrashed &&
                    it.mediaType == currentMediaType &&
                    it.subFolder == currentSubFolder
            }
        }
        val searched = if (searchQuery.isBlank()) base else base.filter { it.originalName.contains(searchQuery, ignoreCase = true) }
        when (sortOption) {
            SortOption.DATE_DESC -> searched.sortedByDescending { it.addedAt }
            SortOption.NAME_ASC -> searched.sortedBy { it.originalName.lowercase(Locale.ROOT) }
            SortOption.SIZE_DESC -> searched.sortedByDescending { it.sizeBytes }
        }
    }

    val filteredFiles = remember(vaultFiles, currentTab, currentSubFolder, searchQuery, sortOption) {
        val base = if (currentTab == 4) vaultFiles.filter { it.isTrashed }
        else vaultFiles.filter { !it.isTrashed && it.mediaType == currentMediaType && it.subFolder == currentSubFolder }
        val searched = if (searchQuery.isBlank()) base else base.filter { it.originalName.contains(searchQuery, ignoreCase = true) }
        when (sortOption) {
            SortOption.DATE_DESC -> searched.sortedByDescending { it.addedAt }
            SortOption.NAME_ASC -> searched.sortedBy { it.originalName.lowercase(Locale.ROOT) }
            SortOption.SIZE_DESC -> searched.sortedByDescending { it.sizeBytes }
        }
    }

    activeViewerIndex?.let { startIndex ->
        if (allVaultMediaList.isNotEmpty()) {
            InAppMediaPagerViewer(
                items = allVaultMediaList,
                initialIndex = startIndex.coerceIn(0, allVaultMediaList.lastIndex),
                onClose = {
                    SecurityCrypto.clearDecryptedTempCache(context)
                    activeViewerIndex = null
                }
            )
            return
        } else {
            activeViewerIndex = null
        }
    }

    val subFolders = remember(vaultFiles, currentMediaType, savedSubFolders) {
        val set = linkedSetOf(savedSubFolders.firstOrNull() ?: "General")
        set.addAll(savedSubFolders)
        vaultFiles.filter { !it.isTrashed && it.mediaType == currentMediaType }.forEach { set.add(it.subFolder) }
        set.toList()
    }

    Scaffold(
        containerColor = Color(0xFF0D0D0D),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A)),
                title = {
                    val headerTitle = when {
                        selectedIds.isNotEmpty() -> AppLanguage.text(context, "${selectedIds.size} selected", "تم تحديد ${selectedIds.size}")
                        currentTab == 4 -> AppLanguage.text(context, "Vault Trash (30d)", "محذوفات الخزنة (30 يومًا)")
                        currentTab == 3 && showSecretTrash -> AppLanguage.text(context, "Hidden Notes Trash", "محذوفات الملاحظات المخفية")
                        else -> AppLanguage.text(context, "Private Vault 🔒", "الخزنة الخاصة 🔒")
                    }
                    Text(headerTitle, color = Color(0xFF69F0AE), fontWeight = FontWeight.Bold, maxLines = 1)
                },
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        if (filteredFiles.isNotEmpty()) {
                            val allVisibleSelected = filteredFiles.all { it.id in selectedIds }
                            IconButton(onClick = {
                                if (allVisibleSelected) {
                                    selectedIds.removeAll(filteredFiles.map { it.id }.toSet())
                                } else {
                                    filteredFiles.forEach { item ->
                                        if (item.id !in selectedIds) selectedIds.add(item.id)
                                    }
                                }
                            }) {
                                Icon(
                                    Icons.Default.SelectAll,
                                    contentDescription = if (allVisibleSelected) AppLanguage.text(context, "Deselect all visible", "إلغاء تحديد الكل") else AppLanguage.text(context, "Select all visible", "تحديد الكل"),
                                    tint = Color.White
                                )
                            }
                        }
                        if (currentTab == 4) {
                            IconButton(onClick = {
                                val updated = vaultFiles.map {
                                    if (it.id in selectedIds) it.copy(isTrashed = false, trashedAt = null) else it
                                }.toMutableList()
                                if (StorageRepository.saveVaultFiles(context, updated)) {
                                    vaultFiles = updated
                                    selectedIds.clear()
                                    Toast.makeText(context, AppLanguage.text(context, "Restored to vault ✅", "تمت الاستعادة إلى الخزنة ✅"), Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.Restore, contentDescription = AppLanguage.text(context, "Restore to vault", "استعادة إلى الخزنة"), tint = Color(0xFF69F0AE))
                            }
                        } else {
                            IconButton(onClick = { showMoveFolderDialog = true }) {
                                Icon(Icons.Default.DriveFileMove, contentDescription = AppLanguage.text(context, "Move to folder", "نقل إلى مجلد"), tint = Color(0xFFFFD54F))
                            }
                        }
                        IconButton(onClick = {
                            pendingRestoreItems = vaultFiles.filter { it.id in selectedIds }
                            onPickerStateChange(true)
                            launchRestorePicker()
                        }) {
                            Icon(Icons.Default.Unarchive, contentDescription = AppLanguage.text(context, "Restore to device", "استعادة إلى الجهاز"), tint = Color(0xFF40C4FF))
                        }
                        IconButton(onClick = {
                            val selectedItems = vaultFiles.filter { it.id in selectedIds }
                            val updated = if (currentTab == 4) vaultFiles.filterNot { it.id in selectedIds }
                            else vaultFiles.map {
                                if (it.id in selectedIds) it.copy(isTrashed = true, trashedAt = System.currentTimeMillis()) else it
                            }
                            if (StorageRepository.saveVaultFiles(context, updated)) {
                                if (currentTab == 4) {
                                    var cleanupFailures = 0
                                    selectedItems.forEach { item ->
                                        val blob = File(StorageRepository.getVaultBlobDir(context), item.encryptedFileName)
                                        if (blob.exists() && !blob.delete() && blob.exists()) cleanupFailures++
                                    }
                                    if (cleanupFailures > 0) {
                                        Toast.makeText(context, AppLanguage.text(context, "$cleanupFailures file(s) were removed from the vault index but could not be physically deleted.", "تمت إزالة $cleanupFailures ملفًا من فهرس الخزنة، لكن تعذّر حذفها فعليًا."), Toast.LENGTH_LONG).show()
                                    }
                                }
                                vaultFiles = updated.toMutableList()
                                selectedIds.clear()
                            } else {
                                Toast.makeText(context, AppLanguage.text(context, "Could not update vault.", "تعذّر تحديث الخزنة."), Toast.LENGTH_LONG).show()
                            }
                        }) {
                            Icon(
                                if (currentTab == 4) Icons.Default.DeleteForever else Icons.Default.Delete,
                                contentDescription = if (currentTab == 4) AppLanguage.text(context, "Delete permanently", "حذف نهائي") else AppLanguage.text(context, "Move to trash", "نقل إلى سلة المحذوفات"),
                                tint = Color(0xFFFF5252)
                            )
                        }
                        IconButton(onClick = { selectedIds.clear() }) {
                            Icon(Icons.Default.Close, contentDescription = AppLanguage.text(context, "Clear selection", "إلغاء التحديد"), tint = Color.White)
                        }
                    } else {
                        if (currentTab in 0..2 || currentTab == 4) {
                            IconButton(
                                onClick = { selectedIds.addAll(filteredFiles.map { it.id }) },
                                enabled = filteredFiles.isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.SelectAll,
                                    contentDescription = AppLanguage.text(context, "Select all", "تحديد الكل"),
                                    tint = if (filteredFiles.isNotEmpty()) Color(0xFF69F0AE) else Color.Gray
                                )
                            }
                            IconButton(onClick = {
                                isGridView = !isGridView
                                vaultViewPrefs.edit().putBoolean("grid_view", isGridView).apply()
                            }) {
                                Icon(
                                    if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                    contentDescription = if (isGridView) AppLanguage.text(context, "List view", "عرض القائمة") else AppLanguage.text(context, "Grid view", "عرض الشبكة"),
                                    tint = Color.White
                                )
                            }
                        }
                        if (currentTab == 4 || (currentTab == 3 && showSecretTrash)) {
                            IconButton(onClick = { showEmptyTrashDialog = true }) {
                                Icon(Icons.Default.DeleteForever, contentDescription = AppLanguage.text(context, "Empty trash", "إفراغ سلة المحذوفات"), tint = Color(0xFFFF5252))
                            }
                        }
                        if (currentTab == 3) {
                            IconButton(onClick = { showSecretTrash = !showSecretTrash }) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = AppLanguage.text(context, "Private notes trash", "محذوفات الملاحظات الخاصة"),
                                    tint = if (showSecretTrash) Color(0xFFFF5252) else Color.White
                                )
                            }
                        }
                        IconButton(onClick = {
                            sortOption = when (sortOption) {
                                SortOption.DATE_DESC -> SortOption.NAME_ASC
                                SortOption.NAME_ASC -> SortOption.SIZE_DESC
                                SortOption.SIZE_DESC -> SortOption.DATE_DESC
                            }
                            val label = when (sortOption) {
                                SortOption.DATE_DESC -> AppLanguage.text(context, "Date Added", "تاريخ الإضافة")
                                SortOption.NAME_ASC -> AppLanguage.text(context, "Name", "الاسم")
                                SortOption.SIZE_DESC -> AppLanguage.text(context, "Size", "الحجم")
                            }
                            Toast.makeText(context, AppLanguage.text(context, "Sorted by: $label", "الترتيب حسب: $label"), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.Sort, contentDescription = AppLanguage.text(context, "Sort", "ترتيب"), tint = Color.White)
                        }
                        if (currentTab != 3) {
                            IconButton(onClick = { currentTab = if (currentTab == 4) 0 else 4 }) {
                                Icon(
                                    Icons.Default.AutoDelete,
                                    contentDescription = AppLanguage.text(context, "Vault trash", "محذوفات الخزنة"),
                                    tint = if (currentTab == 4) Color(0xFFFF5252) else Color.Gray
                                )
                            }
                        }
                        IconButton(onClick = {
                            SecurityCrypto.lockVaultSession(context)
                            onExitVault()
                        }) {
                            Icon(Icons.Default.Lock, contentDescription = AppLanguage.text(context, "Lock vault", "قفل الخزنة"), tint = Color(0xFFFF5252))
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1A1A1A)) {
                NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0 }, icon = { Icon(Icons.Default.PhotoLibrary, null) }, label = { Text(AppLanguage.text(context, "Photos", "الصور")) })
                NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Default.VideoLibrary, null) }, label = { Text(AppLanguage.text(context, "Videos", "الفيديوهات")) })
                NavigationBarItem(selected = currentTab == 2, onClick = { currentTab = 2 }, icon = { Icon(Icons.Default.FolderShared, null) }, label = { Text(AppLanguage.text(context, "Documents", "المستندات")) })
                NavigationBarItem(selected = currentTab == 3, onClick = { currentTab = 3 }, icon = { Icon(Icons.Default.EnhancedEncryption, null) }, label = { Text(AppLanguage.text(context, "Hidden Notes", "الملاحظات المخفية")) })
            }
        },
        floatingActionButton = {
            if (currentTab in 0..3 && !(currentTab == 3 && showSecretTrash)) {
                FloatingActionButton(
                    containerColor = Color(0xFF69F0AE),
                    contentColor = Color.Black,
                    onClick = {
                        when (currentTab) {
                            0 -> { onPickerStateChange(true); safImportLauncher.launch(arrayOf("image/*")) }
                            1 -> { onPickerStateChange(true); safImportLauncher.launch(arrayOf("video/*")) }
                            2 -> { onPickerStateChange(true); safImportLauncher.launch(arrayOf("application/pdf", "text/plain")) }
                            3 -> editingSecretNote = NoteItem(isSecret = true)
                        }
                    }
                ) { Icon(Icons.Default.Add, contentDescription = AppLanguage.text(context, "Add to vault", "إضافة إلى الخزنة")) }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(AppLanguage.text(context, "Search inside vault...", "ابحث داخل الخزنة..."), color = Color.Gray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = AppLanguage.text(context, "Clear", "مسح"), tint = Color.Gray)
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF69F0AE),
                        unfocusedBorderColor = Color(0xFF2C2C2C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Spacer(Modifier.height(10.dp))

                if (StorageRepository.hasVaultStorageReadError()) {
                    Text(
                        AppLanguage.text(context, "Part of the vault storage could not be read safely. Some changes may be unavailable to protect your existing data.", "تعذّرت قراءة جزء من مساحة تخزين الخزنة بأمان. قد تتعذّر بعض التغييرات لحماية بياناتك الحالية."),
                        color = Color(0xFFFF8A80),
                        modifier = Modifier.padding(vertical = 8.dp),
                        fontSize = 13.sp
                    )
                }

                // شريط الألبومات والمجلدات الفرعية مع عرض غلاف الألبوم الحقيقي
                if (currentTab in 0..2) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(subFolders, key = { it }) { folder ->
                            val isSelected = folder == currentSubFolder
                            val coverItem = if (currentTab == 0) {
                                vaultFiles.firstOrNull { !it.isTrashed && it.mediaType == VaultMediaType.PHOTO && it.subFolder == folder && it.isAlbumCover }
                                    ?: vaultFiles.firstOrNull { !it.isTrashed && it.mediaType == VaultMediaType.PHOTO && it.subFolder == folder }
                            } else null
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        currentSubFolder = folder
                                        selectedIds.clear()
                                    },
                                    label = { Text(if (folder == "General") AppLanguage.text(context, "General", "عام") else folder) },
                                    leadingIcon = {
                                        if (coverItem != null) {
                                            VaultFolderCoverIcon(item = coverItem)
                                        } else {
                                            Icon(Icons.Default.Folder, null, tint = if (isSelected) Color(0xFF69F0AE) else Color.Gray)
                                        }
                                    }
                                )
                                if (isSelected) {
                                    IconButton(
                                        modifier = Modifier.size(36.dp),
                                        onClick = {
                                            folderBeingRenamed = folder
                                            showRenameFolderDialog = true
                                        }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = AppLanguage.text(context, "Rename folder", "إعادة تسمية المجلد"), tint = Color(0xFF69F0AE), modifier = Modifier.size(18.dp))
                                    }
                                    if (folder != (savedSubFolders.firstOrNull() ?: "General")) {
                                        IconButton(
                                            modifier = Modifier.size(36.dp),
                                            onClick = {
                                                val hasFiles = vaultFiles.any { it.mediaType == currentMediaType && it.subFolder == folder }
                                                if (hasFiles) {
                                                    Toast.makeText(context, AppLanguage.text(context, "Move or delete files in '$folder' first.", "انقل الملفات الموجودة في '$folder' أو احذفها أولًا."), Toast.LENGTH_SHORT).show()
                                                } else {
                                                    val updatedFolders = savedSubFolders.filterNot { it.equals(folder, ignoreCase = true) }
                                                    if (StorageRepository.saveVaultFolders(context, currentMediaType, updatedFolders)) {
                                                        savedSubFolders = updatedFolders.toMutableList()
                                                        currentSubFolder = savedSubFolders.firstOrNull() ?: "General"
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = AppLanguage.text(context, "Delete folder", "حذف المجلد"), tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            IconButton(onClick = { showNewFolderDialog = true }) {
                                Icon(Icons.Default.CreateNewFolder, AppLanguage.text(context, "New album / subfolder", "ألبوم / مجلد فرعي جديد"), tint = Color(0xFF69F0AE))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // قسم النوتس السرية المشفرة (Hidden Notes)
                if (currentTab == 3) {
                    val displayedSecretNotes = remember(secretNotes, showSecretTrash, searchQuery, sortOption) {
                        val filtered = secretNotes.filter {
                            it.isTrashed == showSecretTrash &&
                                (searchQuery.isBlank() || it.title.contains(searchQuery, true) || it.plainTextPreview.contains(searchQuery, true))
                        }
                        val comparator = when (sortOption) {
                            SortOption.DATE_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.updatedAt }
                            SortOption.NAME_ASC -> compareByDescending<NoteItem> { it.isPinned }.thenBy { it.title.lowercase(Locale.ROOT) }
                            SortOption.SIZE_DESC -> compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.htmlContent.length }
                        }
                        filtered.sortedWith(comparator)
                    }

                    if (displayedSecretNotes.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (showSecretTrash) AppLanguage.text(context, "Hidden notes trash is empty", "سلة محذوفات الملاحظات المخفية فارغة") else AppLanguage.text(context, "No hidden notes yet. Tap + to create one.", "لا توجد ملاحظات مخفية بعد. اضغط + لإنشاء واحدة."),
                                color = Color.Gray
                            )
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(displayedSecretNotes, key = { it.id }) { note ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(
                                            Modifier.weight(1f).combinedClickable(
                                                onClick = { if (!showSecretTrash) editingSecretNote = note },
                                                onLongClick = {
                                                    val updated = secretNotes.map {
                                                        if (it.id == note.id) {
                                                            if (showSecretTrash) it.copy(isTrashed = false, trashedAt = null)
                                                            else it.copy(isTrashed = true, trashedAt = System.currentTimeMillis())
                                                        } else it
                                                    }.toMutableList()
                                                    if (StorageRepository.saveSecretNotes(context, updated)) {
                                                        secretNotes = updated
                                                        if (!showSecretTrash) ReminderReceiver.cancelReminder(context, note.id)
                                                    } else {
                                                        Toast.makeText(context, AppLanguage.text(context, "Could not update hidden notes.", "تعذّر تحديث الملاحظات المخفية."), Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            )
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    note.title.ifBlank { AppLanguage.text(context, "Untitled", "بلا عنوان") },
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (note.isPinned && !showSecretTrash) {
                                                    Icon(Icons.Default.PushPin, AppLanguage.text(context, "Pinned", "مثبّتة"), tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
                                                }
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                note.plainTextPreview.ifBlank { AppLanguage.text(context, "Empty note...", "ملاحظة فارغة...") },
                                                color = Color.LightGray,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 14.sp
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(dateFormatter.format(Date(note.updatedAt)), color = Color.Gray, fontSize = 12.sp)
                                                if (showSecretTrash) {
                                                    val daysLeft = daysRemainingInTrash(note.trashedAt)
                                                    Text(AppLanguage.text(context, "$daysLeft d left • Hold to restore", "متبقي $daysLeft يوم • اضغط مطولًا للاستعادة"), color = Color(0xFF69F0AE), fontSize = 12.sp)
                                                }
                                            }
                                        }
                                        if (showSecretTrash) {
                                            IconButton(onClick = {
                                                val updated = secretNotes.filterNot { it.id == note.id }.toMutableList()
                                                if (StorageRepository.saveSecretNotes(context, updated)) {
                                                    secretNotes = updated
                                                    ReminderReceiver.cancelReminder(context, note.id)
                                                } else {
                                                    Toast.makeText(context, AppLanguage.text(context, "Could not delete hidden note.", "تعذّر حذف الملاحظة المخفية."), Toast.LENGTH_LONG).show()
                                                }
                                            }) {
                                                Icon(Icons.Default.DeleteForever, AppLanguage.text(context, "Delete permanently", "حذف نهائي"), tint = Color(0xFFFF5252))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // عرض الصور والفيديوهات والمستندات وسلة المهملات
                    if (filteredFiles.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (currentTab == 4) AppLanguage.text(context, "Vault trash is empty", "سلة محذوفات الخزنة فارغة") else AppLanguage.text(context, "No encrypted files in '$currentSubFolder' yet.", "لا توجد ملفات مشفّرة في '$currentSubFolder' بعد."),
                                color = Color.Gray
                            )
                        }
                    } else {
                        if (isGridView) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                gridItems(filteredFiles, key = { it.id }) { item ->
                                    val isSelected = item.id in selectedIds
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C)),
                                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).combinedClickable(
                                            onClick = {
                                                if (selectedIds.isNotEmpty()) {
                                                    if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                                } else {
                                                    when (item.mediaType) {
                                                        VaultMediaType.PHOTO, VaultMediaType.VIDEO -> {
                                                            val mediaIndex = allVaultMediaList.indexOfFirst { it.id == item.id }
                                                            if (mediaIndex >= 0) activeViewerIndex = mediaIndex
                                                        }
                                                        VaultMediaType.DOCUMENT -> activePdfItem = item
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                            }
                                        )
                                    ) {
                                        Box(Modifier.fillMaxSize()) {
                                            when (item.mediaType) {
                                                VaultMediaType.PHOTO -> VaultPhotoThumbnail(item = item, modifier = Modifier.fillMaxSize())
                                                VaultMediaType.VIDEO -> VaultVideoThumbnail(item = item, modifier = Modifier.fillMaxSize(), durationAtTop = true)
                                                VaultMediaType.DOCUMENT -> VaultDocumentThumbnail(item = item, modifier = Modifier.fillMaxSize())
                                            }
                                            if (isSelected) {
                                                Icon(Icons.Default.CheckCircle, AppLanguage.text(context, "Selected", "محدد"), tint = Color(0xFF69F0AE),
                                                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(26.dp))
                                            }
                                            Column(
                                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                                    .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 8.dp, vertical = 5.dp)
                                            ) {
                                                Text(item.originalName, color = Color.White, fontWeight = FontWeight.SemiBold,
                                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            Row(
                                                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                                                horizontalArrangement = Arrangement.End,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                    if (item.mediaType == VaultMediaType.PHOTO && !item.isTrashed) {
                                                        IconButton(onClick = {
                                                            val updated = vaultFiles.map {
                                                                if (it.mediaType == item.mediaType && it.subFolder == item.subFolder && !it.isTrashed)
                                                                    it.copy(isAlbumCover = (it.id == item.id)) else it
                                                            }.toMutableList()
                                                            if (StorageRepository.saveVaultFiles(context, updated)) vaultFiles = updated
                                                            else Toast.makeText(context, AppLanguage.text(context, "Could not update vault.", "تعذّر تحديث الخزنة."), Toast.LENGTH_LONG).show()
                                                        }, modifier = Modifier.size(30.dp)) {
                                                            Icon(Icons.Default.Star, AppLanguage.text(context, "Album cover", "غلاف الألبوم"),
                                                                tint = if (item.isAlbumCover) Color(0xFFFFD54F) else Color.White,
                                                                modifier = Modifier.size(18.dp))
                                                        }
                                                    }
                                                    if (item.isTrashed) {
                                                        IconButton(onClick = {
                                                            val updated = vaultFiles.map {
                                                                if (it.id == item.id) it.copy(isTrashed = false, trashedAt = null) else it
                                                            }.toMutableList()
                                                            if (StorageRepository.saveVaultFiles(context, updated)) vaultFiles = updated
                                                            else Toast.makeText(context, AppLanguage.text(context, "Could not restore file.", "تعذّرت استعادة الملف."), Toast.LENGTH_LONG).show()
                                                        }, modifier = Modifier.size(30.dp)) {
                                                            Icon(Icons.Default.Restore, AppLanguage.text(context, "Restore to vault", "استعادة إلى الخزنة"), tint = Color(0xFF40C4FF),
                                                                modifier = Modifier.size(18.dp))
                                                        }
                                                        IconButton(onClick = {
                                                            val updated = vaultFiles.filterNot { it.id == item.id }
                                                            if (StorageRepository.saveVaultFiles(context, updated)) {
                                                                val blob = File(StorageRepository.getVaultBlobDir(context), item.encryptedFileName)
                                                                val cleanupFailed = blob.exists() && !blob.delete() && blob.exists()
                                                                vaultFiles = updated.toMutableList()
                                                                if (cleanupFailed) Toast.makeText(context, AppLanguage.text(context, "File removed from the vault index, but its stored copy could not be physically deleted.", "تمت إزالة الملف من فهرس الخزنة، لكن تعذّر حذف نسخته المخزنة فعليًا."), Toast.LENGTH_LONG).show()
                                                            } else Toast.makeText(context, AppLanguage.text(context, "Could not delete file.", "تعذّر حذف الملف."), Toast.LENGTH_LONG).show()
                                                        }, modifier = Modifier.size(30.dp)) {
                                                            Icon(Icons.Default.DeleteForever, AppLanguage.text(context, "Delete permanently", "حذف نهائي"), tint = Color(0xFFFF5252),
                                                                modifier = Modifier.size(18.dp))
                                                        }
                                                    } else {
                                                        IconButton(onClick = {
                                                            pendingRestoreItems = listOf(item)
                                                            onPickerStateChange(true)
                                                            launchRestorePicker()
                                                        }, modifier = Modifier.size(30.dp)) {
                                                            Icon(Icons.Default.Unarchive, AppLanguage.text(context, "Restore to device", "استعادة إلى الجهاز"), tint = Color(0xFF40C4FF),
                                                                modifier = Modifier.size(18.dp))
                                                        }
                                                    }
                                                }
                                        }
                                    }
                                }
                            }
                        } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredFiles, key = { it.id }) { item ->
                                val isSelected = item.id in selectedIds
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) Color(0xFF263238) else Color(0xFF1C1C1C)),
                                    modifier = Modifier.fillMaxWidth().combinedClickable(
                                        onClick = {
                                            if (selectedIds.isNotEmpty()) {
                                                if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                            } else {
                                                when (item.mediaType) {
                                                    VaultMediaType.PHOTO, VaultMediaType.VIDEO -> {
                                                        val mediaIndex = allVaultMediaList.indexOfFirst { it.id == item.id }
                                                        if (mediaIndex >= 0) activeViewerIndex = mediaIndex
                                                    }
                                                    VaultMediaType.DOCUMENT -> activePdfItem = item
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (isSelected) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                        }
                                    )
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            val icon = when (item.mediaType) {
                                                VaultMediaType.PHOTO -> Icons.Default.Image
                                                VaultMediaType.VIDEO -> Icons.Default.PlayCircleFilled
                                                VaultMediaType.DOCUMENT -> Icons.Default.PictureAsPdf
                                            }
                                            when (item.mediaType) {
                                                VaultMediaType.PHOTO -> VaultPhotoThumbnail(item = item)
                                                VaultMediaType.VIDEO -> VaultVideoThumbnail(item = item)
                                                VaultMediaType.DOCUMENT -> VaultDocumentThumbnail(item = item)
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text(item.originalName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                val sizeKb = (item.sizeBytes / 1024L).coerceAtLeast(1L)
                                                val dateStr = dateFormatter.format(Date(item.addedAt))
                                                val trashInfo = if (item.isTrashed) AppLanguage.text(context, " • ${daysRemainingInTrash(item.trashedAt)}d left", " • متبقي ${daysRemainingInTrash(item.trashedAt)} يوم") else ""
                                                Text("$sizeKb KB • $dateStr$trashInfo", color = Color.Gray, fontSize = 12.sp)
                                            }
                                        }
                                        Row {
                                            if (item.mediaType == VaultMediaType.PHOTO && !item.isTrashed) {
                                                IconButton(onClick = {
                                                    val updated = vaultFiles.map {
                                                        if (it.mediaType == item.mediaType && it.subFolder == item.subFolder && !it.isTrashed)
                                                            it.copy(isAlbumCover = (it.id == item.id)) else it
                                                    }.toMutableList()
                                                    if (StorageRepository.saveVaultFiles(context, updated)) {
                                                        vaultFiles = updated
                                                        Toast.makeText(context, AppLanguage.text(context, "Album cover set 🖼️", "تم تعيين غلاف الألبوم 🖼️"), Toast.LENGTH_SHORT).show()
                                                    } else Toast.makeText(context, AppLanguage.text(context, "Could not update album cover.", "تعذّر تحديث غلاف الألبوم."), Toast.LENGTH_LONG).show()
                                                }) {
                                                    Icon(Icons.Default.Star, AppLanguage.text(context, "Album cover", "غلاف الألبوم"), tint = if (item.isAlbumCover) Color(0xFFFFD54F) else Color(0xFFB0B0B0))
                                                }
                                            }
                                            if (item.isTrashed) {
                                                IconButton(onClick = {
                                                    val updated = vaultFiles.map {
                                                        if (it.id == item.id) it.copy(isTrashed = false, trashedAt = null) else it
                                                    }.toMutableList()
                                                    if (StorageRepository.saveVaultFiles(context, updated)) vaultFiles = updated
                                                    else Toast.makeText(context, AppLanguage.text(context, "Could not restore file.", "تعذّرت استعادة الملف."), Toast.LENGTH_LONG).show()
                                                }) { Icon(Icons.Default.Restore, AppLanguage.text(context, "Restore to vault", "استعادة إلى الخزنة"), tint = Color(0xFF40C4FF)) }
                                                IconButton(onClick = {
                                                    val updated = vaultFiles.filterNot { it.id == item.id }
                                                    if (StorageRepository.saveVaultFiles(context, updated)) {
                                                        val blob = File(StorageRepository.getVaultBlobDir(context), item.encryptedFileName)
                                                        val cleanupFailed = blob.exists() && !blob.delete() && blob.exists()
                                                        vaultFiles = updated.toMutableList()
                                                        if (cleanupFailed) {
                                                            Toast.makeText(context, AppLanguage.text(context, "File removed from the vault index, but its stored copy could not be physically deleted.", "تمت إزالة الملف من فهرس الخزنة، لكن تعذّر حذف نسخته المخزنة فعليًا."), Toast.LENGTH_LONG).show()
                                                        } else {
                                                            Toast.makeText(context, AppLanguage.text(context, "File permanently deleted.", "تم حذف الملف نهائيًا."), Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else Toast.makeText(context, AppLanguage.text(context, "Could not delete file.", "تعذّر حذف الملف."), Toast.LENGTH_LONG).show()
                                                }) { Icon(Icons.Default.DeleteForever, AppLanguage.text(context, "Delete permanently", "حذف نهائي"), tint = Color(0xFFFF5252)) }
                                            } else {
                                                // زر إلغاء الإخفاء واسترجاع الملف للهاتف
                                                IconButton(onClick = {
                                                    pendingRestoreItems = listOf(item)
                                                    onPickerStateChange(true)
                                                    launchRestorePicker()
                                                }) { Icon(Icons.Default.Unarchive, AppLanguage.text(context, "Unhide / Restore to device", "إلغاء الإخفاء / استعادة إلى الجهاز"), tint = Color(0xFF40C4FF)) }
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

            if (isBusy) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(color = Color(0xFF69F0AE), modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(busyMessage, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    if (showRenameFolderDialog) {
        var renamedFolder by remember(folderBeingRenamed) { mutableStateOf(folderBeingRenamed) }
        AlertDialog(
            onDismissRequest = { showRenameFolderDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(AppLanguage.text(context, "Rename folder", "إعادة تسمية المجلد"), color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renamedFolder,
                    onValueChange = { renamedFolder = it },
                    singleLine = true,
                    placeholder = { Text(AppLanguage.text(context, "New folder name...", "اسم المجلد الجديد...")) }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val newName = renamedFolder.trim()
                    val defaultFolder = savedSubFolders.firstOrNull() ?: "General"
                    if (newName.isBlank() || newName.length > 40 || newName.any { it == '/' || it == '\\' }) {
                        Toast.makeText(context, AppLanguage.text(context, "Folder name is invalid (1–40 characters).", "اسم المجلد غير صالح (من 1 إلى 40 حرفًا)."), Toast.LENGTH_SHORT).show()
                    } else if (newName.equals(folderBeingRenamed, ignoreCase = true)) {
                        showRenameFolderDialog = false
                    } else if (subFolders.any { it.equals(newName, ignoreCase = true) }) {
                        Toast.makeText(context, AppLanguage.text(context, "A folder with this name already exists.", "يوجد مجلد بهذا الاسم بالفعل."), Toast.LENGTH_SHORT).show()
                    } else {
                        val updatedFiles = vaultFiles.map { file ->
                            if (file.mediaType == currentMediaType && file.subFolder == folderBeingRenamed) file.copy(subFolder = newName) else file
                        }.toMutableList()
                        val updatedFolders = savedSubFolders.map { if (it == folderBeingRenamed) newName else it }
                        val foldersSaved = StorageRepository.saveVaultFolders(context, currentMediaType, updatedFolders)
                        if (foldersSaved) {
                            if (StorageRepository.saveVaultFiles(context, updatedFiles)) {
                                vaultFiles = updatedFiles
                                savedSubFolders = updatedFolders.toMutableList()
                                if (currentSubFolder == folderBeingRenamed) currentSubFolder = newName
                                Toast.makeText(context, AppLanguage.text(context, "Folder renamed.", "تمت إعادة تسمية المجلد."), Toast.LENGTH_SHORT).show()
                                showRenameFolderDialog = false
                            } else {
                                // Keep the original folder available if the file index could not be updated.
                                val rollbackOk = StorageRepository.saveVaultFolders(context, currentMediaType, savedSubFolders)
                                Toast.makeText(
                                    context,
                                    if (rollbackOk) AppLanguage.text(context, "Could not rename folder. Files were kept unchanged.", "تعذّرت إعادة تسمية المجلد. لم يتم تغيير الملفات.")
                                    else AppLanguage.text(context, "Folder list could not be fully rolled back. Your files remain in their original folder.", "تعذّر التراجع عن قائمة المجلدات بالكامل. ما زالت ملفاتك في مجلدها الأصلي."),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(context, AppLanguage.text(context, "Could not rename folder safely.", "تعذّرت إعادة تسمية المجلد بأمان."), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(AppLanguage.text(context, "Rename", "إعادة تسمية")) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFolderDialog = false }) { Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray) }
            }
        )
    }

    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(AppLanguage.text(context, "Create Subfolder / Album", "إنشاء مجلد فرعي / ألبوم"), color = Color.White) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    placeholder = { Text(AppLanguage.text(context, "Folder or album name...", "اسم المجلد أو الألبوم...")) },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val name = folderName.trim()
                    if (name.isNotBlank() && name.length <= 40 && name.none { it == '/' || it == '\\' }) {
                        val duplicate = subFolders.any { it.equals(name, ignoreCase = true) }
                        if (duplicate) {
                            Toast.makeText(context, AppLanguage.text(context, "A folder with this name already exists.", "يوجد مجلد بهذا الاسم بالفعل."), Toast.LENGTH_SHORT).show()
                        } else {
                            val updatedFolders = (savedSubFolders + name).distinct()
                            if (StorageRepository.saveVaultFolders(context, currentMediaType, updatedFolders)) {
                                savedSubFolders = updatedFolders.toMutableList()
                                currentSubFolder = name
                            } else {
                                Toast.makeText(context, AppLanguage.text(context, "Could not save folder.", "تعذّر حفظ المجلد."), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        Toast.makeText(context, AppLanguage.text(context, "Folder name is invalid (1–40 characters).", "اسم المجلد غير صالح (من 1 إلى 40 حرفًا)."), Toast.LENGTH_SHORT).show()
                    }
                    showNewFolderDialog = false
                }) { Text(AppLanguage.text(context, "Create", "إنشاء")) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray) }
            }
        )
    }

    if (showMoveFolderDialog) {
        AlertDialog(
            onDismissRequest = { showMoveFolderDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(AppLanguage.text(context, "Move ${selectedIds.size} file(s) to folder", "نقل ${selectedIds.size} ملفًا إلى مجلد"), color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    subFolders.forEach { targetFolder ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val updated = vaultFiles.map {
                                    if (it.id in selectedIds) it.copy(subFolder = targetFolder, isAlbumCover = false) else it
                                }.toMutableList()
                                if (StorageRepository.saveVaultFiles(context, updated)) {
                                    vaultFiles = updated
                                    selectedIds.clear()
                                    currentSubFolder = targetFolder
                                    Toast.makeText(context, AppLanguage.text(context, "Moved to '$targetFolder'", "تم النقل إلى '$targetFolder'"), Toast.LENGTH_SHORT).show()
                                }
                                showMoveFolderDialog = false
                            }
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF69F0AE))
                            Spacer(Modifier.width(8.dp))
                            Text(targetFolder, color = Color.White, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMoveFolderDialog = false }) { Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray) }
            }
        )
    }

    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(AppLanguage.text(context, "Empty Trash Permanently?", "إفراغ سلة المحذوفات نهائيًا؟"), color = Color.White) },
            text = { Text(AppLanguage.text(context, "All items in this trash view will be permanently erased.", "سيتم حذف جميع العناصر في سلة المحذوفات هذه نهائيًا."), color = Color.LightGray) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    onClick = {
                        if (currentTab == 3 && showSecretTrash) {
                            val updated = secretNotes.filterNot { it.isTrashed }.toMutableList()
                            if (StorageRepository.saveSecretNotes(context, updated)) {
                                secretNotes = updated
                                Toast.makeText(context, AppLanguage.text(context, "Private notes trash emptied.", "تم إفراغ محذوفات الملاحظات الخاصة."), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, AppLanguage.text(context, "Could not empty private notes trash. Your notes were kept.", "تعذّر إفراغ محذوفات الملاحظات الخاصة. تم الاحتفاظ بملاحظاتك."), Toast.LENGTH_LONG).show()
                            }
                        } else if (currentTab == 4) {
                            val trashedFiles = vaultFiles.filter { it.isTrashed }
                            val updated = vaultFiles.filterNot { it.isTrashed }.toMutableList()
                            if (StorageRepository.saveVaultFiles(context, updated)) {
                                var cleanupFailures = 0
                                trashedFiles.forEach { item ->
                                    val blob = File(StorageRepository.getVaultBlobDir(context), item.encryptedFileName)
                                    if (blob.exists() && !blob.delete() && blob.exists()) cleanupFailures++
                                }
                                vaultFiles = updated
                                if (cleanupFailures == 0) {
                                    Toast.makeText(context, AppLanguage.text(context, "Vault trash emptied.", "تم إفراغ محذوفات الخزنة."), Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, AppLanguage.text(context, "Trash emptied, but $cleanupFailures stored file(s) could not be physically removed.", "تم إفراغ سلة المحذوفات، لكن تعذّر حذف $cleanupFailures ملفًا مخزنًا فعليًا."), Toast.LENGTH_LONG).show()
                                }
                            } else {
                                Toast.makeText(context, AppLanguage.text(context, "Could not empty vault trash. Your files were kept.", "تعذّر إفراغ محذوفات الخزنة. تم الاحتفاظ بملفاتك."), Toast.LENGTH_LONG).show()
                            }
                        }
                        showEmptyTrashDialog = false
                    }
                ) { Text(AppLanguage.text(context, "Delete All", "حذف الكل"), color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) { Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray) }
            }
        )
    }
}

private suspend fun decryptViewerPreview(context: Context, item: VaultFileItem): File? {
    var createdPreview: File? = null
    return try {
        withContext(Dispatchers.IO) {
            createdPreview = SecurityCrypto.decryptFileToTempCache(context, item)
            createdPreview
        }
    } catch (cancelled: CancellationException) {
        // A blocking decrypt may finish after Compose has cancelled its viewer.
        // Clean up the file produced by that abandoned operation before propagating cancellation.
        withContext(NonCancellable + Dispatchers.IO) {
            SecurityCrypto.deletePreviewIfTemporary(context, createdPreview)
        }
        throw cancelled
    }
}

private fun readTextPrefix(file: File, maxChars: Int): String {
    if (maxChars <= 0) return ""
    return file.bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(8192)
        buildString {
            while (length < maxChars) {
                val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - length))
                if (count <= 0) break
                append(buffer, 0, count)
            }
        }
    }
}

private fun daysRemainingInTrash(trashedAt: Long?): Long {
    if (trashedAt == null) return 30L
    val elapsed = System.currentTimeMillis() - trashedAt
    val remainingMs = (30L * 24 * 60 * 60 * 1000L) - elapsed
    return TimeUnit.MILLISECONDS.toDays(remainingMs).coerceIn(0L, 30L)
}

@Composable
private fun VaultFolderCoverIcon(item: VaultFileItem) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
        value = withContext(Dispatchers.IO) { decodeVaultSampledBitmap(context, item, 96) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(22.dp).clip(CircleShape)
        )
    } else {
        Icon(Icons.Default.Album, null, tint = Color(0xFFFFD54F), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun VaultPhotoThumbnail(item: VaultFileItem, modifier: Modifier = Modifier.size(104.dp)) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
        value = withContext(Dispatchers.IO) { decodeVaultSampledBitmap(context, item, 480) }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = item.originalName,
            contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(10.dp)))
    } else {
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF303030)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Image, null, tint = Color(0xFF69F0AE), modifier = Modifier.size(38.dp))
        }
    }
}

@Composable
private fun VaultVideoThumbnail(item: VaultFileItem, modifier: Modifier = Modifier.size(104.dp), durationAtTop: Boolean = false) {
    val context = LocalContext.current
    val preview by produceState<Pair<Bitmap?, Long?>>(initialValue = null to null, key1 = item.id) {
        value = withContext(Dispatchers.IO) { decodeVaultVideoThumbnail(context, item, 480) }
    }
    val bitmap = preview.first
    val duration = preview.second
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF303030))) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = item.originalName,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Icon(Icons.Default.PlayCircleFilled, AppLanguage.text(context, "Play video", "تشغيل الفيديو"), tint = Color.White,
            modifier = Modifier.align(Alignment.Center).size(32.dp))
        if (duration != null && duration > 0L) {
            val totalSeconds = duration / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            val durationText = if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
                else "%02d:%02d".format(minutes, seconds)
            Text(durationText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(if (durationAtTop) Alignment.TopStart else Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 5.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun VaultDocumentThumbnail(item: VaultFileItem, modifier: Modifier = Modifier.size(104.dp)) {
    val context = LocalContext.current
    val isTextDocument = item.mimeType.startsWith("text/") || item.originalName.endsWith(".txt", ignoreCase = true)
    if (isTextDocument) {
        val snippet by produceState<String?>(initialValue = null, key1 = item.id) {
            value = withContext(Dispatchers.IO) {
                var temp: File? = null
                try {
                    temp = SecurityCrypto.decryptFileToTempCache(context, item)
                    temp?.takeIf { it.exists() }?.let { readTextPrefix(it, 220) }
                } catch (_: Exception) { null } finally { SecurityCrypto.deletePreviewIfTemporary(context, temp) }
            }
        }
        Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF303030)).padding(7.dp)) {
            Text(snippet?.ifBlank { AppLanguage.text(context, "Empty text file", "ملف نصي فارغ") } ?: AppLanguage.text(context, "Text document", "مستند نصي"), color = Color(0xFFE0E0E0),
                fontSize = 9.sp, lineHeight = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
    } else {
        val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
            value = withContext(Dispatchers.IO) { decodeVaultPdfThumbnail(context, item, 480) }
        }
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = AppLanguage.text(context, "First page of ${item.originalName}", "الصفحة الأولى من ${item.originalName}"),
                contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(10.dp)))
        } else {
            Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF303030)),
                contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PictureAsPdf,
                    AppLanguage.text(context, "PDF document", "ملف PDF"),
                    tint = Color(0xFF69F0AE),
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}
private fun decodeVaultVideoThumbnail(context: Context, item: VaultFileItem, targetSize: Int): Pair<Bitmap?, Long?> {
    var decryptedFile: File? = null
    val retriever = MediaMetadataRetriever()
    return try {
        decryptedFile = SecurityCrypto.decryptFileToTempCache(context, item)
        if (decryptedFile == null || !decryptedFile.exists()) {
            null to null
        } else {
            retriever.setDataSource(decryptedFile.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

            // Ask the decoder for a thumbnail-sized frame on Android 8.1+ instead
            // of allocating a full-resolution 4K/8K bitmap and scaling it afterward.
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val videoWidth = if (rotation % 180 != 0) rawHeight else rawWidth
            val videoHeight = if (rotation % 180 != 0) rawWidth else rawHeight

            fun extractFrame(timeUs: Long): Bitmap? {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
                    videoWidth != null && videoHeight != null &&
                    videoWidth > 0 && videoHeight > 0
                ) {
                    val scale = minOf(1f, targetSize.toFloat() / maxOf(videoWidth, videoHeight).toFloat())
                    val width = (videoWidth * scale).toInt().coerceAtLeast(1)
                    val height = (videoHeight * scale).toInt().coerceAtLeast(1)
                    val scaled = runCatching {
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            width,
                            height
                        )
                    }.getOrNull()
                    if (scaled != null) return scaled
                }
                // Android 8.0 and unusual files without dimensions use the compatible path.
                return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }

            val frame = extractFrame(1_000_000L)
                ?: extractFrame(0L)
                ?: return null to duration
            val largestDimension = maxOf(frame.width, frame.height)
            val bitmap = if (largestDimension <= targetSize) frame else {
                val scale = targetSize.toFloat() / largestDimension.toFloat()
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== frame) frame.recycle() }
            }
            bitmap to duration
        }
    } catch (_: Exception) {
        null to null
    } finally {
        try { retriever.release() } catch (_: Exception) {}
        SecurityCrypto.deletePreviewIfTemporary(context, decryptedFile)
    }
}
private fun decodeVaultPdfThumbnail(context: Context, item: VaultFileItem, targetSize: Int): Bitmap? {
    var decryptedFile: File? = null
    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    return try {
        decryptedFile = SecurityCrypto.decryptFileToTempCache(context, item)
        if (decryptedFile == null || !decryptedFile.exists()) null else {
            descriptor = ParcelFileDescriptor.open(decryptedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (renderer.pageCount <= 0) null else renderer.openPage(0).use { page ->
                val scale = targetSize.toFloat() / maxOf(page.width, page.height).toFloat()
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    } catch (_: Exception) {
        null
    } finally {
        try { renderer?.close() } catch (_: Exception) {}
        try { descriptor?.close() } catch (_: Exception) {}
        SecurityCrypto.deletePreviewIfTemporary(context, decryptedFile)
    }
}

/**
 * Decodes a sampled image and applies its EXIF orientation for display only.
 * The encrypted/original file is never rewritten, so importing and restoring
 * images remain byte-for-byte unchanged.
 */
private fun decodeExifOrientedBitmap(file: File, targetSize: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (bounds.outWidth / sample > targetSize || bounds.outHeight / sample > targetSize) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null

    return try {
        val orientation = runCatching {
            android.media.ExifInterface(file.absolutePath).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return decoded
        }
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    } catch (_: Exception) {
        decoded
    }
}

private fun decodeVaultSampledBitmap(context: Context, item: VaultFileItem, targetSize: Int): Bitmap? {
    var decryptedFile: File? = null
    return try {
        decryptedFile = SecurityCrypto.decryptFileToTempCache(context, item)
        if (decryptedFile == null || !decryptedFile.exists()) {
            null
        } else {
            decodeExifOrientedBitmap(decryptedFile, targetSize)
        }
    } catch (_: Exception) {
        null
    } finally {
        SecurityCrypto.deletePreviewIfTemporary(context, decryptedFile)
    }
}

// عارض الوسائط المدمج: يدعم التمرير بين الصور والفيديوهات، والتكبير باللمس، ومشغل فيديو بأزرار التقديم والترجيع وملء الشاشة
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InAppMediaPagerViewer(items: List<VaultFileItem>, initialIndex: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var isFullscreenLandscape by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { items.size })

    fun exitViewer() {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onClose()
    }

    BackHandler(onBack = { exitViewer() })

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            CrashReportManager.clearViewerState(context)
        }
    }

    LaunchedEffect(pagerState.currentPage, items.size) {
        val currentItem = items.getOrNull(pagerState.currentPage)
        if (currentItem != null) {
            CrashReportManager.setViewerState(
                context = context,
                page = pagerState.currentPage,
                mediaType = currentItem.mediaType.name,
                itemId = currentItem.id
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            val isCurrentPage = pagerState.currentPage == page
            val decryptedFile by produceState<File?>(initialValue = null, key1 = item.id) {
                value = decryptViewerPreview(context, item)
            }

            DisposableEffect(item.id, decryptedFile) {
                // Capture the file for this effect instance. Reading the delegated state
                // from onDispose can resolve to a newer preview and delete it prematurely.
                val fileToDelete = decryptedFile
                onDispose { SecurityCrypto.deletePreviewIfTemporary(context, fileToDelete) }
            }

            if (decryptedFile != null) {
                if (item.mediaType == VaultMediaType.PHOTO) {
                    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = decryptedFile!!.absolutePath) {
                        value = withContext(Dispatchers.IO) {
                            decodeExifOrientedBitmap(decryptedFile!!, 2048)
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = item.originalName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                } else if (item.mediaType == VaultMediaType.VIDEO) {
                    val exoPlayer = remember(decryptedFile!!.absolutePath) {
                        ExoPlayer.Builder(context)
                            .setSeekBackIncrementMs(10_000L)
                            .setSeekForwardIncrementMs(10_000L)
                            .build()
                            .apply {
                                setMediaItem(MediaItem.fromUri(Uri.fromFile(decryptedFile!!)))
                                CrashReportManager.recordEvent(
                                    context,
                                    "Video player created page=${page} item=${item.id.take(8)}"
                                )
                                prepare()
                            }
                    }
                    LaunchedEffect(isCurrentPage, exoPlayer) {
                        if (isCurrentPage) exoPlayer.play() else exoPlayer.pause()
                    }
                    DisposableEffect(exoPlayer, item.id, page) {
                        val listener = object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                val stateName = when (playbackState) {
                                    Player.STATE_IDLE -> "IDLE"
                                    Player.STATE_BUFFERING -> "BUFFERING"
                                    Player.STATE_READY -> "READY"
                                    Player.STATE_ENDED -> "ENDED"
                                    else -> "UNKNOWN"
                                }
                                CrashReportManager.recordEvent(
                                    context,
                                    "Video state=${stateName} page=${page} item=${item.id.take(8)} positionMs=${exoPlayer.currentPosition} playWhenReady=${exoPlayer.playWhenReady}"
                                )
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                CrashReportManager.recordEvent(
                                    context,
                                    "Video player error page=${page} item=${item.id.take(8)} code=${error.errorCodeName} message=${error.message}",
                                    error
                                )
                            }

                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                CrashReportManager.recordEvent(
                                    context,
                                    "Video isPlaying=${isPlaying} page=${page} item=${item.id.take(8)} positionMs=${exoPlayer.currentPosition}"
                                )
                            }
                        }
                        exoPlayer.addListener(listener)
                        onDispose {
                            exoPlayer.removeListener(listener)
                            exoPlayer.release()
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                    setShowFastForwardButton(true)
                                    setShowRewindButton(true)
                                }
                            }
                        )
                        // زر ملء الشاشة وتدوير العرض للفيديو
                        IconButton(
                            onClick = {
                                isFullscreenLandscape = !isFullscreenLandscape
                                activity?.requestedOrientation = if (isFullscreenLandscape) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .background(Color(0x88000000), RoundedCornerShape(50))
                        ) {
                            Icon(
                                if (isFullscreenLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = AppLanguage.text(context, "Toggle Fullscreen", "تبديل وضع ملء الشاشة"),
                                tint = Color.White
                            )
                        }
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF69F0AE))
                }
            }
        }
        IconButton(
            onClick = { exitViewer() },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color(0x88000000), RoundedCornerShape(50))
        ) {
            Icon(Icons.Default.Close, AppLanguage.text(context, "Close", "إغلاق"), tint = Color.White)
        }
    }
}

// قارئ مستندات وملفات PDF مدمج بالكامل داخل الخزنة
@Composable
fun InAppPdfViewer(item: VaultFileItem, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    var isDecrypting by remember(item.id) { mutableStateOf(true) }
    var decryptedFile by remember(item.id) { mutableStateOf<File?>(null) }
    val renderMutex = remember(item.id) { Mutex() }

    LaunchedEffect(item.id) {
        isDecrypting = true
        decryptedFile = decryptViewerPreview(context, item)
        isDecrypting = false
    }

    DisposableEffect(item.id, decryptedFile) {
        // Capture the file for this effect instance so disposal cannot delete a newer preview.
        val fileToDelete = decryptedFile
        onDispose { SecurityCrypto.deletePreviewIfTemporary(context, fileToDelete) }
    }

    val isTextDocument = item.mimeType.startsWith("text/") || item.originalName.endsWith(".txt", ignoreCase = true)
    val textContent by produceState<String?>(initialValue = null, key1 = decryptedFile, key2 = isTextDocument) {
        value = if (isTextDocument && decryptedFile != null) withContext(Dispatchers.IO) {
            try {
                readTextPrefix(decryptedFile!!, 2_000_000)
            } catch (_: Exception) { null }
        } else null
    }

    val pageCount by produceState(initialValue = 0, key1 = decryptedFile, key2 = isTextDocument) {
        value = withContext(Dispatchers.IO) {
            if (isTextDocument || decryptedFile == null || !decryptedFile!!.exists()) 0 else {
                try {
                    ParcelFileDescriptor.open(decryptedFile!!, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { it.pageCount }
                    }
                } catch (_: Exception) { 0 }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(item.originalName, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, AppLanguage.text(context, "Close", "إغلاق"), tint = Color.White) }
        }
        when {
            isDecrypting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF69F0AE))
            }
            decryptedFile == null -> Text(AppLanguage.text(context, "Unable to decrypt this document.", "تعذّر فك تشفير هذا المستند."), color = Color.White, modifier = Modifier.padding(16.dp))
            isTextDocument && textContent != null -> SelectionContainer {
                Text(
                    textContent!!,
                    color = Color.White,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                )
            }
            isTextDocument -> Text(AppLanguage.text(context, "Unable to read this text document.", "تعذّرت قراءة هذا المستند النصي."), color = Color.White, modifier = Modifier.padding(16.dp))
            pageCount > 0 -> LazyColumn(
                Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pageCount, key = { it }) { pageIndex ->
                    PdfPageImage(file = decryptedFile!!, pageIndex = pageIndex, renderMutex = renderMutex)
                }
            }
            else -> Text(AppLanguage.text(context, "Unsupported document format. Import PDF or plain text files.", "تنسيق المستند غير مدعوم. استورد ملفات PDF أو ملفات نصية عادية."), color = Color.White, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun PdfPageImage(file: File, pageIndex: Int, renderMutex: Mutex) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, file, pageIndex) {
        value = withContext(Dispatchers.IO) {
            renderMutex.withLock {
                var descriptor: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(descriptor)
                    renderer.openPage(pageIndex).use { page ->
                        val scale = minOf(2f, 1600f / maxOf(page.width, page.height).toFloat())
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    try { renderer?.close() } catch (_: Exception) {}
                    try { descriptor?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = AppLanguage.text(context, "PDF page ${pageIndex + 1}", "صفحة PDF رقم ${pageIndex + 1}"),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color.White)
        )
    } else {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF69F0AE))
        }
    }
}