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
 * to decide how far and in w... (truncated)