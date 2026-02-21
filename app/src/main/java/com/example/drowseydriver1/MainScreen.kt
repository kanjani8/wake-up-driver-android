package com.example.drowseydriver1

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.drowseydriver1.ui.theme.Purple80
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class DriverStatus(
    val label: String,
    val confidence: Int,
    val detail1: String,
    val detail2: String,
)

@OptIn(TransformExperimental::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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
    val facemeshAnalyzer = remember(previewView) { CameraAnalyzer(previewView) }

    // for faceMash sample
    //val points by facemashAnalyzer.pointsOnPreview.collectAsState()
    val rois by facemeshAnalyzer.roisOnPreview.collectAsState()

    // Status UI state (replace these updates with your model output)
    var status by remember {
        mutableStateOf(
            DriverStatus(
                label = "Awake",
                confidence = 94,
                detail1 = "Eyes closed for 0.3 sec",
                detail2 = "Yawns 1 time in 3 minutes"
            )
        )
    }

    // Alert banner state
    var showBanner by remember { mutableStateOf(false) }
    var bannerText by remember { mutableStateOf("DROWSINESS DETECTED — Please take a break.") }

    // Beep Alert
    val soundPlayer = rememberSoundPlayer(context)

    // Example trigger simulator (will be deleted later)
    LaunchedEffect(Unit) {
        delay(4000)
        status = status.copy(detail1 = "Eyes closed for 0.9 sec")
        triggerBanner(
            scope = scope,
            soundPlayer = soundPlayer,
            onShow = { text -> bannerText = text; showBanner = true },
            onHide = { showBanner = false }
        )
    }

    LaunchedEffect(hasCameraPermission, facemeshAnalyzer) {
        if (!hasCameraPermission) return@LaunchedEffect

        cameraController.setImageAnalysisAnalyzer(
            ContextCompat.getMainExecutor(context),
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

            Text("Driver Status Checker", style = MaterialTheme.typography.titleLarge)

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

            // Bottom status text
            Text(
                "${status.label}  ${status.confidence}%",
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.height(8.dp))
            Text(status.detail1, style = MaterialTheme.typography.bodyLarge)
            Text(status.detail2, style = MaterialTheme.typography.bodyLarge)

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
                text = bannerText,
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

private fun triggerBanner(
    scope: kotlinx.coroutines.CoroutineScope,
    soundPlayer: SoundPlayer,
    onShow: (String) -> Unit,
    onHide: () -> Unit,
    durationMs: Long = 3000L
) {
    scope.launch {
        onShow("DROWSINESS DETECTED — Please take a break.")
        soundPlayer.play()
        delay(durationMs)
        onHide()
    }
}

