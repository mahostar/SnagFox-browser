package app.vidloader;

import android.net.Uri;
import java.util.*;
import org.json.*;

/** Recovered Subtitle.l and sra.K: caption/translation metadata and direct SRT export. */
final class YoutubeCaptions {
  static String parse(JSONObject response){
    JSONArray result=new JSONArray();JSONObject captions=response.optJSONObject("captions"),renderer=captions==null?null:captions.optJSONObject("playerCaptionsTracklistRenderer");
    if(renderer==null)return result.toString();JSONArray tracks=renderer.optJSONArray("captionTracks");String first="";Set<String> seen=new HashSet<>();
    if(tracks!=null)for(int i=0;i<tracks.length()&&i<128;i++){JSONObject track=tracks.optJSONObject(i);if(track==null)continue;String url=normalize(track.optString("baseUrl"));if(url.isEmpty())continue;if(first.isEmpty())first=url;
      String language=track.optString("languageCode"),label=name(track.optJSONObject("name"),language);boolean auto=track.optString("vssId").startsWith("a.")||track.optString("kind").equals("asr");
      add(result,seen,url,label,language,auto,false);
    }
    for(int i=0;i<result.length();i++){JSONObject caption=result.optJSONObject(i);if(caption!=null&&caption.optBoolean("auto")){result.remove(i);result.put(caption);break;}}
    if(result.optJSONObject(0)!=null)first=result.optJSONObject(0).optString("url");
    JSONArray translations=renderer.optJSONArray("translationLanguages");if(!first.isEmpty()&&translations!=null)for(int i=0;i<translations.length()&&i<256;i++){JSONObject track=translations.optJSONObject(i);if(track==null)continue;String language=track.optString("languageCode");if(language.isEmpty())continue;add(result,seen,Uri.parse(first).buildUpon().appendQueryParameter("tlang",language).build().toString(),name(track.optJSONObject("languageName"),language),language,true,true);}
    return result.toString();
  }
  private static String name(JSONObject name,String fallback){if(name==null)return fallback;String text=name.optString("simpleText");JSONArray runs=name.optJSONArray("runs");if(text.isEmpty()&&runs!=null&&runs.optJSONObject(0)!=null)text=runs.optJSONObject(0).optString("text");return text.isEmpty()?fallback:text;}
  private static String normalize(String value){try{if(value.startsWith("//"))value="https:"+value;else if(value.startsWith("/"))value="https://www.youtube.com"+value;if(!YoutubeMedia.isYoutubeHost(value))return "";Uri source=Uri.parse(value);Uri.Builder builder=source.buildUpon().clearQuery();for(String key:source.getQueryParameterNames())if(!key.equals("fmt")&&!key.equals("tlang"))for(String entry:source.getQueryParameters(key))builder.appendQueryParameter(key,entry);return builder.appendQueryParameter("fmt","ttml").build().toString();}catch(Exception invalid){return "";}}
  private static void add(JSONArray array,Set<String> seen,String url,String label,String language,boolean auto,boolean translated){try{String id="caption:"+language+":"+auto+":"+translated;if(!seen.add(id))return;array.put(new JSONObject().put("id",id).put("url",url).put("label",label).put("language",language).put("auto",auto).put("translated",translated));}catch(JSONException ignored){}}
  static MediaCandidate output(JSONObject caption,MediaCandidate video){
    String url=SnapTubeYoutube.replaceQuery(caption.optString("url"),"fmt","srt");String language=caption.optString("language","und");
    MediaCandidate result=new MediaCandidate(url,video.title.replaceFirst("(?i)\\.(?:mp4|webm|m4a|mp3)$","")+"."+language+(caption.optBoolean("translated")?".translated":caption.optBoolean("auto")?".auto":""),"application/x-subrip",0,0,"YouTube subtitles");
    result.referer=video.referer;result.userAgent=video.userAgent;result.youtubeFormatId=caption.optString("id");result.extractorHeaders.put("User-Agent",video.userAgent);result.extractorHeaders.put("Referer",video.referer);result.extractedMetadata=true;return result;
  }
}
