package cc.hafa.reader.core

/** The most rows of topics shown before the topics scroll sideways. */
const val TOPIC_ROWS = 3

/** Where one item goes in [wrappedRows]: its [row], counted from zero, and its [left] edge. */
data class RowPosition(val row: Int, val left: Int)

/** Where items of these widths go when laid left to right and wrapped at [available], with [gap] between neighbors. */
fun wrappedRows(widths: List<Int>, available: Int, gap: Int): List<RowPosition> {
    val positions = ArrayList<RowPosition>(widths.size)
    var row = -1
    var used = 0
    for (width in widths) {
        if (row < 0 || used + gap + width > available) {
            row += 1
            positions.add(RowPosition(row, 0))
            used = width
        } else {
            positions.add(RowPosition(row, used + gap))
            used += gap + width
        }
    }
    return positions
}

/** The number of rows [positions] fill. */
fun rowCount(positions: List<RowPosition>): Int = positions.lastOrNull()?.row?.plus(1) ?: 0
