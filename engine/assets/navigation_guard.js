(function () {
  'use strict';
  if (window.__vidLoaderNavigationGuard) return;
  window.__vidLoaderNavigationGuard = true;
  var emptyVast = '<?xml version="1.0" encoding="UTF-8"?><VAST version="3.0"></VAST>';
  function emptyResponse(input) {
    try {
      var url=new URL(typeof input==='string'?input:input.url,location.href);
      if (/\/vmap(?:\.(?:xml|php|json))?\/?$/i.test(url.pathname) || ['output','format','type'].some(key=>url.searchParams.get(key)==='vmap'))
        return '<?xml version="1.0"?><vmap:VMAP xmlns:vmap="http://www.iab.net/videosuite/vmap" version="1.0"></vmap:VMAP>';
    } catch (_) {}
    return emptyVast;
  }
  function enabled() {
    try { return !!window.VidLoaderMedia.blockAdsEnabled(); } catch (_) { return false; }
  }
  // KVS exposes its preroll separately from the main fp-engine video. Use its
  // own skip API; never classify the main stream by size, duration or CDN.
  var protectedPlayers = new WeakSet();
  var registeredTags = new Set();
  var reportedAdElements = new WeakSet();
  function configuredAdTag(input) {
    try {
      var url=new URL(typeof input==='string'?input:input.url,location.href).href;
      var players=window.kvsplayer;
      if(!players || typeof players!=='object')return false;
      return Object.keys(players).slice(0,16).some(function(key){
        var conf=players[key] && players[key].conf;
        if(!conf || typeof conf.adv_pre_vast!=='string' || !conf.adv_pre_vast)return false;
        var tag=new URL(conf.adv_pre_vast,location.href).href;
        if(tag!==url)return false;
        if(!registeredTags.has(tag))try{window.VidLoaderMedia.registerAdTag(tag);registeredTags.add(tag);}catch(_){}
        return true;
      });
    }catch(_){return false;}
  }
  function protectConfiguredPlayers() {
    if (!enabled()) return;
    try {
      var players = window.kvsplayer;
      if (!players || typeof players !== 'object') return;
      Object.keys(players).slice(0,16).forEach(function (key) {
        var player=players[key], conf=player && player.conf;
        if (!conf || typeof player.skip_preroll !== 'function') return;
        if (typeof conf.adv_pre_vast==='string' && conf.adv_pre_vast) protectedPlayers.add(player);
        if (!protectedPlayers.has(player)) return;
        configuredAdTag(conf.adv_pre_vast);
        // The player can cache the VAST response before the user presses Play.
        // Its skip handler checks the actual preroll state before doing anything.
        var container=document.getElementById && document.getElementById(key);
        if(container && container.querySelector('video.fp-engine')) {
          var ads=container.querySelectorAll('video:not(.fp-engine)');
          ads.forEach(function (video) {
            var source=video.currentSrc || video.src;
            if(source && !reportedAdElements.has(video))try{window.VidLoaderMedia.report(JSON.stringify({url:source,advertisement:true,source:'KVS preroll'}));reportedAdElements.add(video);}catch(_){}
          });
          if(ads.length)player.skip_preroll();
        }
      });
    } catch (_) {}
  }
  // Observe late player creation and cached prerolls as well as initial markup.
  if(typeof setInterval==='function')setInterval(function(){if(!document.hidden)protectConfiguredPlayers();},250);
  document.addEventListener('DOMContentLoaded',protectConfiguredPlayers);
  var memo = new Map();
  function blocked(input) {
    if (!enabled()) return false;
    if(configuredAdTag(input))return true;
    try {
      var url = new URL(typeof input === 'string' ? input : input.url, location.href).href;
      if (memo.has(url)) return memo.get(url);
      var result = !!window.VidLoaderMedia.isBlockedUrl(url);
      if (memo.size >= 512) memo.clear();
      memo.set(url, result);
      return result;
    } catch (_) { return false; }
  }
  var open = window.open;
  var activatedLink='',activatedUntil=0;
  window.open = function (target) {
    if(!enabled())return open.apply(this,arguments);
    try {
      if(Date.now()<activatedUntil && new URL(target,location.href).href===activatedLink && !blocked(target))
        return open.apply(this,arguments);
    }catch(_){}
    return null;
  };
  document.addEventListener('click', function (event) {
    if (!enabled()) return;
    activatedLink='';activatedUntil=0;
    protectConfiguredPlayers();
    var node = event.target;
    var link = node && node.closest ? node.closest('a') : null;
    if (link && blocked(link.href)) {
      event.preventDefault();
      event.stopImmediatePropagation();
    } else if(link && event.isTrusted) {
      activatedLink=new URL(link.href,location.href).href;activatedUntil=Date.now()+1000;
      try{window.VidLoaderMedia.noteLinkActivation(activatedLink);}catch(_){}
    }
  }, true);
  if (typeof window.fetch === 'function' && typeof Response === 'function') {
    var fetch = window.fetch;
    window.fetch = function (input) {
      if (blocked(input)) return Promise.resolve(new Response(emptyResponse(input), {
        status: 200, headers: { 'Content-Type': 'application/xml' }
      }));
      return fetch.apply(this, arguments);
    };
  }
  // Register opaque player-declared tags before sending XHR. Keep the original
  // method, URL, body and events; native interception owns the empty response.
  if(typeof XMLHttpRequest==='function') {
    var xhrOpen=XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open=function(method,url){
      if(enabled())configuredAdTag(url);
      return xhrOpen.apply(this,arguments);
    };
  }
})();
