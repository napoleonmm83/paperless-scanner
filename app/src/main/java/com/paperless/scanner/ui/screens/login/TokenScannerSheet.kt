package com.paperless.scanner.ui.screens.login

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.paperless.scanner.R
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private const val TAG = "TokenScanner"
private const val STABILITY_DELAY_MS = 500L

@kotlin.OptIn(ExperimentalMaterial3Api::class)
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

    // Pre-load strings that will be used in callbacks
    val positionHintText = stringResource(R.string.token_scanner_position_hint)
    val capturingText = stringResource(R.string.token_scanner_capturing)
    val foundText = stringResource(R.string.token_scanner_found)
    val notFoundText = stringResource(R.string.token_scanner_not_found)
    val recognitionErrorText = stringResource(R.string.token_scanner_recognition_error)
    val captureFailedText = stringResource(R.string.token_scanner_capture_failed)
    val holdStillText = stringResource(R.string.token_scanner_hold_still)
    val textRecognizingText = stringResource(R.string.token_scanner_text_recognizing)

    var isProcessing by remember { mutableStateOf(false) }
    var foundToken by remember { mutableStateOf<String?>(null) }
    var scanStatus by remember { mutableStateOf(positionHintText) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Live detection state
    var liveTokenDetected by remember { mutableStateOf(false) }
    var detectedTokenCandidate by remember { mutableStateOf<String?>(null) }
    var detectionStartTime by remember { mutableLongStateOf(0L) }
    var shouldAutoCapture by remember { mutableStateOf(false) }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // Auto-capture when token is stable
    LaunchedEffect(shouldAutoCapture) {
        if (shouldAutoCapture && !isProcessing && foundToken == null) {
            Log.d(TAG, "Auto-capture triggered after stability period")
            isProcessing = true
            scanStatus = capturingText

            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    @OptIn(ExperimentalGetImage::class)
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        val mediaImage = imageProxy.image
                        if (mediaImage != null) {
                            val image = InputImage.fromMediaImage(
                                mediaImage,
                                imageProxy.imageInfo.rotationDegrees
                            )

                            textRecognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    val tokens = extractTokens(visionText.text)
                                    Log.d(TAG, "High-res OCR found tokens: $tokens")

                                    if (tokens.isNotEmpty()) {
                                        foundToken = tokens.first()
                                        scanStatus = foundText
                                        errorMessage = null
                                    } else {
                                        // Reset for another try
                                        errorMessage = notFoundText
                                        scanStatus = positionHintText
                                        liveTokenDetected = false
                                        detectedTokenCandidate = null
                                        shouldAutoCapture = false
                                    }
                                    isProcessing = false
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "High-res OCR failed", e)
                                    errorMessage = recognitionErrorText
                                    isProcessing = false
                                    liveTokenDetected = false
                                    shouldAutoCapture = false
                                }
                        }
                        imageProxy.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Auto-capture failed", exception)
                        errorMessage = captureFailedText
                        isProcessing = false
                        liveTokenDetected = false
                        shouldAutoCapture = false
                    }
                }
            )
        }
    }

    // Auto-dismiss after token found
    LaunchedEffect(foundToken) {
        foundToken?.let { token ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            delay(1000)
            onTokenFound(token)
        }
    }

    // Haptic feedback when token detected in live view
    LaunchedEffect(liveTokenDetected) {
        if (liveTokenDetected) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            textRecognizer.close()
        }
    }

    // ImageAnalysis for live token detection
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
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
                    text = stringResource(R.string.token_scanner_title),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.token_scanner_close))
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
                        text = stringResource(R.string.token_scanner_camera_required),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.token_scanner_camera_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.token_scanner_camera_allow))
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

                            // Set up live analysis for token detection
                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                processLiveFrame(
                                    imageProxy = imageProxy,
                                    textRecognizer = textRecognizer,
                                    onTokenDetected = { candidate ->
                                        if (!isProcessing && foundToken == null) {
                                            if (candidate != null) {
                                                if (detectedTokenCandidate == candidate) {
                                                    // Same token detected again - check stability
                                                    val elapsed = System.currentTimeMillis() - detectionStartTime
                                                    if (elapsed >= STABILITY_DELAY_MS && !shouldAutoCapture) {
                                                        Log.d(TAG, "Token stable for ${elapsed}ms, triggering capture")
                                                        shouldAutoCapture = true
                                                    }
                                                } else {
                                                    // New token candidate
                                                    detectedTokenCandidate = candidate
                                                    detectionStartTime = System.currentTimeMillis()
                                                    liveTokenDetected = true
                                                    scanStatus = holdStillText
                                                    errorMessage = null
                                                }
                                            } else {
                                                // No token in frame
                                                if (liveTokenDetected && !shouldAutoCapture) {
                                                    liveTokenDetected = false
                                                    detectedTokenCandidate = null
                                                    scanStatus = positionHintText
                                                }
                                            }
                                        }
                                    }
                                )
                            }

                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageCapture,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera binding failed", e)
                            }
                        }, ContextCompat.getMainExecutor(context))
                    }

                    // Focus Frame Overlay - green when token detected
                    ScannerOverlay(
                        isSuccess = foundToken != null,
                        isDetecting = liveTokenDetected && foundToken == null
                    )

                    // Processing overlay
                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }

                    // Status indicator
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-80).dp)
                            .background(
                                color = when {
                                    foundToken != null -> MaterialTheme.colorScheme.primaryContainer
                                    liveTokenDetected -> MaterialTheme.colorScheme.tertiaryContainer
                                    errorMessage != null -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
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
                                text = errorMessage ?: scanStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    foundToken != null -> MaterialTheme.colorScheme.onPrimaryContainer
                                    liveTokenDetected -> MaterialTheme.colorScheme.onTertiaryContainer
                                    errorMessage != null -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Manual capture button (fallback)
                IconButton(
                    onClick = {
                        isProcessing = true
                        errorMessage = null
                        scanStatus = textRecognizingText

                        imageCapture.takePicture(
                            cameraExecutor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                @OptIn(ExperimentalGetImage::class)
                                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val image = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees
                                        )

                                        textRecognizer.process(image)
                                            .addOnSuccessListener { visionText ->
                                                val tokens = extractTokens(visionText.text)
                                                Log.d(TAG, "Manual capture found tokens: $tokens")

                                                if (tokens.isNotEmpty()) {
                                                    foundToken = tokens.first()
                                                    scanStatus = foundText
                                                    errorMessage = null
                                                } else {
                                                    errorMessage = notFoundText
                                                    scanStatus = positionHintText
                                                }
                                                isProcessing = false
                                            }
                                            .addOnFailureListener { e ->
                                                Log.e(TAG, "Text recognition failed", e)
                                                errorMessage = recognitionErrorText
                                                isProcessing = false
                                            }
                                    }
                                    imageProxy.close()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    Log.e(TAG, "Capture failed", exception)
                                    errorMessage = captureFailedText
                                    isProcessing = false
                                }
                            }
                        )
                    },
                    enabled = !isProcessing && foundToken == null,
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            if (isProcessing || foundToken != null) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.token_scanner_take_photo),
                        tint = if (isProcessing || foundToken != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (liveTokenDetected) {
                        stringResource(R.string.token_scanner_hold_camera)
                    } else {
                        stringResource(R.string.token_scanner_auto_active)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Process live camera frame for quick token detection (low-res, fast).
 */
@OptIn(ExperimentalGetImage::class)
private fun processLiveFrame(
    imageProxy: ImageProxy,
    textRecognizer: com.google.mlkit.vision.text.TextRecognizer,
    onTokenDetected: (String?) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        textRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val tokens = extractTokensQuick(visionText.text)
                onTokenDetected(tokens.firstOrNull())
                imageProxy.close()
            }
            .addOnFailureListener {
                onTokenDetected(null)
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

@Composable
private fun ScannerOverlay(
    isSuccess: Boolean,
    isDetecting: Boolean = false
) {
    val overlayColor = Color.Black.copy(alpha = 0.6f)
    val frameColor = when {
        isSuccess -> Color(0xFF4CAF50) // Green - success
        isDetecting -> Color(0xFF2196F3) // Blue - detecting
        else -> Color.White // White - idle
    }
    val cornerRadius = 12.dp

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Calculate scan window dimensions (centered, 85% width, aspect ratio for token)
        val windowWidth = canvasWidth * 0.85f
        val windowHeight = windowWidth * 0.25f
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

        // Draw corner accents
        val accentLength = 30.dp.toPx()
        val accentWidth = 4.dp.toPx()

        // Top-left
        drawLine(frameColor, Offset(windowLeft, windowTop + cornerRadius.toPx()), Offset(windowLeft, windowTop + cornerRadius.toPx() + accentLength), accentWidth)
        drawLine(frameColor, Offset(windowLeft + cornerRadius.toPx(), windowTop), Offset(windowLeft + cornerRadius.toPx() + accentLength, windowTop), accentWidth)

        // Top-right
        drawLine(frameColor, Offset(windowLeft + windowWidth, windowTop + cornerRadius.toPx()), Offset(windowLeft + windowWidth, windowTop + cornerRadius.toPx() + accentLength), accentWidth)
        drawLine(frameColor, Offset(windowLeft + windowWidth - cornerRadius.toPx(), windowTop), Offset(windowLeft + windowWidth - cornerRadius.toPx() - accentLength, windowTop), accentWidth)

        // Bottom-left
        drawLine(frameColor, Offset(windowLeft, windowTop + windowHeight - cornerRadius.toPx()), Offset(windowLeft, windowTop + windowHeight - cornerRadius.toPx() - accentLength), accentWidth)
        drawLine(frameColor, Offset(windowLeft + cornerRadius.toPx(), windowTop + windowHeight), Offset(windowLeft + cornerRadius.toPx() + accentLength, windowTop + windowHeight), accentWidth)

        // Bottom-right
        drawLine(frameColor, Offset(windowLeft + windowWidth, windowTop + windowHeight - cornerRadius.toPx()), Offset(windowLeft + windowWidth, windowTop + windowHeight - cornerRadius.toPx() - accentLength), accentWidth)
        drawLine(frameColor, Offset(windowLeft + windowWidth - cornerRadius.toPx(), windowTop + windowHeight), Offset(windowLeft + windowWidth - cornerRadius.toPx() - accentLength, windowTop + windowHeight), accentWidth)
    }
}

/**
 * Quick token detection for live preview (less strict, faster).
 * Just checks if something token-like is visible.
 */
private fun extractTokensQuick(text: String): List<String> {
    val cleanText = text.replace("\\s+".toRegex(), "")

    // Look for any 40+ character hex-like sequence
    val pattern = "[a-fA-F0-9OoIlLsSBbZzGg]{38,45}".toRegex()
    return pattern.findAll(cleanText)
        .map { it.value }
        .filter { it.length >= 38 }
        .distinct()
        .toList()
}

/**
 * Extracts potential API tokens from recognized text (high-res, accurate).
 * Paperless-ngx tokens are 40 characters, lowercase hex.
 * Handles common OCR errors like O/0, l/1/I, S/5, B/8, Z/2.
 */
private fun extractTokens(text: String): List<String> {
    Log.d(TAG, "Raw OCR text: $text")

    // Remove whitespace and newlines
    val cleanText = text.replace("\\s+".toRegex(), "")

    // First try exact hex match
    val exactPattern = "[a-fA-F0-9]{40}".toRegex()
    val exactMatches = exactPattern.findAll(cleanText)
        .map { it.value.lowercase() }
        .distinct()
        .toList()

    if (exactMatches.isNotEmpty()) {
        Log.d(TAG, "Found exact token match: ${exactMatches.first()}")
        return exactMatches
    }

    // Try to find 40-char sequences and fix OCR errors
    val fuzzyPattern = "[a-fA-F0-9OoIlLsSBbZzGg]{40,42}".toRegex()
    val fuzzyMatches = fuzzyPattern.findAll(cleanText)
        .map { fixOcrErrors(it.value) }
        .filter { it.length == 40 && it.matches("[a-f0-9]{40}".toRegex()) }
        .distinct()
        .toList()

    if (fuzzyMatches.isNotEmpty()) {
        Log.d(TAG, "Found token after OCR correction: ${fuzzyMatches.first()}")
    } else {
        Log.d(TAG, "No token found in text (length: ${cleanText.length})")
    }

    return fuzzyMatches
}

/**
 * Fixes common OCR errors in hex strings.
 */
private fun fixOcrErrors(text: String): String {
    return text.lowercase()
        .replace('o', '0')
        .replace('i', '1')
        .replace('l', '1')
        .replace('s', '5')
        .replace('z', '2')
        .replace('g', '9')
}
