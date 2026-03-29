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

// ─── Auto-scroll state ─────────────────────────────────────────────────────────────

class AutoScrollState {
    var isDragging: Boolean by mutableStateOf(false)
    private var zoneY: Float = 0f
    private var listHeightPx: Float = 0f

    fun update(
        y: Float,
        listTop: Float,
        listBottom: Float,
    ) {
        zoneY = y
        listHeightPx = listBottom - listTop
    }

    fun scrollDeltaForTick(): Float {
        val zone = listHeightPx * 0.15f
        return when {
            zoneY < zone -> -12f
            zoneY > listHeightPx - zone -> 12f
            else -> 0f
        }
    }
}

// ─── Private helpers ─────────────────────────────────────────────────────────────

private fun initialEndIndex(
    lyrics: List<SyncedLyricFrame>,
    fps: Int,
): Int {
    val targetFrames = fps * 60
    val idx = lyrics.indexOfFirst { it.frame >= targetFrames }
    return if (idx == -1) lyrics.lastIndex else idx
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

// ─── Main composable ──────────────────────────────────────────────────────────

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

    // ── Live (in-flight) indices ──────────────────────────────────────────────
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
            if (moveMode) {
                // End tracks start exactly in move mode — use the same startDragOffsetY
                val rangeSize = endLyricIndex - startLyricIndex
                liveStartIndex + rangeSize
            } else {
                val endDelta = computeDeltaItems(listState, endDragOffsetY)
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
}