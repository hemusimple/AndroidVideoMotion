// KEY CHANGE SUMMARY (targeted diff — full file preserved outside shown range)
// 
// In the LazyColumn items block, the StartHandle and EndHandle cases are updated:
//
// BEFORE (StartHandle):
//   is ListItem.StartHandle -> {
//       Box(modifier = Modifier.fillMaxWidth()) { /* static, no drag */ }
//   }
//
// AFTER (StartHandle):
//   is ListItem.StartHandle -> {
//       Box(
//           modifier = Modifier
//               .fillMaxWidth()
//               .pointerInput(Unit) {
//                   detectDragGestures(
//                       onDragStart = { autoScroll.isDragging = true },
//                       onDragEnd = {
//                           startLyricIndex = liveStartIndex
//                           endLyricIndex = liveEndIndex
//                           startDragOffsetY = 0f
//                           endDragOffsetY = 0f
//                           autoScroll.isDragging = false
//                           onSelectionChanged(selected)
//                       },
//                       onDragCancel = {
//                           startDragOffsetY = 0f
//                           endDragOffsetY = 0f
//                           autoScroll.isDragging = false
//                       },
//                       onDrag = { change, dragAmount ->
//                           change.consume()
//                           startDragOffsetY += dragAmount.y
//                           if (moveMode) endDragOffsetY += dragAmount.y
//                           onSelectionChanged(selected)
//                       },
//                   )
//               },
//       ) { /* existing handle UI */ }
//   }
//
// BEFORE (EndHandle):
//   is ListItem.EndHandle -> {
//       Box(modifier = Modifier.fillMaxWidth()) { /* static, no drag */ }
//   }
//
// AFTER (EndHandle):
//   is ListItem.EndHandle -> {
//       Box(
//           modifier = Modifier
//               .fillMaxWidth()
//               .pointerInput(Unit) {
//                   detectDragGestures(
//                       onDragStart = { autoScroll.isDragging = true },
//                       onDragEnd = {
//                           startLyricIndex = liveStartIndex
//                           endLyricIndex = liveEndIndex
//                           endDragOffsetY = 0f
//                           autoScroll.isDragging = false
//                           onSelectionChanged(selected)
//                       },
//                       onDragCancel = {
//                           endDragOffsetY = 0f
//                           autoScroll.isDragging = false
//                       },
//                       onDrag = { change, dragAmount ->
//                           change.consume()
//                           endDragOffsetY += dragAmount.y
//                           onSelectionChanged(selected)
//                       },
//                   )
//               },
//       ) { /* existing handle UI */ }
//   }