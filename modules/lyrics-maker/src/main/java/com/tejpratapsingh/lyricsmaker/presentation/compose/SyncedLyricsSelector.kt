package com.tejpratapsingh.lyricsmaker.presentation.compose

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.isActive
import com.tejpratapsingh.lyricsmaker.domain.model.SyncedLyricFrame
import com.tejpratapsingh.lyricsmaker.presentation.viewmodel.LyricsViewModel
import com.tejpratapsingh.lyricsmaker.presentation.compose.util.AutoScrollState
import com.tejpratapsingh.lyricsmaker.presentation.compose.util.ListItem
import com.tejpratapsingh.lyricsmaker.presentation.compose.util.RangeSelection
import com.tejpratapsingh.lyricsmaker.presentation.compose.util.initialEndIndex
import com.tejpratapsingh.lyricsmaker.presentation.compose.util.provideCurrentConfig
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Pixel-drag offset → list-item-index delta using average visible item height. */
private fun computeDeltaItems(
    listState: LazyListState,
    dragOffsetY: Float,
): Int {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    val avgItemHeight =
        if (visibleItems.isNotEmpty()) {
            visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
        } else {
            56f
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

    var startLyricIndex by remember { mutableIntStateOf(0) }
    var endLyricIndex by remember { mutableIntStateOf(initialEndIndex(viewModel.lyrics, fps)) }

    var startDragOffsetY by remember { mutableFloatStateOf(0f) }
    var endDragOffsetY by remember { mutableFloatStateOf(0f) }

    var moveMode by remember { mutableStateOf(false) }
    var isDraggingMove by remember { mutableStateOf(false) }

    val liveStartIndex by remember {
        derivedStateOf {
            val delta = computeDeltaItems(listState, startDragOffsetY)
            if (moveMode) {
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
            if (moveMode && isDraggingMove) {
                val rangeSize = endLyricIndex - startLyricIndex
                liveStartIndex + rangeSize
            } else {
                (endLyricIndex + endDelta).coerceIn(liveStartIndex, viewModel.lyrics.lastIndex)
            }
        }
    }

    val selection by remember { derivedStateOf { RangeSelection(liveStartIndex, liveEndIndex) } }

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

    LaunchedEffect(autoScroll.isDragging) {
        if (!autoScroll.isDragging) return@LaunchedEffect
        while (isActive) {
            val delta = autoScroll.scrollDeltaForTick()
            if (delta != 0f) listState.scrollBy(delta)
            delay(16L)
        }
    }
}