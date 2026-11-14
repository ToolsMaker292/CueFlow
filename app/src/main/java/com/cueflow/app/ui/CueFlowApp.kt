package com.cueflow.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cueflow.app.MainViewModel
import com.cueflow.app.R
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val EDITOR = "editor?scriptId={scriptId}"
    const val PROMPT = "prompt/{scriptId}"
    const val SETTINGS = "settings"

    fun editor(scriptId: String?): String =
        if (scriptId == null) "editor" else "editor?scriptId=$scriptId"

    fun prompt(scriptId: String): String = "prompt/$scriptId"
}

@Composable
fun CueFlowApp(viewModel: MainViewModel) {
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val scripts by viewModel.scripts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notify: (String) -> Unit = remember(snackbarHostState, scope) {
        { message ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    if (loading) {
        LoadingScreen()
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }
            },
            exitTransition = {
                fadeOut(tween(160)) + slideOutHorizontally(tween(240)) { -it / 12 }
            },
            popEnterTransition = {
                fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it / 12 }
            },
            popExitTransition = {
                fadeOut(tween(160)) + slideOutHorizontally(tween(240)) { it / 12 }
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    viewModel = viewModel,
                    onNewScript = { navController.navigate(Routes.editor(null)) },
                    onEditScript = { id -> navController.navigate(Routes.editor(id)) },
                    onPlayScript = { id -> navController.navigate(Routes.prompt(id)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    notify = notify,
                )
            }

            composable(
                route = Routes.EDITOR,
                arguments = listOf(
                    navArgument("scriptId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                EditorScreen(
                    viewModel = viewModel,
                    scriptId = entry.arguments?.getString("scriptId"),
                    onBack = { navController.popBackStack() },
                    notify = notify,
                )
            }

            composable(
                route = Routes.PROMPT,
                arguments = listOf(navArgument("scriptId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("scriptId")
                val script = scripts.firstOrNull { it.id == id }
                if (script == null) {
                    LaunchedEffect(id) {
                        notify(context.getString(R.string.error_generic))
                        navController.popBackStack()
                    }
                } else {
                    PromptScreen(
                        script = script,
                        settings = settings,
                        onClose = { navController.popBackStack() },
                    )
                }
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    notify = notify,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LoadingScreen() {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                modifier = Modifier.size(84.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.cd_app_logo),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(72.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
