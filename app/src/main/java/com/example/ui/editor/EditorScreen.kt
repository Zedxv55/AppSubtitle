package com.example.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.api.TranscriptionSegment
import com.example.api.TranscriptionWord
import com.example.viewmodel.SubtitleState
import com.example.viewmodel.SubtitleViewModel

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: SubtitleViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToExport: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Editor Setup Parameters state
    var isSettingsDone by remember { mutableStateOf(false) }
    var wordsPerLine by remember { mutableStateOf(3f) }
    var margin by remember { mutableStateOf(16f) }
    var textStyle by remember { mutableStateOf("Modern") }
    val styles = listOf("TikTok", "Modern", "Minimal")

    // The current subtitles being edited
    var segments by remember { mutableStateOf<List<TranscriptionSegment>>(emptyList()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    LaunchedEffect(uiState) {
        if (uiState is SubtitleState.Success) {
            segments = (uiState as SubtitleState.Success).segments
        }
    }

    if (uiState !is SubtitleState.Success) {
        // Safe fallback
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (!isSettingsDone) "Setup Subtitles" else "Editor") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSettingsDone) {
                        IconButton(onClick = onNavigateToExport) {
                            Icon(Icons.Default.Save, contentDescription = "Export")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!isSettingsDone) {
            // Settings Pre-Editor
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
            ) {
                Text("Word per Segment", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = wordsPerLine,
                    onValueChange = { wordsPerLine = it },
                    valueRange = 2f..5f,
                    steps = 2
                )
                Text("${wordsPerLine.toInt()} words")

                Spacer(modifier = Modifier.height(24.dp))
                
                Text("Margin", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = margin,
                    onValueChange = { margin = it },
                    valueRange = 0f..64f
                )
                Text("${margin.toInt()} px")

                Spacer(modifier = Modifier.height(24.dp))

                Text("Style Preset", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    styles.forEach { style ->
                        FilterChip(
                            selected = textStyle == style,
                            onClick = { textStyle = style },
                            label = { Text(style) }
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        val allWords = mutableListOf<TranscriptionWord>()
                        for (seg in segments) {
                            if (!seg.words.isNullOrEmpty()) {
                                allWords.addAll(seg.words!!)
                            } else {
                                val splitTexts = seg.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                                val duration = seg.end - seg.start
                                val timePerWord = if (splitTexts.isNotEmpty()) duration / splitTexts.size else 0.0
                                splitTexts.forEachIndexed { idx, txt ->
                                    allWords.add(TranscriptionWord(
                                        start = seg.start + (idx * timePerWord),
                                        end = seg.start + ((idx + 1) * timePerWord),
                                        word = txt
                                    ))
                                }
                            }
                        }
                        
                        val newSegments = mutableListOf<TranscriptionSegment>()
                        allWords.chunked(wordsPerLine.toInt()).forEach { chunk ->
                            newSegments.add(
                                TranscriptionSegment(
                                    start = chunk.first().start,
                                    end = chunk.last().end,
                                    text = chunk.joinToString(" ") { it.word },
                                    words = chunk
                                )
                            )
                        }
                        segments = newSegments
                        isSettingsDone = true
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Apply & Start Editing")
                }
            }
        } else {
            // Main Editor (Timeline, Text editor, Video placeholder)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Video Preview Dummy Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.5f)
                        .background(Color.Black)
                        .padding(margin.dp)
                ) {
                    val currentText = if (segments.isNotEmpty() && selectedIndex < segments.size) segments[selectedIndex].text else "Video Preview (Live)"
                    Text(
                        text = currentText,
                        style = when(textStyle) {
                            "TikTok" -> MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold)
                            "Minimal" -> MaterialTheme.typography.bodyLarge
                            else -> MaterialTheme.typography.headlineMedium
                        },
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    offsetX += dragAmount.x
                                    offsetY += dragAmount.y
                                }
                            }
                    )
                }

                // Subtitle Editor Segments
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(segments) { index, segment ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedIndex == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = if (selectedIndex == index) 4.dp else 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    "${String.format("%.1f", segment.start)}s -> ${String.format("%.1f", segment.end)}s",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedIndex == index) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = segment.text,
                                    onValueChange = { newText ->
                                        // Update segment text
                                        val newSegments = segments.toMutableList()
                                        newSegments[index] = segment.copy(text = newText)
                                        segments = newSegments
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
