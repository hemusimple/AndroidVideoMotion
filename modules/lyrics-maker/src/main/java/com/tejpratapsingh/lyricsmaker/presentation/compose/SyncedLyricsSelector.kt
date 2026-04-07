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

// ─── Domain model ────────────────────────────────────────────────────────────────────────────────

data class RangeSelection(
    val start: Int,
    val end: Int,
) {
    val minIndex get() = min(start, end)
    val maxIndex get() = max(start, end)

    fun contains(index: Int) = index in minIndex..maxIndex
}

// ─── Display list item types ──────────────────────────────────────────────────────────────────────────

private sealed interface ListItem {
    data class LyricItem(
        val index: Int,
        val frame: SyncedLyricFrame,
    ) : ListItem

    data object StartHandle : ListItem

    data object EndHandle : ListItem
}

// ─── Auto-scroll state ──────────────────────────────────────────────────────────────────────────────

/**
 * Shared mutable state that coordinates auto-scrolling between the drag handles
 * and the [LazyColumn].
 *
 * Each handle reports its pointer Y in root (screen) coordinates while a drag
 * is in progress. A [LaunchedEffect] in [SyncedLyricsSelector] wakes up whenever
 * [isDragging] becomes true, then ticks at ~60 fps and calls [scrollDeltaForTick]
 * to decide how far and in which direction to scroll.
 *
 * Edge zone: the top and bottom [edgeFraction] of the list viewport trigger
 * auto-scroll. Speed scales linearly with how deep into the edge zone the
 * pointer is.
 */
private class AutoScrollState(
    val edgeFraction: Float = 0.15f,
    val maxScrollPerTick: Float = 24f,
) {
    var isDragging by mutableStateOf(false)
    var pointerYInRoot by mutableFloatStateOf(0f)
    var listTopInRoot by mutableFloatStateOf(0f)
    var listBottomInRoot by mutableFloatStateOf(0f)

    fun scrollDeltaForTick(): Float {
        val listHeight = listBottomInRoot - listTopInRoot
        if (listHeight <= 0f) return 0f
        val edgeSize = listHeight * edgeFraction
        val relY = pointerYInRoot - listTopInRoot
        return when {
            relY < edgeSize -> {
                // near top → scroll up (negative)
                val fraction = 1f - (relY / edgeSize).coerceIn(0f, 1f)
                -maxScrollPerTick * fraction
            }
            relY > listHeight - edgeSize -> {
                // near bottom → scroll down (positive)
                val fraction = ((relY - (listHeight - edgeSize)) / edgeSize).coerceIn(0f, 1f)
                maxScrollPerTick * fraction
            }
            else -> 0f
        }
    }
}

// ─── Main composable ───────────────────────────────────────────────────────────────────────────────────

@Composable
fun SyncedLyricsSelector(
    viewModel: LyricsViewModel,
    onConfirm: () -> Unit,
) {
    val lyrics = viewModel.lyrics
    val fps = provideCurrentConfig().fps

    // Range selection indices into `lyrics`
    var selection by remember {
        mutableStateOf(RangeSelection(start = 0, end = lyrics.lastIndex))
    }

    // Whether the handles are locked (cannot be dragged)
    var locked by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val autoScroll = remember { AutoScrollState() }

    // Per-item height cache: index → height in pixels
    val itemHeights = remember { mutableMapOf<Int, Int>() }
    // Top-of-list offset in root coordinates
    var listTopInRoot by remember { mutableFloatStateOf(0f) }

    // Auto-scroll loop
    LaunchedEffect(autoScroll.isDragging) {
        if (!autoScroll.isDragging) return@LaunchedEffect
        while (isActive) {
            val delta = autoScroll.scrollDeltaForTick()
            if (delta != 0f) listState.scrollBy(delta)
            delay(16L)
        }
    }

    /**
     * Given an absolute Y position in root coordinates, return the lyric index
     * that the pointer is hovering over (clamped to valid range).
     */
    fun lyricIndexAtY(pointerYInRoot: Float): Int {
        val firstVisible = listState.firstVisibleItemIndex
        val firstVisibleOffset = listState.firstVisibleItemScrollOffset

        // Walk through visible items to find which one the pointer is over.
        // We use the cached heights; fall back to an even distribution if unknown.
        var accumulatedY = listTopInRoot - firstVisibleOffset
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        for (itemInfo in visibleItems) {
            val itemHeight = itemInfo.size
            val itemTop = listTopInRoot + itemInfo.offset - firstVisibleOffset
            val itemBottom = itemTop + itemHeight
            // The list contains StartHandle at position 0 and EndHandle at the last
            // position; lyric items are in between.
            // We only care about lyric items (key is Int index).
            if (pointerYInRoot in itemTop..itemBottom) {
                val key = itemInfo.key
                if (key is Int) return key.coerceIn(0, lyrics.lastIndex)
            }
        }
        // Fallback: clamp to first/last visible lyric
        return if (pointerYInRoot < listTopInRoot) 0 else lyrics.lastIndex
    }

    // Build the flat display list: StartHandle, lyric items, EndHandle
    val displayItems: List<ListItem> by remember(lyrics, selection) {
        derivedStateOf {
            buildList {
                add(ListItem.StartHandle)
                lyrics.forEachIndexed { index, frame ->
                    add(ListItem.LyricItem(index = index, frame = frame))
                }
                add(ListItem.EndHandle)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Select lyrics range",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { locked = !locked }) {
                    Icon(
                        imageVector = if (locked) Icons.Filled.LockOpen else Icons.Filled.OpenWith,
                        contentDescription = if (locked) "Unlock" else "Lock",
                    )
                }
                TextButton(
                    onClick = {
                        viewModel.selectedLyrics =
                            lyrics.subList(selection.minIndex, selection.maxIndex + 1)
                        onConfirm()
                    },
                ) {
                    Text("Confirm")
                }
            }
        }

        HorizontalDivider()

        // ── Lyrics list ──────────────────────────────────────────────────────
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        val rootPos = coords.positionInRoot()
                        listTopInRoot = rootPos.y
                        autoScroll.listTopInRoot = rootPos.y
                        autoScroll.listBottomInRoot = rootPos.y + coords.size.height
                    },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                itemsIndexed(
                    items = displayItems,
                    key = { _, item ->
                        when (item) {
                            is ListItem.StartHandle -> "start_handle"
                            is ListItem.EndHandle -> "end_handle"
                            is ListItem.LyricItem -> item.index
                        }
                    },
                ) { _, item ->
                    when (item) {
                        is ListItem.StartHandle ->
                            DragHandle(
                                label = "Start",
                                locked = locked,
                                autoScrollState = autoScroll,
                                listState = listState,
                                listTopInRoot = listTopInRoot,
                                lyricsSize = lyrics.size,
                                onDragIndex = { idx ->
                                    selection = selection.copy(start = idx)
                                },
                            )

                        is ListItem.EndHandle ->
                            DragHandle(
                                label = "End",
                                locked = locked,
                                autoScrollState = autoScroll,
                                listState = listState,
                                listTopInRoot = listTopInRoot,
                                lyricsSize = lyrics.size,
                                onDragIndex = { idx ->
                                    selection = selection.copy(end = idx)
                                },
                            )

                        is ListItem.LyricItem -> {
                            val isSelected = selection.contains(item.index)
                            LyricRow(
                                frame = item.frame,
                                fps = fps,
                                isSelected = isSelected,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Drag handle ─────────────────────────────────────────────────────────────

@Composable
private fun DragHandle(
    label: String,
    locked: Boolean,
    autoScrollState: AutoScrollState,
    listState: LazyListState,
    listTopInRoot: Float,
    lyricsSize: Int,
    onDragIndex: (Int) -> Unit,
) {
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDraggingThis by remember { mutableStateOf(false) }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .zIndex(if (isDraggingThis) 1f else 0f)
                .offset { IntOffset(x = 0, y = offsetY.roundToInt()) }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            isDraggingThis = true
                            autoScrollState.isDragging = true
                            offsetY = 0f
                        },
                        onDragEnd = {
                            isDraggingThis = false
                            autoScrollState.isDragging = false
                            offsetY = 0f
                        },
                        onDragCancel = {
                            isDraggingThis = false
                            autoScrollState.isDragging = false
                            offsetY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetY += dragAmount.y
                            // Compute absolute Y of the pointer in root coords
                            val pointerYInRoot =
                                listTopInRoot +
                                    (listState.firstVisibleItemScrollOffset) +
                                    offsetY
                            autoScrollState.pointerYInRoot = change.position.y + listTopInRoot

                            // Map pointer to lyric index via visible items
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            var targetIndex: Int? = null
                            val pointerY = change.position.y + listTopInRoot
                            for (itemInfo in visibleItems) {
                                val itemTop = listTopInRoot + itemInfo.offset
                                val itemBottom = itemTop + itemInfo.size
                                if (pointerY in itemTop..itemBottom) {
                                    val key = itemInfo.key
                                    if (key is Int) {
                                        targetIndex = key.coerceIn(0, lyricsSize - 1)
                                        break
                                    }
                                }
                            }
                            if (targetIndex == null) {
                                targetIndex =
                                    if (pointerY < listTopInRoot) 0 else lyricsSize - 1
                            }
                            onDragIndex(targetIndex)
                        },
                    )
                },
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag $label handle",
                modifier = Modifier.size(20.dp),
                tint =
                    if (locked) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// ─── Single lyric row ────────────────────────────────────────────────────────

@Composable
private fun LyricRow(
    frame: SyncedLyricFrame,
    fps: Int,
    isSelected: Boolean,
) {
    val seconds = frame.frame / fps
    val minutes = seconds / 60
    val secs = seconds % 60
    val timeLabel = "%02d:%02d".format(minutes, secs)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                ).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = frame.text,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}
