package com.example.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.home.HomeScreen
import com.example.ui.processing.ProcessingScreen
import com.example.ui.editor.EditorScreen
import com.example.ui.export.ExportScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Processing : Screen("processing")
    object Editor : Screen("editor")
    object Export : Screen("export")
}

@Composable
fun AppNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    viewModel: com.example.viewmodel.SubtitleViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                viewModel = viewModel,
                onNavigateToProcessing = { 
                    navController.navigate(Screen.Processing.route) 
                },
                onNavigateToEditor = {
                    navController.navigate(Screen.Editor.route)
                }
            )
        }
        composable(Screen.Processing.route) {
            ProcessingScreen(
                viewModel = viewModel,
                onProcessingComplete = {
                    navController.navigate(Screen.Editor.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                    }
                },
                onCancel = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.Editor.route) {
            EditorScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToExport = {
                    navController.navigate(Screen.Export.route)
                }
            )
        }
        composable(Screen.Export.route) {
            ExportScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
