(function () {
    'use strict';
    if (window.__vidLoaderDetector) {
        window.__vidLoaderDetector.scan();
        return;
    }

    // This code observes resources the browser already loads. It never unwraps DRM or fetches media.
    const reported = new Map();
    let inspectedScripts = new WeakMap();
    const observedDocuments = new WeakSet();
    const observedFrames = new WeakSet();
    const mediaPath = /\.(mp4|m4v|mov|webm|mkv|avi|flv|3gp|ts|mp3|m4a|aac|ogg|oga|opus|wav|flac|m3u8|mpd|jpg|jpeg|png|gif|webp|avif)(?:$|[?#])/i;
    const manifestType = /(?:mpegurl|dash\+xml)/i;
    const mediaType = /^(?:video|audio|image)\//i;
    const maxText = 2 * 1024 * 1024;
    let pending = 0;

    function resolve(value, base) {
        if (typeof value !== 'string' || !value.trim()) return '';
        try {
            const url = new URL(value, base || document.baseURI);
            if (!/^https?:$/.test(url.protocol) || url.username || url.password) return '';
            url.hash = '';
            return url.href;
        } catch (_) { return ''; }
    }

    function report(value, mime, source, element, base) {
        const url = resolve(value, base);
        if (!url || !(mediaPath.test(url) || mediaType.test(mime || '') || manifestType.test(mime || ''))) return;
        // A source node belongs to its media element; its own dimensions/duration are absent.
        if (element && /^source$/i.test(element.tagName || '') && element.parentElement
            && /^(video|audio)$/i.test(element.parentElement.tagName || '')) element = element.parentElement;
        const width = Number(element && (element.videoWidth || element.naturalWidth || element.width)) || 0;
        const height = Number(element && (element.videoHeight || element.naturalHeight || element.height)) || 0;
        const duration = Number(element && element.duration);
        const durationMs = Number.isFinite(duration) && duration > 0 ? Math.round(duration * 1000) : 0;
        const thumbnail = element && element.poster ? resolve(element.poster, base) : '';
        const playbackVerified = !!(element && /^(video|audio)$/i.test(element.tagName || '') && element.readyState >= 2
            && durationMs > 0 && (/^audio$/i.test(element.tagName) || (width > 0 && height > 0)));
        let advertisement = false;
        // These are active player states, rather than guessed duration/size thresholds.
        for (let node = element, depth = 0; node && depth < 10; node = node.parentElement, depth++) {
            if (/(?:^|\s)(?:vjs-ad-playing|jw-flag-ads)(?:\s|$)/.test(typeof node.className === 'string' ? node.className : '')) {
                advertisement = true; break;
            }
        }
        const signature = [mime || '', width, height, durationMs, thumbnail, advertisement, playbackVerified].join('|');
        if (reported.get(url) === signature) return;
        if (reported.size >= 1500 && !reported.has(url)) return;
        reported.set(url, signature);
        try {
            window.VidLoaderMedia.report(JSON.stringify({
                url: url, title: document.title || '', mime: mime || '',
                width: width, height: height, durationMs: durationMs, thumbnail: thumbnail, advertisement: advertisement, playbackVerified: playbackVerified, source: source || 'Browser'
                , frameUrl: element && element.ownerDocument && element.ownerDocument.URL || location.href
            }));
        } catch (_) { /* The native bridge may be absent in a cross-origin frame. */ }
    }

    function unescapeUrls(text) {
        let result = String(text || '');
        for (let i = 0; i < 2; i++) {
            result = result.replace(/\\u([0-9a-f]{4})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
                .replace(/\\\//g, '/').replace(/\\\\/g, '\\');
        }
        return result.replace(/&amp;/g, '&').replace(/&#x26;/gi, '&').replace(/&#38;/g, '&');
    }

    function inspectText(text, source, base) {
        if (typeof text !== 'string' || text.length > maxText) return;
        try { if (window.__vidLoaderInspectMetadata) window.__vidLoaderInspectMetadata(text, base); } catch (_) {}
        const decoded = unescapeUrls(text);
        const urls = decoded.match(/(?:https?:)?\/\/[^\s"'<>\\]+/gi) || [];
        urls.forEach(url => report(url, '', source, null, base));
        const fields = /["'](contentUrl|video_url|videoUrl|playbackUrl|playback_url|playUrl|play_url|src|url|hls|dash|thumbnailUrl)["']\s*:\s*["']([^"']+)["']/gi;
        let match;
        while ((match = fields.exec(decoded))) {
            const name = match[1].toLowerCase();
            const mime = name === 'hls' ? 'application/vnd.apple.mpegurl' : name === 'dash' ? 'application/dash+xml' : name === 'thumbnailurl' ? 'image/unknown' : /video|play|contenturl/.test(name) ? 'video/unknown' : '';
            report(match[2], mime, source, null, base);
        }
    }

    function scanDocument(doc) {
        try {
            observeDocument(doc);
            doc.querySelectorAll('video,audio,source,img').forEach(element => {
                const tag = element.tagName.toLowerCase();
                let mime = element.type || '';
                if (!mime && tag === 'source' && element.parentElement) mime = /^(video|audio)$/i.test(element.parentElement.tagName) ? element.parentElement.tagName.toLowerCase() + '/unknown' : '';
                if (!mime) mime = tag === 'video' || tag === 'audio' ? tag + '/unknown' : tag === 'img' ? 'image/unknown' : '';
                [element.currentSrc, element.src, element.getAttribute('data-src'), element.getAttribute('data-video')]
                    .forEach(url => report(url, mime, 'Page ' + tag, element, doc.baseURI));
                if (element.srcset) element.srcset.split(',').forEach(entry => report(entry.trim().split(/\s+/)[0], 'image/unknown', 'Page srcset', element, doc.baseURI));
            });
            doc.querySelectorAll('meta[property],meta[name]').forEach(element => {
                const key = (element.getAttribute('property') || element.getAttribute('name') || '').toLowerCase();
                let mime = '';
                if (/^og:video(?::(?:url|secure_url))?$/.test(key) || key === 'twitter:player:stream') mime = 'video/unknown';
                else if (/^og:audio(?::(?:url|secure_url))?$/.test(key)) mime = 'audio/unknown';
                else if (/^og:image(?::(?:url|secure_url))?$/.test(key) || key === 'twitter:image') mime = 'image/unknown';
                if (mime) report(element.content, mime, 'Page metadata', null, doc.baseURI);
            });
            doc.querySelectorAll('a[href],link[href]').forEach(element => report(element.href, '', 'Page link', null, doc.baseURI));
            doc.querySelectorAll('script').forEach(script => {
                const text = script.textContent || '';
                if (inspectedScripts.get(script) !== text) {
                    inspectedScripts.set(script, text);
                    inspectText(text, 'Page script', doc.baseURI);
                }
            });
            doc.querySelectorAll('iframe').forEach(frame => {
                report(frame.src, '', 'Frame URL', null, doc.baseURI);
                if (!observedFrames.has(frame)) {
                    observedFrames.add(frame);
                    frame.addEventListener('load', scheduleScan);
                }
                try { if (frame.contentDocument) scanDocument(frame.contentDocument); } catch (_) {}
            });
        } catch (_) {}
    }

    function scan() {
        scanDocument(document);
        try { if (window.__vidLoaderSiteScan) window.__vidLoaderSiteScan(); } catch (_) {}
        try { performance.getEntriesByType('resource').forEach(entry => report(entry.name, '', 'Network resource')); } catch (_) {}
        // Also handles navigating directly to a media URL.
        report(location.href, document.contentType || '', 'Current URL');
    }

    function scheduleScan() {
        if (pending) return;
        pending = setTimeout(() => { pending = 0; scan(); }, 350);
    }

    async function readMetadata(response) {
        const clone = response.clone();
        const reader = clone.body && clone.body.getReader();
        if (!reader) return;
        const chunks = []; let bytes = 0;
        try {
            while (true) {
                const item = await reader.read();
                if (item.done) break;
                bytes += item.value.byteLength;
                if (bytes > maxText) { reader.cancel(); return; }
                chunks.push(item.value);
            }
            const data = new Uint8Array(bytes); let offset = 0;
            chunks.forEach(chunk => { data.set(chunk, offset); offset += chunk.byteLength; });
            inspectText(new TextDecoder().decode(data), 'Network metadata', response.url);
        } finally { reader.releaseLock(); }
    }

    const originalFetch = window.fetch;
    if (typeof originalFetch === 'function') {
        window.fetch = function () {
            const args = arguments;
            const request = args[0];
            report(typeof request === 'string' ? request : request && request.url, '', 'Fetch request');
            return originalFetch.apply(this, args).then(response => {
                try {
                    const type = response.headers.get('Content-Type') || '';
                    report(response.url, type, 'Fetch response');
                    const size = Number(response.headers.get('Content-Length') || 0);
                    // Clone only bounded metadata responses, never video or audio bodies.
                    if (metadataType(type) && size <= maxText) readMetadata(response).catch(() => {});
                } catch (_) {}
                return response;
            });
        };
    }

    if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
        const originalOpen = XMLHttpRequest.prototype.open;
        const originalSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function (method, url) {
            this.__vidLoaderUrl = resolve(String(url || ''));
            report(this.__vidLoaderUrl, '', 'XHR request');
            return originalOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function () {
            this.addEventListener('load', () => {
                try {
                    const type = this.getResponseHeader('Content-Type') || '';
                    report(this.responseURL || this.__vidLoaderUrl, type, 'XHR response');
                    if (metadataType(type) && (!this.responseType || this.responseType === 'text')) inspectText(this.responseText, 'Network metadata', this.responseURL);
                    else if (this.responseType === 'json') inspectText(JSON.stringify(this.response), 'Network metadata', this.responseURL);
                } catch (_) {}
            }, { once: true });
            return originalSend.apply(this, arguments);
        };
    }

    try {
        new PerformanceObserver(list => list.getEntries().forEach(entry => report(entry.name, '', 'Network resource'))).observe({ type: 'resource', buffered: true });
    } catch (_) {}
    function metadataType(type) {
        // Players also return configurations as JavaScript/HTML/plain text, not only JSON.
        // Exclude audiovisual bodies even when their URL looks like a configuration endpoint.
        return /^(?:application\/(?:[\w.+-]*json|(?:x-)?javascript)|text\/(?:javascript|html|plain))(?:\s*;|$)/i.test(type || '');
    }
    function observeDocument(doc) {
        if (observedDocuments.has(doc)) return;
        try {
            if (doc.documentElement) new MutationObserver(scheduleScan).observe(doc.documentElement,
                { childList: true, subtree: true, characterData: true, attributes: true,
                    attributeFilter: ['src', 'srcset', 'data-src', 'data-video', 'content', 'poster'] });
            else return;
            ['loadstart', 'loadedmetadata', 'durationchange', 'playing', 'canplay', 'emptied', 'ended', 'DOMContentLoaded']
                .forEach(event => doc.addEventListener(event, scheduleScan, true));
            observedDocuments.add(doc);
        } catch (_) {}
    }
    observeDocument(document); document.addEventListener('DOMContentLoaded', () => { observeDocument(document); scheduleScan(); }, { once: true });
    // Some players update currentSrc internally or replace their configuration without a DOM
    // mutation. Keep observing through preroll completion while the page is visible.
    if (typeof setInterval === 'function') setInterval(() => { if (!document.hidden) scan(); }, 2000);
    window.__vidLoaderDetector = { scan: scan, reset: function () { reported.clear(); inspectedScripts = new WeakMap(); } };
    scan();
})();
