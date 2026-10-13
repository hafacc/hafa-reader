package cc.hafa.reader

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cc.hafa.reader.ui.ReaderScreen
import cc.hafa.reader.ui.ReaderTheme
import cc.hafa.reader.ui.ReaderViewModel

/** The reader's only activity. */
class MainActivity : ComponentActivity() {
    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReaderTheme {
                ReaderScreen(viewModel)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles a dark mode switch itself, so the system bar icons must be set again to stay readable.
        enableEdgeToEdge()
    }

    override fun onStop() {
        super.onStop()
        // send queued changes before the app may be closed in the background
        readerApplication.sync.value?.flushNow()
    }
}
