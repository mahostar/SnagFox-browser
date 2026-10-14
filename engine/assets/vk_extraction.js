(function () {
    'use strict';
    if (window.__vidLoaderVkExtraction) return;
    const maxBody = 1024 * 1024, maxForm = 128 * 1024;
    const videoFields = 'added,episodes,files,image,is_favorite,subtitles,timeline_thumbs,trailer,volume_multiplier';
    const clipFields = 'photo_50,photo_100,photo_200,photo_400,is_nft,about,description,followers_count,is_closed,verified,screen_name,friend_status,is_subscribed,blacklisted,domain,sex,can_write_private_message,first_name_gen,last_name_gen,first_name_acc,is_service_account,is_nft_photo,trust_mark,admin_level,member_status,members_count,is_member,ban_info,can_message,video_lives_data';
    const trustedHosts = /^(?:(?:m|www)\.)?(?:vk|vkvideo)\.(?:com|ru)$/;
    const apiHosts = /^api\.(?:vk|vkvideo)\.(?:com|ru)$/;
    const tokens = { video: '', clip: '' }, revisions = { video: 0, clip: 0 };
    const attempted = new Set(), pending = new Map();
    let sequence = 0, owner = '';

    function feature() {
        try {
            const page = new URL(location.href);
            if (page.protocol !== 'https:' || !trustedHosts.test(page.hostname) || page.username || page.password || (page.port && page.port !== '443')) return null;
            const match = page.pathname.match(/(?:^|\/)(video|clip)(-?\d+_\d+)(?:\/|$)/);
            if (!match) return null;
            return { page: page.href, kind: match[1] === 'clip' ? 'clip' : 'video', id: match[2], domain: page.hostname.replace(/^(?:m|www)\./, ''), key: page.hostname + '/' + match[1] + match[2] };
        } catch (_) { return null; }
    }

    function current() {
        const found = feature();
        if ((found && found.key || '') !== owner) {
            owner = found && found.key || '';
            tokens.video = tokens.clip = ''; revisions.video = revisions.clip = 0;
            attempted.clear(); pending.clear();
        }
        return found;
    }

    function api(url) {
        try {
            const value = new URL(url, location.href);
            return value.protocol === 'https:' && apiHosts.test(value.hostname) && !value.username && !value.password && (!value.port || value.port === '443')
                && /^\/method\/(?:video\.get(?:ByIds)?|shortVideo\.get|execute)$/.test(value.pathname) ? value : null;
        } catch (_) { return null; }
    }

    function formToken(body) {
        try {
            let value = '';
            if (typeof body === 'string' && body.length <= maxForm) value = new URLSearchParams(body).get('access_token') || '';
            else if ((typeof URLSearchParams === 'function' && body instanceof URLSearchParams) || (typeof FormData === 'function' && body instanceof FormData)) value = body.get('access_token') || '';
            return typeof value === 'string' && value.length <= 8192 && !/[\u0000-\u0020\u007f]/.test(value) ? value : '';
        } catch (_) { return ''; }
    }

    function capture(url, body) {
        const found = current();
        if (!found || !api(url)) return null;
        const token = formToken(body);
        if (token && token !== tokens[found.kind]) { tokens[found.kind] = token; revisions[found.kind]++; }
        return found;
    }

    function webUrl(value, base) {
        if (typeof value !== 'string' || !value || value.length > 32768 || /[\u0000-\u001f\u007f]/.test(value)) return '';
        try { const url = new URL(value, base); return /^https?:$/.test(url.protocol) && !url.username && !url.password ? url.href : ''; } catch (_) { return ''; }
    }

    function emit(item, found) {
        if (!item || typeof item !== 'object' || !item.files || typeof item.files !== 'object') return;
        if (['isAd', 'is_ad', 'advertisement', 'isAdvertisement', 'is_advertisement', 'ad', 'isPreroll', 'is_preroll'].some(key => item[key] === true || item[key] === 1)) return;
        // Original F/E select current video/clip; do not mix a known different ID.
        if (item.owner_id !== undefined && item.id !== undefined && String(item.owner_id) + '_' + String(item.id) !== found.id) return;
        const images = found.kind === 'clip' ? item.covers : item.image;
        const thumbnail = Array.isArray(images) && images[0] ? webUrl(images[0].url, found.page) : '';
        const title = String(found.kind === 'clip' ? item.description || document.title || '' : item.title || document.title || '').slice(0, 1000);
        const seconds = Number(found.kind === 'clip' ? item.duration_seconds : item.duration);
        Object.keys(item.files).slice(0, 32).forEach(key => {
            const quality = key.match(/^mp4_(\d{2,4})$/), url = quality && typeof item.files[key] === 'string' ? webUrl(item.files[key], found.page) : '';
            if (!url) return;
            try { window.VidLoaderMedia.report(JSON.stringify({ url, mime: 'video/mp4', title, thumbnail, height: Number(quality[1]), width: 0,
                durationMs: Number.isFinite(seconds) && seconds > 0 ? Math.round(seconds * 1000) : 0, frameUrl: found.page,
                source: 'Original VK API', advertisement: false, pairedAudio: false, audioUrl: '' })); } catch (_) {}
        });
    }

    function parse(payload, found) {
        const now = current();
        if (!now || now.key !== found.key) return;
        try {
            if (typeof payload === 'string') { if (payload.length > maxBody) return; payload = JSON.parse(payload); }
            const response = payload && payload.response;
            if (found.kind === 'clip') {
                const groups = Array.isArray(response) ? response.slice(0, 32) : [response];
                const group = groups.find(value => value && value.feed && Array.isArray(value.feed.items));
                if (group && group.feed.items[0]) emit(group.feed.items[0].item, found);
            } else if (Array.isArray(response)) {
                response.slice(0, 32).forEach(group => { if (group && Array.isArray(group.items)) emit(group.items[0], found); });
            } else if (response) emit(response.current_video || (Array.isArray(response.items) && response.items[0]), found);
        } catch (_) {}
    }

    async function readJson(response) {
        const type = response.headers.get('content-type') || '', length = Number(response.headers.get('content-length')) || 0;
        if (!/(?:application\/json|text\/plain|text\/javascript|application\/javascript)/i.test(type) || length > maxBody || !response.body || !response.body.getReader) return '';
        const reader = response.body.getReader(), decoder = new TextDecoder('utf-8');
        let count = 0, text = '';
        try {
            while (true) {
                const chunk = await reader.read(); if (chunk.done) break;
                count += chunk.value.byteLength;
                if (count > maxBody) { await reader.cancel(); return ''; }
                text += decoder.decode(chunk.value, { stream: true });
            }
            return text + decoder.decode();
        } finally { reader.releaseLock(); }
    }

    const originalFetch = window.fetch;
    if (typeof originalFetch === 'function') window.fetch = function (resource, options) {
        const found = capture(typeof resource === 'string' ? resource : resource && resource.url, options && options.body);
        return originalFetch.apply(this, arguments).then(response => {
            try {
            if (found && api(response.url)) {
                const type = response.headers.get('content-type') || '', length = Number(response.headers.get('content-length')) || 0;
                if (/(?:application\/json|text\/plain|text\/javascript|application\/javascript)/i.test(type) && length <= maxBody)
                    readJson(response.clone()).then(text => { if (text) parse(text, found); }).catch(() => {});
            }
            } catch (_) { /* Observation must never change the player's fetch result. */ }
            return response;
        });
    };

    if (window.XMLHttpRequest && window.XMLHttpRequest.prototype) {
        const proto = window.XMLHttpRequest.prototype, open = proto.open, send = proto.send;
        const requests = new WeakMap();
        proto.open = function (method, url) { requests.set(this, { url }); return open.apply(this, arguments); };
        proto.send = function (body) {
            const request = requests.get(this), found = request && capture(request.url, body);
            if (found) this.addEventListener('load', () => {
                try {
                    if (!api(this.responseURL || request.url)) return;
                    const type = this.getResponseHeader('content-type') || '';
                    if (!/(?:application\/json|text\/plain|text\/javascript|application\/javascript)/i.test(type)) return;
                    if (!this.responseType || this.responseType === 'text') { if (this.responseText.length <= maxBody) parse(this.responseText, found); }
                    else if (this.responseType === 'json') parse(this.response, found);
                } catch (_) {}
            }, { once: true });
            return send.apply(this, arguments);
        };
    }

    function accept(id, payload) {
        const found = pending.get(id); if (!found) return false;
        pending.delete(id); parse(payload, found); return true;
    }

    async function inspect() {
        const found = current();
        if (!found || !tokens[found.kind]) return false;
        const attempt = found.key + ':' + revisions[found.kind];
        if (attempted.has(attempt)) return false;
        attempted.add(attempt);
        const fields = new URLSearchParams();
        fields.set(found.kind === 'clip' ? 'short_video_raw_ids' : 'videos', found.id);
        fields.set(found.kind === 'clip' ? 'fields' : 'video_fields', found.kind === 'clip' ? clipFields : videoFields);
        fields.set('access_token', tokens[found.kind]);
        const url = 'https://api.' + found.domain + '/method/' + (found.kind === 'clip' ? 'shortVideo.get?v=5.280' : 'video.getByIds?v=5.282');
        const id = String(++sequence); pending.set(id, found);
        if (pending.size > 4) pending.delete(pending.keys().next().value);
        try {
            if (window.VidLoaderMedia && typeof window.VidLoaderMedia.vkApiRequest === 'function') {
                const accepted = window.VidLoaderMedia.vkApiRequest(JSON.stringify({ id, url, body: fields.toString(), page: found.page }));
                if (accepted === false) { pending.delete(id); return false; }
                return true;
            }
            if (typeof originalFetch !== 'function') { pending.delete(id); return false; }
            const controller = typeof AbortController === 'function' ? new AbortController() : null;
            const timer = controller ? setTimeout(() => controller.abort(), 12000) : null;
            try {
                const response = await originalFetch.call(window, url, { method: 'POST', credentials: 'include', referrer: found.page, redirect: 'error',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: fields.toString(), signal: controller && controller.signal });
                if (!response.ok || !api(response.url)) { pending.delete(id); return false; }
                const text = await readJson(response); accept(id, text); return Boolean(text);
            } finally { if (timer !== null) clearTimeout(timer); }
        } catch (_) { pending.delete(id); return false; }
    }

    window.__vidLoaderVkExtraction = { inspect, accept };
    current();
})();
