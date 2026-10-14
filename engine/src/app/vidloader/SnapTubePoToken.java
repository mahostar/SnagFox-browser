package app.vidloader;

import android.content.Context;
import android.os.*;
import android.webkit.*;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** BotGuard bootstrap/bridge recovered from SnapTube PoTokenWebView.A0/D0/K0/w0. */
final class SnapTubePoToken {
  static final String UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3";
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final ExecutorService network=Executors.newSingleThreadExecutor();
  private static Generator generator;
  private static final String REQUEST_KEY="O43z0dpjhgX20SCx4KAo";
  private static int mintFailures,cooldownStep;
  private static long cooldownUntil;
  static String mint(Context context,String identifier)throws Exception {return mint(context,identifier,false);}
  static synchronized String mint(Context context,String identifier,boolean force)throws Exception {
    if(force&&generator!=null){generator.close();generator=null;}
    if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Token work must not block UI");
    if(SystemClock.elapsedRealtime()<cooldownUntil)throw new IOException("Token provider is cooling down after repeated failures");
    String namespace=context.getSharedPreferences("settings",0).getString("browserSession","initial")+":"+YoutubeResolver.fingerprint(Optional.ofNullable(android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com")).orElse(""));
    if(generator==null||generator.closed||!namespace.equals(generator.namespace)||SystemClock.elapsedRealtime()>generator.expiry){if(generator!=null)generator.close();generator=new Generator(context.getApplicationContext(),namespace);}
    Generator owner=generator;
    try{
      owner.ready.get(20,TimeUnit.SECONDS);
      String token=owner.tokens.get(identifier);if(token!=null)return token;
      CompletableFuture<String> future=new CompletableFuture<>();
      main.post(()->{
        if(owner.closed){future.completeExceptionally(new IOException("Token generator closed"));return;}
        String code="(function(){try{var v=obtainPoToken(webPoSignalOutput,integrityToken,"+bytes(identifier.getBytes(StandardCharsets.UTF_8))+");return Array.prototype.join.call(v,',');}catch(e){return 'ERROR:'+String(e);}})()";
        owner.web.evaluateJavascript(code,value->{try{
          Object parsed=new JSONTokener(value).nextValue();if(!(parsed instanceof String))throw new IOException("Token generator returned no value");
          String text=(String)parsed;if(text.startsWith("ERROR:")||text.isEmpty()||text.length()>32768)throw new IOException("Token mint failed");
          String[] pieces=text.split(",");byte[] data=new byte[pieces.length];for(int i=0;i<data.length;i++){int b=Integer.parseInt(pieces[i]);if(b<0||b>255)throw new IOException("Invalid token byte");data[i]=(byte)b;}
          future.complete(Base64.encodeToString(data,Base64.URL_SAFE|Base64.NO_WRAP));
        }catch(Exception error){future.completeExceptionally(error);}});
      });
      String tokenValue=future.get(7,TimeUnit.SECONDS);owner.tokens.put(identifier,tokenValue);while(owner.tokens.size()>128)owner.tokens.remove(owner.tokens.keySet().iterator().next());mintFailures=0;cooldownStep=0;cooldownUntil=0;return tokenValue;
    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
    catch(Exception error){
      if(owner.ready.isDone()&&!owner.ready.isCompletedExceptionally()&&++mintFailures>=3){long[] waits={300000,1800000,7200000,21600000};cooldownUntil=SystemClock.elapsedRealtime()+waits[Math.min(cooldownStep++,3)];mintFailures=0;}
      if(!owner.ready.isDone()||owner.ready.isCompletedExceptionally()){owner.close();generator=null;}
      throw new IOException("YouTube token preparation failed",error);
    }
  }
  private static final class Generator {
    final Context context;final String namespace;final CompletableFuture<Void> ready=new CompletableFuture<>();final Map<String,String> tokens=new LinkedHashMap<>();
    volatile long expiry=Long.MAX_VALUE;volatile boolean closed;WebView web;boolean snapshotReceived;
    Generator(Context context,String namespace){this.context=context;this.namespace=namespace;network.submit(()->{
      try{JSONObject challenge=challenge(post("Create",new JSONArray().put(REQUEST_KEY).toString()));
        String html;try(InputStream in=context.getAssets().open("youtube/botguard.html")){html=new String(YoutubeIO.readAll(in,256*1024),StandardCharsets.UTF_8);}
        final String document=html;
        main.post(()->{
          if(closed)return;
          web=new WebView(context);WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setBlockNetworkLoads(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setUserAgentString(UA);
          web.addJavascriptInterface(new Bridge(this),"VidLoaderToken");
          web.setWebViewClient(new WebViewClient(){boolean loaded;@Override public void onPageFinished(WebView view,String url){if(loaded||closed)return;loaded=true;
            view.evaluateJavascript("runBotGuard("+challenge+").then(function(r){window.webPoSignalOutput=r.webPoSignalOutput;VidLoaderToken.snapshot(String(r.botguardResponse));}).catch(function(e){VidLoaderToken.error(String(e));});",null);
          }});
          web.loadDataWithBaseURL("https://www.youtube.com",document,"text/html","UTF-8",null);
        });
      }catch(Exception error){fail(error);}
    });main.postDelayed(()->{if(!ready.isDone())fail(new IOException("Token initialization timed out"));},20000);}
    void fail(Throwable error){ready.completeExceptionally(error);close();}
    void close(){closed=true;main.post(()->{if(web!=null){web.removeJavascriptInterface("VidLoaderToken");web.stopLoading();web.destroy();web=null;}});}
  }
  private static final class Bridge {
    final Generator owner;Bridge(Generator owner){this.owner=owner;}
    @JavascriptInterface public void snapshot(String response){
      synchronized(owner){if(owner.closed||owner.snapshotReceived)return;owner.snapshotReceived=true;}
      if(response==null||response.length()>128*1024){owner.fail(new IOException("Invalid integrity response"));return;}
      network.submit(()->{try{
        JSONArray data=new JSONArray(post("GenerateIT",new JSONArray().put(REQUEST_KEY).put(response).toString()));
        byte[] integrity=decode(data.getString(0));long lifetime=data.optLong(1,7200);if(lifetime<=0)lifetime=7200;
        // Safety margin is our implementation choice; no claim about the APK's obfuscated constant.
        owner.expiry=SystemClock.elapsedRealtime()+Math.max(1,lifetime-30)*1000;
        main.post(()->{if(owner.closed)return;owner.web.evaluateJavascript("window.integrityToken="+bytes(integrity)+";true",value->owner.ready.complete(null));});
      }catch(Exception error){owner.fail(error);}});
    }
    @JavascriptInterface public void error(String ignored){owner.fail(new IOException("BotGuard initialization failed"));}
  }
  private static JSONObject challenge(String raw)throws Exception {
    JSONArray outer=new JSONArray(raw),data;
    if(outer.opt(1) instanceof String){byte[] decoded=decode(outer.getString(1));for(int i=0;i<decoded.length;i++)decoded[i]=(byte)(decoded[i]+97);data=new JSONArray(new String(decoded,StandardCharsets.UTF_8));}
    else data=outer.getJSONArray(0);
    String interpreter=firstString(data.opt(1));if(interpreter.isEmpty())throw new IOException("Integrity challenge has no interpreter");
    return new JSONObject().put("messageId",data.optString(0)).put("interpreterJavascript",new JSONObject().put("privateDoNotAccessOrElseSafeScriptWrappedValue",interpreter).put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",firstString(data.opt(2)))).put("interpreterHash",data.optString(3)).put("program",data.getString(4)).put("globalName",data.getString(5)).put("clientExperimentsStateBlob",data.optString(7));
  }
  private static String firstString(Object value){if(value instanceof String)return (String)value;if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)if(array.opt(i) instanceof String)return array.optString(i);}return "";}
  private static byte[] decode(String text){return Base64.decode(text.replace('-','+').replace('_','/').replace('.','='),Base64.DEFAULT);}
  private static String bytes(byte[] data){StringBuilder text=new StringBuilder("new Uint8Array([");for(int i=0;i<data.length;i++){if(i>0)text.append(',');text.append(data[i]&255);}return text.append("])").toString();}
  private static String post(String method,String body)throws Exception {
    HttpURLConnection connection=(HttpURLConnection)new URL("https://www.youtube.com/api/jnn/v1/"+method).openConnection();connection.setConnectTimeout(7000);connection.setReadTimeout(7000);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setDoOutput(true);
    connection.setRequestProperty("User-Agent",UA);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Content-Type","application/json+protobuf");connection.setRequestProperty("x-user-agent","grpc-web-javascript/0.1");connection.setRequestProperty("x-goog-api-key","AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw");
    byte[] request=body.getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(request.length);
    try{try(OutputStream out=connection.getOutputStream()){out.write(request);}if(connection.getResponseCode()!=200)throw new IOException("Integrity service HTTP "+connection.getResponseCode());
      try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] data=new byte[8192];int count;while((count=in.read(data))!=-1){if(out.size()+count>2*1024*1024)throw new IOException("Integrity response too large");out.write(data,0,count);}return out.toString("UTF-8");}
    }finally{connection.disconnect();}
  }
}
