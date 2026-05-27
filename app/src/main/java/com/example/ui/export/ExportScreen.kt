package com.example.ui.export

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.example.viewmodel.SubtitleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: SubtitleViewModel,
    onNavigateBack: () -> Unit
) {
    var resolution by remember { mutableStateOf("1080p") }
    var fps by remember { mutableStateOf("60 fps") }

    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Export Options") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text("Select Resolution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            
            val resolutions = listOf("480p", "720p", "1080p", "Original")
            resolutions.forEach { res ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = resolution == res,
                        onClick = { resolution = res }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(res, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Subtitle Export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Hardcode subtitles directly into video (Burn-in) or Export as SRT.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.weight(1f))

            if (isExporting) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Rendering Video... ${(exportProgress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Button(
                onClick = { 
                    if (!isExporting) {
                        isExporting = true
                        exportProgress = 0f
                        
                        // Extract segments from current State
                        val segments = if (uiState is com.example.viewmodel.SubtitleState.Success) {
                            (uiState as com.example.viewmodel.SubtitleState.Success).segments
                        } else emptyList()

                        viewModel.exportVideo(
                            context = context,
                            segments = segments,
                            resolution = resolution,
                            onProgress = { progress ->
                                exportProgress = progress
                            },
                            onComplete = { uri ->
                                isExporting = false
                                scope.launch {
                                    if (uri != null) {
                                        snackbarHostState.showSnackbar("Export successful! Video and subtitles saved to Downloads/SubAI.")
                                    } else {
                                        snackbarHostState.showSnackbar("Export failed.")
                                    }
                                }
                            }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isExporting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (isExporting) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Download, contentDescription = "Download")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isExporting) "Exporting..." else "Export Video", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
