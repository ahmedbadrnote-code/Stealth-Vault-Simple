package com.stealth.vaultnotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var originalBaseContext: Context? = null
    private var panicSensor: PanicSensor? = null
    private val isVaultOpenState = mutableStateOf(false)
    private val appLanguageState = mutableStateOf(AppLanguage.ARABIC)
    private val pendingOpenNoteId = mutableStateOf<String?>(null)
    @Volatile private var isVaultPickerActive = false
    @Volatile private var isVaultUnlockInProgress = false

    override fun attachBaseContext(newBase: Context) {
        originalBaseContext = newBase
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLanguageState.value = AppLanguage.getLanguage(this)
        // السماح بلقطات الشاشة في Debug أثناء الاختبار فقط.
        // تظل الحماية مفعّلة تلقائيًا في Release لمنع لقطات الشاشة ومعاينة التطبيقات الحديثة.
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        // A recreated activity starts locked; never retain a cached vault key across recreation.
        SecurityCrypto.lockVaultSession(this)
        pendingOpenNoteId.value = intent?.getStringExtra("openNoteId")

        panicSensor = PanicSensor(this) {
            if (isVaultOpenState.value) {
                runOnUiThread {
                    triggerSubtleVibration()
                    SecurityCrypto.lockVaultSession(this)
                    isVaultOpenState.value = false
                }
            }
        }

        setContent {
            val appDirection = if (appLanguageState.value == AppLanguage.ARABIC) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            CompositionLocalProvider(LocalLayoutDirection provides appDirection) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    StealthNotesApp(
                    activity = this@MainActivity,
                    onLanguageChanged = { language -> appLanguageState.value = language },
                    printContext = originalBaseContext ?: this@MainActivity,
                    isVaultOpen = isVaultOpenState.value,
                    onVaultStateChange = { open ->
                        if (!open) SecurityCrypto.lockVaultSession(this)
                        isVaultOpenState.value = open
                    },
                    onVaultPickerStateChange = { isVaultPickerActive = it },
                    onVaultUnlockProgressChange = { isVaultUnlockInProgress = it },
                    openNoteId = pendingOpenNoteId.value,
                    onOpenNoteHandled = { handledId ->
                        if (pendingOpenNoteId.value == handledId) pendingOpenNoteId.value = null
                    }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenNoteId.value = intent.getStringExtra("openNoteId")
    }

    private fun triggerSubtleVibration() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(45L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(45L)
            }
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        isVaultPickerActive = false
        panicSensor?.start()
    }
    override fun onPause() {
        super.onPause()
        panicSensor?.stop()
    }

    override fun onUserLeaveHint() {
        // Lock in onStop, after editor lifecycle observers have had a chance to save drafts.
        super.onUserLeaveHint()
    }

    override fun onStop() {
        if ((isVaultOpenState.value || isVaultUnlockInProgress) && !isChangingConfigurations && !isVaultPickerActive) {
            SecurityCrypto.lockVaultSession(this)
            isVaultOpenState.value = false
            isVaultUnlockInProgress = false
        }
        // Change launcher aliases only after this Activity has left the foreground.
        // Disabling the alias that launched the currently visible Activity can make
        // Android remove that Activity, which looks like an unexpected app exit.
        AppLanguage.updateLauncherAlias(this, AppLanguage.getLanguage(this))
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            SecurityCrypto.lockVaultSession(this)
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StealthNotesApp(
    activity: ComponentActivity,
    onLanguageChanged: (String) -> Unit,
    printContext: Context,
    isVaultOpen: Boolean,
    onVaultStateChange: (Boolean) -> Unit,
    onVaultPickerStateChange: (Boolean) -> Unit,
    onVaultUnlockProgressChange: (Boolean) -> Unit,
    openNoteId: String?,
    onOpenNoteHandled: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val dateFormatter = remember { SimpleDateFormat("yyyy/MM/dd  hh:mm a", Locale.getDefault()) }

    var isFirstRun by remember { mutableStateOf(SecurityCrypto.isFirstRun(context)) }
    var publicNotes by remember { mutableStateOf(StorageRepository.loadPublicNotes(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var showTrashBin by remember { mutableStateOf(false) }
    var showEmptyPublicTrashDialog by remember { mutableStateOf(false) }
    var activeNoteForEdit by remember { mutableStateOf<NoteItem?>(null) }
    var newNoteId by remember { mutableStateOf<String?>(null) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf(AppLanguage.getLanguage(context)) }

    LaunchedEffect(openNoteId, publicNotes, activeNoteForEdit, isVaultOpen, isFirstRun) {
        val requestedId = openNoteId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (isFirstRun || isVaultOpen || activeNoteForEdit != null) return@LaunchedEffect
        if (StorageRepository.hasPublicNotesReadError()) return@LaunchedEffect
        val requestedNote = publicNotes.firstOrNull { it.id == requestedId && !it.isTrashed }
        if (requestedNote != null) {
            showTrashBin = false
            searchQuery = ""
            activeNoteForEdit = requestedNote
            onOpenNoteHandled(requestedId)
        } else {
            Toast.makeText(context, AppLanguage.text(context, "This reminder note is no longer available.", "تعذّر العثور على الملاحظة المرتبطة بهذا التذكير."), Toast.LENGTH_LONG).show()
            onOpenNoteHandled(requestedId)
        }
    }

    var pendingReminder by remember { mutableStateOf<Pair<NoteItem, Long>?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingReminder
        pendingReminder = null
        if (pending != null) {
            // Keep the alarm scheduled even if notification permission is denied;
            // Android will suppress the notification until the user enables it.
            ReminderReceiver.scheduleReminder(context, pending.first, pending.second)
            val message = if (granted) {
                AppLanguage.text(context, "Note reminder scheduled ⏰", "تم ضبط تذكير الملاحظة ⏰")
            } else {
                AppLanguage.text(
                    context,
                    "Reminder time saved, but notifications are disabled. Enable them in Settings.",
                    "تم حفظ وقت التذكير، لكن الإشعارات غير مفعّلة. فعّلها من الإعدادات."
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } else if (!granted) {
            Toast.makeText(context, AppLanguage.text(context, "Notifications are disabled. Enable them in Settings to receive reminders.", "الإشعارات معطّلة. فعّلها من الإعدادات لاستقبال التذكيرات."), Toast.LENGTH_LONG).show()
        }
    }

    // تصدير النوتس العادية في ملف واحد
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val json = StorageRepository.exportPublicNotesJson(context)
                        val stream = context.contentResolver.openOutputStream(it) ?: return@withContext false
                        stream.use { out -> out.write(json.toByteArray(Charsets.UTF_8)) }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (ok) {
                    Toast.makeText(context, AppLanguage.text(context, "All notes exported successfully ✅", "تم تصدير جميع الملاحظات بنجاح ✅"), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, AppLanguage.text(context, "Could not export notes.", "تعذّر تصدير الملاحظات."), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // استرجاع النوتس العادية من ملف
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val importedOk = withContext(Dispatchers.IO) {
                    try {
                        val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                        StorageRepository.importPublicNotesJson(context, json)
                    } catch (_: Exception) {
                        false
                    }
                }
                if (importedOk) {
                    val previousNotes = publicNotes
                    val refreshedNotes = StorageRepository.loadPublicNotes(context)
                    previousNotes.forEach { previous ->
                        val current = refreshedNotes.firstOrNull { it.id == previous.id }
                        if (current?.reminderTime != previous.reminderTime || current?.isTrashed != previous.isTrashed) {
                            ReminderReceiver.cancelReminder(context, previous.id)
                        }
                    }
                    refreshedNotes.filter {
                        !it.isTrashed && it.reminderTime != null && it.reminderTime!! > System.currentTimeMillis()
                    }.forEach { note ->
                        ReminderReceiver.scheduleReminder(context, note, note.reminderTime!!)
                    }
                    publicNotes = refreshedNotes
                    Toast.makeText(context, AppLanguage.text(context, "Notes imported successfully ✅", "تم استيراد الملاحظات بنجاح ✅"), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, AppLanguage.text(context, "Invalid backup file.", "ملف النسخة الاحتياطية غير صالح."), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 1. شاشة الإعداد الأول (First Run) لتحديد الكلمة السرية
    if (isFirstRun) {
        var newPasscode by remember { mutableStateOf("") }
        var confirmPasscode by remember { mutableStateOf("") }
        var showPass by remember { mutableStateOf(false) }

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.EditNote, contentDescription = null, tint = Color(0xFF69F0AE), modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text(AppLanguage.text(context, "Welcome to Notes Pro", "مرحبًا بك في Notes Pro"), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    AppLanguage.text(context, "To secure your workspace, set your secret passcode now (letters, numbers, or symbols). Type it in the Search bar anytime to open your private vault.", "لتأمين مساحة العمل، أنشئ رمز المرور السري الآن (حروف أو أرقام أو رموز). اكتب الرمز في شريط البحث في أي وقت لفتح خزنتك الخاصة."),
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = newPasscode,
                    onValueChange = { newPasscode = it },
                    label = { Text(AppLanguage.text(context, "Secret passcode", "رمز المرور السري")) },
                    singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = AppLanguage.text(context, "Toggle visibility", "إظهار أو إخفاء رمز المرور"), tint = Color.Gray)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = confirmPasscode,
                    onValueChange = { confirmPasscode = it },
                    label = { Text(AppLanguage.text(context, "Confirm passcode", "تأكيد رمز المرور")) },
                    singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF69F0AE), contentColor = Color.Black),
                    onClick = {
                        val cleanPass = newPasscode.trim()
                        val cleanConfirm = confirmPasscode.trim()
                        when {
                            cleanPass.isEmpty() -> Toast.makeText(context, AppLanguage.text(context, "Please enter a passcode.", "يرجى إدخال رمز المرور."), Toast.LENGTH_SHORT).show()
                            cleanPass != cleanConfirm -> Toast.makeText(context, AppLanguage.text(context, "Passcodes do not match.", "رمزا المرور غير متطابقين."), Toast.LENGTH_SHORT).show()
                            else -> {
                                scope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        try {
                                            SecurityCrypto.setPasscode(context, cleanPass)
                                            true
                                        } catch (_: Exception) {
                                            false
                                        }
                                    }
                                    if (saved) {
                                        isFirstRun = false
                                    } else {
                                        Toast.makeText(context, AppLanguage.text(context, "Could not initialize secure storage. Please try again.", "تعذّر تهيئة التخزين الآمن. حاول مرة أخرى."), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Text(AppLanguage.text(context, "Get started", "ابدأ الآن"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
        return
    }

    // 2. لو الخزنة السرية مفتوحة
    if (isVaultOpen) {
        SecretVaultScreen(
            activity = activity,
            printContext = printContext,
            onExitVault = { onVaultStateChange(false) },
            onPickerStateChange = onVaultPickerStateChange
        )
        return
    }

    // 3. لو فاتح ملاحظة في المحرر المتقدم
    activeNoteForEdit?.let { note ->
        NoteEditorScreen(
            activity = activity,
            printContext = printContext,
            note = note,
            onSave = { updated ->
                val list = publicNotes.toMutableList()
                val idx = list.indexOfFirst { it.id == updated.id }
                if (idx >= 0) list[idx] = updated else list.add(0, updated)
                if (StorageRepository.savePublicNotes(context, list)) {
                    publicNotes = list
                    val reminderAt = updated.reminderTime
                    if (!updated.isTrashed && reminderAt != null && reminderAt > System.currentTimeMillis()) {
                        ReminderReceiver.scheduleReminder(context, updated, reminderAt)
                    } else {
                        ReminderReceiver.cancelReminder(context, updated.id)
                    }
                } else {
                    throw IllegalStateException("Failed to save public note")
                }
            },
            onBack = { activeNoteForEdit = null; newNoteId = null },
            discardIfEmpty = note.id == newNoteId,
            onScheduleReminder = { reminderNote, timeMillis ->
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingReminder = reminderNote to timeMillis
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    ReminderReceiver.scheduleReminder(context, reminderNote, timeMillis)
                    Toast.makeText(context, AppLanguage.text(context, "Note reminder scheduled ⏰", "تم ضبط تذكير الملاحظة ⏰"), Toast.LENGTH_SHORT).show()
                }
            }
        )
        return
    }

    // زر الرجوع عند فتح سلة المهملات يعود للقائمة الرئيسية بدلاً من إغلاق التطبيق
    BackHandler(enabled = showTrashBin || searchQuery.isNotEmpty()) {
        if (showTrashBin) showTrashBin = false else searchQuery = ""
    }

    // 4. الواجهة الرئيسية للملاحظات (Clean Vertical List View)
    val displayedNotes = remember(publicNotes, searchQuery, showTrashBin) {
        val list = publicNotes.filter { it.isTrashed == showTrashBin }
        val filtered = if (searchQuery.isBlank()) list else list.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.plainTextPreview.contains(searchQuery, ignoreCase = true)
        }
        filtered.sortedWith(compareByDescending<NoteItem> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E)),
                title = {
                    Text(
                        if (showTrashBin) AppLanguage.text(context, "Trash (30 days)", "المحذوفات (30 يومًا)") else AppLanguage.text(context, "My Notes", "ملاحظاتي"),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = {
                        selectedLanguage = AppLanguage.getLanguage(context)
                        showLanguageDialog = true
                    }) {
                        Icon(Icons.Default.Language, contentDescription = AppLanguage.text(context, "Change language", "تغيير اللغة"), tint = Color.White)
                    }
                    if (showTrashBin && displayedNotes.isNotEmpty()) {
                        IconButton(onClick = { showEmptyPublicTrashDialog = true }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = AppLanguage.text(context, "Empty trash", "إفراغ سلة المحذوفات"), tint = Color(0xFFFF5252))
                        }
                    }
                    IconButton(onClick = { exportLauncher.launch("notes_backup.json") }) {
                        Icon(Icons.Default.UploadFile, contentDescription = AppLanguage.text(context, "Export notes", "تصدير الملاحظات"), tint = Color.White)
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Default.Download, contentDescription = AppLanguage.text(context, "Import notes", "استيراد الملاحظات"), tint = Color.White)
                    }
                    IconButton(onClick = { showTrashBin = !showTrashBin }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = AppLanguage.text(context, "Trash", "المحذوفات"),
                            tint = if (showTrashBin) Color(0xFFFF5252) else Color.White
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (!showTrashBin && !StorageRepository.hasPublicNotesReadError()) {
                FloatingActionButton(
                    containerColor = Color(0xFF69F0AE),
                    contentColor = Color.Black,
                    onClick = {
                        val newNote = NoteItem()
                        newNoteId = newNote.id
                        activeNoteForEdit = newNote
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = AppLanguage.text(context, "New note", "ملاحظة جديدة"))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            // شريط البحث (المدخل السري الوحيد للخزنة عند كتابة الكلمة السرية والضغط على بحث/إنتر بدون أي أزرار مريبة)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(AppLanguage.text(context, "Search notes...", "ابحث في الملاحظات..."), color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = AppLanguage.text(context, "Clear search", "مسح البحث"), tint = Color.Gray)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        val candidate = searchQuery.trim()
                        if (candidate.isNotEmpty()) {
                            scope.launch {
                                val verification = withContext(Dispatchers.IO) {
                                    try {
                                        SecurityCrypto.verifyPasscode(context, candidate) to null
                                    } catch (e: Exception) {
                                        false to e
                                    }
                                }
                                if (verification.first && SecurityCrypto.hasActiveTransfers()) {
                                    // Do not allow concurrent vault edits while the service is
                                    // committing a transfer against the encrypted index.
                                    SecurityCrypto.lockVaultSession(context)
                                    Toast.makeText(
                                        context,
                                        AppLanguage.text(
                                            context,
                                            "A background file transfer is still running. Please wait until it finishes before reopening the vault.",
                                            "ما زالت عملية نقل ملفات تعمل في الخلفية. انتظر حتى تنتهي قبل فتح الخزنة مرة أخرى."
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    focusManager.clearFocus()
                                } else if (verification.first) {
                                    onVaultUnlockProgressChange(true)
                                    val recovery = withContext(Dispatchers.IO) {
                                        runCatching { VaultTransferManager.recoverPending(context) }
                                    }
                                    if (recovery.isSuccess && activityLifecycleCanContinue(activity)) {
                                        onVaultUnlockProgressChange(false)
                                        searchQuery = ""
                                        focusManager.clearFocus()
                                        onVaultStateChange(true)
                                    } else if (recovery.isFailure && activityLifecycleCanContinue(activity)) {
                                        onVaultUnlockProgressChange(false)
                                        SecurityCrypto.lockVaultSession(context)
                                        Toast.makeText(
                                            context,
                                            AppLanguage.text(
                                                context,
                                                "The vault could not safely recover an interrupted file transfer. Your stored files were not intentionally removed.",
                                                "تعذّر استرداد عملية نقل ملفات متوقفة بأمان. لم يتم حذف الملفات المخزنة عمدًا."
                                            ),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        focusManager.clearFocus()
                                    }
                                } else {
                                    if (verification.second != null) {
                                        Toast.makeText(
                                            context,
                                            AppLanguage.text(context, "Unable to unlock the vault. Existing encrypted data has been preserved.", "تعذّر فتح الخزنة. تم الحفاظ على البيانات المشفّرة الحالية."),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    focusManager.clearFocus()
                                }
                            }
                        } else {
                            focusManager.clearFocus()
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF69F0AE),
                    unfocusedBorderColor = Color(0xFF2C2C2C),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(Modifier.height(12.dp))

            // القائمة الرأسية الكلاسيكية المنظمة (Clean Vertical List View)
            if (displayedNotes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            StorageRepository.hasPublicNotesReadError() -> AppLanguage.text(context, "Notes could not be read safely. Existing data has been protected; do not clear app data.", "تعذّرت قراءة الملاحظات بأمان. تمت حماية البيانات الحالية؛ لا تمسح بيانات التطبيق.")
                            showTrashBin -> AppLanguage.text(context, "Trash is empty", "سلة المحذوفات فارغة")
                            else -> AppLanguage.text(context, "No notes yet. Tap + to create one.", "لا توجد ملاحظات بعد. اضغط + لإنشاء ملاحظة.")
                        },
                        color = if (StorageRepository.hasPublicNotesReadError()) Color(0xFFFF8A80) else Color.Gray
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(displayedNotes, key = { it.id }) { note ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (!showTrashBin) activeNoteForEdit = note
                                    },
                                    onLongClick = {
                                        val movingToTrash = !showTrashBin
                                        val updated = publicNotes.map {
                                            if (it.id == note.id) {
                                                if (movingToTrash) it.copy(isTrashed = true, trashedAt = System.currentTimeMillis())
                                                else it.copy(isTrashed = false, trashedAt = null)
                                            } else it
                                        }.toMutableList()
                                        if (StorageRepository.savePublicNotes(context, updated)) {
                                            publicNotes = updated
                                            if (movingToTrash) {
                                                ReminderReceiver.cancelReminder(context, note.id)
                                                Toast.makeText(context, AppLanguage.text(context, "Moved to Trash (30 days)", "نُقلت إلى المحذوفات (30 يومًا)"), Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, AppLanguage.text(context, "Note restored ✅", "تمت استعادة الملاحظة ✅"), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = note.title.ifBlank { AppLanguage.text(context, "Untitled", "بلا عنوان") },
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (note.isPinned && !showTrashBin) {
                                            Icon(
                                                Icons.Default.PushPin,
                                                contentDescription = AppLanguage.text(context, "Pinned", "مثبّتة"),
                                                tint = Color(0xFFFFD54F),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(6.dp))

                                    // سطرين من المحتوى
                                    Text(
                                        text = note.plainTextPreview.ifBlank { AppLanguage.text(context, "Empty note...", "ملاحظة فارغة...") },
                                        color = Color(0xFFB0B0B0),
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    // تاريخ آخر تعديل
                                    val formattedDate = dateFormatter.format(Date(note.updatedAt))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(formattedDate, color = Color.Gray, fontSize = 12.sp)
                                        if (showTrashBin) {
                                            Text(AppLanguage.text(context, "Long-press to restore", "اضغط مطولًا للاستعادة"), color = Color(0xFF69F0AE), fontSize = 12.sp)
                                        }
                                    }
                                }
                                if (showTrashBin) {
                                    IconButton(onClick = {
                                        val updated = publicNotes.filterNot { it.id == note.id }.toMutableList()
                                        if (StorageRepository.savePublicNotes(context, updated)) {
                                            ReminderReceiver.cancelReminder(context, note.id)
                                            publicNotes = updated
                                        }
                                    }) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = AppLanguage.text(context, "Delete permanently", "حذف نهائي"), tint = Color(0xFFFF5252))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text(
                    if (AppLanguage.getLanguage(context) == AppLanguage.ARABIC) AppLanguage.text(context, "لغة التطبيق", "لغة التطبيق") else AppLanguage.text(context, "App language", AppLanguage.text(context, "لغة التطبيق", "لغة التطبيق")),
                    color = Color.White
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedLanguage == AppLanguage.ARABIC,
                            onClick = { selectedLanguage = AppLanguage.ARABIC }
                        )
                        Text("العربية", color = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedLanguage == AppLanguage.ENGLISH,
                            onClick = { selectedLanguage = AppLanguage.ENGLISH }
                        )
                        Text("English", color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLanguageDialog = false
                    if (selectedLanguage != AppLanguage.getLanguage(context)) {
                        // Persist and apply the in-app language immediately.
                        // Launcher label is synchronized later in MainActivity.onStop(),
                        // after the current launcher alias is no longer in the foreground.
                        AppLanguage.setLanguage(context, selectedLanguage)
                        onLanguageChanged(selectedLanguage)
                    }
                }) {
                    Text(if (selectedLanguage == AppLanguage.ARABIC) "تطبيق" else AppLanguage.text(context, "Apply", "تطبيق"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(if (AppLanguage.getLanguage(context) == AppLanguage.ARABIC) "إلغاء" else AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray)
                }
            }
        )
    }

    if (showEmptyPublicTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyPublicTrashDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(AppLanguage.text(context, "Empty Trash Permanently?", "إفراغ سلة المحذوفات نهائيًا؟"), color = Color.White) },
            text = { Text(AppLanguage.text(context, "All trashed notes will be permanently deleted.", "سيتم حذف جميع الملاحظات الموجودة في سلة المحذوفات نهائيًا."), color = Color.LightGray) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                    onClick = {
                        val trashed = publicNotes.filter { it.isTrashed }
                        val active = publicNotes.filterNot { it.isTrashed }.toMutableList()
                        if (StorageRepository.savePublicNotes(context, active)) {
                            trashed.forEach { ReminderReceiver.cancelReminder(context, it.id) }
                            publicNotes = active
                        }
                        showEmptyPublicTrashDialog = false
                    }
                ) {
                    Text(AppLanguage.text(context, "Delete All", "حذف الكل"), color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyPublicTrashDialog = false }) {
                    Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray)
                }
            }
        )
    }
}