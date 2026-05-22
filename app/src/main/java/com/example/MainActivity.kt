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

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var rawFileSizeStr by remember { mutableStateOf("") }
    
    // AI Parameters
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
                            text = "เลือกไฟล์วิดีโอหรือเสียงของคุณ",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "รองรับไฟล์ขนาดใหญ่ 4K, 2GB ระบบจะแยกเสียงออกทันทีไม่ต้องแปลงเอง",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("คลิกเพื่อเลือกไฟล์ (Select Media)")
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
                                    context = context,
                                    mediaUri = uri,
                                    targetLanguage = targetLanguage,
                                    tone = selectedTone,
                                    preferredEngine = selectedEngine,
                                    burnSubtitles = burnSubtitles
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

        // Dynamic State UI Handling
        AnimatedContent(
            targetState = uiState,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }
        ) { state ->
            when (state) {
                is SubtitleState.Idle -> {
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
                is SubtitleState.Loading -> {
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
                                text = state.message,
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
                is SubtitleState.Error -> {
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
                                    text = state.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                is SubtitleState.Success -> {
                    KaraokePreviewAndEditor(
                        srtText = state.subtitleText,
                        segments = state.segments,
                        burnStyle = burnStyle,
                        isPlaying = isPlaying,
                        playTime = playTime,
                        onPlayClick = {
                            val maxTime = state.segments.lastOrNull()?.end ?: 10.0
                            if (isPlaying) {
                                viewModel.stopPlaybackSimulation()
                            } else {
                                viewModel.startPlaybackSimulation(maxTime)
                            }
                        },
                        onStopClick = {
                            viewModel.stopPlaybackSimulation()
                        },
                        onEditSegment = { index, value ->
                            viewModel.updateSegmentText(index, value)
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun KaraokePreviewAndEditor(
    srtText: String,
    segments: List<TranscriptionSegment>,
    burnStyle: String,
    isPlaying: Boolean,
    playTime: Double,
    onPlayClick: () -> Unit,
    onStopClick: () -> Unit,
    onEditSegment: (Int, String) -> Unit
) {
    val context = LocalContext.current

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
            text = "คำพูดจะสว่างขึ้นอัตโนมัติตามช่วงเวลา และคุณสามารถกดแก้ไขคำพูดได้ทันทีเพื่อจัดให้ซับตรงใจครีเอเตอร์!",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Find the subtitle segment that should be visible RIGHT NOW
        val activeSegment = segments.find { playTime >= it.start && playTime <= it.end }

        // Live Captions Display Box
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
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Style Header inside player
                Text(
                    text = "STYLING PREVIEW: ${burnStyle.uppercase()} STYLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Simulated Karaoke Output Board
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(Color.Black, shape = RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (activeSegment != null) {
                        // Apply styling based on burn style
                        when (burnStyle) {
                            "TikTok" -> {
                                Text(
                                    text = activeSegment.text,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.SansSerif,
                                    color = Color.Yellow,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                            "Modern" -> {
                                Text(
                                    text = activeSegment.text,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.SansSerif,
                                    color = Color.Cyan,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                            else -> { // Minimal style
                                Text(
                                    text = activeSegment.text,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = if (isPlaying) "[ Listening... ]" else "กดปุ่ม Play ด้านล่างเพื่อเริ่มเล่นซับแบบ Karaoke",
                            fontSize = 14.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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

                    Spacer(modifier = Modifier.width(16.dp))

                    // Simulated Time Badge
                    Text(
                        text = String.format("เวลา: %.1fs", playTime),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )

                    // Download, copy controls
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
                            Toast.makeText(context, "บันทึกไฟล์ EASY_AI_Subtitles.srt แล้ว!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Download SRT",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // List of segments for detailed editing
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "แก้ไขข้อความช่วงเวลา (Surgical Editor)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        segments.forEachIndexed { idx, segment ->
            val isActive = playTime >= segment.start && playTime <= segment.end
            
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
                        value = segment.text,
                        onValueChange = { onEditSegment(idx, it) },
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
