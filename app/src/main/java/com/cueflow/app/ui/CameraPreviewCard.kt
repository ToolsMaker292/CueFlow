package com.cueflow.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.OutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cueflow.app.R
import com.cueflow.app.ui.camera.CameraRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun CameraPreviewCard(
    modifier: Modifier = Modifier,
    audioEnabled: Boolean,
    onDrag: (Float, Float) -> Unit,
    onHide: () -> Unit,
    startRequest: Int,
    onClose: () -> Unit,
    onRecordingChanged: (Boolean) -> Unit,
    onSaved: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val previewDescription = stringResource(R.string.camera_preview)
    val cameraOwner = remember { Any() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var handledStartRequest by remember { mutableIntStateOf(0) }

    LaunchedEffect(lensFacing) {
        try {
            val provider = CameraRuntime.provider(context)
            cameraProvider = provider
            val selector = CameraRuntime.selectCamera(provider, lensFacing)
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    ),
                )
                .build()
            val capture = VideoCapture.withOutput(recorder)
            CameraRuntime.bind(
                cameraOwner,
                provider,
                lifecycleOwner,
                selector,
                preview,
                capture,
            )
            videoCapture = capture
            cameraError = null
        } catch (_: Exception) {
            cameraError = context.getString(R.string.camera_no_camera)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recording?.close() }
            cameraProvider?.let { provider ->
                CameraRuntime.release(cameraOwner, provider)
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (!isRecording) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt
            delay(250)
        }
    }

    fun startRecording() {
        if (isRecording || recording != null) return
        val capture = videoCapture ?: return
        cameraError = null
        val name = "CueFlow_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val outputOptions: OutputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CueFlow")
            }
            MediaStoreOutputOptions.Builder(
                context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            ).setContentValues(values).build()
        } else {
            val directory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "CueFlow",
            ).apply { mkdirs() }
            FileOutputOptions.Builder(File(directory, name)).build()
        }

        try {
            var pending = if (outputOptions is MediaStoreOutputOptions) {
                capture.output.prepareRecording(context, outputOptions)
            } else {
                capture.output.prepareRecording(context, outputOptions as FileOutputOptions)
            }
            if (audioEnabled &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        onRecordingChanged(true)
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        recording = null
                        onRecordingChanged(false)
                        if (event.hasError()) {
                            cameraError = context.getString(R.string.camera_failed)
                        } else {
                            onSaved(event.outputResults.outputUri)
                        }
                    }
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            cameraError = context.getString(R.string.camera_failed)
        }
    }

    LaunchedEffect(startRequest, videoCapture) {
        if (startRequest > handledStartRequest && !isRecording && videoCapture != null) {
            handledStartRequest = startRequest
            startRecording()
        }
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .semantics { contentDescription = previewDescription },
        color = Color.Black,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
        shadowElevation = 10.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.62f),
                            ),
                        ),
                    ),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragIndicator,
                    contentDescription = stringResource(R.string.camera_drag),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .size(26.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        },
                )
                if (isRecording) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.58f),
                        shape = CircleShape,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(RecordingRed),
                            )
                            Text(
                                text = formatElapsed(elapsedMs),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onHide,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Rounded.VisibilityOff,
                        contentDescription = stringResource(R.string.camera_hide_preview),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onClose,
                    enabled = !isRecording,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.camera_close),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                            CameraSelector.LENS_FACING_BACK
                        } else {
                            CameraSelector.LENS_FACING_FRONT
                        }
                    },
                    enabled = !isRecording,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Rounded.Cameraswitch,
                        contentDescription = stringResource(R.string.camera_switch),
                        tint = Color.White,
                    )
                }

                if (!audioEnabled) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.58f),
                        shape = CircleShape,
                    ) {
                        Text(
                            text = stringResource(R.string.camera_muted),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }

                FilledIconButton(
                    onClick = {
                        if (isRecording) {
                            recording?.stop()
                        } else {
                            startRecording()
                        }
                    },
                    enabled = videoCapture != null || isRecording,
                    modifier = Modifier.size(46.dp),
                    colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                        containerColor = RecordingRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                        contentDescription = stringResource(
                            if (isRecording) R.string.camera_record_stop else R.string.camera_record_start,
                        ),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            cameraError?.let { message ->
                Surface(
                    color = Color.Black.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(12.dp),
                ) {
                    Text(
                        text = message,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

private val RecordingRed = Color(0xFFE53935)

private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / 1_000L
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
