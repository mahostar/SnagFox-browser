(function () {
    'use strict';
    if (window.__vidLoaderSiteMetadata) {
        window.__vidLoaderSiteMetadata.scan();
        return;
    }

    // Independently written extraction of media metadata already present in a page.
    // Site layouts and public JSON schemas change; no expired API tokens or requests are replayed.
    const maxText = 8 * 1024 * 1024;
    const maxNodes = 18000;
    const maxDepth = 16;
    const reported = new Map();
    const manifests = new Set();
    let scriptValues = new WeakMap();
    const requestedMetadata = new Set();
    const directKeys = /^(?:contenturl|video_url|videourl|playable_url(?:_quality_hd)?|browser_native_(?:hd|sd)_url|playback_?url|play_?url|playaddr|downloadaddr|downloadlink|download_?url|fallback_url|audio_?url|hls(?:_url|auto)?|dash(?:_url)?|mp4(?:_\d{3,4})?|url\d{3,4}|webm|video_(?:alt_)?url\d*)$/i;
    const mediaContainers = /(?:video_versions|video_resources|video_resource|video_info|variants|progressive|playaddr|downloadaddr|bitrateinfo|bit_rate|playback|streams|sources|medias|packagedmedia|reddit_video|hls|dash|videoarray|videos|video_details|mediadefinitions?|playurlinfo|xplayersettings|videoentity|video_player|stream_data|encodings|el-formats|file_versions)/i;
    const extension = /\.(?:mp4|m4v|mov|webm|mkv|3gp|m3u8|mpd|mp3|m4a|aac|ogg|opus|wav|flac)(?:$|[?#])/i;
    let pending = 0;
    let observer;

    function normalize(value, base) {
        if (typeof value !== 'string' || value.length > 32768) return '';
        value = value.trim();
        if (!/^(?:https?:|\/\/|\/|\.{1,2}\/)/i.test(value) && !extension.test(value)) return '';
        try {
            const url = new URL(value.replace(/&amp;/g, '&'), base || document.baseURI || location.href);
            if (!/^https?:$/.test(url.protocol) || url.username || url.password) return '';
            url.hash = '';
            return url.href;
        } catch (_) { return ''; }
    }

    function own(object, key) {
        if (!object || typeof object !== 'object') return undefined;
        try {
            const descriptor = Object.getOwnPropertyDescriptor(object, key);
            return descriptor && Object.prototype.hasOwnProperty.call(descriptor, 'value') ? descriptor.value : undefined;
        } catch (_) { return undefined; }
    }

    function mimeFor(key, path, object, url) {
        // Original GetPear.june envelope declares fileType per item.
        if (/(?:^|\.)videoArray(?:\.|$)/i.test(path)) {
            const fileType = own(object, 'fileType');
            if (fileType === 2) return 'video/mp4';
            if (fileType === 3) return 'image/png';
            if (typeof fileType === 'number') return 'audio/mpeg';
        }
        const declared = own(object, 'mime') || own(object, 'mime_type') || own(object, 'mimeType') || own(object, 'content_type') || own(object, 'type');
        if (typeof declared === 'string' && /^(?:video|audio)\/|mpegurl|dash\+xml/i.test(declared)) return declared;
        const format = own(object, 'format') || declared || '';
        if (/hls|m3u8/i.test(key + ' ' + path + ' ' + url + ' ' + format)) return 'application/vnd.apple.mpegurl';
        if (/dash|mpd/i.test(key + ' ' + path + ' ' + url + ' ' + format)) return 'application/dash+xml';
        if (/audio|music|sound/i.test(key + ' ' + path)) return 'audio/unknown';
        if (/mp4/i.test(format)) return 'video/mp4';
        return 'video/unknown';
    }

    function dimension(value) {
        const number = Number(value);
        return Number.isFinite(number) && number > 0 && number <= 16384 ? Math.round(number) : 0;
    }

    function qualityHeight(value) {
        if (typeof value !== 'string' && typeof value !== 'number') return 0;
        const text = String(value).trim();
        if (/^\d{3,4}p?$/i.test(text)) return dimension(text.replace(/p$/i, ''));
        const pixels = text.match(/(?:^|\D)(\d{3,4})p(?:\D|$)/i);
        if (pixels) return dimension(pixels[1]);
        if (/^4k$/i.test(text)) return 2160;
        if (/^8k$/i.test(text)) return 4320;
        if (/^(?:HD|HQ)$/i.test(text)) return 720;
        if (/^SD$/i.test(text)) return 360;
        if (/^(?:original|High Quality|Automatico)$/i.test(text)) return 1080;
        return 0;
    }

    function details(object, parent) {
        const result = Object.assign({}, parent || {});
        if (!object || typeof object !== 'object') return result;
        const adFlags = ['isAd', 'is_ad', 'advertisement', 'isAdvertisement', 'is_advertisement', 'ad', 'isPreroll', 'is_preroll'];
        if (adFlags.some(key => own(object, key) === true || own(object, key) === 1)) result.advertisement = true;
        result.width = dimension(own(object, 'width') || own(object, 'Width') || own(object, 'video_width') || own(object, 'original_width') || own(object, 'display_width')) || result.width || 0;
        result.height = dimension(own(object, 'height') || own(object, 'Height') || own(object, 'video_height') || own(object, 'original_height') || own(object, 'display_height')) || result.height || 0;
        const quality = own(object, 'quality') || own(object, 'quality_label') || own(object, 'qualityLabel') || own(object, 'label') || own(object, 'labelShort') || own(object, 'resolution') || own(object, 'format_note');
        if (quality !== undefined) {
            const pair = String(quality).match(/(\d{2,5})\s*[x\u00d7]\s*(\d{2,5})/i);
            if (pair) { result.width = dimension(pair[1]); result.height = dimension(pair[2]); }
            else result.height = qualityHeight(quality) || result.height || 0;
        }
        const title = own(object, 'title') || own(object, 'pageTitle') || own(object, 'name') || own(object, 'desc') || own(object, 'description');
        if (typeof title === 'string' && title.length && title.length <= 1000) result.title = title;
        let thumbnail = own(object, 'thumbnailUrl') || own(object, 'thumbnail') || own(object, 'preview_url') || own(object, 'poster') || own(object, 'cover') || own(object, 'originCover') || own(object, 'dynamicCover') || own(object, 'thumb') || own(object, 'image');
        if (Array.isArray(thumbnail)) thumbnail = thumbnail[0];
        if (thumbnail && typeof thumbnail === 'object') {
            const urls = own(thumbnail, 'urlList') || own(thumbnail, 'url_list');
            thumbnail = own(thumbnail, 'url') || (Array.isArray(urls) && urls[0]);
        }
        if (typeof thumbnail === 'string') result.thumbnail = thumbnail;
        const duration = Number(own(object, 'duration') || own(object, 'video_duration'));
        if (Number.isFinite(duration) && duration > 0) result.duration = duration;
        const bytes = Number(own(object, 'size') || own(object, 'fileSize') || own(object, 'filesize'));
        if (Number.isFinite(bytes) && bytes > 0) result.bytes = bytes;
        return result;
    }

    function emit(value, key, path, object, inherited, base, frameUrl) {
        // Original zw0.Y -> hm0.method_122 -> fg.w: TXXX's videofile API
        // serializes the path and query separately, with five Cyrillic lookalikes.
        // This decoder belongs to that exact endpoint, not generic video_url data.
        if (key === 'video_url' && isOriginalVideofileEndpoint(base) && !normalize(value, base)) value = decodeOriginalVideofileUrl(value, base);
        // xHamster player JSON can conceal a URL in a small reversible envelope.
        // This is URL serialization, not content decryption or a license mechanism.
        if (/xplayersettings/i.test(path) && !normalize(value, base)) value = decodePlayerUrl(value);
        const url = normalize(value, base);
        if (!url) return;
        const info = details(object, inherited);
        // A named ad subtree is positive evidence; substrings in arbitrary titles/URLs are not.
        if (/(?:^|\.)(?:ads?|advertisements?|preroll|midroll|postroll|vast|vpaid|ad_?sources?|ad_?breaks?)(?:\.|$)/i.test(path)) info.advertisement = true;
        const siblingQuality = own(object, key + '_text');
        const keyedQuality = key.match(/^(?:mp4_|url|video_url_?)(\d{3,4})$/i);
        info.height = qualityHeight(siblingQuality) || (keyedQuality && dimension(keyedQuality[1])) || info.height || 0;
        const pixels = url.match(/(?:\/|_)(\d{2,5})x(\d{2,5})(?:\/|_|\.)/i);
        if (pixels) { info.width = info.width || dimension(pixels[1]); info.height = info.height || dimension(pixels[2]); }
        if (!extension.test(url) && !directKeys.test(key) && !mediaContainers.test(path)) return;
        if ((own(object, 'remote') === true && /videourl/i.test(key)) || /\/(?:video\/)?get_media(?:[/?]|$)/i.test(url)) {
            requestPlayerMetadata(url, base, frameUrl);
            return;
        }
        const mime = mimeFor(key, path, object, url);
        const originalVideoItem = /(?:^|\.)videoArray(?:\.|$)/i.test(path) && own(object, 'fileType') === 2;
        const audioUrl = originalVideoItem && !info.advertisement ? normalize(info.pairedAudioUrl, info.originalOwnerPage || base) : '';
        const ownerPage = info.originalOwnerPage || frameUrl || location.href;
        const signature = [mime, info.width || 0, info.height || 0, info.title || '', info.thumbnail || '', info.duration || 0, info.bytes || -1, Boolean(info.advertisement), audioUrl, ownerPage].join('|');
        if (reported.get(url) === signature || (reported.size >= 1500 && !reported.has(url))) return;
        reported.set(url, signature);
        try {
            window.VidLoaderMedia.report(JSON.stringify({
                url: url, mime: mime, width: info.width || 0, height: info.height || 0,
                title: info.title || document.title || '', source: 'Page media metadata',
                frameUrl: ownerPage,
                thumbnail: normalize(info.thumbnail || ((document.querySelector && document.querySelector('meta[property="og:image"]')) || {}).content || '', base),
                durationMs: Math.round((info.duration || 0) * 1000), bytes: info.bytes || -1, advertisement: Boolean(info.advertisement),
                pairedAudio: Boolean(audioUrl), audioUrl: audioUrl
            }));
        } catch (_) {}
    }

    function isOriginalVideofileEndpoint(value) {
        try {
            const url = new URL(value);
            return url.protocol === 'https:' && url.hostname === 'txxx.com' && url.pathname === '/api/videofile.php' && /^\d+$/.test(url.searchParams.get('video_id') || '');
        } catch (_) { return false; }
    }

    function decodeOriginalVideofileUrl(value, base) {
        if (typeof value !== 'string' || value.length > 32768 || typeof atob !== 'function') return '';
        try {
            const substitutes = { '\u0415': 'E', '\u041c': 'M', '\u0410': 'A', '\u0421': 'C', '\u0412': 'B' };
            const parts = value.replace(/[\u0415\u041c\u0410\u0421\u0412]/g, letter => substitutes[letter]).split(',');
            if (parts.length > 2 || !parts[0]) return '';
            const decode = part => {
                if (!/^[A-Za-z0-9+/\s]*={0,2}$/.test(part)) throw new Error('Invalid videofile serialization');
                const binary = atob(part);
                return typeof TextDecoder === 'function' ? new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(binary, letter => letter.charCodeAt(0))) : binary;
            };
            let path = decode(parts[0]);
            // The original prepends the page origin to relative paths. Reject
            // non-web schemes instead of interpreting them as a local file name.
            if (!/^https?:\/\//i.test(path) && !/^\/(?!\/)/.test(path)) return '';
            if (parts.length === 2) path += '?' + decode(parts[1]);
            if (/[\u0000-\u001f\u007f]/.test(path)) return '';
            return normalize(path, new URL(base).origin + '/');
        } catch (_) { return ''; }
    }

    function originalMetadataFallback(page) {
        try {
            const url = new URL(page);
            // Bundled method_122 is exactly https://txxx.com/videos/;
            // zw0.a0 extracts the numeric identifier from /videos/<id>/.
            if (url.protocol !== 'https:' || url.hostname !== 'txxx.com') return;
            const match = url.pathname.match(/^\/videos\/(\d+)\//);
            if (match) requestPlayerMetadata(url.origin + '/api/videofile.php?video_id=' + match[1], page, page);
        } catch (_) {}
    }

    function decodePlayerUrl(value) {
        if (typeof value !== 'string' || value.length > 32768) return '';
        try {
            if (/^[0-9a-f]+$/i.test(value) && value.length % 2 === 0 && value.length >= 10) {
                const bytes = new Uint8Array(value.match(/../g).map(pair => parseInt(pair, 16)));
                const algorithm = bytes[0];
                if (algorithm < 1 || algorithm > 7) return '';
                let state = (bytes[1] | bytes[2] << 8 | bytes[3] << 16 | bytes[4] << 24) >>> 0;
                const result = new Uint8Array(bytes.length - 5);
                for (let index = 5; index < bytes.length; index++) {
                    let mask;
                    switch (algorithm) {
                        case 1:
                            state = (Math.imul(state, 1664525) + 1013904223) >>> 0; mask = state; break;
                        case 2:
                            state ^= state << 13; state ^= state >>> 17; state ^= state << 5; mask = state; break;
                        case 3:
                            state = (state + 0x9e3779b9) >>> 0;
                            mask = Math.imul(state ^ state >>> 16, 0x85ebca77);
                            mask = Math.imul(mask ^ mask >>> 13, 0xc2b2ae3d);
                            mask ^= mask >>> 16; break;
                        case 4:
                            state = (state + 1831565813) >>> 0;
                            mask = ((state << 7 | state >>> 25) + 0x9e3779b9) >>> 0;
                            mask = Math.imul(mask ^ mask >>> 11, 668265261); break;
                        case 5:
                            state ^= state << 7; state ^= state >>> 9; state ^= state << 8;
                            state = (state + 0xa5a5a5a5) >>> 0; mask = state; break;
                        case 6:
                            state = (Math.imul(state, 747796405) + 2891336453) >>> 0;
                            mask = (state ^ state >>> 18) >>> (state >>> 27 & 31); break;
                        case 7:
                            state = (state + 0x9e3779b9) >>> 0;
                            mask = Math.imul(state ^ state << 5, 2146121005);
                            mask = Math.imul(mask ^ mask >>> 15, 0x846ca68b); break;
                    }
                    result[index - 5] = bytes[index] ^ mask & 255;
                }
                return new TextDecoder('utf-8', { fatal: true }).decode(result);
            }
            if (typeof atob !== 'function') return '';
            const decoded = atob(value);
            if (decoded.startsWith('rot13_')) return decoded.slice(6).replace(/[a-z]/gi, character => {
                const start = character <= 'Z' ? 65 : 97;
                return String.fromCharCode(start + (character.charCodeAt(0) - start + 13) % 26);
            });
            if (decoded.startsWith('xor_')) {
                const key = 'xh7999';
                return Array.from(decoded.slice(4), (character, index) => String.fromCharCode(character.charCodeAt(0) ^ key.charCodeAt(index % key.length))).join('');
            }
        } catch (_) {}
        return '';
    }

    // Follow only a metadata URL explicitly declared by the page's media-definition
    // object. The browser supplies its current cookies; no API token is fabricated.
    function requestPlayerMetadata(url, base, frameUrl) {
        if (requestedMetadata.has(url) || requestedMetadata.size >= 16 || typeof window.fetch !== 'function') return;
        requestedMetadata.add(url);
        (async () => {
            let controller, timeout;
            try {
                if (typeof AbortController === 'function') { controller = new AbortController(); timeout = setTimeout(() => controller.abort(), 12000); }
                const response = await window.fetch(url, { credentials: 'include', signal: controller && controller.signal });
                if (!response.ok) return;
                const length = Number(response.headers.get('content-length')) || 0;
                const type = response.headers.get('content-type') || '';
                if (length > maxText || /^(?:video|audio|image)\//i.test(type)) return;
                let text = '';
                if (response.body && response.body.getReader && typeof TextDecoder === 'function') {
                    const reader = response.body.getReader(), decoder = new TextDecoder();
                    let size = 0;
                    try {
                        while (true) {
                            const chunk = await reader.read();
                            if (chunk.done) break;
                            size += chunk.value.byteLength;
                            if (size > maxText) { await reader.cancel(); return; }
                            text += decoder.decode(chunk.value, { stream: true });
                        }
                        text += decoder.decode();
                    } finally { reader.releaseLock(); }
                } else if (length > 0 && length <= maxText) text = await response.text();
                if (text.length <= maxText) inspectText(text, response.url || url || base, frameUrl);
            } catch (_) { /* Cross-origin metadata may require the player's own request. */ }
            finally { if (timeout && typeof clearTimeout === 'function') clearTimeout(timeout); }
        })();
    }

    function manifest(value, base, title) {
        if (typeof value !== 'string' || value.length > maxText) return;
        let xml = value;
        if (xml.includes('&lt;MPD')) xml = xml.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&');
        if (!/<(?:[A-Za-z_][\w.-]*:)?MPD(?:\s|>)/.test(xml)) return;
        let hash = 2166136261;
        for (let i = 0; i < xml.length; i++) hash = Math.imul(hash ^ xml.charCodeAt(i), 16777619);
        const id = xml.length + ':' + (hash >>> 0);
        if (manifests.has(id) || manifests.size >= 32) return;
        manifests.add(id);
        try { window.VidLoaderMedia.inlineManifest(xml, base || location.href, title || document.title || ''); } catch (_) {}
    }

    function inspectObject(root, base, rootPath, frameUrl) {
        const seen = new WeakSet();
        const state = { nodes: 0, text: 0 };
        function visit(value, path, inherited, depth) {
            if (++state.nodes > maxNodes || depth > maxDepth || value === null || value === undefined) return;
            if (typeof value === 'string') {
                if (value.length > maxText || (state.text += value.length) > maxText) return;
                if (/dash_manifest|dashmanifest|mpd/i.test(path)) manifest(value, base, inherited && inherited.title);
                const trimmed = value.trim();
                if ((trimmed[0] === '{' || trimmed[0] === '[') && trimmed.length <= maxText) {
                    try { visit(JSON.parse(trimmed), path, inherited, depth + 1); } catch (_) {}
                }
                return;
            }
            if (typeof value !== 'object' || seen.has(value)) return;
            seen.add(value);
            if (Array.isArray(value)) {
                for (let i = 0; i < Math.min(value.length, 2000) && state.nodes < maxNodes; i++) {
                    const child = value[i];
                    if (typeof child === 'string' && /urllist|playaddr|downloadaddr|sources|variants|progressive/i.test(path)) emit(child, 'url', path, null, inherited, base, frameUrl);
                    // fg.v in the original consumes stream_data["720p"][0],
                    // falling back to stream_data.m3u8 when no file quality exists.
                    if (typeof child === 'string' && /stream_data\.(?:\d{3,4}p?|m3u8)$/.test(path)) {
                        const quality = path.split('.').pop();
                        emit(child, quality === 'm3u8' ? 'hls' : 'video_url', path,
                            { quality: quality }, inherited, base, frameUrl);
                    }
                    visit(child, path, inherited, depth + 1);
                }
                return;
            }
            const info = details(value, inherited);
            // x03.june pairs the audioUrl of this specific envelope with its
            // videoArray items. An unrelated audioUrl in generic player/ad
            // metadata does not establish an audio/video relationship.
            const originalEnvelope = Array.isArray(own(value, 'videoArray')) && typeof own(value, 'fatherUrl') === 'string';
            if (originalEnvelope) {
                info.originalOwnerPage = normalize(own(value, 'fatherUrl'), base);
                info.pairedAudioUrl = info.originalOwnerPage ? own(value, 'audioUrl') : '';
            }
            let keys;
            try { keys = Object.keys(value).slice(0, 400); } catch (_) { return; }
            for (const key of keys) {
                if (state.nodes >= maxNodes) break;
                if (originalEnvelope && key === 'audioUrl') continue;
                // Inspect data fields without invoking arbitrary getters in page objects.
                let descriptor;
                try { descriptor = Object.getOwnPropertyDescriptor(value, key); } catch (_) { continue; }
                if (!descriptor || !Object.prototype.hasOwnProperty.call(descriptor, 'value')) continue;
                const child = descriptor.value;
                const next = path + '.' + key;
                if (typeof child === 'string') {
                    if (directKeys.test(key) || (/^(?:url|src|file|uri|baseurl)$/i.test(key) && mediaContainers.test(path))) emit(child, key, next, value, info, base, frameUrl);
                    if (/^dash_manifest$|^dashmanifest$|^mpd$/i.test(key)) manifest(child, base, info.title);
                }
                visit(child, next, info, depth + 1);
            }
        }
        try { visit(root, rootPath || 'metadata', {}, 0); } catch (_) {}
    }

    // JSON assignments are read as JSON only. Page JavaScript is never evaluated by this parser.
    function assignedJson(text, offset) {
        while (/\s/.test(text[offset] || '') && offset < text.length) offset++;
        const first = text[offset];
        if (first !== '{' && first !== '[') return '';
        let depth = 0, quoted = false, escaped = false;
        for (let i = offset; i < text.length; i++) {
            const character = text[i];
            if (quoted) {
                if (escaped) escaped = false;
                else if (character === '\\') escaped = true;
                else if (character === '"') quoted = false;
            } else if (character === '"') quoted = true;
            else if (character === '{' || character === '[') depth++;
            else if ((character === '}' || character === ']') && --depth === 0) return text.slice(offset, i + 1);
        }
        return '';
    }

    function inspectText(text, base, frameUrl) {
        if (typeof text !== 'string' || !text.length || text.length > maxText) return;
        const trimmed = text.trim();
        if (trimmed[0] === '{' || trimmed[0] === '[') {
            try { inspectObject(JSON.parse(trimmed), base, 'metadata', frameUrl); return; } catch (_) {}
        }
        if (/^\s*(?:<\?xml[^>]*>\s*)?<(?:[\w.-]+:)?MPD(?:\s|>)/.test(text)) { manifest(text, base); return; }
        const assignments = /(?:__INITIAL_STATE__|__PRELOADED_STATE__|__NEXT_DATA__|playerConfig|xplayerSettings|stream_data|flashvars(?:_\d+)?|vimeo\.config)\s*=\s*/g;
        let match, count = 0;
        while (count++ < 32 && (match = assignments.exec(text))) {
            const json = assignedJson(text, assignments.lastIndex);
            if (json) { try { inspectObject(JSON.parse(json), base, match[0].replace(/\s*=\s*$/, ''), frameUrl); } catch (_) {} }
        }
    }

    function scan(doc, view, visited) {
        doc = doc || document; view = view || window; visited = visited || new WeakSet();
        if (visited.has(doc)) return;
        visited.add(doc);
        const base = doc.baseURI || location.href;
        const frameUrl = doc.URL || base;
        originalMetadataFallback(frameUrl);
        try {
            doc.querySelectorAll('script').forEach(script => {
                const text = script.textContent || '';
                if (text.length <= maxText && scriptValues.get(script) !== text) {
                    scriptValues.set(script, text);
                    inspectText(text, base, frameUrl);
                }
            });
            doc.querySelectorAll('[data-extra],[data-video],[data-media],[data-props],[data-player],shreddit-player').forEach(element => {
                ['data-extra', 'data-video', 'data-media', 'data-props', 'data-player', 'packaged-media-json'].forEach(name => {
                    const text = element.getAttribute(name);
                    if (text) inspectText(text, base, frameUrl);
                });
                if (element.tagName.toLowerCase() === 'shreddit-player') {
                    const media = element.packagedMediaJSON;
                    if (typeof media === 'string') inspectText(media, base, frameUrl);
                    else if (media) inspectObject(media, base, 'packagedMedia', frameUrl);
                }
            });
        } catch (_) {}
        const globals = ['__INITIAL_STATE__', '__PRELOADED_STATE__', '__NEXT_DATA__', 'SIGI_STATE', '__UNIVERSAL_DATA_FOR_REHYDRATION__', 'playerConfig', 'xplayerSettings', 'stream_data', 'flashvars'];
        // Some players publish flashvars_<video id> instead of a fixed global.
        try { Object.keys(view).filter(name => /^flashvars_\d+$/.test(name)).slice(0, 32).forEach(name => globals.push(name)); } catch (_) {}
        globals.forEach(name => {
            try {
                const value = own(view, name);
                if (typeof value === 'string') inspectText(value, base, frameUrl);
                else if (value && typeof value === 'object' && !value.nodeType) inspectObject(value, base, name, frameUrl);
            } catch (_) {}
        });
        try { if (view.vimeo && view.vimeo.config) inspectObject(view.vimeo.config, base, 'vimeo', frameUrl); } catch (_) {}
        try { if (view.kvsplayer && view.kvsplayer.kt_player && view.kvsplayer.kt_player.conf) inspectObject(view.kvsplayer.kt_player.conf, base, 'video_details', frameUrl); } catch (_) {}
        try {
            const cached = view.localStorage && view.localStorage.getItem('fyp-feed-cache');
            if (cached && cached.length <= maxText) inspectText(cached, base, frameUrl);
        } catch (_) {}
        // Same-origin embedded players have their own configuration globals. Cross-origin
        // documents stay behind the browser boundary and are observed through native requests.
        try { doc.querySelectorAll('iframe').forEach(frame => {
            try { if (frame.contentDocument && frame.contentWindow) scan(frame.contentDocument, frame.contentWindow, visited); } catch (_) {}
        }); } catch (_) {}
    }

    function schedule() {
        if (!pending) pending = setTimeout(() => { pending = 0; scan(); }, 1000);
    }

    function attach() {
        if (!observer && document.documentElement) {
            try {
                observer = new MutationObserver(schedule);
                observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true,
                    attributeFilter: ['data-extra', 'data-video', 'data-media', 'data-props', 'data-player', 'packaged-media-json'] });
            } catch (_) {}
        }
        schedule();
    }

    window.__vidLoaderInspectMetadata = inspectText;
    window.__vidLoaderSiteMetadata = { scan: scan, reset: function () { reported.clear(); manifests.clear(); scriptValues = new WeakMap(); } };
    window.__vidLoaderSiteScan = scan;
    document.addEventListener('DOMContentLoaded', attach, { once: true });
    ['loadedmetadata', 'playing', 'popstate'].forEach(event => window.addEventListener(event, schedule, true));
    attach();
    scan();
})();
