package app.vidloader;

import android.content.Context;
import android.os.*;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

/** SnapTube b/na8/lhc signature and n WebView strategy, using recovered DEX patterns. */
final class SnapTubePlayerScript {
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final LinkedHashMap<String,Engine> engines=new LinkedHashMap<>();
  private static JSONObject patterns;
  private static final class Signature {String name,constant="";boolean helper;}
  static String signature(Context context,String url,String code,String videoId,String value)throws Exception {
    Engine engine=engine(context,url,code);if(engine.signature==null)throw new IOException("Player signature function was not found");
    return engine.invoke(true,value);
  }
  static String throttling(Context context,String url,String code,String videoId,String value)throws Exception {
    Engine engine=engine(context,url,code);if(engine.nName.isEmpty())throw new IOException("Player throttling function was not found");
    return engine.invoke(false,value);
  }
  private static synchronized Engine engine(Context context,String url,String code)throws Exception {
    if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Player script must not block UI");
    if(patterns==null)try(InputStream input=context.getAssets().open("youtube/player_patterns.json")){patterns=new JSONObject(new String(YoutubeIO.readAll(input,256*1024),StandardCharsets.UTF_8));}
    String key=url;Engine engine=engines.get(key);
    if(engine==null||engine.closed||
    (engine.signature==null&&engine.nName.isEmpty())){engine=new Engine(context.getApplicationContext(),code);engines.put(key,engine);while(engines.size()>2){String oldest=engines.keySet().iterator().next();engines.remove(oldest).close();}}
    return engine;
  }
  private static Signature signature(String code)throws Exception {
    JSONArray values=patterns.getJSONArray("signature"),groups=patterns.getJSONArray("signatureGroups");
    for(int i=0;i<values.length();i++){
      Matcher match=Pattern.compile(values.getString(i),Pattern.DOTALL).matcher(code);if(!match.find()||match.groupCount()<groups.getInt(i))continue;
      Signature result=new Signature();result.name=match.group(groups.getInt(i));result.helper=i==0;if(i==1)result.constant=match.group(3);return result;
    }return null;
  }
  private static String legacyN(String code)throws Exception {
    JSONArray values=patterns.getJSONArray("n");for(int i=0;i<values.length();i++){
      Matcher match=Pattern.compile(values.getString(i)).matcher(code);if(!match.find()||match.groupCount()<1)continue;String name=match.group(1);
      if(match.groupCount()>1&&match.group(2)!=null){Matcher array=Pattern.compile("var "+Pattern.quote(name)+"\\s*=\\s*\\[(.+?)][;,]",Pattern.DOTALL).matcher(code);if(!array.find())continue;String[] entries=array.group(1).split(",");int index=Integer.parseInt(match.group(2));if(index>=entries.length)continue;name=entries[index].trim();}
      if(name.matches("[\\w$]+"))return name;
            }return "";
  }
  private static String helperClass(String code,Signature signature){
    if(signature==null||!signature.helper)return "";int start=code.indexOf(signature.name+"=function");if(start<0)return "";
    Matcher helper=Pattern.compile("new\\s+(g\\.[a-zA-Z0-9_$]+)\\s*\\([a-zA-Z0-9_$]+,\\s*(?:!0|true)\\)").matcher(code.substring(start,Math.min(code.length(),start+3000)));
    return helper.find()?helper.group(1):"";
  }
  private static final class Engine {
    final Map<String,String> transforms=new LinkedHashMap<>();
    final Signature signature;final String nName;final CompletableFuture<Void> ready=new CompletableFuture<>();WebView web;volatile boolean closed;
    Engine(Context context,String code)throws Exception {
      signature=signature(code);String n=legacyN(code),helper=helperClass(code,signature);nName=!n.isEmpty()?n:helper.isEmpty()?"":"__n_deobfuscate";
      StringBuilder expose=new StringBuilder();
      if(signature!=null){if(signature.helper)expose.append("window.__sig_deobfuscate=function(input){var h=").append(signature.name).append("('https://www.youtube.com/videoplayback','signature',String(input));return h.get('signature');};");
        else expose.append("window.__sig_deobfuscate=function(input){return ").append(signature.name).append('(').append(signature.constant.isEmpty()?"":signature.constant+",").append("String(input));};");}
      if(!n.isEmpty())expose.append("window.__n_deobfuscate=").append(n).append(';');
      else if(!helper.isEmpty())expose.append("window.__n_deobfuscate=function(input){var h=new ").append(helper).append("('https://www.youtube.com/videoplayback?n='+encodeURIComponent(String(input)),true);return h.get('n');};");
      String marker="})(_yt_player);";int insert=code.lastIndexOf(marker);if(insert<0)throw new IOException("Player script scope does not match recovered wrapper");
      String patched=code.substring(0,insert)+expose+code.substring(insert);
      String html="<!doctype html><html><head><script>"+patched.replace("</script","<\\/script")+"</script></head><body></body></html>";
      main.post(()->{
        if(closed)return;web=new WebView(context);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);settings.setBlockNetworkLoads(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setUserAgentString(SnapTubePoToken.UA);
        web.setWebViewClient(new WebViewClient(){boolean loaded;@Override public void onPageFinished(WebView view,String address){if(loaded||closed)return;loaded=true;view.evaluateJavascript("typeof window.__sig_deobfuscate==='function'||typeof window.__n_deobfuscate==='function'",value->{if("true".equals(value))ready.complete(null);else ready.completeExceptionally(new IOException("Player script initialization failed"));});}});
        web.loadDataWithBaseURL("https://www.youtube.com",html,"text/html","UTF-8",null);
      });
    }
    synchronized String invoke(boolean sig,String input)throws Exception {
      String key=(sig?"s:":"n:")+input;String saved=transforms.get(key);if(saved!=null)return saved;
      try{ready.get(10,TimeUnit.SECONDS);}catch(Exception error){close();throw error;}
      CompletableFuture<String> result=new CompletableFuture<>();
      main.post(()->{if(closed||web==null){result.completeExceptionally(new IOException("Player script closed"));return;}
        String method=sig?"__sig_deobfuscate":"__n_deobfuscate";
        web.evaluateJavascript("(function(){try{return String(window."+method+"("+JSONObject.quote(input)+"));}catch(e){return 'ERROR:'+String(e);}})()",value->{try{Object parsed=new JSONTokener(value).nextValue();if(!(parsed instanceof String)||((String)parsed).startsWith("ERROR:")||((String)parsed).isEmpty()||"undefined".equals(parsed)||"null".equals(parsed))throw new IOException("Player transformation failed");result.complete((String)parsed);}catch(Exception error){result.completeExceptionally(error);}});
      });String transformed=result.get(7,TimeUnit.SECONDS);transforms.put(key,transformed);while(transforms.size()>128)transforms.remove(transforms.keySet().iterator().next());return transformed;
    }
    void close(){closed=true;main.post(()->{if(web!=null){web.stopLoading();web.destroy();web=null;}});}
  }
}
