package cc.hafa.reader.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cc.hafa.reader.core.TOPIC_ROWS
import cc.hafa.reader.core.rowCount
import cc.hafa.reader.core.wrappedRows

/**
 * A [chip] for each of [items], laid left to right in up to [TOPIC_ROWS] rows; when they need more, they go in that
 * many rows read down each column, and the block scrolls sideways. Each chip sits in its own traversal group, ordered
 * by its place in [items], so screen readers go through them in that order either way. Every list of topics or words
 * is shown this way.
 */
@Composable
fun <Item> ChipRows(items: List<Item>, chip: @Composable (Item) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val content = @Composable {
            items.forEachIndexed { index, item ->
                Box(
                    Modifier.semantics {
                        isTraversalGroup = true
                        traversalIndex = index.toFloat()
                    },
                ) { chip(item) }
            }
        }
        Layout(content, Modifier.semantics { isTraversalGroup = true }.horizontalScroll(rememberScrollState())) { measurables, _ ->
            val gapPx = 8.dp.roundToPx()
            val placeables = measurables.map { measurable -> measurable.measure(Constraints()) }
            val wrapped = wrappedRows(placeables.map { placeable -> placeable.width }, available, gapPx)
            val positions: List<IntOffset>
            val width: Int
            val height: Int
            if (rowCount(wrapped) <= TOPIC_ROWS) {
                val rowHeights = IntArray(rowCount(wrapped))
                wrapped.forEachIndexed { index, position ->
                    rowHeights[position.row] = maxOf(rowHeights[position.row], placeables[index].height)
                }
                val rowTops = rowHeights.runningFold(0) { top, rowHeight -> top + rowHeight }
                positions = wrapped.map { position -> IntOffset(position.left, rowTops[position.row]) }
                width = wrapped.indices.maxOfOrNull { index -> wrapped[index].left + placeables[index].width } ?: 0
                height = rowTops.last()
            } else {
                val rowHeight = placeables.maxOf { placeable -> placeable.height }
                val columnPositions = ArrayList<IntOffset>(placeables.size)
                var left = 0
                for (column in placeables.chunked(TOPIC_ROWS)) {
                    column.forEachIndexed { row, _ -> columnPositions.add(IntOffset(left, row * rowHeight)) }
                    left += column.maxOf { placeable -> placeable.width } + gapPx
                }
                positions = columnPositions
                width = left - gapPx
                height = rowHeight * TOPIC_ROWS
            }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable -> placeable.place(positions[index]) }
            }
        }
    }
}
