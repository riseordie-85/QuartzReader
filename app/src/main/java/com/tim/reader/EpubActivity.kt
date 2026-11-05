package com.tim.reader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl

/** The bare EPUB test: the text reader with animated page turns. */
@OptIn(ExperimentalReadiumApi::class)
class EpubActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {

    override fun onCreate(savedInstanceState: Bundle?) {
        val publication = OpenEpub.publication
        if (publication == null) {
            // Android restarted the app, and this test doesn't keep the book open across that.
            supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            finish()
            return
        }

        supportFragmentManager.fragmentFactory =
            EpubNavigatorFactory(publication).createFragmentFactory(initialLocator = null, listener = this)
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
        ResultsLog.add("EPUB: ${publication.metadata.title}, ${publication.readingOrder.size} chapters in reading order")
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) = Unit
}
