package cc.hafa.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.hafa.reader.core.ReaderState
import cc.hafa.reader.core.refreshSuggestedAt
import kotlinx.coroutines.delay

/**
 * Whether a refresh would help now (see [refreshSuggestedAt]). The time is
 * worked out again only when the feeds or the checking marks change, and one
 * wait turns it on when that time comes.
 */
@Composable
fun refreshSuggested(state: ReaderState): Boolean {
    val suggestedAt = remember(state.feeds, state.refreshing, state.checking) { refreshSuggestedAt(state) }
    val suggested by produceState(initialValue = false, suggestedAt) {
        val wait = suggestedAt - System.currentTimeMillis()
        value = wait <= 0
        if (wait > 0 && suggestedAt != Long.MAX_VALUE) {
            delay(wait)
            value = true
        }
    }
    return suggested
}

/**
 * [content] with, when [shown], a small accent dot on its top corner saying a
 * refresh would help, ringed in the bar's color; the web's `due-dot.svelte`.
 * Decorative: the control it marks says it in words.
 */
@Composable
fun WithDueDot(shown: Boolean, content: @Composable () -> Unit) {
    Box {
        content()
        if (shown) {
            val colors = ReaderTheme.colors
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(12.dp)
                    .background(colors.side, CircleShape)
                    .padding(2.dp)
                    .background(colors.accent, CircleShape),
            )
        }
    }
}
