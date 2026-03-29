package com.tejpratapsingh.lyricsmaker.presentation.compose

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.tejpratapsingh.lyricsmaker.presentation.viewmodel.LyricsViewModel
import com.tejpratapsingh.lyricsmaker.domain.model.SyncedLyricFrame
import com.tejpratapsingh.lyricsmaker.util.provideCurrentConfig
import kotlin.math.roundToInt

// ─── Data models ────────────────────────────────────────────────────────────

sealed interface ListItem {
    object StartHandle : ListItem
    object EndHandle : ListItem
    data class LyricItem(val index: Int, val frame: SyncedLyricFrame) : ListItem
}

data class RangeSelection(val minIndex: Int, val maxIndex: Int)

// ─── Helpers ────────────────────────────────────────────────────────────────

private fun initialEndIndex(
    lyrics: List<SyncedLyricFrame>,
    fps: Int,
): Int {
    if (lyrics.isEmpty()) return 0
    val targetFrames = fps * 5
    val end = lyrics.indexOfFirst { it.frame >= targetFrames }
    return if (end == -1) lyrics.lastIndex else end
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
            if (moveMode) {  // FIX: removed erroneous '&& startDragOffsetY != 0f' guard
                // End tracks start exactly in move mode
                val rangeSize = endLyricIndex - startLyricIndex
                liveStartIndex + rangeSize
            } else {
                (endLyricIndex + endDelta).coerceIn(liveStartIndex, viewModel.lyrics.lastIndex)
            }
        }
    }
}