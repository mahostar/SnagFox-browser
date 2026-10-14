package app.vidloader;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Original international Bilibili API dispatch and fg(2).k schema, without transport or pairing. */
public final class BilibiliPlayback {
  private static final String API="https://api.bilibili.tv/intl/gateway/web/playurl?";
  private static final Pattern ID=Pattern.compile("/(\\d{5,})");
  private BilibiliPlayback() {}
  private static URI page(String value) {
    try {
      URI uri=URI.create(value);
      return "https".equalsIgnoreCase(uri.getScheme()) && "www.bilibili.tv".equalsIgnoreCase(uri.getHost()) && uri.getRawUserInfo()==null
          && (uri.getPort()==-1 || uri.getPort()==443) && uri.getRawPath().matches(".*/(?:video|play)/.+")?uri:null;
    }catch(RuntimeException error){return null;}
  }
  public static boolean isFeaturePage(String value){return page(value)!=null;}
  public static String initialRequest(String value) {
    URI uri=page(value);if(uri==null)return "";
    List<String> ids=new ArrayList<>();Matcher match=ID.matcher(uri.getRawPath());while(match.find())ids.add(match.group(1));
    if(uri.getRawPath().contains("/video/") && !ids.isEmpty())return API+"platform=web&aid="+ids.get(0);
    if(uri.getRawPath().contains("/play/") && ids.size()>1)return API+"platform=html5_a&ep_id="+ids.get(1);
    if(uri.getRawPath().contains("/play/") && ids.size()==1)return value;
    return "";
  }
  public static String episodeFallback(String value,String html) {
    URI uri=page(value);if(uri==null || html==null || html.length()>8*1024*1024)return "";
    String path=uri.getRawPath();int index=path.indexOf("/play/");if(index<0)return "";
    String prefix=path.substring(index).replaceAll("/+$","")+"/";
    Matcher match=Pattern.compile(Pattern.quote(prefix)+"(\\d{5,})\\?").matcher(html);
    return match.find()?API+"platform=html5_a&ep_id="+match.group(1):"";
  }
  public static final class Track {
    public final String url;
    public final int width,height;
    public final boolean heightHint;
    private Track(String url,int width,int height,boolean hint){this.url=url;this.width=width;this.height=height;this.heightHint=hint;}
  }
  public static final class Result {
    public final List<Track> videos;
    public final Track audio;
    /** fg.k passes the original duration integer through unchanged; do not multiply it again. */
    public final long durationValue;
    private Result(List<Track> videos,Track audio,long duration){this.videos=Collections.unmodifiableList(videos);this.audio=audio;this.durationValue=duration;}
    /** uy0.U attaches the same page's first quality-10 audio to selected normal video qualities. */
    public String audioFor(Track selected) {
      return audio!=null && selected!=null && videos.contains(selected) && selected.height>10 && selected.height<=10000?audio.url:"";
    }
  }
  private static Map<?,?> object(Object value){return value instanceof Map?(Map<?,?>)value:Collections.emptyMap();}
  private static List<?> array(Object value){return value instanceof List?(List<?>)value:Collections.emptyList();}
  private static int dimension(Object value){return value instanceof Number && ((Number)value).intValue()>0 && ((Number)value).intValue()<=16384?((Number)value).intValue():0;}
  private static String media(Object value) {
    if(!(value instanceof String) || ((String)value).length()>32768 || ((String)value).chars().anyMatch(c->c<32 || c==127))return "";
    try {URI uri=URI.create((String)value);return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost()!=null && uri.getRawUserInfo()==null?uri.toString():"";}catch(RuntimeException error){return "";}
  }
  public static Result parse(Map<String,?> payload) {
    Map<?,?> playurl=object(object(payload==null?null:payload.get("data")).get("playurl"));
    List<Track> videos=new ArrayList<>();Set<String> seen=new HashSet<>();
    List<?> resources=array(playurl.get("video"));
    for(int index=0;index<Math.min(128,resources.size());index++) {
      Map<?,?> resource=object(object(resources.get(index)).get("video_resource"));String url=media(resource.get("url"));
      if(url.isEmpty() || !seen.add(url))continue;
      int height=dimension(resource.get("height"));videos.add(new Track(url,dimension(resource.get("width")),height>0?height:360,height==0));
    }
    Track audio=null;List<?> audios=array(playurl.get("audio_resource"));
    for(int index=0;index<Math.min(128,audios.size());index++) {
      String url=media(object(audios.get(index)).get("url"));
      if(!url.isEmpty()){audio=new Track(url,0,0,false);break;}
    }
    Object duration=playurl.get("duration");long raw=duration instanceof Number?Math.max(0,((Number)duration).longValue()):0;
    return new Result(videos,audio,raw);
  }
}
