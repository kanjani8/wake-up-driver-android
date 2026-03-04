package com.example.drowseydriver1

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.TransformExperimental
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.drowseydriver1.ui.theme.Purple80
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

enum class UserState(@StringRes val textResId: Int) {
    AWAKE(R.string.state_awake),
    SLEEP(R.string.state_sleep),
    DROWSY(R.string.state_drowsy)
}

@OptIn(TransformExperimental::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val backgroundExecutor = remember { Executors.newSingleThreadExecutor() }

    // Camera permission
    var hasCameraPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Camera controller
    val cameraController = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            setEnabledUseCases(
//                LifecycleCameraController.IMAGE_CAPTURE or
//                        LifecycleCameraController.VIDEO_CAPTURE or
                        LifecycleCameraController.IMAGE_ANALYSIS
            )
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
//            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888 // Will be used default YUV, to feed MLKit Facemesh
        }
    }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraController.bindToLifecycle(lifecycleOwner)
        } else {
            cameraController.clearImageAnalysisAnalyzer()
        }
    }
    val previewView = remember{PreviewView(context).apply {
            controller = cameraController
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val facemeshAnalyzer = remember(previewView) { CameraAnalyzer(context, previewView) }


    // for faceMash sample
    //val points by facemeshAnalyzer.pointsOnPreview.collectAsState()
    val rois by facemeshAnalyzer.roisOnPreview.collectAsState()

    // Status UI state
    val status by facemeshAnalyzer.drowsinessState.collectAsState()

    // Alert banner state
    var showBanner by remember { mutableStateOf(false) }

    // Beep Alert
    val soundPlayer = rememberSoundPlayer(context)

    // Example trigger simulator (will be deleted later)
    LaunchedEffect(status) {
        if (status.drowsinessPercent >= 80f || status.isSleeping) {
            if (!showBanner) {
                showBanner = true
                soundPlayer.play()
                delay(3000L) // 3초 대기
                showBanner = false
            }
        }
    }

    LaunchedEffect(hasCameraPermission, facemeshAnalyzer) {
        if (!hasCameraPermission) return@LaunchedEffect

        cameraController.setImageAnalysisAnalyzer(
            backgroundExecutor,
            facemeshAnalyzer
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))

            Text(stringResource(id = R.string.app_title), style = MaterialTheme.typography.titleLarge)

            Spacer(Modifier.height(12.dp))

            // Camera preview box (middle)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                if (hasCameraPermission) {
                    Box(Modifier.fillMaxSize()){
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { previewView }
                        )
                        Canvas(modifier = Modifier.matchParentSize()) {
                            rois.forEach { r ->
                                drawRect(
                                    color = Purple80,
                                    topLeft = Offset(r.left, r.top),
                                    size = Size(r.width(), r.height()),
                                    style = Stroke(width = 3f)
                                )
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Camera permission required")
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            val translatedLabel = stringResource(id = status.label.textResId)
            val scoreInt = if (status.label == UserState.AWAKE) {
                100 - status.drowsinessPercent.toInt()
            } else {
                status.drowsinessPercent.toInt()
            }
            val closedSec = status.eyesClosedMs / 1000f

            // Bottom status text
            Text(
                text = stringResource(id = R.string.status_format, translatedLabel, scoreInt),
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.detail_eyes_closed, closedSec),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.detail_yawns, status.yawnsPer3Min),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(24.dp))
            DebugBitmapPreview(facemeshAnalyzer)
        }

        // In-app banner overlay (top)
        AnimatedVisibility(
            visible = showBanner,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Banner(
                text = stringResource(id = R.string.banner_alert),
                modifier = Modifier
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun Banner(text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
