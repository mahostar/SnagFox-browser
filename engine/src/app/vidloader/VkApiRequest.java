package app.vidloader;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Narrow original VK extraction request contract; never an arbitrary native POST bridge. */
public final class VkApiRequest {
  public static final String VIDEO_FIELDS="added,episodes,files,image,is_favorite,subtitles,timeline_thumbs,trailer,volume_multiplier";
  public static final String CLIP_FIELDS="photo_50,photo_100,photo_200,photo_400,is_nft,about,description,followers_count,is_closed,verified,screen_name,friend_status,is_subscribed,blacklisted,domain,sex,can_write_private_message,first_name_gen,last_name_gen,first_name_acc,is_service_account,is_nft_photo,trust_mark,admin_level,member_status,members_count,is_member,ban_info,can_message,video_lives_data";
  private static final Pattern HOST=Pattern.compile("(?:(?:m|www)\\.)?(?:vk|vkvideo)\\.(?:com|ru)");
  private static final Pattern FEATURE=Pattern.compile("(?:^|/)(video|clip)(-?\\d+_\\d+)(?:/|$)");
  private VkApiRequest() {}

  public static final class Request {
    public final String id,url,body,page;
    private Request(String id,String url,String body,String page) {this.id=id;this.url=url;this.body=body;this.page=page;}
    @Override public String toString(){return "VK extraction request [credentials redacted]";}
  }
  private static URI featurePage(String page) {
    if(page==null || page.length()>32768 || page.chars().anyMatch(c->c<32 || c==127))return null;
    try {
      URI uri=URI.create(page);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && HOST.matcher(uri.getHost().toLowerCase(Locale.ROOT)).matches()
          && uri.getRawUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443) && FEATURE.matcher(uri.getRawPath()).find()?uri:null;
    }catch(RuntimeException error){return null;}
  }

  public static boolean isFeaturePage(String page){return featurePage(page)!=null;}

  public static Request validate(String currentPage,String planPage,String id,String url,String body) {
    URI current=featurePage(currentPage), plan=featurePage(planPage);
    if(current==null || plan==null || !current.getHost().equalsIgnoreCase(plan.getHost()) || id==null || !id.matches("[1-9]\\d{0,12}")
        || url==null || url.length()>1024 || body==null || body.length()>128*1024 || body.chars().anyMatch(c->c<32 || c==127))return null;
    Matcher now=FEATURE.matcher(current.getRawPath()), requested=FEATURE.matcher(plan.getRawPath());now.find();requested.find();
    if(!now.group(1).equals(requested.group(1)) || !now.group(2).equals(requested.group(2)))return null;
    boolean clip=now.group(1).equals("clip");
    String domain=current.getHost().toLowerCase(Locale.ROOT).replaceFirst("^(?:m|www)\\.", "");
    String endpoint="https://api."+domain+"/method/"+(clip?"shortVideo.get?v=5.280":"video.getByIds?v=5.282");
    if(!endpoint.equals(url))return null;
    Map<String,String> values=new LinkedHashMap<>();
    try {
      String[] parts=body.split("&",-1);if(parts.length!=3)return null;
      for(String part:parts) {
        int equals=part.indexOf('=');if(equals<1)return null;
        String key=URLDecoder.decode(part.substring(0,equals),StandardCharsets.UTF_8),value=URLDecoder.decode(part.substring(equals+1),StandardCharsets.UTF_8);
        if(values.putIfAbsent(key,value)!=null || value.chars().anyMatch(c->c<32 || c==127))return null;
      }
    }catch(IllegalArgumentException error){return null;}
    String identity=clip?"short_video_raw_ids":"videos", fields=clip?"fields":"video_fields";
    if(!values.keySet().equals(new HashSet<>(Arrays.asList(identity,fields,"access_token"))) || !now.group(2).equals(values.get(identity))
        || !(clip?CLIP_FIELDS:VIDEO_FIELDS).equals(values.get(fields)))return null;
    String token=values.get("access_token");
    if(token==null || token.isEmpty() || token.length()>8192 || token.chars().anyMatch(c->c<=32 || c>=127))return null;
    return new Request(id,endpoint,body,planPage);
  }
}
