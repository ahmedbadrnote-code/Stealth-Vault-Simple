package com.stealth.vaultnotes

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

data class SerializableStroke(val points: List<Pair<Float, Float>>, val colorHex: Long)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    activity: ComponentActivity,
    printContext: Context,
    note: NoteItem,
    onSave: (NoteItem) -> Unit,
    onBack: () -> Unit,
    onScheduleReminder: (NoteItem, Long) -> Unit,
    allowReminders: Boolean = true
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var title by remember(note.id) { mutableStateOf(note.title) }
    var htmlContent by remember(note.id) { mutableStateOf(note.htmlContent) }
    var plainText by remember(note.id) { mutableStateOf(note.plainTextPreview) }
    var isPinned by remember(note.id) { mutableStateOf(note.isPinned) }
    var reminderTime by remember(note.id) { mutableStateOf(note.reminderTime) }
    var fontSizeLevel by remember(note.id) { mutableIntStateOf(3) } // 1 (min) to 7 (max)
    var isDrawingMode by remember(note.id) { mutableStateOf(false) }
    var selectedPenColor by remember(note.id) { mutableLongStateOf(0xFF69F0AE) }
    var showColorPickerForText by remember(note.id) { mutableStateOf(false) }
    var showHighlightPicker by remember(note.id) { mutableStateOf(false) }
    var showImageSourceDialog by remember(note.id) { mutableStateOf(false) }
    var showReminderDialog by remember(note.id) { mutableStateOf(false) }
    var showPrivatePdfWarning by remember(note.id) { mutableStateOf(false) }
    var textDirection by remember(note.id) { mutableStateOf(if (note.textDirection == "ltr") "ltr" else "rtl") }
    var alignState by remember(note.id) { mutableIntStateOf(if (note.textDirection == "ltr") 2 else 0) } // 0: Right, 1: Center, 2: Left

    val gson = remember { Gson() }
    val strokes = remember(note.id) {
        val initial: List<SerializableStroke> = try {
            if (note.drawingPathsJson.isNotBlank()) {
                val type = object : TypeToken<List<SerializableStroke>>() {}.type
                gson.fromJson(note.drawingPathsJson, type) ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        mutableStateListOf(*initial.toTypedArray())
    }
    var currentStrokePoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val latestHtml = remember(note.id) { AtomicReference(note.htmlContent) }
    val latestPlainText = remember(note.id) { AtomicReference(note.plainTextPreview) }

    fun encodeBitmapToBase64AndInsert(bmp: Bitmap) {
        scope.launch {
            var scaledBitmap: Bitmap? = null
            try {
                val base64 = withContext(Dispatchers.Default) {
                    val maxDim = max(bmp.width, bmp.height)
                    val scaled = if (maxDim > 1080) {
                        val ratio = 1080f / maxDim.toFloat()
                        Bitmap.createScaledBitmap(
                            bmp,
                            (bmp.width * ratio).toInt().coerceAtLeast(1),
                            (bmp.height * ratio).toInt().coerceAtLeast(1),
                            true
                        )
                    } else bmp
                    scaledBitmap = scaled
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
                webViewRef?.evaluateJavascript("insertBase64Image('data:image/jpeg;base64,$base64');", null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, AppLanguage.text(context, "Could not insert selected image.", "تعذّر إدراج الصورة المحددة."), Toast.LENGTH_SHORT).show()
            } finally {
                scaledBitmap?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    // إدراج صورة من المعرض عبر SAF
    val galleryImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                var decodedBitmap: Bitmap? = null
                var handedOff = false
                try {
                    val bmp = withContext(Dispatchers.IO) {
                        val decoded = try {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(it)?.use { s ->
                                BitmapFactory.decodeStream(s, null, bounds)
                            }
                            var sample = 1
                            while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) {
                                sample *= 2
                            }
                            context.contentResolver.openInputStream(it)?.use { s ->
                                BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = sample })
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        decodedBitmap = decoded
                        decoded
                    }
                    if (bmp != null) {
                        handedOff = true
                        encodeBitmapToBase64AndInsert(bmp)
                    } else {
                        Toast.makeText(context, AppLanguage.text(context, "Could not load selected image.", "تعذّر تحميل الصورة المحددة."), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    if (!handedOff) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            decodedBitmap?.takeIf { !it.isRecycled }?.recycle()
                        }
                    }
                }
            }
        }
    }

    // التقاط صورة جديدة بالكاميرا مباشرة بدون أذونات تخزين دائمة
    val cameraImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            encodeBitmapToBase64AndInsert(bitmap)
        }
    }

    fun buildUpdatedNote(): NoteItem = note.copy(
        title = title.ifBlank { "Untitled note" },
        htmlContent = latestHtml.get(),
        plainTextPreview = latestPlainText.get(),
        drawingPathsJson = gson.toJson(strokes.toList()),
        isPinned = isPinned,
        reminderTime = reminderTime,
        textDirection = textDirection,
        updatedAt = System.currentTimeMillis()
    )

    fun persistAndExit() {
        try {
            onSave(buildUpdatedNote())
            Toast.makeText(context, AppLanguage.text(context, "Note saved successfully ✅", "تم حفظ الملاحظة بنجاح ✅"), Toast.LENGTH_SHORT).show()
            onBack()
        } catch (_: Exception) {
            Toast.makeText(context, AppLanguage.text(context, "Could not save note. Please try again.", "تعذّر حفظ الملاحظة. حاول مرة أخرى."), Toast.LENGTH_LONG).show()
        }
    }

    fun saveAndExit() {
        val editorView = webViewRef
        if (editorView == null) {
            persistAndExit()
            return
        }
        editorView.evaluateJavascript(
            "(function(){ var e=document.getElementById('editor'); if(e){ notifyEditorChanged(); } return true; })();"
        ) {
            persistAndExit()
        }
    }


    fun launchPdfExport() {
        val editorView = webViewRef
        if (editorView == null) {
            Toast.makeText(context, AppLanguage.text(context, "The editor is not ready for PDF export.", "المحرر غير جاهز لتصدير PDF."), Toast.LENGTH_SHORT).show()
            return
        }

        val printTitle = title.trim()
            .ifBlank { "Note" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)

        editorView.evaluateJavascript(
            """
            (function() {
                if (typeof notifyEditorChanged === 'function') notifyEditorChanged();
                var style = document.getElementById('pdf-print-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'pdf-print-style';
                    document.head.appendChild(style);
                }
                style.textContent = '@page { size: A4; margin: 12mm; }' +
                    '@media print {' +
                    'html, body { background: #ffffff !important; color: #111111 !important; padding: 0 !important; margin: 0 !important; min-height: 0 !important; }' +
                    '#editor { background: #ffffff !important; color: #111111 !important; min-height: 0 !important; outline: none !important; }' +
                    'img { max-width: 100% !important; height: auto !important; break-inside: avoid; }' +
                    '.todo-item { break-inside: avoid; }' +
                    '}';
                return true;
            })();
            """.trimIndent()
        ) {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    throw IllegalStateException("The activity is no longer active.")
                }
                val printManager = printContext.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    ?: throw IllegalStateException("Print service is unavailable.")
                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setMinMargins(PrintAttributes.Margins(250, 250, 250, 250))
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build()

                printManager.print(
                    printTitle,
                    editorView.createPrintDocumentAdapter(printTitle),
                    printAttributes
                )
                Toast.makeText(
                    context,
                    AppLanguage.text(context, "Choose Save as PDF in the print screen to save this note.", "اختر «حفظ بتنسيق PDF» من شاشة الطباعة لحفظ هذه الملاحظة."),
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                Log.e("StealthVaultPDF", "Failed to start A4 PDF export", error)
                val message = AppLanguage.text(
                    context,
                    "Could not prepare the PDF for printing. Please try again.",
                    "تعذّر تجهيز ملف PDF للطباعة. حاول مرة أخرى."
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Save the note automatically when the device back button is pressed.
    BackHandler {
        if (isDrawingMode) {
            isDrawingMode = false
        } else {
            saveAndExit()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.removeJavascriptInterface("AndroidBridge")
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    // Preserve the current note before the activity is paused (for example, when
    // opening a file picker or leaving the app). This is especially important
    // because the vault locks and removes its editor when sent to the background.
    val saveDraftOnPause by rememberUpdatedState {
        try {
            onSave(buildUpdatedNote())
        } catch (_: Exception) {
            Toast.makeText(context, AppLanguage.text(context, "Could not auto-save this note. Reopen it and try saving again.", "تعذّر الحفظ التلقائي لهذه الملاحظة. افتحها مجددًا وحاول الحفظ."), Toast.LENGTH_LONG).show()
        }
    }
    val hostActivity = context as? ComponentActivity
    DisposableEffect(hostActivity, note.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) saveDraftOnPause()
        }
        hostActivity?.lifecycle?.addObserver(observer)
        onDispose { hostActivity?.lifecycle?.removeObserver(observer) }
    }

    val wordCount = remember(plainText) {
        val clean = plainText.trim()
        if (clean.isEmpty()) 0 else clean.split("\\s+".toRegex()).size
    }
    val charCount = remember(plainText) { plainText.length }
    val readingTimeSec = remember(wordCount) {
        if (wordCount == 0) 0 else ((wordCount / 200.0) * 60).toInt().coerceAtLeast(1)
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E)),
                title = {
                    TextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text(AppLanguage.text(context, "Note title...", "عنوان الملاحظة..."), color = Color.Gray) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { saveAndExit() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = AppLanguage.text(context, "Back", "رجوع"), tint = Color.White)
                    }
                },
                actions = {
                    // التراجع والحفظ يظلان ظاهرين دائمًا بجوار عنوان الملاحظة.
                    IconButton(onClick = { webViewRef?.evaluateJavascript("document.execCommand('undo'); notifyEditorChanged();", null) }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = AppLanguage.text(context, "Undo", "تراجع"), tint = Color.White)
                    }
                    IconButton(onClick = { webViewRef?.evaluateJavascript("document.execCommand('redo'); notifyEditorChanged();", null) }) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = AppLanguage.text(context, "Redo", "إعادة"), tint = Color.White)
                    }
                    IconButton(onClick = { saveAndExit() }) {
                        Icon(Icons.Default.Check, contentDescription = AppLanguage.text(context, "Save", "حفظ"), tint = Color(0xFF69F0AE))
                    }

                    // بقية إجراءات الملاحظة داخل قائمة مختصرة لتوفير مساحة العنوان.
                    var showNoteOptions by remember(note.id) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showNoteOptions = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = AppLanguage.text(context, "More options", "المزيد"), tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showNoteOptions,
                            onDismissRequest = { showNoteOptions = false },
                            modifier = Modifier.background(Color(0xFF2A2A2A))
                        ) {
                            DropdownMenuItem(
                                text = { Text(AppLanguage.text(context, if (isPinned) "Unpin note" else "Pin note", if (isPinned) "إلغاء تثبيت الملاحظة" else "تثبيت الملاحظة")) },
                                leadingIcon = {
                                    Icon(Icons.Default.PushPin, contentDescription = null, tint = if (isPinned) Color(0xFFFFD54F) else Color.White)
                                },
                                onClick = {
                                    isPinned = !isPinned
                                    showNoteOptions = false
                                }
                            )
                            if (allowReminders) {
                                DropdownMenuItem(
                                    text = { Text(AppLanguage.text(context, "Reminder", "التنبيه")) },
                                    leadingIcon = {
                                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = if (reminderTime != null && reminderTime!! > System.currentTimeMillis()) Color(0xFF69F0AE) else Color(0xFF80CBC4))
                                    },
                                    onClick = {
                                        showNoteOptions = false
                                        showReminderDialog = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(AppLanguage.text(context, "Export as PDF", "تصدير PDF")) },
                                leadingIcon = {
                                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFFF8A65))
                                },
                                onClick = {
                                    showNoteOptions = false
                                    if (allowReminders) launchPdfExport() else showPrivatePdfWarning = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .navigationBarsPadding()
            ) {
                // شريط ألوان القلم في وضع الرسم الحر
                if (isDrawingMode) {
                    val penColors = listOf(
                        0xFF69F0AE,
                        0xFFFF5252,
                        0xFFFFD740,
                        0xFF40C4FF,
                        0xFFE040FB,
                        0xFFFFFFFF
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(AppLanguage.text(context, "Pen:", "القلم:"), color = Color.LightGray, fontSize = 12.sp)
                            penColors.forEach { hexLong ->
                                val isSelected = selectedPenColor == hexLong
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 28.dp else 22.dp)
                                        .clip(CircleShape)
                                        .background(Color(hexLong))
                                        .border(if (isSelected) 2.dp else 1.dp, if (isSelected) Color.White else Color.Gray, CircleShape)
                                        .clickable { selectedPenColor = hexLong }
                                )
                            }
                        }
                        Row {
                            if (strokes.isNotEmpty()) {
                                TextButton(onClick = { strokes.removeLastOrNull() }) {
                                    Text(AppLanguage.text(context, "Undo Stroke", "التراجع عن آخر خط"), color = Color(0xFFFFAB40), fontSize = 12.sp)
                                }
                                TextButton(onClick = { strokes.clear() }) {
                                    Text(AppLanguage.text(context, "Clear All", "مسح الكل"), color = Color(0xFFFF5252), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // لوحة اختيار الألوان للنص أو قلم التمييز (Highlight)
                if (showColorPickerForText || showHighlightPicker) {
                    val textPalette = listOf(
                        "#E0E0E0" to Color(0xFFE0E0E0),
                        "#FF5252" to Color(0xFFFF5252),
                        "#FFD740" to Color(0xFFFFD740),
                        "#69F0AE" to Color(0xFF69F0AE),
                        "#40C4FF" to Color(0xFF40C4FF),
                        "#E040FB" to Color(0xFFE040FB),
                        "#FFAB40" to Color(0xFFFFAB40)
                    )
                    val highlightPalette = listOf(
                        "transparent" to Color(0xFF121212),
                        "#5C1616" to Color(0xFF5C1616),
                        "#5A4A00" to Color(0xFF5A4A00),
                        "#0F4D2A" to Color(0xFF0F4D2A),
                        "#0D3C55" to Color(0xFF0D3C55),
                        "#4A155B" to Color(0xFF4A155B)
                    )
                    val activePalette = if (showColorPickerForText) textPalette else highlightPalette
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (showColorPickerForText) AppLanguage.text(context, "Text color:", "لون النص:") else AppLanguage.text(context, "Highlight:", "تمييز:"),
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                        activePalette.forEach { (hex, composeColor) ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(composeColor)
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable {
                                        if (showColorPickerForText) {
                                            webViewRef?.evaluateJavascript("document.execCommand('foreColor', false, '$hex'); notifyEditorChanged();", null)
                                            showColorPickerForText = false
                                        } else {
                                            webViewRef?.evaluateJavascript("document.execCommand('hiliteColor', false, '$hex'); notifyEditorChanged();", null)
                                            showHighlightPicker = false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (hex == "transparent") {
                                    Icon(Icons.Default.FormatColorReset, contentDescription = AppLanguage.text(context, "Remove highlight", "إزالة التمييز"), tint = Color.White, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }

                // شريط الأدوات الكامل (Rich Formatting Toolbar)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. أيقونة الصورة (من المعرض أو التقاط بالكاميرا)
                    IconButton(onClick = { showImageSourceDialog = true }) {
                        Icon(Icons.Default.Image, contentDescription = AppLanguage.text(context, "Insert image", "إدراج صورة"), tint = Color.White)
                    }

                    // 2. أيقونة الخط المنحني (الرسم اليدوي الحر)
                    IconButton(onClick = {
                        isDrawingMode = !isDrawingMode
                        if (isDrawingMode) {
                            focusManager.clearFocus()
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            webViewRef?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
                        }
                    }) {
                        Icon(
                            Icons.Default.Gesture,
                            contentDescription = AppLanguage.text(context, "Draw", "رسم"),
                            tint = if (isDrawingMode) Color(0xFF69F0AE) else Color.White
                        )
                    }

                    // 3. أيقونة مربع الاختيار (قائمة المهام / Checklist)
                    IconButton(onClick = {
                        webViewRef?.evaluateJavascript("insertChecklistItem();", null)
                    }) {
                        Icon(Icons.Default.CheckBox, contentDescription = AppLanguage.text(context, "Checklist", "قائمة مهام"), tint = Color.White)
                    }

                    // 4. أيقونة قلم التمييز (Highlight)
                    IconButton(onClick = {
                        showHighlightPicker = !showHighlightPicker
                        showColorPickerForText = false
                    }) {
                        Icon(Icons.Default.BorderColor, contentDescription = AppLanguage.text(context, "Highlight text", "تمييز النص"), tint = Color(0xFFFFD740))
                    }

                    // 5. أيقونة تلوين النص المحدد
                    IconButton(onClick = {
                        showColorPickerForText = !showColorPickerForText
                        showHighlightPicker = false
                    }) {
                        Icon(Icons.Default.FormatColorText, contentDescription = AppLanguage.text(context, "Text color", "لون النص"), tint = Color(0xFF40C4FF))
                    }

                    // 6. أيقونة A+ (تكبير حجم الخط)
                    TextButton(
                        onClick = {
                            if (fontSizeLevel < 7) {
                                fontSizeLevel++
                                webViewRef?.evaluateJavascript("document.execCommand('fontSize', false, '$fontSizeLevel'); notifyEditorChanged();", null)
                            }
                        }
                    ) {
                        Text("A+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    // 7. أيقونة A- (تصغير حجم الخط - غير مفعلة في أصغر حجم)
                    val isMinFont = fontSizeLevel <= 1
                    TextButton(
                        enabled = !isMinFont,
                        onClick = {
                            if (fontSizeLevel > 1) {
                                fontSizeLevel--
                                webViewRef?.evaluateJavascript("document.execCommand('fontSize', false, '$fontSizeLevel'); notifyEditorChanged();", null)
                            }
                        }
                    ) {
                        Text(
                            "A-",
                            color = if (isMinFont) Color.DarkGray else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    // 8. أيقونة B (Bold / عريض)
                    TextButton(onClick = { webViewRef?.evaluateJavascript("document.execCommand('bold'); notifyEditorChanged();", null) }) {
                        Text("B", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    }

                    // 9. أيقونة I (Italic / مائل)
                    TextButton(onClick = { webViewRef?.evaluateJavascript("document.execCommand('italic'); notifyEditorChanged();", null) }) {
                        Text("I", color = Color.White, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }

                    // 10. أيقونة U (Underline / تسطير)
                    TextButton(onClick = { webViewRef?.evaluateJavascript("document.execCommand('underline'); notifyEditorChanged();", null) }) {
                        Text("U", color = Color.White, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }

                    // 11. أيقونة القائمة النقطية (Bullet List)
                    IconButton(onClick = { webViewRef?.evaluateJavascript("document.execCommand('insertUnorderedList'); notifyEditorChanged();", null) }) {
                        Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = AppLanguage.text(context, "Bulleted list", "قائمة نقطية"), tint = Color.White)
                    }

                    // 12. اتجاه النص والمحاذاة (Alignment: يمين / وسط / يسار)
                    TextButton(onClick = {
                        textDirection = "rtl"
                        alignState = 0
                        webViewRef?.evaluateJavascript("setTextDirection('rtl');", null)
                    }) {
                        Text(AppLanguage.text(context, "RTL", "من اليمين لليسار"), color = if (textDirection == "rtl") Color(0xFF69F0AE) else Color.White, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = {
                        textDirection = "ltr"
                        alignState = 2
                        webViewRef?.evaluateJavascript("setTextDirection('ltr');", null)
                    }) {
                        Text(AppLanguage.text(context, "LTR", "من اليسار لليمين"), color = if (textDirection == "ltr") Color(0xFF69F0AE) else Color.White, fontWeight = FontWeight.Bold)
                    }

                    IconButton(onClick = {
                        alignState = (alignState + 1) % 3
                        val cmd = when (alignState) {
                            0 -> "justifyRight"
                            1 -> "justifyCenter"
                            else -> "justifyLeft"
                        }
                        webViewRef?.evaluateJavascript("document.execCommand('$cmd'); notifyEditorChanged();", null)
                    }) {
                        val icon = when (alignState) {
                            0 -> Icons.AutoMirrored.Filled.FormatAlignRight
                            1 -> Icons.Default.FormatAlignCenter
                            else -> Icons.AutoMirrored.Filled.FormatAlignLeft
                        }
                        Icon(icon, contentDescription = AppLanguage.text(context, "Alignment", "المحاذاة"), tint = Color.White)
                    }
                }

                // شريط الإحصائيات أسفل النوت (عدد الكلمات والحروف ووقت القراءة المتوقع)
                HorizontalDivider(color = Color(0xFF2C2C2C))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = AppLanguage.text(context, "Words: $wordCount  |  Characters: $charCount", "الكلمات: $wordCount  |  الأحرف: $charCount"),
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                    Text(
                        text = AppLanguage.text(context, "Reading time: ~${readingTimeSec} sec", "وقت القراءة: نحو ${readingTimeSec} ثانية"),
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // محرر النصوص المتقدم المعزول أمنياً
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(activity).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.blockNetworkImage = true
                        setBackgroundColor(android.graphics.Color.parseColor("#121212"))
                        webViewClient = WebViewClient()

                        val editorWebView = this
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onContentChanged(html: String, text: String) {
                                latestHtml.set(html)
                                latestPlainText.set(text)
                                Handler(Looper.getMainLooper()).post {
                                    htmlContent = html
                                    plainText = text
                                }
                            }

                            @JavascriptInterface
                            fun requestKeyboard() {
                                Handler(Looper.getMainLooper()).post {
                                    editorWebView.requestFocus()
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showSoftInput(editorWebView, InputMethodManager.SHOW_IMPLICIT)
                                }
                            }
                        }, "AndroidBridge")

                        val initialBase64 = Base64.encodeToString(
                            note.htmlContent.toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP
                        )

                        val editorHtml = """
                            <!DOCTYPE html>
                            <html dir="$textDirection">
                            <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <style>
                                html, body {
                                    min-height: 100%;
                                    background-color: #121212;
                                    color: #E0E0E0;
                                    font-family: sans-serif;
                                    font-size: 16px;
                                    padding: 16px;
                                    margin: 0;
                                    min-height: 90vh;
                                    outline: none;
                                }
                                img {
                                    max-width: 100%;
                                    border-radius: 8px;
                                    margin: 8px 0;
                                }
                                .todo-item {
                                    display: flex;
                                    align-items: flex-start;
                                    gap: 10px;
                                    margin: 8px 0;
                                    min-height: 24px;
                                    width: 100%;
                                }
                                .todo-checkbox {
                                    flex: 0 0 20px;
                                    width: 20px;
                                    height: 20px;
                                    margin: 2px 0 0 0;
                                    accent-color: #69F0AE;
                                    cursor: pointer;
                                }
                                .todo-text {
                                    display: block;
                                    flex: 1 1 auto;
                                    min-width: 0;
                                    min-height: 24px;
                                    outline: none;
                                    white-space: pre-wrap;
                                    overflow-wrap: anywhere;
                                }
                            </style>
                            </head>
                            <body>
                            <div id="editor" dir="$textDirection" contenteditable="true" style="min-height:85vh; outline:none; direction:$textDirection;"></div>
                            <script>
                                const editor = document.getElementById('editor');

                                function decodeBase64Utf8(base64) {
                                    try {
                                        if (!base64) return "";
                                        const binary = atob(base64);
                                        const bytes = new Uint8Array(binary.length);
                                        for (let i = 0; i < binary.length; i++) {
                                            bytes[i] = binary.charCodeAt(i);
                                        }
                                        return new TextDecoder('utf-8').decode(bytes);
                                    } catch (e) {
                                        return "";
                                    }
                                }

                                editor.innerHTML = decodeBase64Utf8('$initialBase64');

                                function getTodoCheckbox(task) {
                                    return task ? task.querySelector("input[type='checkbox']") : null;
                                }

                                function getTodoLabel(task) {
                                    if (!task) return null;
                                    return task.querySelector(".todo-text") ||
                                        task.querySelector("span[contenteditable='true']") ||
                                        task.querySelector("span");
                                }

                                function syncTodoTask(task) {
                                    if (!task) return;
                                    const checkbox = getTodoCheckbox(task);
                                    let label = getTodoLabel(task);
                                    if (!checkbox) return;

                                    checkbox.classList.add("todo-checkbox");
                                    checkbox.setAttribute("contenteditable", "false");

                                    if (!label) {
                                        label = document.createElement("span");
                                        label.className = "todo-text";
                                        Array.from(task.childNodes).forEach(function(node) {
                                            if (node !== checkbox) label.appendChild(node);
                                        });
                                        task.appendChild(label);
                                    }

                                    label.classList.add("todo-text");
                                    label.removeAttribute("contenteditable");
                                    checkbox.checked = !!checkbox.checked;
                                    if (checkbox.checked) checkbox.setAttribute("checked", "");
                                    else checkbox.removeAttribute("checked");
                                    label.style.textDecoration = checkbox.checked ? "line-through" : "none";
                                }

                                function normalizeTodoTasks() {
                                    editor.querySelectorAll(".todo-item").forEach(function(task) {
                                        const checkbox = getTodoCheckbox(task);
                                        const label = getTodoLabel(task);
                                        if (!checkbox) {
                                            task.classList.remove("todo-item");
                                            task.removeAttribute("data-todo-item");
                                            return;
                                        }
                                        const oldDecoration = label
                                            ? (label.style.textDecoration || label.style.textDecorationLine || "")
                                            : "";
                                        const savedChecked = checkbox.hasAttribute("checked");
                                        checkbox.checked = savedChecked || oldDecoration.indexOf("line-through") !== -1;
                                        task.setAttribute("data-todo-item", "true");
                                        syncTodoTask(task);
                                    });
                                }

                                function serializeEditorHtml() {
                                    const copy = editor.cloneNode(true);
                                    const liveTasks = editor.querySelectorAll(".todo-item");
                                    const savedTasks = copy.querySelectorAll(".todo-item");

                                    savedTasks.forEach(function(task, index) {
                                        const liveTask = liveTasks[index];
                                        const liveCheckbox = liveTask ? getTodoCheckbox(liveTask) : null;
                                        const savedCheckbox = getTodoCheckbox(task);
                                        const savedLabel = getTodoLabel(task);
                                        if (liveCheckbox && savedCheckbox) {
                                            if (liveCheckbox.checked) savedCheckbox.setAttribute("checked", "");
                                            else savedCheckbox.removeAttribute("checked");
                                            if (savedLabel) {
                                                savedLabel.style.textDecoration =
                                                    liveCheckbox.checked ? "line-through" : "none";
                                            }
                                        }
                                    });
                                    return copy.innerHTML;
                                }

                                function notifyEditorChanged() {
                                    AndroidBridge.onContentChanged(
                                        serializeEditorHtml(),
                                        editor.innerText || editor.textContent || ""
                                    );
                                }

                                function setTextDirection(direction) {
                                    editor.setAttribute("dir", direction);
                                    editor.style.direction = direction;
                                    notifyEditorChanged();
                                }

                                function getCurrentTodoLabel() {
                                    const selection = window.getSelection();
                                    if (!selection || selection.rangeCount === 0) return null;
                                    let node = selection.anchorNode;
                                    if (!node || !editor.contains(node)) return null;
                                    if (node.nodeType === Node.TEXT_NODE) node = node.parentElement;
                                    if (!node || !node.closest) return null;
                                    const label = node.closest(".todo-text, .todo-item span");
                                    return label && editor.contains(label) ? label : null;
                                }

                                function focusEditable(element, atStart) {
                                    if (!element) return;
                                    editor.focus();
                                    const range = document.createRange();
                                    range.selectNodeContents(element);
                                    range.collapse(!!atStart);
                                    const selection = window.getSelection();
                                    selection.removeAllRanges();
                                    selection.addRange(range);
                                }

                                function isCaretAtEdge(element, atStart) {
                                    const selection = window.getSelection();
                                    if (!selection || selection.rangeCount === 0 || !selection.isCollapsed) return false;
                                    const current = selection.getRangeAt(0);
                                    if (!element.contains(current.startContainer) && current.startContainer !== element) return false;
                                    const probe = document.createRange();
                                    probe.selectNodeContents(element);
                                    probe.setEnd(current.startContainer, current.startOffset);
                                    const textBefore = probe.toString().length;
                                    return atStart ? textBefore === 0 : textBefore === (element.textContent || "").length;
                                }

                                function createTodoTask(initialText) {
                                    const task = document.createElement("div");
                                    task.className = "todo-item";
                                    task.setAttribute("data-todo-item", "true");

                                    const checkbox = document.createElement("input");
                                    checkbox.type = "checkbox";
                                    checkbox.className = "todo-checkbox";
                                    checkbox.setAttribute("contenteditable", "false");

                                    const label = document.createElement("span");
                                    label.className = "todo-text";
                                    label.textContent = initialText || "";

                                    task.appendChild(checkbox);
                                    task.appendChild(label);
                                    return { task: task, checkbox: checkbox, label: label };
                                }

                                function createPlainParagraph() {
                                    const paragraph = document.createElement("div");
                                    paragraph.appendChild(document.createElement("br"));
                                    return paragraph;
                                }

                                function exitTodoAt(task) {
                                    const paragraph = createPlainParagraph();
                                    if (task && task.parentNode) {
                                        if ((task.textContent || "").trim().length === 0) {
                                            task.parentNode.replaceChild(paragraph, task);
                                        } else {
                                            task.parentNode.insertBefore(paragraph, task.nextSibling);
                                        }
                                    } else {
                                        editor.appendChild(paragraph);
                                    }
                                    focusEditable(paragraph, true);
                                    notifyEditorChanged();
                                }

                                function insertChecklistItem() {
                                    const currentLabel = getCurrentTodoLabel();
                                    const currentTask = currentLabel ? currentLabel.closest(".todo-item") : null;

                                    if (currentTask && (currentLabel.textContent || "").trim().length === 0) {
                                        exitTodoAt(currentTask);
                                        return;
                                    }

                                    const selection = window.getSelection();
                                    let range = null;
                                    if (selection && selection.rangeCount > 0) {
                                        const activeRange = selection.getRangeAt(0);
                                        if (editor.contains(activeRange.commonAncestorContainer)) {
                                            range = activeRange.cloneRange();
                                        }
                                    }

                                    const created = createTodoTask("");
                                    if (currentTask && currentTask.parentNode) {
                                        currentTask.parentNode.insertBefore(created.task, currentTask.nextSibling);
                                    } else if (range) {
                                        range.deleteContents();
                                        range.insertNode(created.task);
                                    } else {
                                        editor.appendChild(created.task);
                                    }

                                    focusEditable(created.label, true);
                                    notifyEditorChanged();
                                }

                                function splitTodoAtCaret(label, currentTask) {
                                    const selection = window.getSelection();
                                    if (!selection || selection.rangeCount === 0) return;
                                    const range = selection.getRangeAt(0);
                                    if (!label.contains(range.startContainer) && range.startContainer !== label) return;

                                    range.deleteContents();
                                    range.collapse(true);
                                    const tailRange = document.createRange();
                                    tailRange.setStart(range.startContainer, range.startOffset);
                                    tailRange.setEnd(label, label.childNodes.length);
                                    const trailingContent = tailRange.extractContents();

                                    const created = createTodoTask("");
                                    created.label.appendChild(trailingContent);
                                    currentTask.parentNode.insertBefore(created.task, currentTask.nextSibling);
                                    focusEditable(created.label, true);
                                    notifyEditorChanged();
                                }

                                function mergeWithPreviousTask(label, task) {
                                    const previous = task.previousElementSibling;
                                    if (!previous || !previous.classList.contains("todo-item")) return false;
                                    const previousLabel = getTodoLabel(previous);
                                    if (!previousLabel) return false;

                                    const previousText = previousLabel.textContent || "";
                                    const currentText = label.textContent || "";
                                    if (previousText.length && currentText.length &&
                                        !/\s$/.test(previousText) && !/^\s/.test(currentText)) {
                                        previousLabel.appendChild(document.createTextNode(" "));
                                    }
                                    while (label.firstChild) previousLabel.appendChild(label.firstChild);
                                    task.remove();
                                    focusEditable(previousLabel, false);
                                    notifyEditorChanged();
                                    return true;
                                }

                                normalizeTodoTasks();

                                editor.addEventListener("input", function() {
                                    notifyEditorChanged();
                                });

                                editor.addEventListener("change", function(event) {
                                    const checkbox = event.target;
                                    if (!checkbox || checkbox.type !== "checkbox" ||
                                        !checkbox.closest(".todo-item") || !editor.contains(checkbox)) return;
                                    syncTodoTask(checkbox.closest(".todo-item"));
                                    notifyEditorChanged();
                                });

                                editor.addEventListener("keydown", function(event) {
                                    const currentLabel = getCurrentTodoLabel();
                                    if (!currentLabel) return;
                                    const currentTask = currentLabel.closest(".todo-item");
                                    if (!currentTask) return;

                                    if (event.key === "Enter" && !event.shiftKey) {
                                        event.preventDefault();
                                        if ((currentLabel.textContent || "").trim().length === 0) {
                                            exitTodoAt(currentTask);
                                            return;
                                        }
                                        splitTodoAtCaret(currentLabel, currentTask);
                                        return;
                                    }

                                    if (event.key === "Backspace" && isCaretAtEdge(currentLabel, true)) {
                                        if ((currentLabel.textContent || "").length === 0) {
                                            event.preventDefault();
                                            exitTodoAt(currentTask);
                                            return;
                                        }
                                        const previousTask = currentTask.previousElementSibling;
                                        if (previousTask && previousTask.classList.contains("todo-item")) {
                                            event.preventDefault();
                                            mergeWithPreviousTask(currentLabel, currentTask);
                                        }
                                    }
                                });

                                document.addEventListener("click", function(event) {
                                    if (event.target && event.target.closest("input[type='checkbox']")) return;
                                    editor.focus();
                                    AndroidBridge.requestKeyboard();
                                });

                                function insertBase64Image(src) {
                                    editor.focus();
                                    const html = '<img src="' + src + '" /><br>';
                                    document.execCommand('insertHTML', false, html);
                                    notifyEditorChanged();
                                }
                            </script>
                            </body>
                            </html>
                        """.trimIndent()

                        loadDataWithBaseURL(null, editorHtml, "text/html", "UTF-8", null)
                        webViewRef = this
                    }
                }
            )

            // طبقة الرسم الحر باليد (تدعم الخطوط المنحنية والنقاط المفردة للحروف العربية)
            if (strokes.isNotEmpty() || isDrawingMode) {
                val cachedPaths = remember(strokes.size, strokes.toList()) {
                    strokes.map { stroke ->
                        val path = Path()
                        stroke.points.forEachIndexed { idx, pair ->
                            if (idx == 0) path.moveTo(pair.first, pair.second)
                            else path.lineTo(pair.first, pair.second)
                        }
                        path to Color(stroke.colorHex)
                    }
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isDrawingMode) {
                                Modifier
                                    .pointerInput(selectedPenColor) {
                                        detectTapGestures { offset ->
                                            strokes.add(
                                                SerializableStroke(
                                                    listOf(offset.x to offset.y, (offset.x + 0.5f) to (offset.y + 0.5f)),
                                                    selectedPenColor
                                                )
                                            )
                                        }
                                    }
                                    .pointerInput(selectedPenColor) {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                currentStrokePoints = listOf(offset.x to offset.y)
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                currentStrokePoints = currentStrokePoints + (change.position.x to change.position.y)
                                            },
                                            onDragEnd = {
                                                if (currentStrokePoints.size == 1) {
                                                    val p = currentStrokePoints.first()
                                                    strokes.add(
                                                        SerializableStroke(
                                                            listOf(p, (p.first + 0.5f) to (p.second + 0.5f)),
                                                            selectedPenColor
                                                        )
                                                    )
                                                } else if (currentStrokePoints.size > 1) {
                                                    strokes.add(SerializableStroke(currentStrokePoints, selectedPenColor))
                                                }
                                                currentStrokePoints = emptyList()
                                            }
                                        )
                                    }
                            } else Modifier
                        )
                ) {
                    cachedPaths.forEach { (path, color) ->
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                    if (currentStrokePoints.isNotEmpty()) {
                        val livePath = Path()
                        currentStrokePoints.forEachIndexed { idx, pair ->
                            if (idx == 0) livePath.moveTo(pair.first, pair.second)
                            else livePath.lineTo(pair.first, pair.second)
                        }
                        drawPath(
                            path = livePath,
                            color = Color(selectedPenColor),
                            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }
                }
            }
        }
    }

    // نافذة اختيار مصدر الصورة (معرض الصور أو التقاط صورة بالكاميرا)
    if (showImageSourceDialog) {
        AlertDialog(
            onDismissRequest = { showImageSourceDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(AppLanguage.text(context, "Insert Image", "إدراج صورة"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C)),
                        onClick = {
                            showImageSourceDialog = false
                            galleryImageLauncher.launch(arrayOf("image/*"))
                        }
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Color(0xFF69F0AE))
                        Spacer(Modifier.width(10.dp))
                        Text(AppLanguage.text(context, "Choose from Gallery", "اختيار من المعرض"), color = Color.White)
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C)),
                        onClick = {
                            showImageSourceDialog = false
                            cameraImageLauncher.launch(null)
                        }
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Color(0xFF40C4FF))
                        Spacer(Modifier.width(10.dp))
                        Text(AppLanguage.text(context, "Take a New Photo", "التقاط صورة جديدة"), color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageSourceDialog = false }) {
                    Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray)
                }
            }
        )
    }

    // نافذة ضبط تاريخ ووقت للتذكير بالملاحظة
    if (allowReminders && showReminderDialog) {
        var minutesFromNow by remember { mutableStateOf("10") }
        val reminderFormatter = remember { SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale.getDefault()) }

        AlertDialog(
            onDismissRequest = { showReminderDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(AppLanguage.text(context, "Note Reminder ⏰", "تذكير الملاحظة ⏰"), color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (reminderTime != null && reminderTime!! > System.currentTimeMillis()) {
                        Text(
                            AppLanguage.text(context, "Current reminder: ${reminderFormatter.format(Date(reminderTime!!))}", "التذكير الحالي: ${reminderFormatter.format(Date(reminderTime!!))}"),
                            color = Color(0xFF69F0AE),
                            fontSize = 13.sp
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF69F0AE), contentColor = Color.Black),
                        onClick = {
                            val cal = Calendar.getInstance()
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    TimePickerDialog(
                                        context,
                                        { _, hourOfDay, minute ->
                                            val chosen = Calendar.getInstance().apply {
                                                set(year, month, dayOfMonth, hourOfDay, minute, 0)
                                                set(Calendar.MILLISECOND, 0)
                                            }
                                            val trigger = chosen.timeInMillis
                                            if (trigger <= System.currentTimeMillis()) {
                                                Toast.makeText(context, AppLanguage.text(context, "Please select a future date & time.", "يرجى اختيار تاريخ ووقت في المستقبل."), Toast.LENGTH_SHORT).show()
                                            } else {
                                                reminderTime = trigger
                                                onScheduleReminder(
                                                    note.copy(
                                                        title = title.ifBlank { "Note reminder" },
                                                        plainTextPreview = latestPlainText.get(),
                                                        reminderTime = trigger
                                                    ),
                                                    trigger
                                                )
                                                showReminderDialog = false
                                            }
                                        },
                                        cal.get(Calendar.HOUR_OF_DAY),
                                        cal.get(Calendar.MINUTE),
                                        false
                                    ).show()
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(AppLanguage.text(context, "Pick Exact Date & Time", "اختيار التاريخ والوقت بدقة"), fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = Color(0xFF2C2C2C))
                    Text(AppLanguage.text(context, "Or quick reminder in minutes:", "أو تذكير سريع بالدقائق:"), color = Color.LightGray, fontSize = 13.sp)
                    OutlinedTextField(
                        value = minutesFromNow,
                        onValueChange = { minutesFromNow = it.filter { ch -> ch.isDigit() } },
                        label = { Text(AppLanguage.text(context, "Minutes from now", "عدد الدقائق من الآن")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val mins = (minutesFromNow.toLongOrNull() ?: 10L).coerceAtLeast(1L)
                    val triggerTime = System.currentTimeMillis() + (mins * 60 * 1000L)
                    reminderTime = triggerTime
                    onScheduleReminder(
                        note.copy(
                            title = title.ifBlank { "Note reminder" },
                            plainTextPreview = latestPlainText.get(),
                            reminderTime = triggerTime
                        ),
                        triggerTime
                    )
                    showReminderDialog = false
                }) {
                    Text(AppLanguage.text(context, "Set Quick Timer", "ضبط مؤقت سريع"))
                }
            },
            dismissButton = {
                Row {
                    if (reminderTime != null) {
                        TextButton(onClick = {
                            ReminderReceiver.cancelReminder(context, note.id)
                            reminderTime = null
                            Toast.makeText(context, AppLanguage.text(context, "Reminder cancelled", "تم إلغاء التذكير"), Toast.LENGTH_SHORT).show()
                            showReminderDialog = false
                        }) {
                            Text(AppLanguage.text(context, "Clear", "مسح"), color = Color(0xFFFF5252))
                        }
                    }
                    TextButton(onClick = { showReminderDialog = false }) {
                        Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray)
                    }
                }
            }
        )
    }

    if (showPrivatePdfWarning) {
        AlertDialog(
            onDismissRequest = { showPrivatePdfWarning = false },
            containerColor = Color(0xFF1E1E1E),
            title = {
                Text(AppLanguage.text(context, "Export private note?", "تصدير الملاحظة الخاصة؟"), color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    AppLanguage.text(context, "The PDF will be created outside the encrypted vault and will not be protected by the app passcode. Continue only if you want an external copy of this note.", "سيتم إنشاء ملف PDF خارج الخزنة المشفّرة، ولن يكون محميًا برمز مرور التطبيق. تابع فقط إذا كنت تريد نسخة خارجية من هذه الملاحظة."),
                    color = Color.LightGray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPrivatePdfWarning = false
                    launchPdfExport()
                }) {
                    Text(AppLanguage.text(context, "Continue", "متابعة"), color = Color(0xFFFF8A65))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrivatePdfWarning = false }) {
                    Text(AppLanguage.text(context, "Cancel", "إلغاء"), color = Color.Gray)
                }
            }
        )
    }

}