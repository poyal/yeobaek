// App-owned navigation chrome. Original EPUB scripts remain disabled.
// The empty slot lets Compose draw a PaperButton after the last sentence, without
// changing the original element hierarchy or adding text to searches/bookmarks.
(function (options) {
    const key = '__yeobaekNavigation';
    if (window[key]) {
        window[key].update(options);
        return;
    }
    let config = options;
    let slot = null;
    let frame = 0;
    let last = '';
    let anchors = [];
    const originalTopPadding = document.body.style.getPropertyValue('padding-top');
    const originalTopPriority = document.body.style.getPropertyPriority('padding-top');
    let topInsetApplied = false;
    const root = () => document.scrollingElement || document.documentElement;
    const verticalText = () => /vertical|sideways/.test(getComputedStyle(document.body).writingMode) ||
        /vertical|sideways/.test(getComputedStyle(document.documentElement).writingMode);
    function configure() {
        // Chrome overlays a stable, full-height viewport. Reserve its clearance
        // only at the beginning of the document, so it scrolls away with the text.
        // Preserve the publisher's padding and original element/selector hierarchy.
        if (topInsetApplied) {
            if (originalTopPadding) document.body.style.setProperty('padding-top', originalTopPadding, originalTopPriority);
            else document.body.style.removeProperty('padding-top');
            topInsetApplied = false;
        }
        if (config.footer && !verticalText() && config.top > 0) {
            const padding = getComputedStyle(document.body).paddingTop;
            const inset = config.top * innerHeight / config.viewportHeight;
            document.body.style.setProperty('padding-top', 'calc(' + padding + ' + ' + inset + 'px)', 'important');
            topInsetApplied = true;
        }
        anchors = config.outline.map(item => ({
            index: item.index,
            element: item.fragment ? document.getElementById(item.fragment) : document.body
        })).filter(item => item.element);
        // A native footer is supported in the vertical scrolling viewport. The
        // existing horizontal/vertical-text renderer retains its own geometry.
        const needsSlot = config.footer && !verticalText() && config.height > 0;
        if (needsSlot && !slot) {
            slot = document.createElement('div');
            let id = 'yeobaek-navigation-end';
            while (document.getElementById(id)) id += '-slot';
            slot.id = id;
            slot.setAttribute('data-yeobaek-navigation', '');
            slot.setAttribute('aria-hidden', 'true');
            document.body.appendChild(slot);
        } else if (!needsSlot && slot) {
            slot.remove();
            slot = null;
        }
        if (slot) {
            // Android pixels / CSS pixels; this also handles WebView text zoom.
            const height = config.height * innerHeight / config.viewportHeight;
            slot.style.cssText = 'display:block!important;clear:both!important;box-sizing:border-box!important;' +
                'position:relative!important;float:none!important;margin:0!important;padding:0!important;' +
                'border:0!important;width:100%!important;min-width:0!important;max-width:none!important;' +
                'height:' + height + 'px!important;min-height:' + height + 'px!important;max-height:' + height + 'px!important;' +
                'overflow:hidden!important;pointer-events:none!important;';
        }
    }
    function report() {
        frame = 0;
        const e = root();
        const horizontal = verticalText() || !config.scroll;
        const rtl = getComputedStyle(document.documentElement).direction === 'rtl' ||
            getComputedStyle(document.body).writingMode === 'vertical-rl';
        let selected = anchors.length ? anchors[0].index : -1;
        for (const item of anchors) {
            const rect = item.element.getBoundingClientRect();
            if (item.element === document.body || (horizontal
                ? (rtl ? rect.right >= innerWidth - 2 : rect.left <= 2)
                : rect.top <= 2)) selected = item.index;
        }
        let top = null;
        let atEnd;
        if (slot) {
            const rect = slot.getBoundingClientRect();
            atEnd = rect.top <= innerHeight + 2;
            if (rect.top < innerHeight && rect.bottom > 0) top = rect.top / innerHeight;
        } else {
            atEnd = horizontal
                ? Math.abs(e.scrollLeft) + e.clientWidth >= e.scrollWidth - 2
                : e.scrollTop + e.clientHeight >= e.scrollHeight - 2;
        }
        const value = JSON.stringify({ atEnd, top, outlineIndex: selected });
        if (value !== last) {
            last = value;
            YeobaekNavigation.report(value);
        }
    }
    function schedule() {
        if (!frame) frame = requestAnimationFrame(report);
    }
    window[key] = {
        update(value) { config = value; last = ''; configure(); schedule(); },
        // When a large native footer fills the viewport, retain the last real
        // content element as the semantic anchor instead of bookmarking chrome.
        lastContentLocator() {
            if (!slot) return null;
            let element = slot.previousElementSibling;
            const readable = e => e && !/^(SCRIPT|STYLE|LINK|META)$/.test(e.tagName) &&
                (e.textContent.trim() || e.matches('img,svg') || e.querySelector('img,svg'));
            while (element && !readable(element)) element = element.previousElementSibling;
            if (!element) return null;
            let child;
            while ((child = Array.from(element.children).reverse().find(readable))) element = child;
            const path = [];
            for (let node = element; node && node !== document.documentElement; node = node.parentElement) {
                if (node.id) { path.unshift('#' + CSS.escape(node.id)); break; }
                const siblings = Array.from(node.parentElement.children).filter(e => e.tagName === node.tagName);
                path.unshift(node.tagName.toLowerCase() + ':nth-of-type(' + (siblings.indexOf(node) + 1) + ')');
            }
            return { selector: path.join(' > '), text: element.textContent.trim().slice(0, 256) };
        },
        atEnd() { return slot ? slot.getBoundingClientRect().top <= innerHeight + 2 :
            verticalText() ? Math.abs(root().scrollLeft) + root().clientWidth >= root().scrollWidth - 2 :
                root().scrollTop + root().clientHeight >= root().scrollHeight - 2; }
    };
    addEventListener('scroll', schedule, { passive: true });
    addEventListener('resize', () => { configure(); schedule(); });
    document.addEventListener('load', schedule, true);
    new ResizeObserver(schedule).observe(document.body);
    if (document.fonts) document.fonts.ready.then(schedule);
    configure();
    schedule();
})(YEOBAEK_NAVIGATION_OPTIONS);
