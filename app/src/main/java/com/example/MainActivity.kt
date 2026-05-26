package com.example

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SubtitleState
import com.example.viewmodel.SubtitleViewModel
import com.example.api.TranscriptionSegment

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    AutoSubtitleScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSubtitleScreen(
    modifier: Modifier = Modifier,
    viewModel: SubtitleViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playTime by viewModel.currentPlayTimeSeconds.collectAsState()
    val history by viewModel.historyState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadHistory()
    }

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var rawFileSizeStr by remember { mutableStateOf("") }
    
    // AI Parameters
    var sourceLanguage by remember { mutableStateOf("Auto") }
    val sourceLanguages = listOf("Auto", "th", "en")
    var targetLanguage by remember { mutableStateOf("None") }
    val languages = listOf("None", "English", "Thai", "Japanese", "Chinese", "Korean")
    
    var selectedTone by remember { mutableStateOf("Casual") }
    val tones = listOf("Casual", "Hype", "Formal", "Educational")

    var selectedEngine by remember { mutableStateOf("DEEPSEEK") }
    val engines = listOf("DEEPSEEK", "MISTRAL", "GEMINI")

    // Style Parameters
    var burnSubtitles by remember { mutableStateOf(false) }
    var burnStyle by remember { mutableStateOf("TikTok") }
    val burnStyles = listOf("TikTok", "Modern", "Minimal")

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            // Attempt to retrieve name & size
            try {
                context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameCol != -1) fileName = cursor.getString(nameCol) ?: "unnamed_media"
                        
                        val sizeCol = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeCol != -1) {
                            val bytes = cursor.getLong(sizeCol)
                            rawFileSizeStr = String.format("%.2f MB", bytes.toDouble() / 1_000_000.0)
                        }
                    }
                }
            } catch (e: Exception) {
                fileName = "selected_media_file"
                rawFileSizeStr = "Unknown size"
            }
        }
    }

    val srtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            var srtFileName = "imported_subtitles.srt"
            try {
                context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameCol != -1) srtFileName = cursor.getString(nameCol) ?: "imported_subtitles.srt"
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
            viewModel.importSubtitleFile(it, srtFileName)
        }
    }

    var isVideo by remember { mutableStateOf(false) }
    LaunchedEffect(selectedFileUri) {
        selectedFileUri?.let { uri ->
            try {
                val type = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.getType(uri)
                }
                isVideo = type?.startsWith("video/") == true
            } catch (e: Exception) {
                isVideo = false
            }
        }
    }

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary
        )
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        // Brand Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(gradientBrush, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ClosedCaption,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "EASY AI Subtitles",
                style = TextStyle(
                    brush = gradientBrush,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        Text(
            text = "สร้างมาเพื่อครีเอเตอร์ แปลและเบิร์นในแอปเดียว",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Step 1 Layout
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StepHeader(number = "01", title = "อัปโหลดไฟล์วิดีโอหรือเสียง (Any size)")
            
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { filePickerLauncher.launch("*/*") }
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (selectedFileUri != null) {
                        Icon(
                            imageVector = if (isVideo) Icons.Default.Movie else Icons.Default.AudioFile,
                            contentDescription = "Media Icon",
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = fileName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            SuggestionChip(
                                onClick = {},
                                label = { Text(if (isVideo) "VIDEO" else "AUDIO") }
                            )
                            SuggestionChip(
                                onClick = {},
                                label = { Text(rawFileSizeStr) }
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "💡 Auto-compressed using native extractor",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = "Upload Icon",
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "เลือกไฟล์วิดีโอ/เสียง หรือ นำเข้าไฟล์ซับไตเติ้ล",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "รองรับวิดีโอขนาดใหญ่ หรือเปิดไฟล์ซับ SRT / VTT เพื่อพรีวิว แก้ไขคำ และดาวน์โหลดใหม่ ได้ทันที",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1.1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("เลือกไฟล์แกะซับ", fontSize = 12.sp, maxLines = 1)
                            }
                            
                            OutlinedButton(
                                onClick = { srtPickerLauncher.launch("*/*") },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("นำเข้าซับ SRT/VTT", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Step 2 Layout
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StepHeader(number = "02", title = "ตั้งค่า AI แปลภาษา (Ultra Precision)")
            
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    
                    // Original Audio Language Selection
                    Text(
                        text = "Spoken Audio Language (ภาษาพูดในวิดีโอหลัก)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sourceLanguages.forEach { lang ->
                            val display = when (lang) {
                                "Auto" -> "Auto (ตรวจจับภาษาอัตโนมัติ)"
                                "th" -> "Thai (ภาษาไทย)"
                                "en" -> "English (ภาษาอังกฤษ)"
                                else -> lang
                            }
                            FilterChip(
                                selected = sourceLanguage == lang,
                                onClick = { sourceLanguage = lang },
                                label = { Text(display) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Language Choice
                    Text(
                        text = "Translate to (ภาษาที่ต้องการแปล)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        languages.forEach { lang ->
                            FilterChip(
                                selected = targetLanguage == lang,
                                onClick = { targetLanguage = lang },
                                label = { Text(if (lang == "None") "Original (ต้นฉบับ)" else lang) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Engine Selection Group
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Translation Engine",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Auto-Heal Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        engines.forEach { eng ->
                            ElevatedCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedEngine = eng }
                                    .border(
                                        if (selectedEngine == eng) 2.dp else 1.dp,
                                        if (selectedEngine == eng) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha=0.3f),
                                        RoundedCornerShape(12.dp)
                                    ),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (selectedEngine == eng) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                )
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = eng,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (selectedEngine == eng) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Real-time AI Performance Analytics Panel
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Analytics,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "สถานะการทำงาน AI & Dynamic Priority",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "เรียลไทม์",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            engines.forEach { eng ->
                                val stats = viewModel.getProviderStats(eng)
                                val total = stats.successCount + stats.errorCount
                                val successRatePct = if (total > 0) {
                                    "${(stats.successCount * 100) / total}%"
                                } else {
                                    "100% (นิ่ง)"
                                }
                                val latencyText = if (stats.averageLatencyMs > 0) {
                                    String.format("%.2f วินาที", stats.averageLatencyMs.toDouble() / 1000.0)
                                } else {
                                    "พร้อมใช้งาน"
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    if (stats.errorCount > 0 && stats.successCount == 0) Color.Red else Color.Green,
                                                    shape = CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = eng,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "คำตอบ: $latencyText",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                        Surface(
                                            color = if (stats.errorCount > 0 && stats.successCount == 0) {
                                                MaterialTheme.colorScheme.errorContainer
                                            } else {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "อัตราสำเร็จ: $successRatePct",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (stats.errorCount > 0 && stats.successCount == 0) {
                                                    MaterialTheme.colorScheme.onErrorContainer
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Tone Control Presets
                    Text(
                        text = "Tone Control (อารมณ์/สไตล์ของซับ)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tones.forEach { tone ->
                            FilterChip(
                                selected = selectedTone == tone,
                                onClick = { selectedTone = tone },
                                label = { Text(tone) },
                                shape = RoundedCornerShape(12.dp),
                                leadingIcon = {
                                    if (selectedTone == tone) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Step 3 Layout
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            StepHeader(number = "03", title = "เลือกรูปแบบและซับฝังลงวิดีโอ (Burn-in)")
            
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = burnSubtitles,
                            onCheckedChange = { if (isVideo) burnSubtitles = it else Toast.makeText(context, "ต้องใช้กับไฟล์วิดีโอเท่านั้น", Toast.LENGTH_SHORT).show() }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "เบิร์นซับไตเติ้ลฝังลงในวิดีโอ (Burn captions)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "ส่งออกวิดีโอ MP4 พร้อมตัวหนังสือฝังในภาพ",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }

                    if (burnSubtitles) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Burn-in Style Pack (แพ็กเกจสไตล์ซับ)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            burnStyles.forEach { style ->
                                FilterChip(
                                    selected = burnStyle == style,
                                    onClick = { burnStyle = style },
                                    label = { Text(style) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Main Action button
                    Button(
                        onClick = {
                            selectedFileUri?.let { uri ->
                                viewModel.generateSubtitles(
                                    mediaUri = uri,
                                    fileName = fileName,
                                    targetLanguage = targetLanguage,
                                    tone = selectedTone,
                                    preferredEngine = selectedEngine,
                                    burnSubtitles = burnSubtitles,
                                    sourceLanguage = sourceLanguage
                                )
                            } ?: run {
                                Toast.makeText(context, "กรุณาเลือกไฟล์ก่อนนะ (ขั้นตอนที่ 1)!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = selectedFileUri != null && uiState !is SubtitleState.Loading
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (targetLanguage != "None") "ถอดเสียงและแปลซับ ($targetLanguage)" else "ถอดเสียงแบบต้นฉบับ",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Black)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Stable state type check to prevent destructive container recycling during text editing
        val stateType = remember(uiState) {
            when (uiState) {
                is SubtitleState.Idle -> "idle"
                is SubtitleState.Loading -> "loading"
                is SubtitleState.Error -> "error"
                is SubtitleState.Success -> "success"
            }
        }

        // Dynamic State UI Handling Optimized for Performance and Thread Safety
        AnimatedContent(
            targetState = stateType,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }
        ) { targetType ->
            val currentState = uiState
            when (targetType) {
                "idle" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "กดยืนยันด้านบนเพื่อเริ่มขบวนการจัดทำซับไตเติ้ล",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
                "loading" -> {
                    val loadingState = currentState as? SubtitleState.Loading
                    val messageText = loadingState?.message ?: "กำลังดำเนินการ..."
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.6f))
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(24.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = messageText,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⚠️ ห้ามปิดหน้าจอ ระบบกำลังบีบอัดไฟล์เสียงและประมวลผลผ่าน AI...",
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                "error" -> {
                    val errorState = currentState as? SubtitleState.Error
                    val errorMsg = errorState?.error ?: "เกิดข้อผิดพลาด"
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.error,
                                RoundedCornerShape(20.dp)
                             ),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(20.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Error Logo",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "เกิดข้อผิดพลาดในการรันงาน:",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = errorMsg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                "success" -> {
                    val successState = currentState as? SubtitleState.Success
                    if (successState != null) {
                        KaraokePreviewAndEditor(
                            srtText = successState.subtitleText,
                            segments = successState.segments,
                            burnStyle = burnStyle,
                            isPlaying = isPlaying,
                            playTime = playTime,
                            videoUri = selectedFileUri,
                            onPlayClick = {
                                val maxTime = successState.segments.lastOrNull()?.end ?: 10.0
                                if (isPlaying) {
                                    viewModel.stopPlaybackSimulation()
                                } else {
                                    viewModel.startPlaybackSimulation(maxTime, isVideoAttached = (selectedFileUri != null))
                                }
                            },
                            onStopClick = {
                                viewModel.stopPlaybackSimulation()
                            },
                            onSeek = { seconds ->
                                viewModel.seekToPosition(seconds)
                            },
                            onEditSegment = { index, value ->
                                viewModel.updateSegmentText(index, value)
                            },
                            onRechunk = { maxWords ->
                                viewModel.rechunkSegments(maxWords)
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ประวัติคำแปลล่าสุด",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearAllHistory() }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ล้างทั้งหมด", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (history.isEmpty()) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ClosedCaption,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ยังไม่มีประวัติในแอปนี้",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "ประวัติงานแปลของคุณจะแสดงขึ้นอัตโนมัติเมื่อจัดทำเสร็จ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    history.forEach { item ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.fileName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            SuggestionChip(
                                                onClick = {},
                                                label = { Text("Engine: ${item.engineUsed}") }
                                            )
                                            SuggestionChip(
                                                onClick = {},
                                                label = { Text("แปลเป็น: ${if (item.targetLanguage == "None") "ต้นฉบับ" else item.targetLanguage}") }
                                            )
                                        }
                                    }
                                    
                                    IconButton(
                                        onClick = { viewModel.deleteHistoryItem(item) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete record",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🕒 ${formatHistoryTimestamp(item.timestamp)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Load segment to editor
                                        TextButton(
                                            onClick = {
                                                viewModel.loadHistoryItem(item)
                                                Toast.makeText(context, "โหลดงานแปลนี้ไปยัง Editor สำเร็จ!", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = MaterialTheme.colorScheme.primary
                                            )
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("เปิดดู/แก้ไข", fontSize = 12.sp)
                                        }

                                        // Copy SRT to clipboard
                                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        OutlinedButton(
                                            onClick = {
                                                val clip = android.content.ClipData.newPlainText("SRT Captions", item.srtContent)
                                                clipboardManager.setPrimaryClip(clip)
                                                Toast.makeText(context, "คัดลอกไฟล์ซับ SRT สำเร็จ!", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.3f)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("คัดลอก SRT", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

fun formatHistoryTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("dd MMM yyyy HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
fun SubtitleOverlayText(
    text: String,
    burnStyle: String,
    fontName: String = "SansSerif",
    colorName: String = "Yellow",
    fontSizeSp: Int = 20,
    bgOpacity: Float = 0.65f
) {
    val fontFamily = when (fontName) {
        "Serif" -> FontFamily.Serif
        "Monospace" -> FontFamily.Monospace
        "Cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }

    val textColor = when (colorName) {
        "Cyan" -> Color.Cyan
        "White" -> Color.White
        "Green" -> Color(0xFF39FF14) // Neon Green
        "Orange" -> Color(0xFFFF5722) // Neon Orange
        else -> Color.Yellow
    }

    val styleBgColor = Color.Black.copy(alpha = bgOpacity)

    Box(
        modifier = Modifier
            .background(styleBgColor, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = fontFamily,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun VideoPlayerWithSubtitles(
    videoUri: Uri,
    burnStyle: String,
    isPlaying: Boolean,
    playTime: Double,
    manualSeekTime: Double?,
    onSeekHandled: () -> Unit,
    onPlayStateChanged: (Boolean) -> Unit,
    onPositionChanged: (Double) -> Unit,
    activeSegmentText: String?,
    fontName: String,
    colorName: String,
    fontSizeSp: Int,
    bgOpacity: Float,
    videoAspectRatio: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize ExoPlayer and keep it across recompositions
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    // Prepare and set media items upon URI updates cleanly
    LaunchedEffect(videoUri) {
        try {
            val mediaItem = MediaItem.fromUri(videoUri)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.seekTo((playTime * 1000).toLong())
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayer", "Error preparing ExoPlayer Uri: ${e.message}")
        }
    }

    // Synchronize play/pause commands to ExoPlayer safely
    LaunchedEffect(isPlaying) {
        try {
            if (isPlaying) {
                exoPlayer.play()
            } else {
                exoPlayer.pause()
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayer", "Error controlling ExoPlayer isPlaying: ${e.message}")
        }
    }

    // Capture manual timeline ticks/scrub seeks to seek ExoPlayer safely
    LaunchedEffect(manualSeekTime) {
        manualSeekTime?.let { time ->
            try {
                exoPlayer.seekTo((time * 1000).toLong())
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "Error handling manual seek: ${e.message}")
            }
            onSeekHandled()
        }
    }

    // Register Player.Listener and automate release lifecycle behavior to avoid leaks
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onPlayStateChanged(false)
                    onPositionChanged(0.0)
                    try {
                        exoPlayer.seekTo(0)
                    } catch (e: Exception) {
                        // Suppress
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            try {
                exoPlayer.removeListener(listener)
                exoPlayer.release()
            } catch (e: Exception) {
                // Suppress
            }
        }
    }

    // Direct super-high fidelity polling loop to feed master playback time back to ViewModel Flow
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            try {
                while (true) {
                    val currentPosSeconds = exoPlayer.currentPosition.toDouble() / 1000.0
                    onPositionChanged(currentPosSeconds)
                    kotlinx.coroutines.delay(50L) // Outstanding 50ms polling rate!
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayer", "ExoPlayer progress polling error: ${e.message}")
            }
        }
    }

    val isLandscape = videoAspectRatio == "16:9"
    val videoBoxModifier = if (isLandscape) {
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    } else {
        modifier
            .height(300.dp)
            .aspectRatio(9f / 16f)
    }

    Box(
        modifier = videoBoxModifier
            .background(Color.Black, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false // Hide default controllers
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.player = exoPlayer
            }
        )

        // Render Custom Subtitles layer dynamically over the video with custom font properties
        if (!activeSegmentText.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                SubtitleOverlayText(
                    text = activeSegmentText,
                    burnStyle = burnStyle,
                    fontName = fontName,
                    colorName = colorName,
                    fontSizeSp = fontSizeSp,
                    bgOpacity = bgOpacity
                )
            }
        }
    }
}

@Composable
fun KaraokePreviewAndEditor(
    srtText: String,
    segments: List<TranscriptionSegment>,
    burnStyle: String,
    isPlaying: Boolean,
    playTime: Double,
    videoUri: Uri?,
    onPlayClick: () -> Unit,
    onStopClick: () -> Unit,
    onSeek: (Double) -> Unit,
    onEditSegment: (Int, String) -> Unit,
    onRechunk: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    
    var textToSave by remember { mutableStateOf("") }
    var defaultFileName by remember { mutableStateOf("EASY_AI_Subtitles.srt") }

    // Custom Font & Style options
    var selectedFont by remember { mutableStateOf("SansSerif") }
    var selectedColor by remember { mutableStateOf("Yellow") }
    var selectedFontSize by remember { mutableStateOf(20) }
    var selectedBgOpacity by remember { mutableStateOf(0.65f) }
    var videoAspectRatio by remember { mutableStateOf("16:9") }

    // On-the-fly customizable video capability
    var attachedVideoUri by remember { mutableStateOf<Uri?>(null) }
    
    // Pagination and surgical editor optimizations to prevent major thread locking / app crashes (InputDispatcher channel broken)
    var currentPage by remember { mutableStateOf(0) }
    val pageSize = 5
    var searchQuery by remember { mutableStateOf("") }
    var autoFollowActiveSegment by remember { mutableStateOf(true) }
    var manualSeekTime by remember { mutableStateOf<Double?>(null) }

    val activeSegmentIdx = remember(playTime, segments) {
        segments.indexOfFirst { playTime >= it.start && playTime <= it.end }
    }

    LaunchedEffect(activeSegmentIdx, autoFollowActiveSegment) {
        if (autoFollowActiveSegment && activeSegmentIdx != -1 && searchQuery.isEmpty()) {
            val targetPage = activeSegmentIdx / pageSize
            if (currentPage != targetPage) {
                currentPage = targetPage
            }
        }
    }
    
    LaunchedEffect(videoUri) {
        if (videoUri != null) {
            attachedVideoUri = videoUri
        }
    }

    val latestTextToSave = rememberUpdatedState(textToSave)
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openOutputStream(it)?.use { output ->
                    output.write(latestTextToSave.value.toByteArray())
                }
                Toast.makeText(context, "บันทึกไฟล์สำเร็จ!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "บันทึกไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            attachedVideoUri = it
            Toast.makeText(context, "แนบวิดีโอสำหรับพรีวิวสำเร็จ!", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        // Karaoke Player Box Header
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "🎬 Karaoke-Style Player / Editor Beta",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = "คำพูดจะสว่างขึ้นอัตโนมัติตามช่วงเวลา และคุณสามารถกดแก้ไขคำพูด จัดสัดส่วนฟอนต์ เลือกประโยค และเปลี่ยนคลิปวิดีโอได้ทันที!",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 🎨 Subtitle Font, Size, Aspect Ratio Studio Panel
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "สตูดิโอแต่งซับและจัดจอรูปภาพ (Subtitle & Screen Studio)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(10.dp))

                // 📐 Aspect Ratio Selector
                Text(
                    text = "📐 กำหนดสัดส่วนของวิดีโอ (Aspect Ratio)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("16:9", "9:16").forEach { ratio ->
                        FilterChip(
                            selected = videoAspectRatio == ratio,
                            onClick = { videoAspectRatio = ratio },
                            label = { Text(if (ratio == "16:9") "แนวนอนมาตรฐาน (16:9)" else "แนวตั้งสั้น Reels/TikTok (9:16)") },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                // 🔤 Font Choices
                Text(
                    text = "🔤 ชนิดของตัวอักษร (Caption Fonts)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("SansSerif", "Serif", "Monospace", "Cursive").forEach { font ->
                        FilterChip(
                            selected = selectedFont == font,
                            onClick = { selectedFont = font },
                            label = { Text(font) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 🎨 Subtitle Text Color Selection
                Text(
                    text = "🎨 พาเลทสีตัวหนังสือ (Text Color Palette)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Yellow", "Cyan", "White", "Green", "Orange").forEach { color ->
                        FilterChip(
                            selected = selectedColor == color,
                            onClick = { selectedColor = color },
                            label = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val indicatorColor = when (color) {
                                        "Cyan" -> Color.Cyan
                                        "White" -> Color.White
                                        "Green" -> Color(0xFF39FF14) // Neon Green
                                        "Orange" -> Color(0xFFFF5722) // Neon Orange
                                        else -> Color.Yellow
                                    }
                                    Box(modifier = Modifier.size(10.dp).background(indicatorColor, CircleShape))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(color)
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 📏 Font Size & Background Opacity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📏 ขนาดตัวหนังสือ (${selectedFontSize}sp)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            listOf(14, 18, 20, 24, 28).forEach { size ->
                                InputChip(
                                    selected = selectedFontSize == size,
                                    onClick = { selectedFontSize = size },
                                    label = { Text("${size}") }
                                )
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "⬛ ความโปร่งใสของแถบหลัง",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp), 
                            modifier = Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())
                        ) {
                            listOf(0.0f to "0%", 0.3f to "30%", 0.5f to "50%", 0.65f to "65%", 0.8f to "80%").forEach { (alpha, pct) ->
                                InputChip(
                                    selected = selectedBgOpacity == alpha,
                                    onClick = { selectedBgOpacity = alpha },
                                    label = { Text(pct) }
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(12.dp))

                // 📏 Smart Segment Splitting
                Text(
                    text = "✂️ กำหนดและจัดแบ่งประโยคกี่คำ (Smart Word Splitter)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "เลือกจำนวนคำมากสุดต่อท่อนซับ เพื่อความสบายตาในการมองเห็นหน้าจอ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                
                var targetWordsLimit by remember { mutableStateOf(5) }
                
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(2, 3, 5, 8, 10, 15).forEach { limit ->
                        InputChip(
                            selected = targetWordsLimit == limit,
                            onClick = { targetWordsLimit = limit },
                            label = { Text("${limit} คำ") }
                        )
                    }
                }
                
                Button(
                    onClick = {
                        onRechunk(targetWordsLimit)
                        Toast.makeText(context, "จัดระเบียบกลุ่มคำใหม่เหลือสูงสุด ${targetWordsLimit} คำสำเร็จ!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        contentColor = Color.Black
                    )
                ) {
                    Icon(Icons.Default.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("จัดรูปแบ่งคำบนหน้าจอทันที (Auto-Group Segments)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // Find the subtitle segment that should be visible RIGHT NOW
        val activeSegment = segments.find { playTime >= it.start && playTime <= it.end }

        // Live Captions Display Box containing real-time player and subtitle card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .border(
                    2.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Style Header inside player
                Text(
                    text = "🎬 PREVIEW AREA: STYLE=${burnStyle.uppercase()} | ASPECT=${videoAspectRatio}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (attachedVideoUri != null) {
                    // Actual Hardware-Accelerated Video Player showing original media in real-time
                    VideoPlayerWithSubtitles(
                        videoUri = attachedVideoUri!!,
                        burnStyle = burnStyle,
                        isPlaying = isPlaying,
                        playTime = playTime,
                        manualSeekTime = manualSeekTime,
                        onSeekHandled = { manualSeekTime = null },
                        onPlayStateChanged = { playing ->
                            if (!playing) onStopClick()
                        },
                        onPositionChanged = { seconds ->
                            onSeek(seconds)
                        },
                        activeSegmentText = activeSegment?.text,
                        fontName = selectedFont,
                        colorName = selectedColor,
                        fontSizeSp = selectedFontSize,
                        bgOpacity = selectedBgOpacity,
                        videoAspectRatio = videoAspectRatio,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Fallback to Simulated Board if loaded from history/import files only
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(Color.Black, shape = RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (activeSegment != null) {
                            SubtitleOverlayText(
                                text = activeSegment.text,
                                burnStyle = burnStyle,
                                fontName = selectedFont,
                                colorName = selectedColor,
                                fontSizeSp = selectedFontSize,
                                bgOpacity = selectedBgOpacity
                            )
                        } else {
                            Text(
                                text = if (isPlaying) "[ Listening... ]" else "กดปุ่ม Play หรือ 'แนบไฟล์วิดีโอเสริม' ด้านล่างเพื่อเริ่มเล่นซับแบบ Karaoke",
                                fontSize = 13.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Custom Timeline Scrubbing Slider
        val maxDuration = segments.lastOrNull()?.end ?: 30.0
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Slider(
                value = playTime.toFloat().coerceIn(0f, maxDuration.toFloat()),
                onValueChange = { newTime ->
                    manualSeekTime = newTime.toDouble()
                    onSeek(newTime.toDouble())
                },
                valueRange = 0f..maxDuration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = String.format("⏱️ เวลาเล่น: %.1fs", playTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format("เวลาทั้งหมด: %.1fs", maxDuration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

                Spacer(modifier = Modifier.height(12.dp))

                // Control Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onPlayClick,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black
                        )
                    }
                    
                    if (isPlaying || playTime > 0.0) {
                        IconButton(
                            onClick = onStopClick,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(MaterialTheme.colorScheme.outline.copy(alpha=0.3f), shape = CircleShape)
                                .size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    OutlinedButton(
                        onClick = { videoPickerLauncher.launch("video/*") },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.VideoFile, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (attachedVideoUri != null) "เปลี่ยนคลิป" else "แนบวิดีโอ (.mp4)", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Download, copy control buttons
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Subtitle SRT", srtText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "คัดลอกไฟล์ SRT ลง Clipboard แล้ว!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy SRT",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = {
                            textToSave = srtText
                            defaultFileName = "EASY_AI_Subtitles.srt"
                            createDocumentLauncher.launch(defaultFileName)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Download SRT",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

        // --- easysub.io Styled Brand Save & Export Card ---
        Spacer(modifier = Modifier.height(16.dp))
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
                            .padding(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "ส่งออกและบันทึกซับไตเติล (Save Captions)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "เบิร์นฝังลงวิดีโอเดี่ยว หรือดาวน์โหลดแยกไฟล์ SRT / VTT / TXT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Primary Action: Share Subtitle File
                Button(
                    onClick = {
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, if (defaultFileName.isNotEmpty()) defaultFileName else "EASY_AI_Subtitles.srt")
                            putExtra(android.content.Intent.EXTRA_TEXT, srtText)
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "แชร์ไฟล์ซับไตเติล (Share Subtitles)"))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "แชร์ไฟล์ซับไปยังแอปอื่น (Share SRT / VTT / TXT)",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Beautiful, Proportional Info Card on how to use SRT with original video
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "คำแนะนำการนำไฟล์ซับบันทึกคู่กับวิดีโอหลัก",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "1. กดดาวน์โหลดปุ่มด้านล่าง (แนะนำรูปแบบ SRT)\n" +
                                   "2. นำไฟล์ .srt ที่บันทึกเสร็จ ไปวางไว้ใน 'โฟลเดอร์เดียวกัน' กับวิดีโอต้นฉบับ\n" +
                                   "3. ตั้งชื่อไฟล์ .srt และวิดีโอ .mp4 ให้ 'เหมือนกันทุกประการ'\n" +
                                   "4. เปิดด้วยโปรแกรมทั่วไป (VLC, MX Player, โทรทัศน์) ซับไตเติลจะขึ้นอัจฉริยะขึ้นจอทันที ด้วยสีฟอนต์และสไตล์ที่เลือกปรับแต่งได้อย่างแม่นยำ!",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "หรือ ดาวน์โหลดเป็นไฟล์แบบเดี่ยว (Or just the file):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // SRT
                    OutlinedButton(
                        onClick = {
                            textToSave = srtText
                            defaultFileName = "EASY_AI_Subtitles.srt"
                            createDocumentLauncher.launch(defaultFileName)
                        },
                        modifier = Modifier.weight(1.1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.3f))
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("SRT", fontSize = 11.sp, maxLines = 1)
                    }

                    // VTT
                    OutlinedButton(
                        onClick = {
                            textToSave = com.example.util.SubtitleFormatter.jsonToVtt(segments)
                            defaultFileName = "EASY_AI_Subtitles.vtt"
                            createDocumentLauncher.launch(defaultFileName)
                        },
                        modifier = Modifier.weight(1.1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.3f))
                    ) {
                        Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("VTT", fontSize = 11.sp, maxLines = 1)
                    }

                    // TXT
                    OutlinedButton(
                        onClick = {
                            textToSave = com.example.util.SubtitleFormatter.jsonToTxt(segments)
                            defaultFileName = "EASY_AI_Subtitles.txt"
                            createDocumentLauncher.launch(defaultFileName)
                        },
                        modifier = Modifier.weight(1.1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.3f))
                    ) {
                        Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("TXT", fontSize = 11.sp, maxLines = 1)
                    }

                    // Copy
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Subtitle Text", srtText)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "คัดลอก SRT ไปยัง Clipboard สำเร็จ!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=0.3f))
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // List of segments for detailed editing (Optimized Pagination and Search to solve UI thread freeze / InputDispatcher crash)
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "แก้ไขข้อความช่วงเวลา (Surgical Editor)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Auto Scroll Follow toggle using a beautiful Row clickable card
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            if (autoFollowActiveSegment) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { autoFollowActiveSegment = !autoFollowActiveSegment }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = autoFollowActiveSegment,
                        onCheckedChange = { autoFollowActiveSegment = it ?: false },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            checkmarkColor = Color.Black
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "เลื่อนตามเวลาเล่น",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (autoFollowActiveSegment) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Search filter box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    currentPage = 0 // Reset to page 0 upon searching
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("ค้นหาช่วงเวลาด้วยคำพูด/ประโยค...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = ""; currentPage = 0 }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                )
            )

            // Filtering & Pagination Calculations
            val filteredWithIdx = remember(segments, searchQuery) {
                segments.mapIndexed { idx, seg -> idx to seg }
                    .filter { (_, seg) -> 
                        searchQuery.isEmpty() || seg.text.contains(searchQuery, ignoreCase = true) 
                    }
            }

            val totalFiltered = filteredWithIdx.size
            val totalPages = if (totalFiltered == 0) 1 else (java.lang.Math.ceil(totalFiltered.toDouble() / pageSize)).toInt()
            val clampedPage = currentPage.coerceIn(0, totalPages - 1)
            
            if (clampedPage != currentPage) {
                LaunchedEffect(clampedPage) {
                    currentPage = clampedPage
                }
            }
            
            // Render segment cards for the current page
            if (totalFiltered == 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "ไม่พบข้อความที่ตรงกับคำที่ท่านกำลังค้นหา",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val startIdx = clampedPage * pageSize
                val endIdx = (startIdx + pageSize).coerceAtMost(totalFiltered)
                val currentPageItems = filteredWithIdx.subList(startIdx, endIdx)

                currentPageItems.forEach { (originalIdx, segment) ->
                    val isActive = playTime >= segment.start && playTime <= segment.end
                    SegmentEditorCard(
                        idx = originalIdx,
                        segment = segment,
                        isActive = isActive,
                        onEditSegment = onEditSegment
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Beautiful, high-density Pagination Row Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (clampedPage > 0) currentPage = clampedPage - 1 },
                        enabled = clampedPage > 0
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Previous Page",
                            tint = if (clampedPage > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=0.3f)
                        )
                    }

                    Text(
                        text = "หน้าที่ ${clampedPage + 1} จาก $totalPages (แสดงที่ ${startIdx + 1}-${endIdx} จาก $totalFiltered ท่อน)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    IconButton(
                        onClick = { if (clampedPage < totalPages - 1) currentPage = clampedPage + 1 },
                        enabled = clampedPage < totalPages - 1
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "Next Page",
                            tint = if (clampedPage < totalPages - 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha=0.3f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Raw SRT viewer for advanced programmers
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
        ) {
            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Compiled Subtitle File (SRT Format)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = srtText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun SegmentEditorCard(
    idx: Int,
    segment: TranscriptionSegment,
    isActive: Boolean,
    onEditSegment: (Int, String) -> Unit
) {
    var localText by remember(segment.start, segment.end) { mutableStateOf(segment.text) }

    LaunchedEffect(segment.text) {
        if (localText != segment.text) {
            localText = segment.text
        }
    }

    LaunchedEffect(localText) {
        if (localText != segment.text) {
            kotlinx.coroutines.delay(400L)
            onEditSegment(idx, localText)
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(
                if (isActive) 2.dp else 1.dp,
                if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                RoundedCornerShape(14.dp)
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha=0.2f),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = String.format("%.2fs - %.2fs", segment.start, segment.end),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) Color.Black else MaterialTheme.colorScheme.onSurface
                    )
                }
                
                Text(
                    text = "กลุ่มคำที่ ${idx + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=0.6f)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = localText,
                onValueChange = { localText = it },
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                singleLine = true,
                placeholder = { Text("กรอกข้อความ...") }
            )
        }
    }
}

@Composable
fun StepHeader(number: String, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp, top = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primary, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number, 
                color = Color.Black, 
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title, 
            style = MaterialTheme.typography.titleMedium, 
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
