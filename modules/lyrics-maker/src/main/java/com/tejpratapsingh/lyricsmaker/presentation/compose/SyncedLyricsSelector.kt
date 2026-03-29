148: /** Pixel-drag offset → list-item-index delta using average visible item height. */
149: private fun computeDeltaItems(
150:     listState: LazyListState,
151:     dragOffsetY: Float,
152: ): Int {
153:     val visibleItems = listState.layoutInfo.visibleItemsInfo
154:     val avgItemHeight =
155:         if (visibleItems.isNotEmpty()) {
156:             visibleItems.sumOf { it.size } / visibleItems.size
157:         } else {
158:             56
159:         }
160:     return (dragOffsetY / avgItemHeight).roundToInt()
161: }
162: 
163: private fun formatDuration(
164:     frames: Int,
165:     fps: Int,
166: ): String {
167:     val totalSecs = (frames / fps)
168:     val m = totalSecs / 60
169:     val s = totalSecs % 60
170:     return "$m:${s.toString().padStart(2, '0')}"
171: }
172: 
173: @Composable
174: fun SyncedLyricsSelector(
175:     viewModel: LyricsViewModel,
176:     modifier: Modifier = Modifier,
177:     onSelectionChanged: (List<SyncedLyricFrame>) -> Unit = {},
178:     onFinalize: (List<SyncedLyricFrame>) -> Unit = {},
179: ) {
180:     val listState = rememberLazyListState()
181:     val fps = provideCurrentConfig().fps
182:     val autoScroll = remember { AutoScrollState() }
183: 
184:     var startLyricIndex by remember { mutableIntStateOf(0) }
185:     var endLyricIndex by remember { mutableIntStateOf(initialEndIndex(viewModel.lyrics, fps)) }
186: 
187:     var startDragOffsetY by remember { mutableFloatStateOf(0f) }
188:     var endDragOffsetY by remember { mutableFloatStateOf(0f) }
189: 
190:     var moveMode by remember { mutableStateOf(false) }
191: 
192:     val liveStartIndex by remember {
193:         derivedStateOf {
194:             val delta = computeDeltaItems(listState, startDragOffsetY)
195:             if (moveMode) {
196:                 val rangeSize = endLyricIndex - startLyricIndex
197:                 (startLyricIndex + delta).coerceIn(0, viewModel.lyrics.lastIndex - rangeSize)
198:             } else {
199:                 (startLyricIndex + delta).coerceIn(0, endLyricIndex)
200:             }
201:         }
202:     }
203:     val liveEndIndex by remember {
204:         derivedStateOf {
205:             val endDelta = computeDeltaItems(listState, endDragOffsetY)
206:             if (moveMode) {
207:                 // End tracks start exactly in move mode
208:                 val rangeSize = endLyricIndex - startLyricIndex
209:                 liveStartIndex + rangeSize
210:             } else {
211:                 (endLyricIndex + endDelta).coerceIn(liveStartIndex, viewModel.lyrics.lastIndex)
212:             }
213:         }
214:     }
215: }