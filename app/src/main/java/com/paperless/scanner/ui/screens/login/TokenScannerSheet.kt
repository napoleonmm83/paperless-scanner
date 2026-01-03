package com.paperless.scanner.ui.screens.login

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TokenScanner"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenScannerSheet(
    onDismiss: () -> Unit,
    onTokenFound: (String) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var isScanning by remember { mutableStateOf(true) }
    var foundToken by remember { mutableStateOf<String?>(null) }
    var scanStatus by remember { mutableStateOf("Positioniere den Schlüssel im Rahmen") }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val isProcessing = remember { AtomicBoolean(false) }

    // Auto-dismiss after token found
    LaunchedEffect(foundToken) {
        foundToken?.let { token ->
            // Haptic feedback
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            delay(1500) // Show success state briefly
            onTokenFound(token)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            textRecognizer.close()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Schlüssel scannen",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Schließen")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!hasPermission) {
                // Permission needed
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Kamerazugriff wird benötigt",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Um den Schlüssel scannen zu können, braucht die App Zugriff auf deine Kamera",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Kamerazugriff erlauben")
                    }
                }
            } else {
                // Camera Preview with Focus Frame
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Camera Preview
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    ) { previewView ->
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()

                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            // Image Analysis for live scanning
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                                .also { analysis ->
                                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (isScanning && foundToken == null && !isProcessing.getAndSet(true)) {
                                            processImage(
                                                imageProxy = imageProxy,
                                                textRecognizer = textRecognizer,
                                                onTokenFound = { token ->
                                                    foundToken = token
                                                    isScanning = false
                                                    scanStatus = "Schlüssel gefunden!"
                                                },
                                                onProcessed = {
                                                    isProcessing.set(false)
                                                }
                                            )
                                        } else {
                                            imageProxy.close()
                                        }
                                    }
                                }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }

                    // Focus Frame Overlay
                    ScannerOverlay(
                        isSuccess = foundToken != null
                    )

                    // Status indicator at bottom of scan area
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-80).dp)
                            .background(
                                color = if (foundToken != null) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                },
                                shape = RoundedCornerShape(20.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (foundToken != null) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = scanStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (foundToken != null) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Instructions
                Text(
                    text = "Halte die Kamera ruhig auf den Bildschirm mit dem Schlüssel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ScannerOverlay(
    isSuccess: Boolean
) {
    val overlayColor = Color.Black.copy(alpha = 0.6f)
    val frameColor = if (isSuccess) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.White
    }
    val cornerRadius = 12.dp

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Calculate scan window dimensions (centered, 80% width, aspect ratio for token)
        val windowWidth = canvasWidth * 0.85f
        val windowHeight = windowWidth * 0.25f // Token is usually wide and short
        val windowLeft = (canvasWidth - windowWidth) / 2
        val windowTop = (canvasHeight - windowHeight) / 2

        // Create path for the scan window
        val scanWindowPath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(
                        offset = Offset(windowLeft, windowTop),
                        size = Size(windowWidth, windowHeight)
                    ),
                    cornerRadius = CornerRadius(cornerRadius.toPx())
                )
            )
        }

        // Draw dark overlay with cutout
        clipPath(scanWindowPath, clipOp = ClipOp.Difference) {
            drawRect(color = overlayColor)
        }

        // Draw frame border
        drawRoundRect(
            color = frameColor,
            topLeft = Offset(windowLeft, windowTop),
            size = Size(windowWidth, windowHeight),
            cornerRadius = CornerRadius(cornerRadius.toPx()),
            style = Stroke(width = 3.dp.toPx())
        )

        // Draw corner accents for better visibility
        val accentLength = 30.dp.toPx()
        val accentWidth = 4.dp.toPx()

        // Top-left corner
        drawLine(
            color = frameColor,
            start = Offset(windowLeft, windowTop + cornerRadius.toPx()),
            end = Offset(windowLeft, windowTop + cornerRadius.toPx() + accentLength),
            strokeWidth = accentWidth
        )
        drawLine(
            color = frameColor,
            start = Offset(windowLeft + cornerRadius.toPx(), windowTop),
            end = Offset(windowLeft + cornerRadius.toPx() + accentLength, windowTop),
            strokeWidth = accentWidth
        )

        // Top-right corner
        drawLine(
            color = frameColor,
            start = Offset(windowLeft + windowWidth, windowTop + cornerRadius.toPx()),
            end = Offset(windowLeft + windowWidth, windowTop + cornerRadius.toPx() + accentLength),
            strokeWidth = accentWidth
        )
        drawLine(
            color = frameColor,
            start = Offset(windowLeft + windowWidth - cornerRadius.toPx(), windowTop),
            end = Offset(windowLeft + windowWidth - cornerRadius.toPx() - accentLength, windowTop),
            strokeWidth = accentWidth
        )

        // Bottom-left corner
        drawLine(
            color = frameColor,
            start = Offset(windowLeft, windowTop + windowHeight - cornerRadius.toPx()),
            end = Offset(windowLeft, windowTop + windowHeight - cornerRadius.toPx() - accentLength),
            strokeWidth = accentWidth
        )
        drawLine(
            color = frameColor,
            start = Offset(windowLeft + cornerRadius.toPx(), windowTop + windowHeight),
            end = Offset(windowLeft + cornerRadius.toPx() + accentLength, windowTop + windowHeight),
            strokeWidth = accentWidth
        )

        // Bottom-right corner
        drawLine(
            color = frameColor,
            start = Offset(windowLeft + windowWidth, windowTop + windowHeight - cornerRadius.toPx()),
            end = Offset(windowLeft + windowWidth, windowTop + windowHeight - cornerRadius.toPx() - accentLength),
            strokeWidth = accentWidth
        )
        drawLine(
            color = frameColor,
            start = Offset(windowLeft + windowWidth - cornerRadius.toPx(), windowTop + windowHeight),
            end = Offset(windowLeft + windowWidth - cornerRadius.toPx() - accentLength, windowTop + windowHeight),
            strokeWidth = accentWidth
        )
    }
}

private fun processImage(
    imageProxy: ImageProxy,
    textRecognizer: com.google.mlkit.vision.text.TextRecognizer,
    onTokenFound: (String) -> Unit,
    onProcessed: () -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val tokens = extractTokens(visionText.text)
                if (tokens.isNotEmpty()) {
                    Log.d(TAG, "Found token: ${tokens.first()}")
                    onTokenFound(tokens.first())
                }
                onProcessed()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
                onProcessed()
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
        onProcessed()
    }
}

/**
 * Extracts potential API tokens from recognized text.
 * Paperless-ngx tokens are 40 characters, alphanumeric (lowercase hex).
 */
private fun extractTokens(text: String): List<String> {
    // Remove whitespace and newlines for better matching
    val cleanText = text.replace("\\s+".toRegex(), "")

    // Pattern for Paperless-ngx API tokens: 40 hex characters
    val tokenPattern = "[a-fA-F0-9]{40}".toRegex()

    return tokenPattern.findAll(cleanText)
        .map { it.value.lowercase() }
        .distinct()
        .toList()
}
