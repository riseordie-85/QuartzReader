package com.tim.reader

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderLook
import com.tim.reader.library.DeviceSettings
import com.tim.reader.reader.epubPreferences
import com.tim.reader.ui.EpubControls
import com.tim.reader.ui.showsTwoPages
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl

/** The text reader: Readium draws the book, and floating Compose controls sit over it. */
@OptIn(ExperimentalReadiumApi::class)
class EpubActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {
    private var opened: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var controls by mutableStateOf(false)
    private var animatedTurns = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val publication = OpenEpub.publication
        if (publication == null) {
            // Android restarted the app, so the user goes back to the library to reopen the book.
            supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        opened = publication
        val start = OpenEpub.locator?.let { saved ->
            runCatching { Locator.fromJSON(JSONObject(saved)) }.getOrNull()
        }
        val repository = LibraryRepository.get(this)
        val book = OpenEpub.bookId?.let { repository.state.value.book(it) }
        val look = book?.let { repository.state.value.readerLook(it) } ?: ReaderLook()
        val direction = book?.direction ?: ReadDirection.LeftToRight
        val screen = resources.configuration
        val twoColumns = showsTwoPages(
            DeviceSettings.twoPage,
            direction,
            screen.screenWidthDp.toFloat(),
            screen.screenHeightDp.toFloat(),
        )
        supportFragmentManager.fragmentFactory = EpubNavigatorFactory(publication).createFragmentFactory(
            initialLocator = start,
            initialPreferences = epubPreferences(look, direction, twoColumns),
            listener = this,
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub)

        // The bars come and go, but text should stay clear of a camera cutout.
        val container = findViewById<FragmentContainerView>(R.id.navigator_container)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.setPadding(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }

        val tag = "epub"
        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(R.id.navigator_container, EpubNavigatorFragment::class.java, Bundle(), tag)
            }
        }
        val navigator = supportFragmentManager.findFragmentByTag(tag) as EpubNavigatorFragment
        this.navigator = navigator
        val animated = DirectionalNavigationAdapter(navigator, animatedTransition = true)
        val instant = DirectionalNavigationAdapter(navigator, animatedTransition = false)
        navigator.addInputListener(object : InputListener {
            private val adapter get() = if (animatedTurns) animated else instant

            // The side thirds turn pages, and anything else that reaches here toggles the controls.
            override fun onTap(event: TapEvent): Boolean {
                if (adapter.onTap(event)) return true
                controls = !controls
                return true
            }

            override fun onDrag(event: DragEvent) = adapter.onDrag(event)

            override fun onKey(event: org.readium.r2.navigator.input.KeyEvent) = adapter.onKey(event)
        })

        addContentView(
            ComposeView(this).apply {
                setContent {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        EpubControls(
                            book = book,
                            publication = publication,
                            navigator = navigator,
                            showControls = controls,
                            onAnimatedTurns = { animatedTurns = it },
                            onPageColor = container::setBackgroundColor,
                        )
                    }
                }
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val bookId = OpenEpub.bookId
        if (bookId != null) {
            lifecycleScope.launch {
                val positions = publication.positions().size
                navigator.currentLocator.collect { locator ->
                    // Readium's last position reports just under 100%, so it counts as finished here.
                    val atEnd = positions > 0 && (locator.locations.position ?: 0) >= positions
                    val progress = if (atEnd) 1f else locator.locations.totalProgression?.toFloat() ?: 0f
                    repository.savePosition(bookId, 0, progress, locator.toJSON().toString())
                }
            }
        }
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) = Unit

    /** Volume keys turn pages, as they do in the image reader. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isForward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return super.dispatchKeyEvent(event)
        }
        val current = navigator ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (isForward) current.goForward(animated = animatedTurns) else current.goBackward(animated = animatedTurns)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        val mine = opened
        if (isFinishing && mine != null) {
            if (OpenEpub.publication === mine) OpenEpub.publication = null
            appScope.launch { mine.close() }
        }
    }
}
