package cc.hafa.reader.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TopicRowsTest {
    @Test
    fun `items wrap when the next one would pass the available width`() {
        assertEquals(0, rowCount(wrappedRows(emptyList(), 100, 8)))
        assertEquals(1, rowCount(wrappedRows(listOf(46, 46), 100, 8)))
        assertEquals(2, rowCount(wrappedRows(listOf(46, 47), 100, 8)))
        assertEquals(3, rowCount(wrappedRows(listOf(40, 40, 40, 40, 40), 100, 8)))
    }

    @Test
    fun `positions give each item's row and left edge`() {
        assertEquals(
            listOf(RowPosition(0, 0), RowPosition(0, 48), RowPosition(1, 0)),
            wrappedRows(listOf(40, 40, 40), 100, 8),
        )
    }

    @Test
    fun `an item wider than the space takes a row of its own`() {
        assertEquals(
            listOf(RowPosition(0, 0), RowPosition(1, 0), RowPosition(1, 28)),
            wrappedRows(listOf(150, 20, 20), 100, 8),
        )
    }
}
