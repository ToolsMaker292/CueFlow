package com.cueflow.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cueflow.app.MainViewModel
import com.cueflow.app.R
import com.cueflow.app.data.DocumentImportError
import com.cueflow.app.data.DocumentImportException
import com.cueflow.app.ui.camera.CameraRuntime
import java.io.File
import kotlinx.coroutines.launch

/**
 * Standalone photo-to-script screen. It uses CameraRuntime, the same CameraX
 * provider as the teleprompter video card, so only one camera session exists.
 */
@Composable
fun PhotoOcrScreen(
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onImported: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraOwner = remember { Any() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        permissionDenied = !granted
    }

    LaunchedEffect(Unit) {
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(cameraGranted, lensFacing) {
        if (!cameraGranted) return@LaunchedEffect
        try {
            val cameraProvider = CameraRuntime.provider(context)
            val selector = CameraRuntime.selectCamera(cameraProvider, lensFacing)
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
            CameraRuntime.bind(
                cameraOwner,
                cameraProvider,
                lifecycleOwner,
                selector,
                preview,
                capture,
            )
            provider = cameraProvider
            imageCapture = capture
            errorMessage = null
        } catch (_: Exception) {
            errorMessage = context.getString(R.string.photo_ocr_camera_failed)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            provider?.let { CameraRuntime.release(cameraOwner, it) }
        }
    }

    BackHandler(enabled = !recognizing) { onClose() }

    fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing || recognizing) return
        capturing = true
        errorMessage = null
        val file = File(context.cacheDir, "ocr-photo-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    recognizing = true
                    capturing = false
                    scope.launch {
                        try {
                            val result = viewModel.importDocument(Uri.fromFile(file))
                            result.fold(
                                onSuccess = { imported -> onImported(imported.title) },
                                onFailure = { error ->
                                    errorMessage = when (
                                        (error as? DocumentImportException)?.reason
                                    ) {
                                        DocumentImportError.NO_TEXT ->
                                            context.getString(R.string.photo_ocr_no_text)
                                        DocumentImportError.OCR_FAILED ->
                                            context.getString(R.string.import_document_ocr_failed)
                                        else -> context.getString(R.string.photo_ocr_import_failed)
                                    }
                                },
                            )
                        } finally {
                            file.delete()
                            recognizing = false
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    capturing = false
                    errorMessage = context.getString(R.string.photo_ocr_capture_failed)
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 120.dp)
                .border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(24.dp),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
            ) {
                IconButton(onClick = onClose, enabled = !recognizing) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.photo_ocr_close),
                        tint = Color.White,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.photo_ocr_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            errorMessage?.let { message ->
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(bottom = 14.dp),
                ) {
                    Text(
                        text = message,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.photo_ocr_hint),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    enabled = !capturing && !recognizing,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Rounded.Cameraswitch,
                        contentDescription = stringResource(R.string.photo_ocr_switch),
                        tint = Color.White,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(7.dp)
                        .background(Color.White, CircleShape)
                        .clickable(enabled = !capturing && !recognizing) { takePhoto() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Spacer(Modifier.size(52.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.photo_ocr_capture_description),
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (recognizing) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.import_ocr_busy_body),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (permissionDenied) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.photo_ocr_permission_title)) },
            text = { Text(stringResource(R.string.photo_ocr_permission_body)) },
            confirmButton = {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.photo_ocr_close))
                }
            },
        )
    }
}
