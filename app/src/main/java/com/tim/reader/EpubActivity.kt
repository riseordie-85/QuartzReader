package com.tim.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.stats.ReadingTracker
import com.tim.reader.theme.BookFonts
import com.tim.reader.data.ReadDirection
import com.tim.reader.data.ReaderLook
import com.tim.reader.library.CameraEdge
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
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl

/** The text reader: Readium draws the book, and floating Compose controls sit over it. */
private const val MENU_COPY = 1

@OptIn(ExperimentalReadiumApi::class)
class EpubActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {
    private var opened: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var controls by mutableStateOf(false)
    private var selecting by mutableStateOf(false)
    private var selectionMode: ActionMode? = null
    private var animatedTurns = true
    private var pageContainer: FragmentContainerView? = null
    private var cutout = Insets.NONE
    private var outerMargin = 0
    private var cameraGap = 0
    private var cameraEdge = CameraEdge.Auto

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
        BookFonts.init(this)
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
            initialPreferences = epubPreferences(look, direction, twoColumns, publication.metadata.layout == Layout.FIXED),
            listener = this,
            // The activity keeps pages clear of the cutout, so Readium shouldn't add it again.
            configuration = EpubNavigatorFragment.Configuration(
                servedAssets = listOf(BookFonts.SERVED),
                shouldApplyInsetsPadding = false,
                // Readium can't read a selection from a fixed-layout page, so those keep Android's menu.
                selectionActionModeCallback = selectionWatcher.takeIf { publication.metadata.layout != Layout.FIXED },
            ).also(BookFonts::declareIn),
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub)

        // The bars come and go, but text should stay clear of a camera cutout.
        val container = findViewById<FragmentContainerView>(R.id.navigator_container)
        pageContainer = container
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            val found = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (found != cutout) {
                cutout = found
                ResultsLog.add("Screen cutout: $found, shapes ${insets.displayCutout?.boundingRects}")
            }
            updatePagePadding()
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
                            isSelecting = selecting,
                            onEndSelection = ::endSelection,
                            onShowControls = { controls = it },
                            onAnimatedTurns = { animatedTurns = it },
                            onPageColor = container::setBackgroundColor,
                            onPagePadding = { outer, gap, edge ->
                                outerMargin = outer
                                cameraGap = gap
                                cameraEdge = edge
                                updatePagePadding()
                            },
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
                var tracking = false
                navigator.currentLocator.collect { locator ->
                    // Readium's last position reports just under 100%, so it counts as finished here.
                    val position = locator.locations.position ?: 0
                    val atEnd = positions > 0 && position >= positions
                    val progress = if (atEnd) 1f else locator.locations.totalProgression?.toFloat() ?: 0f
                    // Readium reports position 0 before the first page is laid out.
                    if (position >= 1 && book != null) {
                        if (!tracking) {
                            tracking = true
                            ReadingTracker.start(this@EpubActivity, book, positions, position, progress)
                        } else {
                            ReadingTracker.turn(this@EpubActivity, position, progress, atEnd)
                        }
                    }
                    repository.savePosition(bookId, 0, progress, locator.toJSON().toString())
                }
            }
        }
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) = Unit

    /** Readium hands text selection to this, so the app knows when to show its buttons. */
    private val selectionWatcher = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            selecting = true
            selectionMode = mode
            menu.add(Menu.NONE, MENU_COPY, Menu.NONE, "Copy")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val current = navigator ?: return false
            lifecycleScope.launch {
                val text = current.currentSelection()?.locator?.text?.highlight
                if (text != null) {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Book text", text))
                }
                endSelection()
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            selecting = false
            selectionMode = null
        }
    }

    private fun endSelection() {
        navigator?.clearSelection()
        selectionMode?.finish()
    }

    /**
     * Keeps text clear of the camera. Both sides match even when only one has a camera, so the
     * spine stays in the middle. Auto follows a cutout Android reports, and otherwise guesses: a
     * big screen held sideways has its camera on top, like an unfolded Fold or a tablet, and a
     * phone held sideways has it on a side.
     */
    private fun updatePagePadding() {
        val configuration = resources.configuration
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isLarge = configuration.smallestScreenWidthDp >= 600
        val edge = when {
            cameraEdge != CameraEdge.Auto -> cameraEdge
            cutout.top > 0 -> CameraEdge.Top
            cutout.bottom > 0 -> CameraEdge.Bottom
            cutout.left > 0 || cutout.right > 0 -> CameraEdge.Sides
            isLandscape && !isLarge -> CameraEdge.Sides
            else -> CameraEdge.Top
        }
        val sideGap = if (edge == CameraEdge.Sides) cameraGap else 0
        val topGap = if (edge == CameraEdge.Top) cameraGap else 0
        val bottomGap = if (edge == CameraEdge.Bottom) cameraGap else 0
        val side = maxOf(cutout.left, cutout.right) + outerMargin + sideGap
        pageContainer?.setPadding(side, cutout.top + topGap, side, cutout.bottom + bottomGap)
    }

    /** Volume keys turn pages, as they do in the image reader. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isForward = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> return super.dispatchKeyEvent(event)
        }
        val current = navigator ?: return super.dispatchKeyEvent(event)
        // A selection stays on its old page if the chapter changes under it.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !selecting) {
            if (current.overflow.value.scroll) {
                scrollOneScreen(current, isForward)
            } else if (isForward) {
                current.goForward(animated = animatedTurns)
            } else {
                current.goBackward(animated = animatedTurns)
            }
        }
        return true
    }

    /** In scroll mode, Readium's forward jumps a whole chapter, so this moves one screen instead. */
    private fun scrollOneScreen(current: EpubNavigatorFragment, isForward: Boolean) {
        val direction = if (isForward) "1" else "-1"
        val script = "(function(){var s=document.scrollingElement,b=s.scrollTop;" +
            "s.scrollTop+=$direction*window.innerHeight*0.9;return s.scrollTop!==b})()"
        lifecycleScope.launch {
            // At the end of a chapter there's nothing left to scroll, so it moves to the next one.
            if (current.evaluateJavascript(script) != "true") {
                if (isForward) current.goForward() else current.goBackward()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ReadingTracker.resume(this)
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) ReadingTracker.stop(this) else ReadingTracker.pause(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) ReadingTracker.stop(this)
        val mine = opened
        if (isFinishing && mine != null) {
            if (OpenEpub.publication === mine) OpenEpub.publication = null
            appScope.launch { mine.close() }
        }
    }
}
