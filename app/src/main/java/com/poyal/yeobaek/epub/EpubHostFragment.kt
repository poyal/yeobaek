@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package com.poyal.yeobaek.epub

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.webkit.JavascriptInterface
import androidx.annotation.Keep
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
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.json.JSONArray
import org.json.JSONObject

/** Owns Readium's Fragment lifecycle; the activity ViewModel owns the open publication. */
class EpubHostFragment : Fragment(), EpubRenderer {
    private val vm: AppViewModel by activityViewModels()
    private var navigator: EpubNavigatorFragment? = null
    private var token: String? = null
    private var loaded = false
    private var locationJob: Job? = null
    private var navigationJob: Job? = null
    private var moveTimeout: Job? = null
    private var expectedHref: Url? = null
    private var configuredHref: Url? = null
    private var initialAnchor: Locator? = null
    private var chapterFooter = false
    private val navigationScript by lazy { requireContext().assets.open("epub-navigation.js").bufferedReader().use { it.readText() } }
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
        EpubGestureFrameLayout(requireContext()).apply {
            id = R.id.epub_navigator_container
            onUserMovement = { vm.epubReader.userNavigation() }
        }

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
                    // Overlay preferences must not rebuild the reading document or change its anchor.
                    combine(vm.epub, vm.app) { prefs, app ->
                        prefs.copy(menuVisibility = MenuVisibility.TAP, chapterButtons = EpubChapterButtons.ALWAYS) to app.theme
                    }.distinctUntilChanged().collectLatest { (prefs, theme) ->
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
        viewLifecycleOwner.lifecycleScope.launch {
            combine(vm.epubReader.endActionHeight, vm.epubReader.startInsetHeight) { end, start -> end to start }.collect {
                navigator?.takeIf { loaded }?.let { native -> configureNavigation(native, renderGeneration) }
            }
        }
    }
    private fun show(opened: OpenEpub, anchor: Locator?, prefs: EpubReaderPreferences, appTheme: AppTheme) {
        vm.epubReader.restoring()
        val generation = ++renderGeneration
        locationJob?.cancel(); navigationJob?.cancel(); moveTimeout?.cancel(); loaded = false
        expectedHref = null; configuredHref = null; initialAnchor = anchor
        vm.epubReader.ready.value = false
        vm.epubReader.navigation.value = EpubNavigationState()
        navigator?.let { childFragmentManager.beginTransaction().remove(it).commitNow() }
        layoutPreferences = "${prefs.mode}:${prefs.publisherStyles}:${prefs.font}:${prefs.size}:${prefs.line}:${prefs.margin}"
        chapterFooter = !opened.document.fixed && prefs.mode == EpubMode.SCROLL
        (view as? EpubGestureFrameLayout)?.apply {
            fixedLayout = opened.document.fixed; scrollMode = prefs.mode == EpubMode.SCROLL
            swipeToTurn = prefs.swipeToTurn; pinchZoom = prefs.pinchZoom
        }
        val dark = when (appTheme) {
            AppTheme.DARK -> true; AppTheme.LIGHT -> false
            AppTheme.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val app = if (dark) DarkPaper else LightPaper
        val colors = if (opened.document.fixed) when (prefs.canvas) { PdfCanvas.AUTO -> app; PdfCanvas.LIGHT -> LightPaper; PdfCanvas.DARK -> DarkPaper }
            else readerColors(prefs.paper, app)
        val config = EpubNavigatorFragment.Configuration(shouldApplyInsetsPadding = false,
            disablePageTurnsWhileScrolling = !prefs.swipeToTurn).apply {
            servedAssets += "noto_serif_kr.otf"
            addFontFamilyDeclaration(FontFamily("Yeobaek Serif")) { addFontFace { addSource("noto_serif_kr.otf") } }
            registerJavascriptInterface("YeobaekNavigation") { link ->
                NavigationBridge { value ->
                    view?.post {
                        val native = navigator
                        if (native != null && active() && generation == renderGeneration &&
                            sameEpubResource(native.currentLocator.value.href, link.url())) {
                            receiveNavigation(native, value)
                        }
                    }
                }
            }
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
                    if (active()) {
                        finishMove()
                        vm.action { throw EpubFailure("EPUB 본문 일부를 표시하지 못했습니다. 원본 파일을 확인해 주세요.") }
                    }
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
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    page = pageIndex; pageCount = totalPages
                    navigator?.takeIf { active() }?.let { updateNavigation(it) }
                }
                override fun onPageLoaded() {
                    if (!active()) return
                    // Readium changes its navigation state to Ready after this callback returns.
                    view?.post {
                        if (active() && view != null && generation == renderGeneration) {
                            navigator?.let { configureNavigation(it, generation) }
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
                    updateNavigation(native)
                    if (configuredHref?.let { sameEpubResource(it, at.href) } != true) configureNavigation(native, generation)
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
    private fun updateNavigation(native: EpubNavigatorFragment) {
        val document = vm.ui.value.epubReader?.document ?: return
        val at = native.currentLocator.value
        val index = document.resourceIndex(at)
        val old = vm.epubReader.navigation.value
        val scroll = !document.fixed && native.overflow.value.scroll
        val same = old.resourceIndex == index
        vm.epubReader.navigation.value = EpubNavigationState(
            resourceIndex = index, scroll = scroll,
            canGoPrevious = index > 0 || (!scroll && !document.fixed && page > 0),
            canGoNext = index < document.publication.readingOrder.lastIndex || (!scroll && !document.fixed && page < pageCount - 1),
            moving = expectedHref != null,
            atEnd = if (same) old.atEnd else false,
            endActionTop = if (same) old.endActionTop else null,
            outlineIndex = if (same && old.outlineIndex >= 0) old.outlineIndex else epubOutlineIndex(document.outline, at.href),
        )
    }
    private fun configureNavigation(native: EpubNavigatorFragment, generation: Int) {
        navigationJob?.cancel()
        navigationJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!active() || navigator !== native || renderGeneration != generation) return@launch
            val opened = vm.ui.value.epubReader ?: return@launch
            val href = native.currentLocator.value.href
            if (!opened.document.fixed) {
                val outline = JSONArray()
                opened.document.outline.forEachIndexed { index, entry ->
                    val url = entry.link.url()
                    if (sameEpubResource(url, href)) outline.put(JSONObject().put("index", index)
                        .put("fragment", Uri.decode(url.toString().substringAfter('#', ""))))
                }
                val options = JSONObject().put("outline", outline)
                    .put("scroll", native.overflow.value.scroll)
                    .put("footer", vm.epub.value.mode == EpubMode.SCROLL && native.overflow.value.scroll)
                    .put("height", vm.epubReader.endActionHeight.value)
                    .put("top", vm.epubReader.startInsetHeight.value)
                    .put("viewportHeight", view?.height?.coerceAtLeast(1) ?: 1)
                native.evaluateJavascript(navigationScript.replace("YEOBAEK_NAVIGATION_OPTIONS", options.toString()))
                currentCoroutineContext().ensureActive()
            }
            if (!active() || navigator !== native || renderGeneration != generation ||
                !sameEpubResource(native.currentLocator.value.href, href)) return@launch
            configuredHref = href
            // Readium initially restores before the native footer's empty slot exists.
            // Reapply once after reserving its measured height; this is not user navigation.
            initialAnchor?.let { at ->
                initialAnchor = null
                if (chapterFooter && sameEpubResource(at.href, href)) native.go(restoreLocator(at))
            }
            loaded = true
            updateNavigation(native)
            vm.epubReader.rendered()
            vm.epubReader.position(native.currentLocator.value)
            vm.epubReader.pending.value?.let { if (move(it)) vm.epubReader.pending.compareAndSet(it, null) }
        }
    }
    private fun receiveNavigation(native: EpubNavigatorFragment, value: String) {
        val sample = runCatching { JSONObject(value) }.getOrNull() ?: return
        val href = native.currentLocator.value.href
        if (expectedHref?.let { sameEpubResource(it, href) } == true) finishMove()
        updateNavigation(native)
        val top = if (sample.isNull("top")) null else sample.optDouble("top").takeIf { it.isFinite() && it in -10.0..1.0 }?.toFloat()
        val document = vm.ui.value.epubReader?.document ?: return
        val outlineIndex = sample.optInt("outlineIndex", -1).takeIf { index ->
            index in document.outline.indices && sameEpubResource(document.outline[index].link.url(), href)
        } ?: epubOutlineIndex(document.outline, href)
        vm.epubReader.navigation.update { it.copy(atEnd = sample.optBoolean("atEnd"), endActionTop = top, outlineIndex = outlineIndex) }
        if (loaded && expectedHref == null) vm.epubReader.pending.value?.let {
            if (move(it)) vm.epubReader.pending.compareAndSet(it, null)
        }
    }
    private fun beginMove(href: Url) {
        expectedHref = href
        vm.epubReader.navigation.update { it.copy(moving = true, endActionTop = null, atEnd = false) }
        moveTimeout?.cancel()
        moveTimeout = viewLifecycleOwner.lifecycleScope.launch {
            delay(15_000)
            if (active() && expectedHref != null) {
                finishMove()
                vm.action { throw EpubFailure("장 이동을 완료하지 못했습니다. 다시 시도해 주세요.") }
            }
        }
    }
    private fun finishMove() {
        expectedHref = null; moveTimeout?.cancel(); moveTimeout = null
        vm.epubReader.navigation.update { it.copy(moving = false) }
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
            var at = if (rich == null || rich.href != current.href) current else rich.copy(title = current.title,
                locations = rich.locations.copy(progression = current.locations.progression, totalProgression = current.locations.totalProgression, position = current.locations.position))
            if (chapterFooter && vm.epubReader.navigation.value.atEnd &&
                (rich == null || rich.locations.cssSelector?.contains("yeobaek-navigation-end") == true)) {
                val raw = native.evaluateJavascript("window.__yeobaekNavigation && window.__yeobaekNavigation.lastContentLocator()")
                currentCoroutineContext().ensureActive()
                if (!active() || navigator !== native || !sameEpubResource(native.currentLocator.value.href, current.href)) return null
                val last = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
                last?.optString("selector")?.takeIf { it.isNotBlank() }?.let { selector ->
                    at = current.copy(text = Locator.Text(highlight = last.optString("text").takeIf { it.isNotBlank() }),
                        locations = current.locations.copy(otherLocations = current.locations.otherLocations + ("cssSelector" to selector)))
                }
            }
            at.copy(locations = at.locations.copy(otherLocations = at.locations.otherLocations + ("yeobaekLayout" to layoutKey())))
        }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { native.currentLocator.value }
    }
    private fun layoutKey(): String = "${view?.width}:${view?.height}:${resources.displayMetrics.density}:${resources.configuration.fontScale}:$layoutPreferences" +
        if (chapterFooter) ":nav2:${vm.epubReader.startInsetHeight.value}:${vm.epubReader.endActionHeight.value}" else ""
    private fun restoreLocator(at: Locator): Locator {
        // A block may span two pages. With unchanged layout, restore its exact viewport progression
        // instead of the beginning of that block; after reflow, retain the semantic text anchor.
        return if (at.locations.progression != null && at.locations.otherLocations["yeobaekLayout"] == layoutKey())
            at.copy(text = Locator.Text(), locations = at.locations.copy(fragments = emptyList())) else at
    }
    override fun move(locator: Locator): Boolean {
        if (!loaded || expectedHref != null) return false
        vm.epubReader.userNavigation()
        val native = navigator ?: return false
        if (native.overflow.value.scroll && !sameEpubResource(native.currentLocator.value.href, locator.href)) beginMove(locator.href)
        val moved = native.go(restoreLocator(locator))
        if (!moved) finishMove()
        return moved
    }
    private suspend fun atEnd(opened: OpenEpub, native: EpubNavigatorFragment): Boolean {
        if (!active() || navigator !== native || !loaded) return false
        val before = native.currentLocator.value
        val doc = opened.document
        if (doc.resourceIndex(before) != doc.publication.readingOrder.lastIndex) return false
        val end = if (doc.fixed) true else if (!native.overflow.value.scroll) page >= pageCount - 1 else {
            native.evaluateJavascript("(function(){if(window.__yeobaekNavigation)return window.__yeobaekNavigation.atEnd();var e=document.scrollingElement||document.documentElement;return e.scrollTop+e.clientHeight>=e.scrollHeight-2;})()") == "true"
        }
        return end && active() && navigator === native && native.currentLocator.value == before
    }
    override fun advance(forward: Boolean): Boolean {
        val opened = vm.ui.value.epubReader ?: return false
        val native = navigator ?: return false
        if (!loaded || expectedHref != null) return false
        vm.epubReader.userNavigation()
        val document = opened.document
        val resource = document.resourceIndex(native.currentLocator.value)
        val last = resource == document.publication.readingOrder.lastIndex
        if (!document.fixed && native.overflow.value.scroll) {
            val next = resource + if (forward) 1 else -1
            if (next in document.publication.readingOrder.indices) {
                val link = document.publication.readingOrder[next]
                beginMove(link.url())
                val moved = native.go(link)
                if (!moved) finishMove()
                return moved
            }
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
        loaded = false; locationJob?.cancel(); navigationJob?.cancel(); finishMove()
        vm.epubReader.navigation.value = EpubNavigationState()
        navigator?.let { if (!childFragmentManager.isDestroyed) childFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss() }
        navigator = null
    }
    override fun onDestroyView() {
        if (vm.epubReader.renderer === this) { vm.epubReader.ready.value = false; vm.epubReader.renderer = null }
        loaded = false; navigator = null; locationJob?.cancel(); navigationJob?.cancel(); moveTimeout?.cancel()
        super.onDestroyView()
    }
}

@Keep
private class NavigationBridge(private val receive: (String) -> Unit) {
    @JavascriptInterface fun report(value: String) { receive(value) }
}
