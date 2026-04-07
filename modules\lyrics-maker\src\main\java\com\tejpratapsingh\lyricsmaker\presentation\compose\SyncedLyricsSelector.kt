package com.tejpratapsingh.lyricsmaker.presentation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.PointerEventType
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
 * to decide how far and in which direction to scroll.
 *
 * Edge zone: the top and bottom [edgeFraction] of the list height trigger
 * scrolling. Scroll speed ramps linearly from 0 at the zone boundary to
 * [maxScrollPxPerTick] at the very edge.
 */
private class AutoScrollState {
    var isDragging by mutableStateOf(false)
    var pointerYInRoot by mutableFloatStateOf(0f)
    var listTopInRoot by mutableFloatStateOf(0f)
    var listBottomInRoot by mutableFloatStateOf(0f)

    val edgeFraction = 0.10f
    val maxScrollPxPerTick = 18f

    fun scrollDeltaForTick(): Float {
        if (!isDragging) return 0f
        val listHeight = listBottomInRoot - listTopInRoot
        if (listHeight <= 0f) return 0f
        val edgeZone = listHeight * edgeFraction
        val distFromTop = pointerYInRoot - listTopInRoot
        val distFromBottom = listBottomInRoot - pointerYInRoot
        return when {
            distFromTop in 0f..edgeZone -> {
                -maxScrollPxPerTick * (1f - distFromTop / edgeZone)
            }
            distFromBottom in 0f..edgeZone -> {
                maxScrollPxPerTick * (1f - distFromBottom / edgeZone)
            }
            else -> 0f
        }
    }
}

// ─── Main composable ──────────────────────────────────────────────────────────

@Composable
fun SyncedLyricsSelector(
    lyrics: List<SyncedLyricFrame>,
    onSelectionConfirmed: (List<SyncedLyricFrame>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalFrames = provideCurrentConfig().let { it.fps * 30 } // fallback

    // Build the flat display list: StartHandle, all lyric items, EndHandle
    val lyricItems = remember(lyrics) {
        lyrics.mapIndexed { index, frame -> ListItem.LyricItem(index, frame) }
    }

    var selection by remember {
        mutableStateOf(RangeSelection(start = 0, end = lyrics.lastIndex))
    }

    val listState: LazyListState = rememberLazyListState()
    val autoScrollState = remember { AutoScrollState() }

    // Auto-scroll loop
    LaunchedEffect(autoScrollState.isDragging) {
        if (!autoScrollState.isDragging) return@LaunchedEffect
        while (isActive && autoScrollState.isDragging) {
            val delta = autoScrollState.scrollDeltaForTick()
            if (delta != 0f) {
                listState.scrollBy(delta)
            }
            delay(16L) // ~60 fps
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Confirm button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Select lyrics range",
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = {
                val selected = lyrics.filterIndexed { index, _ ->
                    selection.contains(index)
                }
                onSelectionConfirmed(selected)
            }) {
                Text("Confirm")
            }
        }

        HorizontalDivider()

        // The list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val posInRoot = coords.positionInRoot()
                    autoScrollState.listTopInRoot = posInRoot.y
                    autoScrollState.listBottomInRoot = posInRoot.y + coords.size.height
                },
            contentPadding = PaddingValues(vertical = 8.dp),
            // Disable user scrolling while a handle drag is active to prevent gesture conflict
            userScrollEnabled = !autoScrollState.isDragging,
        ) {
            // Start handle
            item(key = "start_handle") {
                DragHandle(
                    label = "Start",
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = "Start handle",
                        )
                    },
                    autoScrollState = autoScrollState,
                    onDragDelta = { deltaY ->
                        // Find the item closest to the pointer and update selection start
                        val newStart = findIndexForPointerY(
                            pointerY = autoScrollState.pointerYInRoot,
                            listTopInRoot = autoScrollState.listTopInRoot,
                            listState = listState,
                            itemCount = lyrics.size,
                            clampMin = 0,
                            clampMax = selection.end,
                        )
                        if (newStart != null) {
                            selection = selection.copy(start = newStart)
                        }
                    },
                )
            }

            // Lyric items
            itemsIndexed(
                items = lyricItems,
                key = { _, item -> "lyric_${item.index}" },
            ) { _, item ->
                LyricRow(
                    lyricItem = item,
                    isSelected = selection.contains(item.index),
                )
            }

            // End handle
            item(key = "end_handle") {
                DragHandle(
                    label = "End",
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.OpenWith,
                            contentDescription = "End handle",
                        )
                    },
                    autoScrollState = autoScrollState,
                    onDragDelta = { deltaY ->
                        val newEnd = findIndexForPointerY(
                            pointerY = autoScrollState.pointerYInRoot,
                            listTopInRoot = autoScrollState.listTopInRoot,
                            listState = listState,
                            itemCount = lyrics.size,
                            clampMin = selection.start,
                            clampMax = lyrics.lastIndex,
                        )
                        if (newEnd != null) {
                            selection = selection.copy(end = newEnd)
                        }
                    },
                )
            }
        }
    }
}

// ─── Helper: map pointer Y to list index ─────────────────────────────────────

private fun findIndexForPointerY(
    pointerY: Float,
    listTopInRoot: Float,
    listState: LazyListState,
    itemCount: Int,
    clampMin: Int,
    clampMax: Int,
): Int? {
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return null

    val relativeY = pointerY - listTopInRoot + layoutInfo.viewportStartOffset

    // Find the visible item whose vertical center is closest to relativeY
    val closest = visibleItems.minByOrNull { item ->
        val center = item.offset + item.size / 2f
        kotlin.math.abs(center - relativeY)
    } ?: return null

    // The key for lyric items is "lyric_<index>"
    val key = closest.key
    val index = if (key is String && key.startsWith("lyric_")) {
        key.removePrefix("lyric_").toIntOrNull()
    } else {
        null
    } ?: return null

    return index.coerceIn(clampMin, clampMax)
}

// ─── Drag handle composable ───────────────────────────────────────────────────

@Composable
private fun DragHandle(
    label: String,
    icon: @Composable () -> Unit,
    autoScrollState: AutoScrollState,
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(1f)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            // Use awaitEachGesture with manual pointer tracking to avoid LazyColumn
            // stealing the gesture during vertical drags.
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 1. Wait for the initial finger-down event.
                    //    requireUnconsumed = false so we get it even if something
                    //    else already consumed it (defensive).
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 2. Consume the down event immediately so LazyColumn cannot
                    //    treat this touch as the start of a scroll gesture.
                    down.consume()

                    autoScrollState.isDragging = true
                    // Track absolute Y using initial position + cumulative delta
                    var currentAbsoluteY = down.position.y +
                        autoScrollState.listTopInRoot
                    autoScrollState.pointerYInRoot = currentAbsoluteY

                    // 3. Loop over subsequent pointer events until release/cancel.
                    var dragConsumed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val anyPressed = event.changes.any { it.pressed }

                        when (event.type) {
                            PointerEventType.Move -> {
                                // Accumulate delta from all active pointers
                                val dragChange = event.changes.firstOrNull() ?: break
                                val delta = dragChange.position.y - dragChange.previousPosition.y
                                currentAbsoluteY += delta
                                autoScrollState.pointerYInRoot = currentAbsoluteY
                                // Consume the move so LazyColumn doesn't scroll
                                dragChange.consume()
                                onDragDelta(delta)
                                dragConsumed = true
                            }
                            PointerEventType.Release -> {
                                // Consume the release event
                                event.changes.forEach { it.consume() }
                                autoScrollState.isDragging = false
                                break
                            }
                            else -> {
                                // Cancel or unknown — stop dragging
                                if (!anyPressed) {
                                    autoScrollState.isDragging = false
                                    break
                                }
                            }
                        }
                    }

                    // Ensure isDragging is cleared even if we exit the loop unexpectedly
                    autoScrollState.isDragging = false
                }
            },
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

// ─── Lyric row composable ─────────────────────────────────────────────────────

@Composable
private fun LyricRow(
    lyricItem: ListItem.LyricItem,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = lyricItem.frame.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}
