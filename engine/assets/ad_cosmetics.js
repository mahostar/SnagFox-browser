(function(){
  'use strict';
  if(window.__vidLoaderAdCosmetics)return;
  window.__vidLoaderAdCosmetics=true;
  var marker='data-vidloader-ad-slot',styleId='vidloader-ad-cosmetics',pending=0,observing=false;
  function enabled(){try{return !!window.VidLoaderMedia.blockAdsEnabled();}catch(_){return false;}}
  function blocked(url){try{return !!url && !!window.VidLoaderMedia.isBlockedUrl(url);}catch(_){return false;}}
  function apply(){
    pending=0;
    var root=document.documentElement;if(!root)return;
    if(!enabled()){root.removeAttribute('data-vidloader-ads-blocked');return;}
    root.setAttribute('data-vidloader-ads-blocked','true');
    if(!document.getElementById(styleId)){
      var style=document.createElement('style');style.id=styleId;
      var selectors=['ins.adsbygoogle','[data-ad-client][data-ad-slot]','[data-advertisement]','.ad-banner','.advertisement','['+marker+']'];
      style.textContent=selectors.map(function(s){return 'html[data-vidloader-ads-blocked="true"] '+s;}).join(',')+'{display:none!important}';
      (document.head||root).appendChild(style);
    }
    // Hide only frames whose actual URL is blocked. Remove a surrounding slot
    // only when it is explicitly an ad slot and contains no video/audio player.
    document.querySelectorAll('iframe[src]').forEach(function(frame){
      if(blocked(frame.src)){
        frame.setAttribute(marker,'true');
        var slot=frame.closest('.spot,.ad-slot,.ad-container,[data-ad-slot]');
        if(slot&&!slot.querySelector('video,audio'))slot.setAttribute(marker,'true');
      }else frame.removeAttribute(marker);
    });
  }
  function schedule(){if(!pending)pending=setTimeout(apply,100);}
  function attach(){if(document.documentElement&&!observing){observing=true;new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});apply();}}
  document.addEventListener('DOMContentLoaded',attach,{once:true});
  if(document.documentElement)attach();
  // Restore original display automatically when the existing setting is disabled.
  document.addEventListener('visibilitychange',schedule);
  setInterval(function(){var root=document.documentElement;if(!document.hidden&&root&&enabled()!==root.hasAttribute('data-vidloader-ads-blocked'))schedule();},500);
})();
