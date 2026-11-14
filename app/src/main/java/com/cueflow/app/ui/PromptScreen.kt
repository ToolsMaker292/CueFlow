package com.cueflow.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cueflow.app.R
import com.cueflow.app.data.AppSettings
import com.cueflow.app.data.Script
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val PromptBackground = Color(0xFF07080C)
private val PromptText = Color(0xFFF5F2FC)
private val PromptPanel = Color(0xFF171821)
private val PromptPanelBorder = Color(0xFF2C2E3A)

@Composable
fun PromptScreen(
    script: Script,
    settings: AppSettings,
    onClose: () -> Unit,
) {
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var speed by rememberSaveable(script.id) { mutableIntStateOf(settings.defaultSpeed) }
    var fontSize by rememberSaveable(script.id) { mutableIntStateOf(settings.defaultFontSize) }
    var mirrorH by rememberSaveable(script.id) { mutableStateOf(settings.mirrorHorizontal) }
    var mirrorV by rememberSaveable(script.id) { mutableStateOf(settings.mirrorVertical) }
    var isPlaying by rememberSaveable(script.id) { mutableStateOf(false) }
    var hasPlayed by rememberSaveable(script.id) { mutableStateOf(false) }
    var countdown by rememberSaveable(script.id) { mutableIntStateOf(0) }
    var finished by rememberSaveable(script.id) { mutableStateOf(false) }
    var controlsVisible by rememberSaveable(script.id) { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    var cameraEnabled by rememberSaveable(script.id) { mutableStateOf(false) }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    var savedVideoUri by remember { mutableStateOf<Uri?>(null) }
    var cameraPreviewHidden by rememberSaveable(script.id) { mutableStateOf(false) }
    var cameraRecording by remember { mutableStateOf(false) }
    var cameraOffset by remember(script.id) { mutableStateOf<IntOffset?>(null) }

    LaunchedEffect(cameraRecording, cameraPreviewHidden) {
        if (cameraPreviewHidden && !cameraRecording) {
            cameraPreviewHidden = false
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (cameraGranted) {
            cameraEnabled = true
            controlsVisible = true
        } else {
            showCameraPermissionDialog = true
        }
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            audioGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            cameraEnabled = true
            controlsVisible = true
        } else {
            cameraPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                ),
            )
        }
    }

    val progress by remember {
        derivedStateOf {
            if (scrollState.maxValue <= 0) 0f
            else (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
        }
    }

    val activity = remember(view) { view.context.findActivity() }
    val window = activity?.window

    DisposableEffect(Unit) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.let {
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (settings.keepScreenOn) {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    BackHandler(onBack = onClose)

    fun markInteraction() {
        interactionTick++
        controlsVisible = true
    }

    fun startCountdownOrPlay() {
        if (!hasPlayed && settings.defaultCountdown > 0 && scrollState.value == 0) {
            countdown = settings.defaultCountdown
        } else {
            isPlaying = true
            hasPlayed = true
            finished = false
        }
    }

    fun startPlayback() {
        if (finished) {
            scope.launch {
                scrollState.scrollTo(0)
                finished = false
                hasPlayed = false
                startCountdownOrPlay()
            }
        } else {
            startCountdownOrPlay()
        }
    }

    fun togglePlayback() {
        if (countdown > 0) {
            countdown = 0
            isPlaying = false
            return
        }
        if (isPlaying) {
            isPlaying = false
            controlsVisible = true
        } else {
            startPlayback()
            controlsVisible = true
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        interactionTick++
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            if (countdown <= 1) {
                countdown = 0
                isPlaying = true
                hasPlayed = true
            } else {
                countdown--
            }
        }
    }

    LaunchedEffect(isPlaying, speed) {
        if (!isPlaying) return@LaunchedEffect
        var previousFrame = 0L
        while (isActive && isPlaying) {
            val frame = withFrameNanos { it }
            if (previousFrame != 0L) {
                val seconds = ((frame - previousFrame) / 1_000_000_000.0)
                    .coerceIn(0.0, 0.05)
                    .toFloat()
                val pixels = speed * density.density * seconds
                if (pixels > 0f) {
                    try {
                        scrollState.scrollBy(pixels)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The scroll container can be detached during a configuration change.
                    }
                }
                if (!scrollState.canScrollForward) {
                    isPlaying = false
                    finished = true
                    controlsVisible = true
                    break
                }
            }
            previousFrame = frame
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, countdown, interactionTick) {
        if (controlsVisible && isPlaying && countdown == 0) {
            delay(3_800)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PromptBackground)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionTick++
                    },
                )
            },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val horizontalPadding = if (maxWidth > 600.dp) 64.dp else 24.dp
            // Keep the first lines clear of the floating camera preview.
            val topSpace = maxHeight * if (cameraEnabled) 0.36f else 0.30f
            // Keeps the final line on the reading guide when playback reaches the end.
            val bottomSpace = maxHeight * 0.66f

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (mirrorH) -1f else 1f
                        scaleY = if (mirrorV) -1f else 1f
                    }
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(topSpace))
                Text(
                    text = script.content,
                    color = PromptText,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * settings.defaultLineHeight).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start,
                    letterSpacing = 0.2.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 1_100.dp),
                )
                Spacer(Modifier.height(bottomSpace))
            }

            ReadingGuide(
                topOffset = maxHeight * 0.34f,
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    PromptBackground.copy(alpha = 0.96f),
                                    PromptBackground.copy(alpha = 0.55f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically { -it / 3 },
                exit = fadeOut() + slideOutVertically { -it / 3 },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                TopControls(
                    title = script.title.ifBlank { stringResource(R.string.script_untitled) },
                    progress = progress,
                    onClose = onClose,
                    onCamera = ::openCamera,
                    cameraEnabled = cameraEnabled,
                )
            }

            if (cameraEnabled) {
                val cameraCardWidth = 148.dp
                val cameraCardWidthPx = with(density) { cameraCardWidth.toPx() }
                val cameraCardHeightPx = cameraCardWidthPx * 4f / 3f
                val maxCameraX = (with(density) { maxWidth.toPx() } - cameraCardWidthPx)
                    .coerceAtLeast(0f)
                val maxCameraY = (with(density) { maxHeight.toPx() } - cameraCardHeightPx)
                    .coerceAtLeast(0f)
                val defaultCameraOffset = IntOffset(
                    (maxCameraX - with(density) { 12.dp.toPx() })
                        .roundToInt()
                        .coerceAtLeast(0),
                    with(density) { 108.dp.toPx() }
                        .roundToInt()
                        .coerceAtMost(maxCameraY.roundToInt()),
                )
                val cameraPosition = cameraOffset ?: defaultCameraOffset
                val clampedCameraPosition = IntOffset(
                    cameraPosition.x.coerceIn(0, maxCameraX.roundToInt()),
                    cameraPosition.y.coerceIn(0, maxCameraY.roundToInt()),
                )

                CameraPreviewCard(
                    modifier = if (cameraPreviewHidden) {
                        Modifier
                            .size(1.dp)
                            .alpha(0f)
                    } else {
                        Modifier
                            .offset { clampedCameraPosition }
                            .width(cameraCardWidth)
                            .aspectRatio(3f / 4f)
                    },
                    audioEnabled = audioGranted,
                    onDrag = { dx, dy ->
                        val current = cameraOffset ?: defaultCameraOffset
                        cameraOffset = IntOffset(
                            (current.x + dx.roundToInt())
                                .coerceIn(0, maxCameraX.roundToInt()),
                            (current.y + dy.roundToInt())
                                .coerceIn(0, maxCameraY.roundToInt()),
                        )
                    },
                    onHide = { cameraPreviewHidden = true },
                    onClose = {
                        cameraEnabled = false
                        cameraPreviewHidden = false
                        cameraRecording = false
                    },
                    onRecordingChanged = { cameraRecording = it },
                    onSaved = { uri -> savedVideoUri = uri },
                )

                if (cameraRecording && cameraPreviewHidden) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(top = 8.dp, end = 12.dp)
                            .size(40.dp)
                            .clickable { cameraPreviewHidden = false }
                            .semantics {
                                contentDescription = buildString {
                                    append(context.getString(R.string.camera_recording_indicator))
                                    append(" · ")
                                    append(context.getString(R.string.camera_show_preview))
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE53935)),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = countdown > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CountdownOverlay(countdown = countdown)
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.56f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    PromptBackground.copy(alpha = 0.72f),
                                    PromptBackground.copy(alpha = 0.98f),
                                ),
                            ),
                        ),
                )
            }

            AnimatedVisibility(
                visible = finished && !isPlaying,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = maxHeight * 0.12f),
            ) {
                Surface(
                    color = PromptPanel.copy(alpha = 0.94f),
                    shape = CircleShape,
                    border = androidx.compose.foundation.BorderStroke(1.dp, PromptPanelBorder),
                ) {
                    Text(
                        text = stringResource(R.string.prompt_finished),
                        color = PromptText,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                PlaybackControls(
                    speed = speed,
                    fontSize = fontSize,
                    mirrorH = mirrorH,
                    mirrorV = mirrorV,
                    isPlaying = isPlaying,
                    countdown = countdown,
                    onSpeedChange = {
                        speed = it.roundToInt().coerceIn(10, 120)
                        markInteraction()
                    },
                    onFontDecrease = {
                        fontSize = (fontSize - 2).coerceAtLeast(24)
                        markInteraction()
                    },
                    onFontIncrease = {
                        fontSize = (fontSize + 2).coerceAtMost(64)
                        markInteraction()
                    },
                    onToggleMirrorH = {
                        mirrorH = !mirrorH
                        markInteraction()
                    },
                    onToggleMirrorV = {
                        mirrorV = !mirrorV
                        markInteraction()
                    },
                    onRestart = {
                        scope.launch {
                            isPlaying = false
                            countdown = 0
                            finished = false
                            hasPlayed = false
                            scrollState.scrollTo(0)
                        }
                        markInteraction()
                    },
                    onTogglePlay = ::togglePlayback,
                )
            }
        }
    }

    if (showCameraPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionDialog = false },
            title = { Text(stringResource(R.string.camera_permission_title)) },
            text = { Text(stringResource(R.string.camera_permission_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCameraPermissionDialog = false
                        openAppSettings(context)
                    },
                ) {
                    Text(stringResource(R.string.camera_permission_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCameraPermissionDialog = false }) {
                    Text(stringResource(R.string.camera_permission_cancel))
                }
            },
        )
    }

    savedVideoUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { savedVideoUri = null },
            title = { Text(stringResource(R.string.camera_saved_title)) },
            text = { Text(stringResource(R.string.camera_saved_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        savedVideoUri = null
                        shareRecording(context, uri)
                    },
                ) {
                    Text(stringResource(R.string.camera_share))
                }
            },
            dismissButton = {
                TextButton(onClick = { savedVideoUri = null }) {
                    Text(stringResource(R.string.camera_done))
                }
            },
        )
    }
}

@Composable
private fun TopControls(
    title: String,
    progress: Float,
    onClose: () -> Unit,
    onCamera: () -> Unit,
    cameraEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledIconButton(
                onClick = onClose,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = PromptPanel.copy(alpha = 0.92f),
                    contentColor = PromptText,
                ),
                modifier = Modifier.testTag("prompt_close"),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.prompt_close),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = PromptText,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.prompt_progress, (progress * 100).roundToInt()),
                    color = PromptText.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (!cameraEnabled) {
                IconButton(onClick = onCamera) {
                    Icon(
                        Icons.Rounded.Videocam,
                        contentDescription = stringResource(R.string.camera_open),
                        tint = PromptText,
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = PromptText.copy(alpha = 0.12f),
        )
    }
}

@Composable
private fun PlaybackControls(
    speed: Int,
    fontSize: Int,
    mirrorH: Boolean,
    mirrorV: Boolean,
    isPlaying: Boolean,
    countdown: Int,
    onSpeedChange: (Float) -> Unit,
    onFontDecrease: () -> Unit,
    onFontIncrease: () -> Unit,
    onToggleMirrorH: () -> Unit,
    onToggleMirrorV: () -> Unit,
    onRestart: () -> Unit,
    onTogglePlay: () -> Unit,
) {
    val speedDescription = stringResource(R.string.prompt_speed)
    val decreaseFontDescription = stringResource(R.string.prompt_decrease_font)
    val increaseFontDescription = stringResource(R.string.prompt_increase_font)
    Surface(
        color = PromptPanel,
        contentColor = PromptText,
        shape = RoundedCornerShape(28.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PromptPanelBorder),
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = PromptText.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.prompt_speed),
                    color = PromptText.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(44.dp),
                )
                Slider(
                    value = speed.toFloat(),
                    onValueChange = onSpeedChange,
                    valueRange = 10f..120f,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = speedDescription
                        },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = PromptText.copy(alpha = 0.18f),
                    ),
                )
                Text(
                    text = stringResource(R.string.prompt_speed_value, speed),
                    color = PromptText,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(28.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Rounded.TextFields,
                    contentDescription = null,
                    tint = PromptText.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
                IconButton(
                    onClick = onFontDecrease,
                    enabled = fontSize > 24,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = PromptText,
                        disabledContentColor = PromptText.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.size(36.dp),
                ) {
                    Text(
                        text = "A−",
                        color = PromptText,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics {
                            contentDescription = decreaseFontDescription
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.prompt_font_value, fontSize),
                    color = PromptText,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = onFontIncrease,
                    enabled = fontSize < 64,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = PromptText,
                        disabledContentColor = PromptText.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.size(36.dp),
                ) {
                    Text(
                        text = "A+",
                        color = PromptText,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics {
                            contentDescription = increaseFontDescription
                        },
                    )
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onRestart,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = PromptText),
                ) {
                    Icon(
                        Icons.Rounded.RestartAlt,
                        contentDescription = stringResource(R.string.prompt_restart),
                    )
                }
                FilledIconToggleButton(
                    checked = mirrorH,
                    onCheckedChange = { onToggleMirrorH() },
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = PromptText.copy(alpha = 0.08f),
                        contentColor = PromptText,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        Icons.Rounded.Flip,
                        contentDescription = stringResource(R.string.prompt_mirror_horizontal),
                    )
                }
                FilledIconToggleButton(
                    checked = mirrorV,
                    onCheckedChange = { onToggleMirrorV() },
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = PromptText.copy(alpha = 0.08f),
                        contentColor = PromptText,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        Icons.Rounded.Flip,
                        contentDescription = stringResource(R.string.prompt_mirror_vertical),
                        modifier = Modifier.graphicsLayer { rotationZ = 90f },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("prompt_play_pause"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.prompt_pause else R.string.prompt_play,
                        ),
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownOverlay(countdown: Int) {
    Surface(
        color = Color.Black.copy(alpha = 0.62f),
        shape = RoundedCornerShape(36.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 44.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = countdown.toString(),
                color = PromptText,
                fontSize = 82.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.prompt_get_ready),
                color = PromptText.copy(alpha = 0.8f),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun ReadingGuide(
    topOffset: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(top = topOffset)
            .height(18.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            PromptText.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(PromptText.copy(alpha = 0.35f)),
            )
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(PromptText.copy(alpha = 0.35f)),
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun shareRecording(context: Context, uri: Uri) {
    val shareUri = if (uri.scheme == "file") {
        val file = uri.path?.let(::File) ?: return
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    } else {
        uri
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.camera_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
