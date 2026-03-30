package com.tejpratapsingh.lyricsmaker.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tejpratapsingh.lyricsmaker.data.lrc.SyncedLyricFrame
import com.tejpratapsingh.lyricsmaker.presentation.viewmodel.LyricsViewModel
import com.tejpratapsingh.motionlib.core.provideCurrentConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ─── Domain model ─────────────────────────────────────────────────────────────

data class RangeSelection(
    val start: Int,
    val end: Int,
) {
    val minIndex get() = min(start, end)
    val maxIndex get() = max(start, end)

    fun contains(index: Int) = index in minIndex..maxIndex
}

// ─── Display list item types ──────────────────────────────────────────────────

private sealed interface ListItem {
    data class LyricItem(
        val index: Int,
        val frame: SyncedLyricFrame,
    ) : ListItem

    data object StartHandle : ListItem

    data object EndHandle : ListItem
}

// ─── Auto-scroll state ────────────────────────────────────────────────────────

/**
 * Shared mutable state that coordinates auto-scrolling between the drag handles
 * and the [LazyColumn].
 *
 * Each handle reports its pointer Y in root (screen) coordinates while a drag
 * is in progress. A [LaunchedEffect] in [SyncedLyricsSelector] wakes up whenever
 * [isDragging] becomes true, then ticks at ~60 fps and calls [scrollDeltaForTick]
 * to decide how far and which direction to scroll.
 */
class AutoScrollState {
    var isDragging by mutableStateOf(false)
    private var pointerY by mutableFloatStateOf(0f)
    private var containerTop by mutableFloatStateOf(0f)
    private var containerBottom by mutableFloatStateOf(0f)

    fun updatePointer(y: Float) {
        pointerY = y
    }

    fun updateContainer(top: Float, bottom: Float) {
        containerTop = top
        containerBottom = bottom
    }

    fun scrollDeltaForTick(): Float {
        val edgeZone = 80f
        val maxSpeed = 12f
        return when {
            pointerY < containerTop + edgeZone -> {
                val frac = 1f - ((pointerY - containerTop) / edgeZone).coerceIn(0f, 1f)
                -(frac * maxSpeed)
            }

            pointerY > containerBottom - edgeZone -> {
                val frac =
                    1f - ((containerBottom - pointerY) / edgeZone).coerceIn(0f, 1f)
                frac * maxSpeed
            }

            else -> 0f
        }
    }
}

// ─── Helpers ───────────────────────────────────────────────────────────────────

/** Compute the default end index: whichever lyric is closest to ~30 s from the start. */
private fun initialEndIndex(
    lyrics: List<SyncedLyricFrame>,
    fps: Int,
): Int {
    val targetFrames = 30 * fps
    if (lyrics.isEmpty()) return 0
    val startFrame = lyrics.first().frame
    return lyrics
        .indexOfLast { it.frame - startFrame <= targetFrames }
        .coerceAtLeast(0)
}

/** Pixel-drag offset → list-item-index delta using average visible item height. */
private fun computeDeltaItems(
    listState: LazyListState,
    dragOffsetY: Float,
): Int {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val avgItemHeight =
        if (visibleItems.isNotEmpty()) {
            visibleItems.sumOf { it.size } / visibleItems.size
        } else {
            56
        }
    return (dragOffsetY / avgItemHeight).roundToInt()
}

private fun formatDuration(
    frames: Int,
    fps: Int,
): String {
    val totalSecs = (frames / fps)
    val m = totalSecs / 60
    val s = totalSecs % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

// ─── Main composable ────────────────────────────────────────────────────────

@Composable
fun SyncedLyricsSelector(
    viewModel: LyricsViewModel,
    modifier: Modifier = Modifier,
    onSelectionChanged: (List<SyncedLyricFrame>) -> Unit = {},
    onFinalize: (List<SyncedLyricFrame>) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val fps = provideCurrentConfig().fps
    val autoScroll = remember { AutoScrollState() }

    // Committed indices — only written on drag-end or reset.
    var startLyricIndex by remember { mutableIntStateOf(0) }
    var endLyricIndex by remember { mutableIntStateOf(initialEndIndex(viewModel.lyrics, fps)) }

    // Live pixel offsets, accumulated during an active drag.
    // Kept separate from the committed indices so a cancelled drag rolls back cleanly.
    var startDragOffsetY by remember { mutableFloatStateOf(0f) }
    var endDragOffsetY by remember { mutableFloatStateOf(0f) }

    var moveMode by remember { mutableStateOf(false) }

    // ── Live (in-flight) indices ─────────────────────────────────────────────
    // Recomputed on every drag event — these drive everything visible in the UI.
    val liveStartIndex by remember {
        derivedStateOf {
            val delta = computeDeltaItems(listState, startDragOffsetY)
            if (moveMode) {
                // In move mode the end moves with start, so clamp against (lastIndex - rangeSize)
                val rangeSize = endLyricIndex - startLyricIndex
                (startLyricIndex + delta).coerceIn(0, viewModel.lyrics.lastIndex - rangeSize)
            } else {
                (startLyricIndex + delta).coerceIn(0, endLyricIndex)
            }
        }
    }
    val liveEndIndex by remember {
        derivedStateOf {
            val endDelta = computeDeltaItems(listState, endDragOffsetY)
            if (moveMode) {
                // End tracks start exactly in move mode
                val rangeSize = endLyricIndex - startLyricIndex
                liveStartIndex + rangeSize
            } else {
                (endLyricIndex + endDelta).coerceIn(liveStartIndex, viewModel.lyrics.lastIndex)
            }
        }
    }

    val selection by remember { derivedStateOf { RangeSelection(liveStartIndex, liveEndIndex) } }
    val selected by remember {
        derivedStateOf {
            if (viewModel.lyrics.isEmpty()) {
                emptyList()
            } else {
                viewModel.lyrics.subList(selection.minIndex, selection.maxIndex + 1)
            }
        }
    }
    val selectedDurationLabel by remember {
        derivedStateOf {
            if (selected.size < 2) {
                "0:00"
            } else {
                formatDuration(selected.last().frame - selected.first().frame, fps)
            }
        }
    }
    val displayItems: List<ListItem> by remember {
        derivedStateOf {
            buildList {
                val lo = selection.minIndex
                val hi = selection.maxIndex
                viewModel.lyrics.forEachIndexed { i, frame ->
                    if (i == lo) add(ListItem.StartHandle)
                    add(ListItem.LyricItem(i, frame))
                    if (i == hi) add(ListItem.EndHandle)
                }
            }
        }
    }

    // ── Auto-scroll loop ──────────────────────────────────────────────────────
    LaunchedEffect(autoScroll.isDragging) {
        if (!autoScroll.isDragging) return@LaunchedEffect
        while (isActive) {
            val delta = autoScroll.scrollDeltaForTick()
            if (delta != 0f) listState.scrollBy(delta)
            delay(16L)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Summary bar ───────────────────────────────────────────────────────
        if (viewModel.lyrics.isNotEmpty()) {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Left group: mode toggle
                    IconButton(
                        onClick = { moveMode = !moveMode },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor =
                                    if (moveMode) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                            ),
                    ) {
                        Icon(
                            imageVector = if (moveMode) Icons.Default.OpenWith else Icons.Default.LockOpen,
                            contentDescription = if (moveMode) "Move mode" else "Independent handles",
                        )
                    }

                    // Centre: duration label
                    Text(
                        text = selectedDurationLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    // Right group: finalise
                    TextButton(onClick = { onFinalize(selected) }) {
                        Text("Done")
                    }
                }
            }
        }

        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        autoScroll.updateContainer(pos.y, pos.y + coords.size.height)
                    },
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(
                    items = displayItems,
                    key = { _, item ->
                        when (item) {
                            ListItem.StartHandle -> "start_handle"
                            ListItem.EndHandle -> "end_handle"
                            is ListItem.LyricItem -> "lyric_${item.index}"
                        }
                    },
                ) { _, item ->
                    when (item) {
                        ListItem.StartHandle ->
                            StartHandle(
                                autoScroll = autoScroll,
                                onDragChange = { dy -> startDragOffsetY += dy },
                                onDragEnd = {
                                    startLyricIndex = liveStartIndex
                                    if (moveMode) endLyricIndex = liveEndIndex
                                    startDragOffsetY = 0f
                                },
                            )

                        ListItem.EndHandle ->
                            EndHandle(
                                autoScroll = autoScroll,
                                onDragChange = { dy -> endDragOffsetY += dy },
                                onDragEnd = {
                                    endLyricIndex = liveEndIndex
                                    endDragOffsetY = 0f
                                },
                            )

                        is ListItem.LyricItem ->
                            LyricRow(
                                frame = item.frame,
                                isSelected = selection.contains(item.index),
                                fps = fps,
                            )
                    }
                }
            }
        }
    }

    LaunchedEffect(selected) {
        onSelectionChanged(selected)
    }
}

// ─── Item composables ───────────────────────────────────────────────────────

@Composable
private fun StartHandle(
    autoScroll: AutoScrollState,
    onDragChange: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { autoScroll.isDragging = true },
                        onDragEnd = {
                            autoScroll.isDragging = false
                            onDragEnd()
                        },
                        onDragCancel = {
                            autoScroll.isDragging = false
                            onDragEnd()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            autoScroll.updatePointer(change.position.y)
                            onDragChange(dragAmount.y)
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Start handle",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun EndHandle(
    autoScroll: AutoScrollState,
    onDragChange: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { autoScroll.isDragging = true },
                        onDragEnd = {
                            autoScroll.isDragging = false
                            onDragEnd()
                        },
                        onDragCancel = {
                            autoScroll.isDragging = false
                            onDragEnd()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            autoScroll.updatePointer(change.position.y)
                            onDragChange(dragAmount.y)
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "End handle",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun LyricRow(
    frame: SyncedLyricFrame,
    isSelected: Boolean,
    fps: Int,
) {
    val bgColor =
        if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val totalSecs = frame.frame / fps
        val m = totalSecs / 60
        val s = totalSecs % 60
        Text(
            text = "$m:${s.toString().padStart(2, '0')}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = frame.text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    HorizontalDivider(thickness = 0.5.dp)
}
