@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek.epub

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.core.view.doOnLayout
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.toArgb
import com.poyal.yeobaek.AppViewModel
import com.poyal.yeobaek.R
import com.poyal.yeobaek.data.*
import com.poyal.yeobaek.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.*
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError

/** Owns Readium's Fragment lifecycle; the activity ViewModel owns the open publication. */
class EpubHostFragment : Fragment(), EpubRenderer {
    private val vm: AppViewModel by activityViewModels()
    private var navigator: EpubNavigatorFragment? = null
    private var token: String? = null
    private var loaded = false
    private var locationJob: Job? = null
    private var page = 0
    private var pageCount = 1
    private var layoutPreferences = ""
    private var renderGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // A restored process has no Publication yet. Restore only to dispose of the old child safely.
        childFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        super.onCreate(savedInstanceState)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        object : FrameLayout(requireContext()) {
            private var downX = 0f
            private var downY = 0f
            private val slop = ViewConfiguration.get(context).scaledTouchSlop
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
                if (event.actionMasked == MotionEvent.ACTION_MOVE && event.pointerCount == 1 &&
                    (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop)) vm.epubReader.userNavigation()
                return super.dispatchTouchEvent(event)
            }
        }.apply { id = R.id.epub_navigator_container }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.fragments.toList().forEach { childFragmentManager.beginTransaction().remove(it).commitNow() }
        val opened = vm.ui.value.epubReader ?: return
        token = opened.token
        vm.epubReader.ready.value = false
        vm.epubReader.renderer = this
        view.doOnLayout {
            view.post {
                if (!isAdded || this.view !== view) return@post
                viewLifecycleOwner.lifecycleScope.launch {
                    combine(vm.epub, vm.app) { prefs, app -> prefs.copy(menuVisibility = MenuVisibility.TAP) to app.theme }.distinctUntilChanged().collectLatest { (prefs, theme) ->
                        val anchor = capture() ?: vm.epubReader.locator.value ?: opened.initial
                        // Readium's JavaScript callback can resume after its WebView was removed.
                        currentCoroutineContext().ensureActive()
                        if (!isAdded || this@EpubHostFragment.view !== view || vm.ui.value.epubReader?.token != token) return@collectLatest
                        show(opened, anchor, prefs, theme)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.epubReader.pending.filterNotNull().collect { at ->
                if (loaded && move(at)) vm.epubReader.pending.compareAndSet(at, null)
            }
        }
    }
    private fun show(opened: OpenEpub, anchor: Locator?, prefs: EpubReaderPreferences, appTheme: AppTheme) {
        vm.epubReader.restoring()
        val generation = ++renderGeneration
        locationJob?.cancel(); loaded = false
        vm.epubReader.ready.value = false
        navigator?.let { childFragmentManager.beginTransaction().remove(it).commitNow() }
        layoutPreferences = "${prefs.mode}:${prefs.publisherStyles}:${prefs.font}:${prefs.size}:${prefs.line}:${prefs.margin}"
        val dark = when (appTheme) {
            AppTheme.DARK -> true; AppTheme.LIGHT -> false
            AppTheme.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val app = if (dark) DarkPaper else LightPaper
        val colors = if (opened.document.fixed) when (prefs.canvas) { PdfCanvas.AUTO -> app; PdfCanvas.LIGHT -> LightPaper; PdfCanvas.DARK -> DarkPaper }
            else readerColors(prefs.paper, app)
        val config = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false).apply {
            servedAssets += "noto_serif_kr.otf"
            addFontFamilyDeclaration(FontFamily("Yeobaek Serif")) { addFontFace { addSource("noto_serif_kr.otf") } }
        }
        val initialPrefs = EpubPreferences(
            scroll = !opened.document.fixed && prefs.mode == EpubMode.SCROLL,
            spread = Spread.NEVER, columnCount = ColumnCount.ONE,
            publisherStyles = prefs.publisherStyles,
            fontFamily = when (prefs.font) { EpubFont.ORIGINAL -> null; EpubFont.SERIF -> FontFamily("Yeobaek Serif"); EpubFont.SANS -> FontFamily.SANS_SERIF; EpubFont.MONOSPACE -> FontFamily.MONOSPACE },
            fontSize = prefs.size.toDouble(), lineHeight = if (prefs.publisherStyles) null else prefs.line.toDouble(),
            pageMargins = if (prefs.publisherStyles) null else prefs.margin.toDouble(),
            backgroundColor = Color(colors.surface.toArgb()),
            textColor = if (opened.document.fixed) null else Color(colors.ink.toArgb()),
            theme = if (opened.document.fixed) Theme.LIGHT else when (prefs.paper) {
                TxtPaper.LIGHT -> Theme.LIGHT; TxtPaper.SEPIA -> Theme.SEPIA; TxtPaper.DARK -> Theme.DARK
                TxtPaper.FOLLOW_APP -> if (dark) Theme.DARK else Theme.LIGHT
            },
        )
        val factory = EpubNavigatorFactory(opened.document.publication).createFragmentFactory(
            initialLocator = anchor?.let(::restoreLocator), initialPreferences = initialPrefs, configuration = config,
            listener = object : EpubNavigatorFragment.Listener {
                override fun onResourceLoadFailed(href: Url, error: ReadError) {
                    if (active()) vm.action { throw EpubFailure("EPUB 본문 일부를 표시하지 못했습니다. 원본 파일을 확인해 주세요.") }
                }
                override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
                    vm.epubReader.follow(link)
                    return false
                }
                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    if (url.toString().startsWith("https://") || url.toString().startsWith("http://")) {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) }
                            .onFailure { vm.action { throw EpubFailure("외부 링크를 열 수 있는 브라우저가 없습니다.") } }
                    }
                }
            },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) { page = pageIndex; pageCount = totalPages }
                override fun onPageLoaded() {
                    if (!active()) return
                    // Readium changes its navigation state to Ready after this callback returns.
                    view?.post {
                        if (active() && view != null && generation == renderGeneration) {
                            loaded = true
                            vm.epubReader.rendered()
                            vm.epubReader.pending.value?.let { if (move(it)) vm.epubReader.pending.compareAndSet(it, null) }
                        }
                    }
                }
            },
        )
        childFragmentManager.fragmentFactory = factory
        val native = factory.instantiate(requireContext().classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
        navigator = native
        childFragmentManager.beginTransaction().replace(R.id.epub_navigator_container, native, "epub_native").commitNow()
        native.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean { if (vm.epub.value.menuVisibility == MenuVisibility.TAP) vm.epubReader.chrome.value = !vm.epubReader.chrome.value; return true }
        })
        locationJob = viewLifecycleOwner.lifecycleScope.launch {
            native.currentLocator.collectLatest { at ->
                if (loaded && active()) {
                    vm.epubReader.position(at)
                    vm.readingProgress(opened.token, vm.epubReader.completed.value)
                    if (vm.epubReader.userNavigated) {
                        delay(300)
                        if (active() && generation == renderGeneration && atEnd(opened, native)) {
                            vm.epubReader.finish(); vm.readingFinished(opened.token)
                        }
                    }
                }
            }
        }
    }
    private fun active() = isAdded && view != null && vm.epubReader.renderer === this && token != null &&
        vm.ui.value.epubReader?.token == token && vm.ui.value.epubReader?.document?.closed?.get() == false
    override suspend fun capture(): Locator? {
        val native = navigator ?: return null
        if (!loaded || !active()) return null
        return try {
            val rich = native.firstVisibleElementLocator()
            currentCoroutineContext().ensureActive()
            if (!loaded || !active() || !isAdded || view == null || navigator !== native) return null
            val current = native.currentLocator.value
            val at = if (rich == null || rich.href != current.href) current else rich.copy(title = current.title,
                locations = rich.locations.copy(progression = current.locations.progression, totalProgression = current.locations.totalProgression, position = current.locations.position))
            at.copy(locations = at.locations.copy(otherLocations = at.locations.otherLocations + ("yeobaekLayout" to layoutKey())))
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { native.currentLocator.value }
    }
    private fun layoutKey(): String = "${view?.width}:${view?.height}:${resources.displayMetrics.density}:${resources.configuration.fontScale}:$layoutPreferences"
    private fun restoreLocator(at: Locator): Locator {
        // A block may span two pages. With unchanged layout, restore its exact viewport progression
        // instead of the beginning of that block; after reflow, retain the semantic text anchor.
        return if (at.locations.progression != null && at.locations.otherLocations["yeobaekLayout"] == layoutKey())
            at.copy(text = Locator.Text(), locations = at.locations.copy(fragments = emptyList())) else at
    }
    override fun move(locator: Locator): Boolean {
        if (!loaded) return false
        vm.epubReader.userNavigation()
        return navigator?.go(restoreLocator(locator)) == true
    }
    private suspend fun atEnd(opened: OpenEpub, native: EpubNavigatorFragment): Boolean {
        if (!active() || navigator !== native || !loaded) return false
        val before = native.currentLocator.value
        val doc = opened.document
        if (doc.resourceIndex(before) != doc.publication.readingOrder.lastIndex) return false
        val end = if (doc.fixed) true else if (!native.overflow.value.scroll) page >= pageCount - 1 else {
            native.evaluateJavascript("(function(){var e=document.scrollingElement||document.documentElement;return e.scrollTop+e.clientHeight>=e.scrollHeight-2;})()") == "true"
        }
        return end && active() && navigator === native && native.currentLocator.value == before
    }
    override fun advance(forward: Boolean): Boolean {
        val opened = vm.ui.value.epubReader ?: return false
        val native = navigator ?: return false
        if (!loaded) return false
        vm.epubReader.userNavigation()
        val document = opened.document
        val resource = document.resourceIndex(native.currentLocator.value)
        val last = resource == document.publication.readingOrder.lastIndex
        if (!document.fixed && native.overflow.value.scroll) {
            val next = resource + if (forward) 1 else -1
            if (next in document.publication.readingOrder.indices) return native.go(document.publication.readingOrder[next])
            if (forward && last) viewLifecycleOwner.lifecycleScope.launch {
                if (atEnd(opened, native)) { vm.epubReader.finish(); vm.readingFinished(opened.token) }
            }
            return false
        }
        val moved = if (forward) native.goForward() else native.goBackward()
        if (forward && last && !moved && (document.fixed || page >= pageCount - 1)) {
            vm.epubReader.finish(); vm.readingFinished(opened.token)
        }
        return moved
    }
    override fun release() {
        loaded = false; locationJob?.cancel()
        navigator?.let { if (!childFragmentManager.isDestroyed) childFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss() }
        navigator = null
    }
    override fun onDestroyView() {
        if (vm.epubReader.renderer === this) { vm.epubReader.ready.value = false; vm.epubReader.renderer = null }
        loaded = false; navigator = null; locationJob?.cancel()
        super.onDestroyView()
    }
}
