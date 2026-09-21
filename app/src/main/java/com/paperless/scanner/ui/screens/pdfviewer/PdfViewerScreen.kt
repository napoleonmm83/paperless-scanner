package com.paperless.scanner.ui.screens.pdfviewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.paperless.scanner.R
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
// INTENTIONAL-UNTESTED: imports for the diagnostic-report offer; see the note at the
// state dispatch below.
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.paperless.scanner.util.DiagnosticReportSender
import java.io.File

@Composable
fun PdfViewerScreen(
    onNavigateBack: () -> Unit,
    viewModel: PdfViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Top App Bar
        TopBar(
            title = viewModel.documentTitle,
            currentPage = (uiState as? PdfViewerUiState.Viewing)?.currentPage ?: 0,
            totalPages = (uiState as? PdfViewerUiState.Viewing)?.totalPages ?: 0,
            isViewing = uiState is PdfViewerUiState.Viewing,
            isPdf = (uiState as? PdfViewerUiState.Viewing)?.isPdf ?: true,
            onNavigateBack = onNavigateBack,
            onShare = viewModel::shareDocument,
            onOpenExternal = viewModel::openInExternalApp
        )

        // Content
        when (val state = uiState) {
            is PdfViewerUiState.Idle -> {
                // Should not happen, download starts immediately
            }
            is PdfViewerUiState.Downloading -> {
                DownloadingView(progress = state.progress)
            }
            // INTENTIONAL-UNTESTED: this file has no Compose-UI test harness (Roborazzi
            // is deferred as issue #391), so nothing here can be pinned from a unit
            // test. Two things make that acceptable rather than convenient:
            //
            //  - The behaviour being replaced is `e.printStackTrace()` plus a progress
            //    indicator that never stops. There is no old behaviour to preserve —
            //    a pin test would assert that nothing happens.
            //  - Everything the new callbacks DO lives in PdfViewerViewModel and is
            //    pinned there (onRenderFailure sets the error state, reports the
            //    exception and emits the event). What stays untested is only the wiring.
            is PdfViewerUiState.Viewing -> {
                if (state.isPdf) {
                    PdfView(
                        pdfFile = state.pdfFile,
                        onPageChanged = { page, total ->
                            viewModel.updatePageInfo(page, total)
                        },
                        onRenderFailure = viewModel::onRenderFailure,
                        onPageRenderFailure = viewModel::onPageRenderFailure
                    )
                } else {
                    ImageView(
                        file = state.pdfFile,
                        onRenderFailure = viewModel::onRenderFailure
                    )
                }
            }
            is PdfViewerUiState.Error -> {
                // INTENTIONAL-UNTESTED: see the note at the state dispatch above. The
                // decision of WHETHER to offer the report is pinned in
                // PdfViewerViewModelTest; only the wiring lives here.
                val context = LocalContext.current
                val coroutineScope = rememberCoroutineScope()
                ErrorView(
                    message = state.message,
                    onRetry = viewModel::downloadDocument,
                    onOpenExternal = viewModel::openInExternalApp.takeIf { state.canOpenExternally },
                    onSendReport = if (state.canSendReport) {
                        {
                            // INTENTIONAL-UNTESTED: see the note at the state dispatch
                            // above — no Compose-UI harness exists here (issue #391).
                            // Which Result each path produces IS pinned, in
                            // DiagnosticReportSender's own outcomes; only the choice of
                            // toast lives here.
                            // INTENTIONAL-UNTESTED: no Compose-UI harness here (#391);
                            // the callback became a suspend call, the toast choice below
                            // is unchanged.
                            coroutineScope.launch {
                                val result = viewModel.sendDiagnosticReport()
                                // A device with no mail app and no share target must not
                                // be left wondering whether the tap registered — and the
                                // two silent outcomes say DIFFERENT things, so they get
                                // different messages. Claiming a clipboard copy that did
                                // not happen sends the user looking for a report that is
                                // not there.
                                val message = when (result) {
                                    DiagnosticReportSender.Result.COPIED_TO_CLIPBOARD ->
                                        R.string.diagnostic_report_copied
                                    DiagnosticReportSender.Result.NO_TARGET ->
                                        R.string.diagnostic_report_no_target
                                    else -> null
                                }
                                message?.let {
                                    Toast.makeText(
                                        context,
                                        context.getString(it),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    currentPage: Int,
    totalPages: Int,
    isViewing: Boolean,
    isPdf: Boolean,
    onNavigateBack: () -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.pdf_viewer_back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isViewing && isPdf && totalPages > 0) {
                Text(
                    text = stringResource(R.string.pdf_viewer_page_info, currentPage + 1, totalPages),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isViewing) {
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.pdf_viewer_share),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onOpenExternal) {
                Icon(
                    imageVector = Icons.Filled.OpenInBrowser,
                    contentDescription = stringResource(R.string.pdf_viewer_open_external),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun DownloadingView(progress: Float) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.padding(16.dp))
            Text(
                text = stringResource(R.string.pdf_viewer_downloading),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.padding(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.width(200.dp)
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PdfView(
    pdfFile: File,
    onPageChanged: (Int, Int) -> Unit,
    // Document-scoped: the file could not be opened at all, so no page is readable.
    onRenderFailure: (Throwable) -> Unit,
    // Page-scoped: one page failed while the rest of the document stays readable.
    onPageRenderFailure: (Int, Throwable) -> Unit
) {
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var totalPages by remember { mutableStateOf(0) }

    // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
    //
    // PdfRenderer allows exactly ONE open page at a time. Before API 35 — which is
    // nearly every device this app runs on, minSdk is 26 — both openPage() and close()
    // begin with throwIfPageOpened() and raise IllegalStateException("Current page not
    // closed") otherwise. The pager below pre-renders neighbours
    // (beyondViewportPageCount = 1), so two or three PdfPage composables start their
    // LaunchedEffect in the same frame and race on openPage from IO threads.
    //
    // The race predates this change; its CONSEQUENCE did not. It used to end in
    // printStackTrace and one page that never stopped loading. Now the catch reports,
    // so without this lock the loser of the race would tear down the whole document,
    // file a non-fatal, and fill the very telemetry this change exists to make usable
    // with noise from an ordinary page turn. Serialising the access removes the cause
    // rather than muting the symptom.
    val pageMutex = remember { Mutex() }

    // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
    //
    // Set BEFORE close() in onDispose and checked INSIDE the lock, because the lock
    // alone does not order teardown against rendering: a render that was waiting for the
    // mutex can acquire it after the renderer has already been closed, and then touches
    // a dead handle. Checking a flag the disposer set first turns that into a no-op.
    // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
    // Keyed on pdfFile like the effect below. A bare `remember` would keep the flag at
    // true after a key change disposed the old renderer — every page would then return
    // from the lock immediately and spin forever. No path reaches that today (a retry
    // goes through Downloading, which removes PdfView), so this is a latch that cannot
    // stick rather than a fix for a live defect.
    val rendererClosed = remember(pdfFile) { java.util.concurrent.atomic.AtomicBoolean(false) }

    // Initialize PDF renderer
    DisposableEffect(pdfFile) {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(
                pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = PdfRenderer(fileDescriptor)
            pdfRenderer = renderer
            totalPages = renderer.pageCount
        } catch (e: Exception) {
            // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
            //
            // This used to be `e.printStackTrace()` alone. PdfRenderer throws here for
            // an encrypted PDF, a truncated download, a zero-byte file, and for a server
            // error page saved under a .pdf name — and the screen then fell through to
            // the `else` branch below and showed its progress indicator indefinitely.
            // No message, no retry, no report: the one shape of failure a user cannot
            // even describe to support.
            onRenderFailure(e)
        }

        onDispose {
            // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
            //
            // runCatching, and it is not defensive padding. Before API 35, close() calls
            // throwIfPageOpened() and throws IllegalStateException if a page is still
            // open. A render in flight when the screen goes away is exactly that case,
            // and the throw would land uncaught on the main thread. pageMutex below
            // makes it rare; this makes it harmless. Teardown of a screen the user has
            // already left is nothing to report.
            //
            // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
            // The flag is raised FIRST so a render still waiting on the mutex sees a
            // closed renderer and does nothing, instead of acquiring the lock after
            // teardown and reaching into a dead handle. The lock alone cannot order
            // these two: a waiter may be granted it at any point after close().
            rendererClosed.set(true)
            runCatching { pdfRenderer?.close() }
        }
    }

    // Only create pager when we know the page count
    if (pdfRenderer != null && totalPages > 0) {
        val pagerState = rememberPagerState(pageCount = { totalPages })

        // Update page info when page changes
        LaunchedEffect(pagerState.currentPage) {
            onPageChanged(pagerState.currentPage, totalPages)
        }

        // Swipe hint for multi-page PDFs
        var showSwipeHint by remember { mutableStateOf(totalPages > 1) }
        LaunchedEffect(Unit) {
            if (totalPages > 1) {
                delay(3000) // Show for 3 seconds
                showSwipeHint = false
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1  // Pre-render 1 page on each side for smooth scrolling
            ) { pageIndex ->
                // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
                PdfPage(
                    pdfRenderer = pdfRenderer!!,
                    pageIndex = pageIndex,
                    pageMutex = pageMutex,
                    rendererClosed = rendererClosed,
                    onPageRenderFailure = onPageRenderFailure
                )
            }

            // Swipe hint overlay
            AnimatedVisibility(
                visible = showSwipeHint,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.cd_previous_page),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.pdf_viewer_swipe_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.cd_next_page),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Page indicator dots
            if (totalPages > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(totalPages) { index ->
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (index == pagerState.currentPage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                    },
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }
        }
    } else {
        // Loading indicator while PDF is being initialized
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun PdfPage(
    pdfRenderer: PdfRenderer,
    pageIndex: Int,
    // INTENTIONAL-UNTESTED: see the note at the state dispatch above; no Compose-UI test
    // harness exists here (issue #391). What the callback DOES is pinned in
    // PdfViewerViewModelTest; what stays untested is the wiring.
    //
    // pageMutex is shared with every sibling page — see the note at its declaration.
    // PdfRenderer permits one open page at a time, and the pager composes neighbours
    // concurrently.
    pageMutex: Mutex,
    rendererClosed: java.util.concurrent.atomic.AtomicBoolean,
    // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
    // Page-SCOPED, not the document callback. The pager pre-composes a neighbour on each
    // side, so this fires for pages the user has not reached; routing it into the
    // document callback replaced a readable 20-page document with a full-screen error
    // because page 5 had a corrupt content stream.
    onPageRenderFailure: (Int, Throwable) -> Unit
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var renderFailed by remember(pageIndex) { mutableStateOf(false) }
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }

    // Render page in background
    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
                // The lock spans open, render and close — a page must be closed before
                // the next one may be opened, so releasing earlier would not help.
                pageMutex.withLock {
                if (rendererClosed.get()) return@withLock
                pdfRenderer.openPage(pageIndex).use { page ->
                    // Calculate safe bitmap dimensions
                    // Reduced from 2x to 1.5x for better performance
                    val maxPixels = 50_000_000L // ~50MB for ARGB_8888 (4 bytes per pixel)
                    val pagePixels = page.width.toLong() * page.height.toLong()

                    // Calculate scale factor - prefer 1.5x but reduce if needed
                    val desiredScale = 1.5f
                    val scaledPixels = (pagePixels * desiredScale * desiredScale).toLong()

                    val finalScale = if (scaledPixels > maxPixels) {
                        // Calculate maximum possible scale
                        Math.sqrt(maxPixels.toDouble() / pagePixels.toDouble()).toFloat().coerceAtLeast(1f)
                    } else {
                        desiredScale
                    }

                    val renderWidth = (page.width * finalScale).toInt()
                    val renderHeight = (page.height * finalScale).toInt()

                    val renderBitmap = Bitmap.createBitmap(
                        renderWidth,
                        renderHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    // Fill with white background to ensure PDFs with transparent backgrounds are readable
                    val canvas = Canvas(renderBitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(
                        renderBitmap,
                        null,
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    bitmap = renderBitmap
                }
                } // INTENTIONAL-UNTESTED: closes pageMutex.withLock, see note above
            } catch (e: CancellationException) {
                // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
                //
                // MUST be rethrown, and MUST come before the Exception branch. Swiping to
                // the next page cancels this LaunchedEffect; the old `catch (e: Exception)`
                // swallowed that cancellation, breaking structured concurrency and — worse
                // — reporting an ordinary page turn as a render failure once the branch
                // below started reporting anything. Project rule
                // feedback_cancellation_exception_ordering.
                throw e
            } catch (e: Exception) {
                // This was `e.printStackTrace()`, and it is the SAME defect the opening
                // path was fixed for, one function further down: `bitmap` stayed null,
                // the else-branch below kept spinning its progress indicator, and nothing
                // was reported. A PDF whose header is valid and which PdfRenderer opens
                // happily can still fail on page zero — a corrupt content stream, a
                // degenerate page size, an encrypted page, or BitmapFactory returning
                // null instead of throwing (issue #402). The user's report for all of
                // them reads "the app hangs", which is the one description nobody can act
                // on.
                //
                // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
                // ensureActive() first: once this coroutine is cancelled, a call into a
                // torn-down renderer raises IllegalStateException rather than
                // CancellationException, so the catch above does not see it and an
                // ordinary page turn would be filed as a render failure. That noise would
                // land in exactly the telemetry this change exists to make readable.
                currentCoroutineContext().ensureActive()
                renderFailed = true
                onPageRenderFailure(pageIndex, e)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.White),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_viewer_pdf_page, pageIndex + 1),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else if (renderFailed) {
            // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
            // A page that cannot be rendered says so, on that page. The spinner below is
            // for a page still working; leaving a failed page on it was the endless
            // spinner this change set out to remove, and replacing the whole document
            // instead would take away the pages that still read fine.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = stringResource(R.string.cd_error),
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.padding(8.dp))
                Text(
                    text = stringResource(R.string.pdf_viewer_page_failed, pageIndex + 1),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Loading indicator while page is rendering
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ImageView(
    file: File,
    onRenderFailure: (Throwable) -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file)
                .crossfade(true)
                .build(),
            // INTENTIONAL-UNTESTED: see the note at the state dispatch above. There was
            // no error handler at all here, so a failed decode left an empty grey
            // surface with no message and nothing reported — the image-path twin of the
            // endless spinner in PdfView.
            onError = { onRenderFailure(it.result.throwable) },
            contentDescription = stringResource(R.string.pdf_viewer_document_image),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            offset = Offset.Zero
                        }
                    }
                },
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun ErrorView(
    message: String,
    onRetry: () -> Unit,
    // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
    // Null when the app cannot help further; non-null when the file arrived intact and
    // only this app cannot render it, which is a different situation for the user and
    // deserves a different offer than "try again".
    onOpenExternal: (() -> Unit)? = null,
    // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
    // Null unless the app genuinely does not know what went wrong. Offering this on
    // "check your internet connection" would collect reports that teach us nothing and
    // train the user to ignore the button by the time it matters.
    onSendReport: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // The messages carry a cause now instead of two words, so they wrap. Without
            // a gutter and centre alignment they ran edge to edge on a phone.
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = stringResource(R.string.cd_error),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.padding(16.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.padding(16.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.pdf_viewer_retry),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (onOpenExternal != null) {
                TextButton(onClick = onOpenExternal) {
                    Text(text = stringResource(R.string.pdf_viewer_open_external))
                }
            }
            if (onSendReport != null) {
                // INTENTIONAL-UNTESTED: see the note at the state dispatch above.
                TextButton(onClick = onSendReport) {
                    Text(text = stringResource(R.string.diagnostic_report_send))
                }
            }
        }
    }
}
