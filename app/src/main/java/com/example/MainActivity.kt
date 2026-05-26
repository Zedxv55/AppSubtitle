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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.flow.debounce
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

    val AmoledBackground = Color(0xFF080B12)
    val AmoledSurface = Color(0xFF0F1825)
    val AmoledSurfaceVariant = Color(0xFF131F2E)
    val AmoledBorderColor = Color(0xFF1E4060)
    val AccentColor = Color(0xFF00E5B4)
    val BlueGradientEnd = Color(0xFF00A3FF)
    val TextPrimary = Color(0xFFE0E8F0)
    val TextSecondary = Color(0xFF4A6A7A)
    val GradientTealBlue = Brush.horizontalGradient(listOf(Color(0xFF00E5B4), Color(0xFF00A3FF)))

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            AccentColor,
            BlueGradientEnd
        )
    )

    // Breathing scale animation for Logo icon
    val infiniteTransition = rememberInfiniteTransition(label = "globalAnimations")
    val scaleAnim by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoBreathing"
    )

    // Animated color cycle for dashed border cycling when empty
    val borderColorAnim by infiniteTransition.animateColor(
        initialValue = Color(0xFF1E4060),
        targetValue = Color(0xFF00E5B4),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowBorder"
    )

    // Infinite rotation for spinning ring
    val rotationAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )

    val stateType = remember(uiState) {
        when (uiState) {
            is SubtitleState.Idle -> "home"
            is SubtitleState.Error -> "home"
            is SubtitleState.Loading -> "loading"
            is SubtitleState.Success -> "result"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AmoledBackground)
    ) {
        AnimatedContent(
            targetState = stateType,
            transitionSpec = {
                (slideInHorizontally(initialOffsetX = { it }) + fadeIn()) togetherWith
                        (slideOutHorizontally(targetOffsetX = { -it }) + fadeOut())
            },
            label = "screen_transition"
        ) { targetScreen ->
            when (targetScreen) {
                "home" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header (App Name / Hero title: 20sp ExtraBold letter-spacing -0.5)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .scale(scaleAnim)
                                        .size(32.dp)
                                        .background(gradientBrush, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ClosedCaption,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SubAI",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = TextPrimary,
                                    letterSpacing = (-0.5).sp
                                )
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(AmoledSurfaceVariant, CircleShape)
                                    .border(1.dp, AmoledBorderColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = AccentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (uiState is SubtitleState.Error) {
                            val errorState = uiState as? SubtitleState.Error
                            if (errorState != null) {
                                val errorMsg = errorState.error
                                ElevatedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .border(1.dp, Color.Red, RoundedCornerShape(12.dp)),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0x33FF0000))
                                ) {
                                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Error, "error", tint = Color.Red, modifier = Modifier.size(28.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text("เกิดข้อผิดพลาดในการรันงาน:", fontWeight = FontWeight.Bold, color = Color.Red, fontSize = 13.sp)
                                            Text(errorMsg, color = Color.Red, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Upload Zone Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable { filePickerLauncher.launch("*/*") }
                                .drawBehind {
                                    val stroke = Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                                    )
                                    drawRoundRect(
                                        color = if (selectedFileUri == null) borderColorAnim else AmoledBorderColor,
                                        style = stroke,
                                        cornerRadius = CornerRadius(18.dp.toPx())
                                    )
                                },
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = AmoledSurface)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .background(Brush.horizontalGradient(listOf(Color.Transparent, AccentColor, Color.Transparent)))
                                )

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    if (selectedFileUri != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .background(GradientTealBlue, RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isVideo) Icons.Default.Movie else Icons.Default.AudioFile,
                                                contentDescription = "Selected file",
                                                tint = Color.Black,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = fileName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(AmoledSurfaceVariant, RoundedCornerShape(6.dp))
                                                    .border(1.dp, AmoledBorderColor, RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(rawFileSizeStr, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(AmoledSurfaceVariant, RoundedCornerShape(6.dp))
                                                    .border(1.dp, AmoledBorderColor, RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(if (isVideo) "VIDEO" else "AUDIO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AccentColor)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier
                                                .background(Color(0x1F00E5B4), RoundedCornerShape(12.dp))
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, null, tint = AccentColor, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("พร้อมประมวลผล", color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .background(GradientTealBlue, RoundedCornerShape(12.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CloudUpload,
                                                contentDescription = "Upload Icon",
                                                tint = Color.Black,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "เลือกไฟล์วิดีโอ/เสียง หรือ นำเข้าไฟล์ซับไตเติ้ล",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "รองรับวิดีโอใหญ่ ทรานสคริปต์ได้ทันที",
                                            fontSize = 11.sp,
                                            color = TextSecondary,
                                            textAlign = TextAlign.Center
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { filePickerLauncher.launch("*/*") },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1.1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = AccentColor,
                                                contentColor = Color.Black
                                            ),
                                            contentPadding = PaddingValues(vertical = 10.dp)
                                        ) {
                                            Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("เลือกไฟล์หลัก", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }

                                        OutlinedButton(
                                            onClick = { srtPickerLauncher.launch("*/*") },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.weight(1.0f),
                                            border = BorderStroke(1.dp, AmoledBorderColor),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = BlueGradientEnd
                                            ),
                                            contentPadding = PaddingValues(vertical = 10.dp)
                                        ) {
                                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("นำเข้า SRT/VTT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Section 2
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(AmoledSurface, RoundedCornerShape(18.dp))
                                .border(1.dp, AmoledBorderColor, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(AmoledSurfaceVariant, RoundedCornerShape(4.dp))
                                        .border(1.dp, AmoledBorderColor, RoundedCornerShape(4.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "02",
                                        style = TextStyle(brush = gradientBrush),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "กำหนดการแปลและ AI Engine",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }

                            Text(
                                text = "ภาษาในไฟล์วิดีโอ (Spoken Language)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                sourceLanguages.forEach { lang ->
                                    val display = when (lang) {
                                        "Auto" -> "Auto (อัตโนมัติ)"
                                        "th" -> "ไทย (Thai)"
                                        "en" -> "อังกฤษ (English)"
                                        else -> lang
                                    }
                                    val isSelected = sourceLanguage == lang
                                    val labelScale by animateFloatAsState(if (isSelected) 1.05f else 1f, spring(), label = "chipScale")
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { sourceLanguage = lang },
                                        label = { Text(display, color = if (isSelected) AccentColor else TextSecondary, modifier = Modifier.scale(labelScale)) },
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            borderColor = if (isSelected) AccentColor else AmoledBorderColor,
                                            selectedBorderColor = AccentColor
                                        ),
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = Color.Transparent,
                                            selectedContainerColor = Color(0x1F00E5B4),
                                            labelColor = TextSecondary,
                                            selectedLabelColor = AccentColor
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "ภาษาปลายทางที่ต้องการแปล (Translate destination)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                languages.forEach { lang ->
                                    val isSelected = targetLanguage == lang
                                    val labelScale by animateFloatAsState(if (isSelected) 1.05f else 1f, spring(), label = "chipScaleTarget")
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { targetLanguage = lang },
                                        label = { Text(if (lang == "None") "ไม่มีการแปล (ต้นฉบับ)" else lang, color = if (isSelected) AccentColor else TextSecondary, modifier = Modifier.scale(labelScale)) },
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            borderColor = if (isSelected) AccentColor else AmoledBorderColor,
                                            selectedBorderColor = AccentColor
                                        ),
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = Color.Transparent,
                                            selectedContainerColor = Color(0x1F00E5B4),
                                            labelColor = TextSecondary,
                                            selectedLabelColor = AccentColor
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "ผู้ช่วยการแปลภาษา (Translation AI Engine)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                engines.forEach { eng ->
                                    val stats = viewModel.getProviderStats(eng)
                                    val isSelected = selectedEngine == eng
                                    
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedEngine = eng }
                                            .then(
                                                if (isSelected) {
                                                    Modifier
                                                        .drawBehind {
                                                            drawRoundRect(
                                                                brush = gradientBrush,
                                                                style = Stroke(width = 2.dp.toPx()),
                                                                cornerRadius = CornerRadius(12.dp.toPx())
                                                            )
                                                        }
                                                        .background(Color(0x1A00E5B4), RoundedCornerShape(12.dp))
                                                } else {
                                                    Modifier
                                                        .border(1.dp, AmoledBorderColor, RoundedCornerShape(12.dp))
                                                        .background(AmoledSurfaceVariant, RoundedCornerShape(12.dp))
                                                }
                                            )
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = eng,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (isSelected) AccentColor else TextPrimary
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .background(if (stats.errorCount > 0 && stats.successCount == 0) Color.Red else AccentColor, CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (stats.errorCount > 0 && stats.successCount == 0) "Error" else "Active",
                                                    fontSize = 9.sp,
                                                    color = if (isSelected) AccentColor else TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "น้ำเสียงของคำบรรยาย (Tone Accent)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                tones.forEach { tone ->
                                    val isSelected = selectedTone == tone
                                    val labelScale by animateFloatAsState(if (isSelected) 1.05f else 1f, spring(), label = "toneChipScale")
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedTone = tone },
                                        label = { Text(tone, color = if (isSelected) AccentColor else TextSecondary, modifier = Modifier.scale(labelScale)) },
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            borderColor = if (isSelected) AccentColor else AmoledBorderColor,
                                            selectedBorderColor = AccentColor
                                        ),
                                        colors = FilterChipDefaults.filterChipColors(
                                            containerColor = Color.Transparent,
                                            selectedContainerColor = Color(0x1F00E5B4),
                                            labelColor = TextSecondary,
                                            selectedLabelColor = AccentColor
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }

                        // Burn Options
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .background(AmoledSurface, RoundedCornerShape(18.dp))
                                .border(1.dp, AmoledBorderColor, RoundedCornerShape(18.dp))
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = burnSubtitles,
                                    onCheckedChange = { if (isVideo) burnSubtitles = it else Toast.makeText(context, "ต้องใช้กับไฟล์วิดีโอเท่านั้น", Toast.LENGTH_SHORT).show() },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentColor, checkmarkColor = Color.Black)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "เบิร์นซับไตเติ้ลฝังลงในวิดีโอ (Burn captions)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        "ส่งออกวิดีโอ MP4 พร้อมฝังภาพในตัว",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextSecondary
                                    )
                                }
                            }

                            if (burnSubtitles) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(start = 32.dp)
                                ) {
                                    burnStyles.forEach { style ->
                                        val isSelected = burnStyle == style
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { burnStyle = style },
                                            label = { Text(style, color = if (isSelected) AccentColor else TextSecondary) },
                                            shape = RoundedCornerShape(12.dp),
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = isSelected,
                                                borderColor = if (isSelected) AccentColor else AmoledBorderColor,
                                                selectedBorderColor = AccentColor
                                            ),
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = Color.Transparent,
                                                selectedContainerColor = Color(0x1F00E5B4)
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Run Premium Button with high contrast theme-aligned gradient
                        val runPressedSource = remember { MutableInteractionSource() }
                        val runIsPressed by runPressedSource.collectIsPressedAsState()
                        val runScale by animateFloatAsState(if (runIsPressed) 0.96f else 1.0f, label = "runButtonScale")

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                                .height(52.dp)
                                .scale(runScale)
                                .background(gradientBrush, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
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
                                        Toast.makeText(context, "กรุณาเลือกไฟล์ก่อนเริ่มจัดทำ!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = Color.Black
                                ),
                                interactionSource = runPressedSource,
                                enabled = selectedFileUri != null
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (targetLanguage != "None") "ถอดเสียงและแปลซับ ($targetLanguage)" else "ถอดเสียงต้นฉบับด่วน",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            }
                        }

                        // Recent History
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
                                        tint = AccentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "ประวัติงานแปลล่าสุด",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }

                                if (history.isNotEmpty()) {
                                    TextButton(onClick = { viewModel.clearAllHistory() }) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Red)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("ล้างทั้งหมด", fontSize = 11.sp, color = Color.Red)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (history.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(AmoledSurface, RoundedCornerShape(16.dp))
                                        .border(1.dp, AmoledBorderColor, RoundedCornerShape(16.dp))
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("ไม่มีประวัติการแกะซับล่าสุด", color = TextSecondary, fontSize = 12.sp)
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    history.forEach { item ->
                                        ElevatedCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = CardDefaults.elevatedCardColors(containerColor = AmoledSurface)
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = item.fileName,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = TextPrimary,
                                                            maxLines = 1
                                                        )
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(AmoledSurfaceVariant, RoundedCornerShape(4.dp))
                                                                    .border(1.dp, AmoledBorderColor, RoundedCornerShape(4.dp))
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                            ) {
                                                                Text("Engine: ${item.engineUsed}", fontSize = 9.sp, color = TextSecondary)
                                                            }
                                                            Box(
                                                                modifier = Modifier
                                                                    .background(AmoledSurfaceVariant, RoundedCornerShape(4.dp))
                                                                    .border(1.dp, AmoledBorderColor, RoundedCornerShape(4.dp))
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                            ) {
                                                                Text("แปลเป็น: ${if (item.targetLanguage == "None") "ต้นฉบับ" else item.targetLanguage}", fontSize = 9.sp, color = AccentColor)
                                                            }
                                                        }
                                                    }

                                                    IconButton(
                                                        onClick = { viewModel.deleteHistoryItem(item) },
                                                        modifier = Modifier.size(32.dp)
                                                    ) {
                                                        Icon(Icons.Default.Delete, "Delete", tint = Color.Red.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(10.dp))

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "🕒 ${formatHistoryTimestamp(item.timestamp)}",
                                                        fontSize = 10.sp,
                                                        color = TextSecondary
                                                    )

                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                        TextButton(
                                                            onClick = {
                                                                viewModel.loadHistoryItem(item)
                                                                Toast.makeText(context, "โหลดข้อมูลเข้า Editor แล้ว", Toast.LENGTH_SHORT).show()
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                        ) {
                                                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(12.dp), tint = AccentColor)
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("เปิดแก้ไข", fontSize = 11.sp, color = AccentColor)
                                                        }

                                                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        OutlinedButton(
                                                            onClick = {
                                                                val clip = android.content.ClipData.newPlainText("SRT Captions", item.srtContent)
                                                                clipboardManager.setPrimaryClip(clip)
                                                                Toast.makeText(context, "คัดลอกไฟล์ซับสำเร็จ!", Toast.LENGTH_SHORT).show()
                                                            },
                                                            shape = RoundedCornerShape(6.dp),
                                                            border = BorderStroke(1.dp, AmoledBorderColor),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                                        ) {
                                                            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(10.dp), tint = TextSecondary)
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("คัดลอก SRT", fontSize = 10.sp, color = TextSecondary)
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

                "loading" -> {
                    val loadingState = uiState as? SubtitleState.Loading
                    val messageText = loadingState?.message ?: "กำลังดำเนินการ..."

                    val currentStep = when {
                        messageText.contains("extracting") || messageText.contains("compressing") -> 1
                        messageText.contains("Transcribing") || messageText.contains("นำเข้า") -> 2
                        messageText.contains("Translating") || messageText.contains("Formatting") -> 3
                        messageText.contains("🎬") || messageText.contains("สำเร็จ") -> 4
                        else -> 1
                    }

                    var stepVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        stepVisible = true
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
                            Canvas(modifier = Modifier.size(100.dp).rotate(rotationAnim)) {
                                drawArc(
                                    brush = Brush.sweepGradient(listOf(Color(0xFF00E5B4), Color(0xFF00A3FF), Color(0xFF00E5B4))),
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = Stroke(width = 3.dp.toPx())
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        Brush.linearGradient(listOf(Color(0xFF00E5B4), Color(0xFF00A3FF))),
                                        alpha = 0.15f
                                    )
                                    .border(1.dp, AmoledBorderColor, RoundedCornerShape(20.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OfflineBolt,
                                    contentDescription = null,
                                    tint = AccentColor,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "กำลังประมวลผลผ่าน AI...",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "กรุณาเปิดหน้านี้ไว้ ระบบกำลังสร้างความแม่นยำสูง",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        val steps = listOf(
                            Pair(1, "แยกช่องสัญญาณเสียง (High Precision)"),
                            Pair(2, "ถอดเสียงพูดและจับจังหวะผ่าน AI (Groq Whisper)"),
                            Pair(3, "แปลและย่อประโยคให้ลงตัวด้วย AI"),
                            Pair(4, "ประกอบสื่อและเตรียมรับชมไฟล์ผลงาน")
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            steps.forEach { (idx, label) ->
                                val stepState = when {
                                    idx < currentStep -> "done"
                                    idx == currentStep -> "active"
                                    else -> "idle"
                                }

                                AnimatedVisibility(
                                    visible = stepVisible,
                                    enter = fadeIn() + slideInVertically(initialOffsetY = { 50 + idx * 20 })
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(AmoledSurface, RoundedCornerShape(10.dp))
                                            .border(1.dp, if (stepState == "active") AccentColor else AmoledBorderColor, RoundedCornerShape(10.dp))
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        when (stepState) {
                                            "done" -> {
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .background(AccentColor, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(12.dp))
                                                }
                                            }
                                            "active" -> {
                                                val activePulse by infiniteTransition.animateFloat(
                                                    initialValue = 1.0f,
                                                    targetValue = 1.2f,
                                                    animationSpec = infiniteRepeatable(
                                                        animation = tween(800, easing = FastOutSlowInEasing),
                                                        repeatMode = RepeatMode.Reverse
                                                    ),
                                                    label = "pulseStep"
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .scale(activePulse)
                                                        .background(AmoledBackground, CircleShape)
                                                        .border(2.dp, BlueGradientEnd, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(modifier = Modifier.size(8.dp).background(BlueGradientEnd, CircleShape))
                                                }
                                            }
                                            else -> {
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .background(AmoledBackground, CircleShape)
                                                        .border(1.dp, AmoledBorderColor, CircleShape)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (stepState == "active") FontWeight.Bold else FontWeight.Normal,
                                            color = when (stepState) {
                                                "done" -> AccentColor
                                                "active" -> BlueGradientEnd
                                                else -> TextSecondary
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        val progressPercent = when (currentStep) {
                            1 -> 0.15f
                            2 -> 0.45f
                            3 -> 0.75f
                            4 -> 1.0f
                            else -> 0.1f
                        }
                        val animProgress by animateFloatAsState(progressPercent, spring(), label = "progressAnimation")

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(AmoledBorderColor, RoundedCornerShape(3.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(animProgress)
                                        .height(6.dp)
                                        .background(gradientBrush, RoundedCornerShape(3.dp))
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "ขั้นตอนที่ $currentStep/4 — ${steps[currentStep-1].second}",
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "${(animProgress * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(40.dp))

                        OutlinedButton(
                            onClick = { viewModel.cancelJob() },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) {
                            Text("ยกเลิกทำงานและย้อนกลับ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                "result" -> {
                    val successState = uiState as? SubtitleState.Success
                    if (successState != null) {
                       Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AmoledSurface)
                                    .border(BorderStroke(1.dp, AmoledBorderColor))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { viewModel.cancelJob() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "ผลลัพธ์ & แก้ไขคำแปล",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .background(Color(0x1F00E5B4), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .border(1.dp, AccentColor, RoundedCornerShape(12.dp))
                                ) {
                                    Text(
                                        text = "✓ สำเร็จ",
                                        color = AccentColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Box(modifier = Modifier.weight(1f)) {
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
            }
        }
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
    bgOpacity: Float = 0.65f,
    shadowEnabled: Boolean = true
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
            textAlign = TextAlign.Center,
            style = if (shadowEnabled) {
                TextStyle(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color.Black,
                        offset = androidx.compose.ui.geometry.Offset(3f, 3f),
                        blurRadius = 6f
                    )
                )
            } else {
                TextStyle.Default
            }
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
    videoPosition: String = "Bottom",
    videoOffsetDp: Float = 24f,
    shadowEnabled: Boolean = true,
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
                while (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
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
            val contentAlignment = when (videoPosition) {
                "Top" -> Alignment.TopCenter
                "Middle" -> Alignment.Center
                else -> Alignment.BottomCenter
            }
            val paddingModifier = when (videoPosition) {
                "Top" -> Modifier.padding(top = videoOffsetDp.dp, start = 16.dp, end = 16.dp)
                "Bottom" -> Modifier.padding(bottom = videoOffsetDp.dp, start = 16.dp, end = 16.dp)
                else -> Modifier.padding(start = 16.dp, end = 16.dp)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(paddingModifier),
                contentAlignment = contentAlignment
            ) {
                SubtitleOverlayText(
                    text = activeSegmentText,
                    burnStyle = burnStyle,
                    fontName = fontName,
                    colorName = colorName,
                    fontSizeSp = fontSizeSp,
                    bgOpacity = bgOpacity,
                    shadowEnabled = shadowEnabled
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
    var selectedPosition by remember { mutableStateOf("Bottom") }
    var selectedOffsetDp by remember { mutableStateOf(24f) }
    var shadowEnabled by remember { mutableStateOf(true) }

    // On-the-fly customizable video capability
    var attachedVideoUri by remember { mutableStateOf<Uri?>(null) }
    
    // Pagination and surgical editor optimizations to prevent major thread locking / app crashes (InputDispatcher channel broken)
    var currentPage by remember { mutableStateOf(0) }
    val pageSize = 5
    var searchQuery by remember { mutableStateOf("") }
    var autoFollowActiveSegment by remember { mutableStateOf(true) }
    var manualSeekTime by remember { mutableStateOf<Double?>(null) }
    var targetWordsLimit by remember { mutableStateOf(5) }

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

    LaunchedEffect(attachedVideoUri) {
        attachedVideoUri?.let { uri ->
            try {
                val detectedRatio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(context, uri)
                        val widthStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val heightStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        retriever.release()

                        val width = widthStr?.toIntOrNull() ?: 1920
                        val height = heightStr?.toIntOrNull() ?: 1080
                        val rotation = rotationStr?.toIntOrNull() ?: 0

                        val (actualWidth, actualHeight) = if (rotation == 90 || rotation == 270) {
                            Pair(height, width)
                        } else {
                            Pair(width, height)
                        }

                        if (actualHeight > actualWidth) "9:16" else "16:9"
                    } catch (e: Exception) {
                        android.util.Log.e("AspectDetectLocal", "Failed to retrieve video metadata", e)
                        "16:9"
                    }
                }
                videoAspectRatio = detectedRatio
            } catch (e: Exception) {
                // Ignore
            }
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

                // 📍 Subtitle Position & Shadow Settings
                Text(
                    text = "📍 ตำแหน่งและเงาของซับไตเติล (Position & Shadows)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("Bottom", "Middle", "Top").forEach { pos ->
                        FilterChip(
                            selected = selectedPosition == pos,
                            onClick = { selectedPosition = pos },
                            label = { Text(if (pos == "Bottom") "ด้านล่าง (Bottom)" else if (pos == "Middle") "กึ่งกลาง (Middle)" else "ด้านบน (Top)") },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1.2f)) {
                        Text(
                            text = "📐 ระยะขอบ (${selectedOffsetDp.toInt()}dp)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = selectedOffsetDp,
                            onValueChange = { selectedOffsetDp = it },
                            valueRange = 8f..120f,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    
                    Column(modifier = Modifier.weight(0.8f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { shadowEnabled = !shadowEnabled }
                        ) {
                            Checkbox(
                                checked = shadowEnabled,
                                onCheckedChange = { shadowEnabled = it },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = "แสดงเงาหลังอักษร",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                    Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
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
                        videoPosition = selectedPosition,
                        videoOffsetDp = selectedOffsetDp,
                        shadowEnabled = shadowEnabled,
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
                                bgOpacity = selectedBgOpacity,
                                shadowEnabled = shadowEnabled
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
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(14.dp))
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
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
    var localText by remember(segment.start, segment.end, segment.text) { mutableStateOf(segment.text) }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(segment) {
        snapshotFlow { localText }
            .debounce(400L)
            .collect { text ->
                if (text != segment.text) {
                    onEditSegment(idx, text)
                }
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
