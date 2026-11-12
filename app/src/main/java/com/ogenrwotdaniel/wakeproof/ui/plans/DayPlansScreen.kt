package com.ogenrwotdaniel.wakeproof.ui.plans

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.plan.DayPlanPhoto
import com.ogenrwotdaniel.wakeproof.plan.loadDayPlanBitmap
import com.ogenrwotdaniel.wakeproof.plan.maxPan
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val THUMBNAIL_SIDE = 480
private const val FULL_SIDE = 2400
private const val MAX_ZOOM = 5f

/**
 * The library of day-plan photos: one page per morning, newest first, each
 * opened full screen to read (pinch to zoom). They live on this device only.
 */
@Composable
fun DayPlansScreen(
    onBack: () -> Unit,
    onAddPlan: () -> Unit,
    onReviewDay: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DayPlansViewModel = hiltViewModel()
) {
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val reviews by viewModel.reviews.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<DayPlanPhoto?>(null) }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Day plans", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "The page you write each morning, photographed at the day-plan " +
                            "alarm. Kept on this device only, for the latest 90 days of plans.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onAddPlan, modifier = Modifier.fillMaxWidth()) {
                        Text("Photograph a plan now")
                    }
                    OutlinedButton(onClick = onReviewDay, modifier = Modifier.fillMaxWidth()) {
                        Text("Review today and plan tomorrow")
                    }
                    if (plans?.isEmpty() == true) {
                        Text(
                            text = "No plans yet. The first one lands here after the next day-plan alarm.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            items(plans.orEmpty(), key = { it.file.name }) { photo ->
                PlanThumbnail(
                    photo = photo,
                    done = reviews[photo.epochDay]?.done?.label,
                    onOpen = { open = photo }
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Back")
                }
            }
        }
    }

    open?.let { photo ->
        PlanViewer(
            photo = photo,
            onClose = { open = null },
            onDelete = {
                viewModel.delete(photo)
                open = null
            }
        )
    }
}

/** "Thu, 12 Nov 2026 · 08:21", in the phone's own date and clock style. */
@Composable
private fun planCaption(photo: DayPlanPhoto): String {
    val context = LocalContext.current
    val date = LocalDate.ofEpochDay(photo.epochDay)
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val time = Instant.ofEpochMilli(photo.takenAtMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "$date · ${formatAlarmTime(context, time.hour, time.minute)}"
}

@Composable
private fun PlanThumbnail(photo: DayPlanPhoto, done: String?, onOpen: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photo.file) {
        value = loadDayPlanBitmap(photo.file, THUMBNAIL_SIDE)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open this plan", onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Text(
            text = planCaption(photo),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // From that evening's review, when one was written.
        done?.let {
            Text(
                text = "Done: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** One plan, full screen: pinch to zoom, drag to move. */
@Composable
private fun PlanViewer(photo: DayPlanPhoto, onClose: () -> Unit, onDelete: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, photo.file) {
        value = loadDayPlanBitmap(photo.file, FULL_SIDE)
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewer by remember { mutableStateOf(IntSize.Zero) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.dp))
                    .onSizeChanged { viewer = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                            // Held to the page: dragged freely it could be moved
                            // off the screen with no way to find it again.
                            val moved = offset + pan
                            val x = maxPan(zoom, viewer.width)
                            val y = maxPan(zoom, viewer.height)
                            offset = Offset(moved.x.coerceIn(-x, x), moved.y.coerceIn(-y, y))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Day plan, ${planCaption(photo)}",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = offset.x
                                translationY = offset.y
                            },
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Text(
                text = planCaption(photo),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = { confirmingDelete = true }, modifier = Modifier.weight(1f)) {
                    Text("Delete")
                }
                Button(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this plan?") },
            text = { Text("The photo is removed from this device for good.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Keep") } }
        )
    }
}
