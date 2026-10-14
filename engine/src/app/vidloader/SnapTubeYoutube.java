package app.vidloader;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Standalone player API/model reconstruction from SnapTube Parser and ClientConfig. */
final class SnapTubeYoutube {
  static final String API_UA="Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/79.0.3945.116 Mobile Safari/537.36";
  private static final Map<String,Page> pages=new LinkedHashMap<>();
  private static final Map<String,String> scripts=new LinkedHashMap<>();
  static final class Page {
    String html="",visitor="",baseJs="",script="";int timestamp;JSONObject initial;
    final long created=android.os.SystemClock.elapsedRealtime();
  }
  static final class Client {
    final String name,version,host,ua;final int number;final boolean js,token;
    Client(String name,String version,String host,String ua,int number,boolean js,boolean token){this.name=name;this.version=version;this.host=host;this.ua=ua;this.number=number;this.js=js;this.token=token;}
  }
  static Client client(int route){
    switch(route){
      case 3:return new Client("VISIONOS","1.02","www.youtube.com","com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X;)",101,false,false);
      case 2:return new Client("WEB_REMIX","1.20260707.12.00","music.youtube.com",API_UA,67,true,true);
      case 4:return new Client("WEB_EMBEDDED_PLAYER","2.20260708.00.00","www.youtube.com",API_UA,0,true,false);
      case 5:return new Client("TVHTML5","5.20260707","www.youtube.com","Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",75,true,false);
      default:return new Client("MWEB","2.20260708.05.00","m.youtube.com","Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",2,true,true);
    }
  }
  static List<MediaCandidate> extract(Context context,String page,int route,YoutubeResolver.Status status)throws Exception {
    String id=YoutubeResolver.id(page);if(id.isEmpty())throw new IOException("Open an individual YouTube video or song");
    Client config=client(route);Page info=page(context,page,config.js);
    if(config.js&&(info.script.isEmpty()||info.timestamp==0))throw new IOException("Player script or signature timestamp is unavailable");
    if(config.token&&info.visitor.isEmpty())throw new IOException("Player session has no visitor identity");
    JSONObject apiClient=new JSONObject().put("clientName",config.name).put("clientVersion",config.version).put("hl",Locale.getDefault().getLanguage()).put("gl",Locale.getDefault().getCountry()).put("userAgent",config.ua);
    if(!info.visitor.isEmpty())apiClient.put("visitorData",info.visitor);
    if(route==3)apiClient.put("deviceMake","Apple").put("deviceModel","RealityDevice14,1").put("osName","visionOS").put("osVersion","25.6.0.23O471").put("platform","MOBILE");
    JSONObject playback=new JSONObject().put("html5Preference","HTML5_PREF_WANTS");if(info.timestamp>0)playback.put("signatureTimestamp",info.timestamp);
    JSONObject request=new JSONObject().put("context",new JSONObject().put("client",apiClient)).put("videoId",id).put("contentCheckOk",true).put("racyCheckOk",true).put("playbackContext",new JSONObject().put("contentPlaybackContext",playback));
    if(route==4)request.getJSONObject("context").put("thirdParty",new JSONObject().put("embedUrl","https://www.youtube.com/"));
    Map<String,String> headers=new LinkedHashMap<>();headers.put("User-Agent",config.ua);headers.put("Content-Type","application/json");if(config.number>0){headers.put("X-YouTube-Client-Name",Integer.toString(config.number));headers.put("X-YouTube-Client-Version",config.version);}headers.put("Origin","https://"+config.host);headers.put("Referer",page);
    if((route==3||route==4||route==5)&&!info.visitor.isEmpty())headers.put("X-Goog-Visitor-Id",info.visitor);
    if(route!=3){String authorization=sapisid(config.host);if(!authorization.isEmpty()){headers.put("Authorization",authorization);headers.put("X-Origin","https://"+config.host);}}
    JSONObject response=new JSONObject(fetch("https://"+config.host+"/youtubei/v1/player?prettyPrint=false",headers,request.toString(),6*1024*1024));
    JSONObject nested=response.optJSONObject("playerResponse");if(nested!=null)response=nested;
    JSONObject details=response.optJSONObject("videoDetails");if(details==null||!id.equals(details.optString("videoId")))throw new IOException("Player response does not match the requested video");
    JSONObject playability=response.optJSONObject("playabilityStatus");if(playability!=null&&!"OK".equals(playability.optString("status")))throw new IOException(playability.optString("reason","Player rejected this client"));
    JSONObject streaming=response.optJSONObject("streamingData");if(streaming==null)throw new IOException("Player returned no streaming data");
    String token="";if(config.token){status.update("Preparing media access…");token=SnapTubePoToken.mint(context,id);}
    String title=details.optString("title","YouTube video"),thumb=thumbnail(details.optJSONObject("thumbnail"));long duration=details.optLong("lengthSeconds")*1000;
    List<JSONObject> items=new ArrayList<>();for(String key:new String[]{"formats","adaptiveFormats"}){JSONArray values=streaming.optJSONArray(key);if(values!=null)for(int i=0;i<values.length();i++){JSONObject item=values.optJSONObject(i);if(item!=null)items.add(item);}}
    Map<JSONObject,String> urls=new LinkedHashMap<>();
    for(JSONObject item:items){try{
      String url=item.optString("url"),cipher=item.optString("signatureCipher",item.optString("cipher"));
      if(url.isEmpty()&&!cipher.isEmpty()){
        Map<String,String> values=query(cipher);url=values.getOrDefault("url","");String encrypted=values.getOrDefault("s","");String signature=values.getOrDefault("sig",values.getOrDefault("signature",""));
        if(!encrypted.isEmpty()){if(info.script.isEmpty())info=page(context,page,true);if(info.script.isEmpty())throw new IOException("Player signature script unavailable");signature=SnapTubePlayerScript.signature(context,info.baseJs,info.script,id,encrypted);}
        if(!signature.isEmpty())url=Uri.parse(url).buildUpon().appendQueryParameter(values.getOrDefault("sp","signature"),signature).build().toString();
      }
      if(!BrowserProtection.isWebUrl(url))continue;
      String n=Uri.parse(url).getQueryParameter("n");if(n!=null&&!n.isEmpty()){
        if(info.script.isEmpty()){info=page(context,page,true);}if(info.script.isEmpty())throw new IOException("Player throttling script unavailable");
        String changed=SnapTubePlayerScript.throttling(context,info.baseJs,info.script,id,n);
        if(changed.isEmpty()||changed.equals(n))throw new IOException("Player throttling transformation failed");url=replaceQuery(url,"n",changed);
      }
      if(!token.isEmpty())url=replaceQuery(url,"pot",token);urls.put(item,url);
    }catch(InterruptedException cancelled){throw cancelled;}catch(Exception failure){YoutubeDiagnostics.record(context,"format","itag="+item.optInt("itag")+" "+YoutubeResolver.safeError(failure));}}
    List<MediaCandidate> result=new ArrayList<>();
    for(Map.Entry<JSONObject,String> entry:urls.entrySet()){
      JSONObject item=entry.getKey();String type=item.optString("mimeType"),mime=type.split(";")[0];if(!mime.startsWith("video/")&&!mime.startsWith("audio/"))continue;
      if(mime.contains("webm")&&mime.startsWith("video/")&&(android.os.Build.VERSION.SDK_INT<29||(!type.contains("vp9")&&!type.contains("vp09")&&!type.contains("vp8"))))continue;
      boolean video=mime.startsWith("video/"),separate=video&&!item.has("audioQuality");JSONObject audio=null;
      if(separate)for(JSONObject possible:urls.keySet()){String audioMime=possible.optString("mimeType");if(!audioMime.startsWith(mime.contains("webm")?"audio/webm":"audio/mp4"))continue;
        JSONObject track=possible.optJSONObject("audioTrack"),previous=audio==null?null:audio.optJSONObject("audioTrack");
        boolean preferred=track!=null&&track.optBoolean("audioIsDefault"),previousPreferred=previous!=null&&previous.optBoolean("audioIsDefault");
        if(audio==null||(preferred&&!previousPreferred)||(preferred==previousPreferred&&possible.optLong("bitrate")>audio.optLong("bitrate")))audio=possible;}
      if(separate&&audio==null)continue;
      MediaCandidate candidate=new MediaCandidate(entry.getValue(),title,mime,item.optInt("width"),item.optInt("height"),"YouTube SnapTube REA",MediaCandidate.Kind.DIRECT,item.optLong("averageBitrate",item.optLong("bitrate")),type,"",audio==null?"":urls.get(audio),false,"",Collections.emptyList(),"",Collections.emptyList(),"");
      candidate.referer=page;candidate.userAgent=config.ua;candidate.durationMs=item.optLong("approxDurationMs",duration);candidate.thumbnailUrl=thumb;candidate.youtubeFormatId=Integer.toString(item.optInt("itag"));candidate.contentLength=item.optLong("contentLength",-1);candidate.framesPerSecond=item.optDouble("fps");candidate.extractedMetadata=true;
      audioIdentity(candidate,item);
      candidate.youtubeChunkSize=9*1024*1024;candidate.extractorHeaders.put("User-Agent",config.ua);candidate.extractorHeaders.put("Referer",page);
      if(audio!=null){candidate.youtubeAudioFormatId=Integer.toString(audio.optInt("itag"));candidate.audioContentLength=audio.optLong("contentLength",-1);candidate.audioBandwidth=audio.optLong("averageBitrate",audio.optLong("bitrate"));candidate.youtubeAudioChunkSize=9*1024*1024;candidate.audioExtractorHeaders.putAll(candidate.extractorHeaders);}
      result.add(candidate);
    }
    String sabrUrl=streaming.optString("serverAbrStreamingUrl");
    JSONObject playerConfig=response.optJSONObject("playerConfig"),common=playerConfig==null?null:playerConfig.optJSONObject("mediaCommonConfig"),streamer=common==null?null:common.optJSONObject("mediaUstreamerRequestConfig");
    String ustreamer=streamer==null?"":streamer.optString("videoPlaybackUstreamerConfig");
    if(!sabrUrl.isEmpty()&&!ustreamer.isEmpty())try{
      String n=Uri.parse(sabrUrl).getQueryParameter("n");if(n!=null&&!n.isEmpty()){if(info.script.isEmpty())info=page(context,page,true);sabrUrl=replaceQuery(sabrUrl,"n",SnapTubePlayerScript.throttling(context,info.baseJs,info.script,id,n));}
      if(!token.isEmpty())sabrUrl=replaceQuery(sabrUrl,"pot",token);
      for(JSONObject item:items){String mime=item.optString("mimeType").split(";")[0];boolean video=mime.startsWith("video/");if(!video&&!mime.startsWith("audio/"))continue;if(item.optString("lastModified").isEmpty())continue;
        if(video&&mime.contains("webm")&&(android.os.Build.VERSION.SDK_INT<29||(!item.optString("mimeType").contains("vp9")&&!item.optString("mimeType").contains("vp09")&&!item.optString("mimeType").contains("vp8"))))continue;
        JSONObject companion=null;
        if(video){for(JSONObject possible:items){if(!possible.optString("mimeType").startsWith(mime.contains("webm")?"audio/webm":"audio/mp4")||possible.optString("lastModified").isEmpty())continue;
          JSONObject track=possible.optJSONObject("audioTrack"),previous=companion==null?null:companion.optJSONObject("audioTrack");boolean preferred=track!=null&&track.optBoolean("audioIsDefault"),previousPreferred=previous!=null&&previous.optBoolean("audioIsDefault");
          if(companion==null||(preferred&&!previousPreferred)||(preferred==previousPreferred&&possible.optLong("bitrate")>companion.optLong("bitrate")))companion=possible;
        }if(companion==null)continue;}
        JSONObject description=new JSONObject().put("videoId",id).put("url",sabrUrl).put("ustreamer",ustreamer).put("height",item.optInt("height")).put("duration",duration).put("token",token);
        if(video)description.put("video",sabrFormat(item)).put("audio",sabrFormat(companion));else description.put("audio",sabrFormat(item));
        MediaCandidate candidate=new MediaCandidate(sabrUrl,title,mime,item.optInt("width"),item.optInt("height"),"YouTube SnapTube REA SABR",MediaCandidate.Kind.SABR,item.optLong("averageBitrate",item.optLong("bitrate")),item.optString("mimeType"),"","",false,"",Collections.emptyList(),"",Collections.emptyList(),"");
        audioIdentity(candidate,item);candidate.youtubeSabr=description.toString();candidate.youtubeFormatId=Integer.toString(item.optInt("itag"));candidate.referer=page;candidate.userAgent=config.ua;candidate.durationMs=duration;candidate.thumbnailUrl=thumb;candidate.extractedMetadata=true;candidate.framesPerSecond=item.optDouble("fps");candidate.contentLength=item.optLong("contentLength",-1);
        if(companion!=null){candidate.youtubeAudioFormatId=Integer.toString(companion.optInt("itag"));candidate.audioContentLength=companion.optLong("contentLength",-1);candidate.audioBandwidth=companion.optLong("averageBitrate",companion.optLong("bitrate"));candidate.contentLength=candidate.contentLength>0&&candidate.audioContentLength>0?candidate.contentLength+candidate.audioContentLength:-1;}result.add(candidate);
      }
    }
    catch(InterruptedException cancelled){throw cancelled;}catch(Exception alternative){if(result.isEmpty())throw alternative;YoutubeDiagnostics.record(context,"sabr-alternative",YoutubeResolver.safeError(alternative));}
    if(result.isEmpty()){
      String hls=streaming.optString("hlsManifestUrl");if(!hls.isEmpty()){
        String n=Uri.parse(hls).getQueryParameter("n");if(n!=null&&!n.isEmpty()){if(info.script.isEmpty())info=page(context,page,true);hls=replaceQuery(hls,"n",SnapTubePlayerScript.throttling(context,info.baseJs,info.script,id,n));}
        if(!token.isEmpty())hls=replaceQuery(hls,"pot",token);
        Map<String,String> mediaHeaders=new LinkedHashMap<>();mediaHeaders.put("User-Agent",config.ua);mediaHeaders.put("Referer",page);
        HttpMedia.TextResponse manifest=HttpMedia.fetchText(hls,mediaHeaders,2*1024*1024,c->{c.setConnectTimeout(5000);c.setReadTimeout(5000);});
        for(MediaCandidate parsed:MediaParser.parseHls(manifest.text,manifest.url,title)){
          MediaCandidate candidate=new MediaCandidate(parsed.url,parsed.title,parsed.mime,parsed.width,parsed.height,"YouTube SnapTube REA HLS",parsed.kind,parsed.bandwidth,parsed.codecs,parsed.audioGroup,parsed.audioUrl,parsed.drm,parsed.initUrl,parsed.segments,parsed.audioInitUrl,parsed.audioSegments,parsed.unsupportedReason,parsed.manifestUrl);
          candidate.referer=page;candidate.thumbnailUrl=thumb;candidate.durationMs=duration;candidate.userAgent=config.ua;candidate.extractorHeaders.putAll(mediaHeaders);candidate.extractedMetadata=true;result.add(candidate);
        }
      }
    }
    if(result.isEmpty())throw new IOException(streaming.has("serverAbrStreamingUrl")?"This player response requires SABR transport":"No usable media sources from this client");
    String captions=YoutubeCaptions.parse(response);for(MediaCandidate candidate:result)candidate.youtubeCaptions=captions;
    return result;
  }
  private static void audioIdentity(MediaCandidate candidate,JSONObject item){
    JSONObject track=item.optJSONObject("audioTrack");if(track==null)return;
    candidate.youtubeAudioTrack=track.optString("id");candidate.youtubeAudioTrackName=track.optString("displayName");candidate.youtubeDefaultAudio=track.optBoolean("audioIsDefault");
  }
  private static JSONObject sabrFormat(JSONObject item)throws Exception {
    return new JSONObject().put("itag",item.getInt("itag")).put("lastModified",item.getLong("lastModified")).put("xtags",item.optString("xtags")).put("contentLength",item.optLong("contentLength",-1));
  }
  private static String sapisid(String host)throws Exception {
    String cookies=Optional.ofNullable(CookieManager.getInstance().getCookie("https://"+host)).orElse("");String secret="";
    for(String cookie:cookies.split(";")){String pair=cookie.trim();if(pair.startsWith("SAPISID="))secret=pair.substring(8);}
    if(secret.isEmpty())return "";long time=System.currentTimeMillis()/1000;String data=time+" "+secret+" https://"+host;
    byte[] hash=java.security.MessageDigest.getInstance("SHA-1").digest(data.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte value:hash)hex.append(String.format(Locale.ROOT,"%02x",value));return "SAPISIDHASH "+time+"_"+hex;
  }
  private static Page page(Context context,String page,boolean needScript)throws Exception {
    String key=YoutubeResolver.id(page)+":"+YoutubeResolver.fingerprint(Optional.ofNullable(CookieManager.getInstance().getCookie(page)).orElse(""));
    synchronized(pages){Page saved=pages.get(key);if(saved!=null&&android.os.SystemClock.elapsedRealtime()-saved.created<300000&&(!needScript||!saved.script.isEmpty()))return saved;}
    Page result=new Page();result.html=fetch("https://www.youtube.com/watch?v="+YoutubeResolver.id(page),Collections.singletonMap("User-Agent",SnapTubePoToken.UA),null,4*1024*1024);
    result.visitor=stringField(result.html,"VISITOR_DATA");if(result.visitor.isEmpty())result.visitor=stringField(result.html,"visitorData");
    result.baseJs=stringField(result.html,"PLAYER_JS_URL");if(result.baseJs.isEmpty())result.baseJs=stringField(result.html,"jsUrl");
    if(result.baseJs.isEmpty()){Matcher js=Pattern.compile("[\"'](/s/player/[^\"']+/base\\.js)[\"']").matcher(result.html);if(js.find())result.baseJs=js.group(1);}
    if(result.baseJs.startsWith("/"))result.baseJs="https://www.youtube.com"+result.baseJs;
    if(needScript&&result.baseJs.startsWith("https://www.youtube.com/s/player/")){
      synchronized(scripts){result.script=scripts.getOrDefault(result.baseJs,"");}
      if(result.script.isEmpty()){result.script=fetch(result.baseJs,Collections.singletonMap("User-Agent",SnapTubePoToken.UA),null,8*1024*1024);synchronized(scripts){scripts.put(result.baseJs,result.script);while(scripts.size()>3)scripts.remove(scripts.keySet().iterator().next());}}
      Matcher timestamp=Pattern.compile("(?:signatureTimestamp|sts)\\s*[:=]\\s*(\\d+)").matcher(result.script);if(timestamp.find())result.timestamp=Integer.parseInt(timestamp.group(1));
    }
    result.html="";
    synchronized(pages){pages.put(key,result);while(pages.size()>8)pages.remove(pages.keySet().iterator().next());}return result;
  }
  private static String stringField(String html,String name){Matcher value=Pattern.compile("\""+Pattern.quote(name)+"\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")").matcher(html);if(value.find())try{return (String)new JSONTokener(value.group(1)).nextValue();}catch(Exception ignored){}return "";}
  static Map<String,String> query(String value)throws Exception {Map<String,String> result=new LinkedHashMap<>();for(String pair:value.split("&")){int equals=pair.indexOf('=');if(equals>=0)result.put(URLDecoder.decode(pair.substring(0,equals),"UTF-8"),URLDecoder.decode(pair.substring(equals+1),"UTF-8"));}return result;}
  static String replaceQuery(String url,String key,String value){Uri uri=Uri.parse(url);Uri.Builder builder=uri.buildUpon().clearQuery();for(String name:uri.getQueryParameterNames())if(!name.equals(key))for(String existing:uri.getQueryParameters(name))builder.appendQueryParameter(name,existing);return builder.appendQueryParameter(key,value).build().toString();}
  private static String thumbnail(JSONObject thumbnail){JSONArray images=thumbnail==null?null:thumbnail.optJSONArray("thumbnails");return images==null||images.length()==0?"":images.optJSONObject(images.length()-1).optString("url");}
  static String fetch(String address,Map<String,String> original,String body,int max)throws Exception {
    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Inspection cancelled");
    URL url=new URL(address);String host=url.getHost();if(!url.getProtocol().equals("https")||!(host.equals("youtube.com")||host.endsWith(".youtube.com")))throw new IOException("Unexpected player endpoint");
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(7000);connection.setReadTimeout(7000);connection.setRequestProperty("Accept-Encoding","identity");
    for(Map.Entry<String,String> header:original.entrySet())connection.setRequestProperty(header.getKey(),header.getValue());
    String cookie=CookieManager.getInstance().getCookie(address);if(cookie!=null)connection.setRequestProperty("Cookie",cookie);
    Timer timeout=new Timer(true);timeout.schedule(new TimerTask(){public void run(){connection.disconnect();}},12000);
    try{if(body!=null){byte[] data=body.getBytes(StandardCharsets.UTF_8);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(data.length);try(OutputStream out=connection.getOutputStream()){out.write(data);}}
      int status=connection.getResponseCode();if(status!=200)throw DownloadPolicy.HttpFailure.from(connection);
      try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] data=new byte[16384];int n;while((n=in.read(data))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Inspection cancelled");if(out.size()+n>max)throw new IOException("Player response exceeds limit");out.write(data,0,n);}return out.toString("UTF-8");}
    }finally{timeout.cancel();connection.disconnect();}
  }
}
