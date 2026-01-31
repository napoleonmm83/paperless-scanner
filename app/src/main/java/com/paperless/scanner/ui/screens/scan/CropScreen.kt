package com.paperless.scanner.ui.screens.scan

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.paperless.scanner.R
import kotlin.math.abs

enum class DragHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    LEFT_EDGE,
    RIGHT_EDGE,
    TOP_EDGE,
    BOTTOM_EDGE
}

data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun toRect(): Rect = Rect(left, top, right, bottom)

    companion object {
        fun fromPercent(
            leftPercent: Float,
            topPercent: Float,
            rightPercent: Float,
            bottomPercent: Float,
            containerSize: Size
        ): CropRect {
            return CropRect(
                left = containerSize.width * leftPercent,
                top = containerSize.height * topPercent,
                right = containerSize.width * rightPercent,
                bottom = containerSize.height * bottomPercent
            )
        }
    }
}

/**
 * Crop screen for image cropping with draggable handles.
 *
 * User can drag the 4 corners and 4 edges to adjust the crop area.
 * Grid overlay (3x3) helps with visual alignment.
 *
 * @param uri Image URI to crop
 * @param rotation Current rotation of the image
 * @param onDismiss Called when user cancels
 * @param onCropApply Called when user applies crop with the crop rectangle (normalized 0-1)
 */
@Composable
fun CropScreen(
    uri: Uri,
    rotation: Int,
    onDismiss: () -> Unit,
    onCropApply: (CropRect) -> Unit
) {
    var imageSize by remember { mutableStateOf(0f to 0f) }

    // Crop rectangle (in pixels, relative to displayed image size)
    var cropRect by remember {
        mutableStateOf(CropRect(0f, 0f, 0f, 0f))
    }

    // Initialize crop rect to 80% of image (centered)
    LaunchedEffect(imageSize) {
        val (width, height) = imageSize
        if (width > 0 && height > 0 && cropRect.width == 0f) {
            val margin = 0.1f
            cropRect = CropRect(
                left = width * margin,
                top = height * margin,
                right = width * (1 - margin),
                bottom = height * (1 - margin)
            )
        }
    }

    fun resetCrop() {
        val (width, height) = imageSize
        if (width > 0 && height > 0) {
            val margin = 0.1f
            cropRect = CropRect(
                left = width * margin,
                top = height * margin,
                right = width * (1 - margin),
                bottom = height * (1 - margin)
            )
        }
    }

    fun applyCrop() {
        val (width, height) = imageSize
        if (width > 0 && height > 0) {
            // Normalize to 0-1 range
            val normalized = CropRect(
                left = cropRect.left / width,
                top = cropRect.top / height,
                right = cropRect.right / width,
                bottom = cropRect.bottom / height
            )
            onCropApply(normalized)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top toolbar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.scan_crop_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Title
                Text(
                    text = stringResource(R.string.scan_crop_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                // Reset button
                IconButton(
                    onClick = { resetCrop() },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.scan_crop_reset),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Apply button
                IconButton(
                    onClick = { applyCrop() },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.scan_crop_apply),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            // Hint text
            Text(
                text = stringResource(R.string.scan_crop_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // Image with crop overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CropImageWithOverlay(
                    uri = uri,
                    rotation = rotation,
                    cropRect = cropRect,
                    onCropRectChange = { cropRect = it },
                    onImageSizeChange = { width, height -> imageSize = width to height }
                )
            }
        }
    }
}

@Composable
private fun CropImageWithOverlay(
    uri: Uri,
    rotation: Int,
    cropRect: CropRect,
    onCropRectChange: (CropRect) -> Unit,
    onImageSizeChange: (Float, Float) -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val handleRadius = with(LocalDensity.current) { 12.dp.toPx() }
    val touchRadius = with(LocalDensity.current) { 24.dp.toPx() }

    // Keep updated reference to cropRect without restarting pointerInput
    val currentCropRect by rememberUpdatedState(cropRect)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                containerSize = coordinates.size
                val width = coordinates.size.width.toFloat()
                val height = coordinates.size.height.toFloat()
                onImageSizeChange(width, height)
            }
    ) {
        // Image
        AsyncImage(
            model = uri,
            contentDescription = stringResource(R.string.scan_crop_title),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )

        // Crop overlay with draggable handles
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val touchX = down.position.x
                        val touchY = down.position.y
                        val rect = currentCropRect

                        // Determine which handle/edge was touched ONCE at start
                        val draggedHandle = when {
                            // Corner handles (priority)
                            abs(touchX - rect.left) < touchRadius && abs(touchY - rect.top) < touchRadius -> DragHandle.TOP_LEFT
                            abs(touchX - rect.right) < touchRadius && abs(touchY - rect.top) < touchRadius -> DragHandle.TOP_RIGHT
                            abs(touchX - rect.left) < touchRadius && abs(touchY - rect.bottom) < touchRadius -> DragHandle.BOTTOM_LEFT
                            abs(touchX - rect.right) < touchRadius && abs(touchY - rect.bottom) < touchRadius -> DragHandle.BOTTOM_RIGHT
                            // Edge handles
                            abs(touchX - rect.left) < touchRadius && touchY > rect.top && touchY < rect.bottom -> DragHandle.LEFT_EDGE
                            abs(touchX - rect.right) < touchRadius && touchY > rect.top && touchY < rect.bottom -> DragHandle.RIGHT_EDGE
                            abs(touchY - rect.top) < touchRadius && touchX > rect.left && touchX < rect.right -> DragHandle.TOP_EDGE
                            abs(touchY - rect.bottom) < touchRadius && touchX > rect.left && touchX < rect.right -> DragHandle.BOTTOM_EDGE
                            else -> null
                        }

                        if (draggedHandle != null) {
                            down.consume()
                            drag(down.id) { change ->
                                change.consume()
                                val dragAmount = change.position - change.previousPosition

                                val rect = currentCropRect
                                val minSize = 100f
                                val width = containerSize.width.toFloat()
                                val height = containerSize.height.toFloat()

                                val newRect = when (draggedHandle) {
                                    DragHandle.TOP_LEFT -> rect.copy(
                                        left = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSize),
                                        top = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSize)
                                    )
                                    DragHandle.TOP_RIGHT -> rect.copy(
                                        right = (rect.right + dragAmount.x).coerceIn(rect.left + minSize, width),
                                        top = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSize)
                                    )
                                    DragHandle.BOTTOM_LEFT -> rect.copy(
                                        left = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSize),
                                        bottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSize, height)
                                    )
                                    DragHandle.BOTTOM_RIGHT -> rect.copy(
                                        right = (rect.right + dragAmount.x).coerceIn(rect.left + minSize, width),
                                        bottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSize, height)
                                    )
                                    DragHandle.LEFT_EDGE -> rect.copy(
                                        left = (rect.left + dragAmount.x).coerceIn(0f, rect.right - minSize)
                                    )
                                    DragHandle.RIGHT_EDGE -> rect.copy(
                                        right = (rect.right + dragAmount.x).coerceIn(rect.left + minSize, width)
                                    )
                                    DragHandle.TOP_EDGE -> rect.copy(
                                        top = (rect.top + dragAmount.y).coerceIn(0f, rect.bottom - minSize)
                                    )
                                    DragHandle.BOTTOM_EDGE -> rect.copy(
                                        bottom = (rect.bottom + dragAmount.y).coerceIn(rect.top + minSize, height)
                                    )
                                }

                                onCropRectChange(newRect)
                            }
                        }
                    }
                }
        ) {
            val rect = cropRect.toRect()

            // Darken outside crop area
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset.Zero,
                size = Size(size.width, rect.top)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(0f, rect.bottom),
                size = Size(size.width, size.height - rect.bottom)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(0f, rect.top),
                size = Size(rect.left, rect.height)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.5f),
                topLeft = Offset(rect.right, rect.top),
                size = Size(size.width - rect.right, rect.height)
            )

            // Crop rectangle border
            drawRect(
                color = Color.White,
                topLeft = rect.topLeft,
                size = rect.size,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Grid lines (3x3)
            val gridColor = Color.White.copy(alpha = 0.5f)
            val gridStyle = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )

            // Vertical lines
            drawLine(
                color = gridColor,
                start = Offset(rect.left + rect.width / 3, rect.top),
                end = Offset(rect.left + rect.width / 3, rect.bottom),
                strokeWidth = gridStyle.width
            )
            drawLine(
                color = gridColor,
                start = Offset(rect.left + 2 * rect.width / 3, rect.top),
                end = Offset(rect.left + 2 * rect.width / 3, rect.bottom),
                strokeWidth = gridStyle.width
            )

            // Horizontal lines
            drawLine(
                color = gridColor,
                start = Offset(rect.left, rect.top + rect.height / 3),
                end = Offset(rect.right, rect.top + rect.height / 3),
                strokeWidth = gridStyle.width
            )
            drawLine(
                color = gridColor,
                start = Offset(rect.left, rect.top + 2 * rect.height / 3),
                end = Offset(rect.right, rect.top + 2 * rect.height / 3),
                strokeWidth = gridStyle.width
            )

            // Corner handles
            val handleColor = Color.White
            val cornerHandles = listOf(
                Offset(rect.left, rect.top),        // Top left
                Offset(rect.right, rect.top),       // Top right
                Offset(rect.left, rect.bottom),     // Bottom left
                Offset(rect.right, rect.bottom)     // Bottom right
            )

            cornerHandles.forEach { handlePos ->
                drawCircle(
                    color = handleColor,
                    radius = handleRadius,
                    center = handlePos
                )
                drawCircle(
                    color = Color.Black,
                    radius = handleRadius / 2,
                    center = handlePos
                )
            }

            // Edge handles (midpoints)
            val edgeHandles = listOf(
                Offset((rect.left + rect.right) / 2, rect.top),      // Top edge
                Offset((rect.left + rect.right) / 2, rect.bottom),   // Bottom edge
                Offset(rect.left, (rect.top + rect.bottom) / 2),     // Left edge
                Offset(rect.right, (rect.top + rect.bottom) / 2)     // Right edge
            )

            edgeHandles.forEach { handlePos ->
                drawCircle(
                    color = handleColor,
                    radius = handleRadius * 0.7f,
                    center = handlePos
                )
                drawCircle(
                    color = Color.Black,
                    radius = handleRadius * 0.35f,
                    center = handlePos
                )
            }
        }
    }
}
