package com.enya.pdfreader.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.enya.pdfreader.R
import kotlin.math.max
import kotlin.math.min

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val HI_RES_THRESHOLD = 1.6f
private const val HI_RES_FACTOR = 2.5f
private const val HI_RES_MAX_WIDTH = 3500

private val InvertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

/**
 * One PDF page, fitted to the viewport, with pinch-to-zoom, drag-to-pan and double-tap zoom.
 * Single-finger drags are left to the pager while not zoomed so page swiping keeps working.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PdfPageView(
    viewModel: ReaderViewModel,
    pageIndex: Int,
    nightMode: Boolean,
    isCurrent: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportW = constraints.maxWidth.coerceAtLeast(1)
        val viewportH = constraints.maxHeight.coerceAtLeast(1)

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var failed by remember { mutableStateOf(false) }

        val hiRes = scale > HI_RES_THRESHOLD
        val renderWidth = if (hiRes) {
            (viewportW * HI_RES_FACTOR).toInt().coerceAtMost(HI_RES_MAX_WIDTH)
        } else {
            viewportW
        }

        LaunchedEffect(pageIndex, renderWidth) {
            try {
                bitmap = viewModel.render(pageIndex, renderWidth)
                failed = false
            } catch (e: Exception) {
                if (bitmap == null) failed = true
            }
        }

        // Reset zoom once the user has swiped away from this page.
        LaunchedEffect(isCurrent) {
            if (!isCurrent) {
                scale = 1f
                offset = Offset.Zero
            }
        }

        fun clampOffset(candidate: Offset, atScale: Float): Offset {
            val bmp = bitmap ?: return Offset.Zero
            val fit = min(viewportW / bmp.width.toFloat(), viewportH / bmp.height.toFloat())
            val shownW = bmp.width * fit * atScale
            val shownH = bmp.height * fit * atScale
            val maxX = max(0f, (shownW - viewportW) / 2f)
            val maxY = max(0f, (shownH - viewportH) / 2f)
            return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
            scale = newScale
            offset = if (newScale > 1f) clampOffset(offset + panChange, newScale) else Offset.Zero
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val center = Offset(viewportW / 2f, viewportH / 2f)
                                offset = clampOffset((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE)
                                scale = DOUBLE_TAP_SCALE
                            }
                        },
                    )
                }
                .transformable(state = transformState, canPan = { scale > 1f }),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            when {
                bmp != null -> {
                    val image = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        colorFilter = if (nightMode) InvertFilter else null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
                failed -> Text(
                    text = stringResource(R.string.error_page),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> CircularProgressIndicator(color = Color.White)
            }
        }
    }
}
