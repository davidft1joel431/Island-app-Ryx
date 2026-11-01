package com.d4viddf.hyperbridge

import android.content.Context
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.NavDisplay
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.db.AppDatabase
import com.d4viddf.hyperbridge.ui.components.ChangelogSheet
import com.d4viddf.hyperbridge.ui.components.UpdateDialog
import com.d4viddf.hyperbridge.ui.navigation.Navigator
import com.d4viddf.hyperbridge.ui.navigation.Screen
import com.d4viddf.hyperbridge.ui.navigation.mainNavGraph
import com.d4viddf.hyperbridge.ui.navigation.rememberNavigationState
import com.d4viddf.hyperbridge.ui.navigation.toEntries
import com.d4viddf.hyperbridge.ui.theme.HyperBridgeTheme
import com.d4viddf.hyperbridge.util.BackupManager
import com.d4viddf.hyperbridge.util.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import androidx.activity.enableEdgeToEdge

private val RyxEmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val RyxEmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainRootNavigation(onExit = { finish() })
                }
            }
        }
    }
}

@Composable
fun MainRootNavigation(onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { AppPreferences(context) }

    // Let's use lazy delegates
    val database by remember { lazy { AppDatabase.getDatabase(context) } }
    val backupManager by remember { lazy { BackupManager(context, preferences, database) } }

    val packageInfo by androidx.compose.runtime.produceState<android.content.pm.PackageInfo?>(initialValue = null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        }
    }
    @Suppress("DEPRECATION")
    val currentVersionCode = packageInfo?.longVersionCode?.toInt() ?: 0
    val currentVersionName = packageInfo?.versionName ?: "1.0.8"

    val isSetupComplete by produceState<Boolean?>(initialValue = null) {
        preferences.isSetupComplete.collect { value = it }
    }

    if (isSetupComplete == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        androidx.compose.runtime.key(isSetupComplete) {
            MainNavigationContent(
                isSetupComplete = isSetupComplete!!,
                context = context,
                scope = scope,
                preferences = preferences,
                backupManager = backupManager,
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName,
                onExit = onExit
            )
        }
    }
}

@Composable
private fun MainNavigationContent(
    isSetupComplete: Boolean,
    context: Context,
    scope: CoroutineScope,
    preferences: AppPreferences,
    backupManager: BackupManager,
    currentVersionCode: Int,
    currentVersionName: String,
    onExit: () -> Unit
) {
    val isInitiallySetup = remember { isSetupComplete }
    val lastSeenVersion by preferences.lastSeenVersion.collectAsState(initial = -1)

    var showChangelog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    LaunchedEffect(isSetupComplete) {
        if (!isSetupComplete) return@LaunchedEffect
        val result = UpdateChecker.checkForUpdate(context, force = false)
        if (result is UpdateChecker.Result.Available &&
            UpdateChecker.shouldPrompt(context, result.info)
        ) {
            updateInfo = result.info
        }
    }

    val initialStartRoute = remember(isSetupComplete) { if (isSetupComplete) Screen.Home else Screen.Onboarding }
    val allPossibleTopLevel = remember(isSetupComplete) { setOf(Screen.Onboarding, Screen.Home) }

    val navigationState = rememberNavigationState(
        startRoute = initialStartRoute,
        topLevelRoutes = allPossibleTopLevel
    )
    val navigator = remember(navigationState) { Navigator(navigationState) }

    LaunchedEffect(isSetupComplete, lastSeenVersion) {
        if (isSetupComplete && isInitiallySetup && lastSeenVersion != -1) {
            if (currentVersionCode > lastSeenVersion) {
                showChangelog = true
            }
        }
    }

    val entryProvider = mainNavGraph(
        context = context,
        scope = scope,
        preferences = preferences,
        navigator = navigator,
        backupManager = backupManager,
        currentVersionCode = currentVersionCode,
        onExit = onExit
    )

    BackHandler {
        if (!navigator.goBack()) {
            onExit()
        }
    }

    NavDisplay(
        entries = navigationState.toEntries(entryProvider),
        onBack = {
            if (!navigator.goBack()) {
                onExit()
            }
        },
        transitionSpec = {
            (
                slideInHorizontally(
                    initialOffsetX = { it / 5 },
                    animationSpec = tween(420, easing = RyxEmphasizedDecelerate)
                ) + fadeIn(animationSpec = tween(280, delayMillis = 50)) +
                    scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(420, easing = RyxEmphasizedDecelerate)
                    )
                ) togetherWith (
                slideOutHorizontally(
                    targetOffsetX = { -it / 12 },
                    animationSpec = tween(300, easing = RyxEmphasizedAccelerate)
                ) + fadeOut(animationSpec = tween(220))
                )
        },
        popTransitionSpec = {
            (
                slideInHorizontally(
                    initialOffsetX = { -it / 5 },
                    animationSpec = tween(420, easing = RyxEmphasizedDecelerate)
                ) + fadeIn(animationSpec = tween(280, delayMillis = 50)) +
                    scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(420, easing = RyxEmphasizedDecelerate)
                    )
                ) togetherWith (
                slideOutHorizontally(
                    targetOffsetX = { it / 12 },
                    animationSpec = tween(300, easing = RyxEmphasizedAccelerate)
                ) + fadeOut(animationSpec = tween(220))
                )
        },
        predictivePopTransitionSpec = { swipeEdge ->
            val origin = if (swipeEdge == BackEventCompat.EDGE_LEFT) {
                TransformOrigin(0.92f, 0.5f)
            } else {
                TransformOrigin(0.08f, 0.5f)
            }
            EnterTransition.None togetherWith (
                scaleOut(
                    targetScale = 0.88f,
                    transformOrigin = origin,
                    animationSpec = tween(260, easing = RyxEmphasizedAccelerate)
                ) + slideOutHorizontally(
                    targetOffsetX = { if (swipeEdge == BackEventCompat.EDGE_LEFT) -it / 30 else it / 30 },
                    animationSpec = tween(260, easing = RyxEmphasizedAccelerate)
                ) + fadeOut(animationSpec = tween(180))
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
    )

    if (showChangelog) {
        ChangelogSheet(
            currentVersionName = currentVersionName,
            changelogText = stringResource(R.string.changelog_1_0_8),
            onDismiss = {
                showChangelog = false
                scope.launch {
                    preferences.setLastSeenVersion(currentVersionCode)
                }
            }
        )
    }

    val pendingUpdate = updateInfo
    if (pendingUpdate != null && !showChangelog) {
        UpdateDialog(
            info = pendingUpdate,
            currentVersion = currentVersionName,
            onDismiss = {
                UpdateChecker.markPrompted(context, pendingUpdate)
                updateInfo = null
            },
            onDownload = {
                UpdateChecker.markPrompted(context, pendingUpdate)
                updateInfo = null
                try {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(pendingUpdate.apkUrl)
                        )
                    )
                } catch (_: Exception) {
                    // El navegador no está disponible; el usuario puede abrir el release manualmente.
                }
            }
        )
    }

}
