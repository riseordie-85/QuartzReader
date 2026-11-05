package com.tim.reader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import com.tim.reader.data.LibraryRepository
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl

/** The bare text reader, with animated page turns. Phase 3 builds out its settings. */
@OptIn(ExperimentalReadiumApi::class)
class EpubActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {
    private var opened: Publication? = null

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
        supportFragmentManager.fragmentFactory =
            EpubNavigatorFactory(publication).createFragmentFactory(initialLocator = start, listener = this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epub)

        val tag = "epub"
        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(R.id.navigator_container, EpubNavigatorFragment::class.java, Bundle(), tag)
            }
        }
        val navigator = supportFragmentManager.findFragmentByTag(tag) as EpubNavigatorFragment
        navigator.addInputListener(DirectionalNavigationAdapter(navigator, animatedTransition = true))

        val bookId = OpenEpub.bookId
        if (bookId != null) {
            val repository = LibraryRepository.get(this)
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

    override fun onDestroy() {
        super.onDestroy()
        val mine = opened
        if (isFinishing && mine != null) {
            if (OpenEpub.publication === mine) OpenEpub.publication = null
            appScope.launch { mine.close() }
        }
    }
}
