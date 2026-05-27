package com.example.ui.processing

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.viewmodel.SubtitleState
import com.example.viewmodel.SubtitleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    viewModel: SubtitleViewModel,
    onProcessingComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is SubtitleState.Success) {
            onProcessingComplete()
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val state = uiState) {
                is SubtitleState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    OutlinedButton(onClick = {
                        viewModel.cancelJob()
                        onCancel()
                    }) {
                        Text("Cancel")
                    }
                }
                is SubtitleState.Error -> {
                    Text(
                        text = "Error occurred",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = state.error, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onCancel) {
                        Text("Go Back")
                    }
                }
                else -> {
                    // Success or Idle handled via LaunchedEffect
                }
            }
        }
    }
}
