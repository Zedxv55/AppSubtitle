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
import com.example.viewmodel.SubtitleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    viewModel: SubtitleViewModel,
    onNavigateBack: () -> Unit
) {
    var resolution by remember { mutableStateOf("1080p") }
    var fps by remember { mutableStateOf("60 fps") }

    Scaffold(
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

            Button(
                onClick = { /* TODO: Hook up with FFmpegKit later */ },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Export Video", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
