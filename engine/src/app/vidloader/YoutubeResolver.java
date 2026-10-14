package app.vidloader;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.CookieManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** One session-aware extraction contract for clipboard, browser and source renewal. */
final class YoutubeResolver {
  interface Status { void update(String stage); }
  private static final ReentrantLock[] locks=new ReentrantLock[32];
  static{for(int i=0;i<locks.length;i++)locks[i]=new ReentrantLock();}
  private static final Map<String,Cached> cache=new LinkedHashMap<>();
  private static final Map<String,Integer> preferred=new ConcurrentHashMap<>();
  private static final class Cached {
    final List<MediaCandidate> formats;final long time=SystemClock.elapsedRealtime();
    Cached(List<MediaCandidate> formats){this.formats=new ArrayList<>(formats);}
  }
  static String id(String page) {
    if(!YoutubeMedia.isFeaturePage(page))return "";
    Uri uri=Uri.parse(page);String id=uri.getQueryParameter("v");
    if(id==null){List<String> parts=uri.getPathSegments();id=parts.isEmpty()?"":parts.get(parts.size()-1);}
    return id.matches("[A-Za-z0-9_-]{11}")?id:"";
  }
  static String fingerprint(String value) {
    try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b));return text.toString();}
    catch(Exception unavailable){throw new IllegalStateException(unavailable);}
  }
  static List<MediaCandidate> resolve(Context context,String page,boolean refresh,Status status)throws Exception {
    if(id(page).isEmpty())throw new IOException("Open an individual YouTube video or song");
    boolean music="music.youtube.com".equalsIgnoreCase(Uri.parse(page).getHost());
    String cookies=Optional.ofNullable(CookieManager.getInstance().getCookie("https://www.youtube.com")).orElse("");
    String session=(music?"music":"video")+":"+context.getSharedPreferences("settings",0).getString("browserSession","initial")+":"+fingerprint(cookies);
    String key=session+":"+id(page);ReentrantLock lock=locks[(key.hashCode()&0x7fffffff)%locks.length];
    status.update("Waiting for this video's inspection…");
    if(!lock.tryLock(90,TimeUnit.SECONDS))throw new IOException("Inspection timed out. Try again.");
    try {
      synchronized(cache){Cached saved=cache.get(key);if(!refresh&&saved!=null&&SystemClock.elapsedRealtime()-saved.time<120000&&!expiresSoon(saved.formats))return new ArrayList<>(saved.formats);}
      boolean authenticated=java.util.regex.Pattern.compile("(?:^|;\\s*)(?:SID|SAPISID|__Secure-1PAPISID)=").matcher(cookies).find();
      LinkedHashSet<Integer> routes=new LinkedHashSet<>();Integer good=preferred.get(session);if(good!=null)routes.add(good);
      // Recovered SnapTube dispatcher order; unsupported Onesie is not mislabeled as HTTP.
      if(music)routes.add(2);routes.add(3);if(authenticated)routes.add(5);routes.add(4);routes.add(2);routes.add(0);
      Exception last=new IOException("No usable formats");long started=SystemClock.elapsedRealtime();int count=0;
      for(int route:routes){
        if(Thread.currentThread().isInterrupted())throw new InterruptedException("Inspection cancelled");
        if(count>=5||SystemClock.elapsedRealtime()-started>65000)break;
        status.update("Finding "+(music?"music":"video")+" formats · method "+(++count)+" of 5…");
        YoutubeDiagnostics.record(context,"extract", "route="+route+" begin");
        try {
          List<MediaCandidate> formats=SnapTubeYoutube.extract(context,page,route,status);
          if(formats.isEmpty()||(!music&&formats.stream().noneMatch(c->!c.mime.startsWith("audio/"))))throw new IOException("No video formats from this method");
          status.update("Checking media-server access…");
          List<MediaCandidate> ordinary=new ArrayList<>(),sabr=new ArrayList<>();for(MediaCandidate candidate:formats){if(candidate.kind==MediaCandidate.Kind.SABR)sabr.add(candidate);else ordinary.add(candidate);}
          try{verifyRepresentative(ordinary,music);formats=ordinary;}catch(Exception inaccessible){if(Thread.currentThread().isInterrupted()||inaccessible instanceof InterruptedException)throw inaccessible;if(sabr.isEmpty())throw inaccessible;formats=sabr;status.update("Preparing adaptive stream choices…");}
          preferred.put(session,route);remember(key,formats);
          YoutubeDiagnostics.record(context,"extract","route="+route+" ready elapsedMs="+(SystemClock.elapsedRealtime()-started));
          return formats;
        }catch(Exception error){if(Thread.currentThread().isInterrupted()||error instanceof InterruptedException)throw error;last=error;YoutubeDiagnostics.record(context,"extract","route="+route+" "+safeError(error));}
      }
      throw new IOException("YouTube could not provide an accessible media source: "+safeError(last),last);
    }finally {lock.unlock();}
  }
  private static void remember(String key,List<MediaCandidate> formats){synchronized(cache){cache.put(key,new Cached(formats));while(cache.size()>8)cache.remove(cache.keySet().iterator().next());}}
  private static boolean expiresSoon(List<MediaCandidate> formats){
    for(MediaCandidate format:formats)try{String expiry=Uri.parse(format.url).getQueryParameter("expire");if(expiry!=null&&Long.parseLong(expiry)*1000-System.currentTimeMillis()<90000)return true;}catch(RuntimeException ignored){}
    return false;
  }
  private static void verifyRepresentative(List<MediaCandidate> formats,boolean music)throws Exception {
    if(formats.isEmpty())throw new IOException("No formats");
    MediaCandidate chosen=null;
    for(MediaCandidate candidate:formats){if(music!=candidate.mime.startsWith("audio/"))continue;
      if(chosen==null||Math.abs(candidate.height-480)<Math.abs(chosen.height-480))chosen=candidate;}
    if(chosen==null)chosen=formats.get(0);
    if(chosen.kind!=MediaCandidate.Kind.DIRECT)return; // HLS is checked by its manifest loader.
    probe(chosen.url,chosen.extractorHeaders,chosen.referer,chosen.userAgent);
    chosen.responseVerified=true;
    if(!chosen.audioUrl.isEmpty())probe(chosen.audioUrl,chosen.audioExtractorHeaders,chosen.referer,chosen.userAgent);
  }
  private static void probe(String url,Map<String,String> extracted,String page,String ua)throws Exception {
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Inspection cancelled");
    Map<String,String> headers=new LinkedHashMap<>();headers.put("Referer",page);headers.put("User-Agent",ua);headers.putAll(extracted);headers.put("Range","bytes=0-0");
    HttpURLConnection c=HttpMedia.open(url,headers,connection->{connection.setConnectTimeout(5000);connection.setReadTimeout(5000);});
    try{int code=c.getResponseCode();if(code!=200&&code!=206)throw DownloadPolicy.HttpFailure.from(c);
      String type=Optional.ofNullable(c.getContentType()).orElse("").toLowerCase(Locale.ROOT);if(type.contains("text/html")||type.contains("json"))throw new IOException("Media server returned an error document");
      try(InputStream input=c.getInputStream()){if(input.read()<0)throw new IOException("Media server returned no bytes");}
    }finally{c.disconnect();}
  }
  static String safeError(Throwable error){
    String text=error.getMessage();if(text==null)text=error.getClass().getSimpleName();
    text=text.replaceAll("https?://\\S+","[address]").replaceAll("(?i)(cookie|authorization|pot|token|signature)\\s*[:=]\\s*\\S+","$1=[redacted]");
    return text.substring(0,Math.min(500,text.length()));
  }
}
