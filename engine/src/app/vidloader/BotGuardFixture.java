package app.vidloader;
import android.webkit.*;import android.content.Context;import java.util.concurrent.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import org.json.*;
final class BotGuardFixture{
 static String eval(WebView web,String code)throws Exception{CompletableFuture<String> f=new CompletableFuture<>();web.evaluateJavascript(code,f::complete);return f.get(10,TimeUnit.SECONDS);}
 static final class Bridge{final CompletableFuture<String> result=new CompletableFuture<>();@JavascriptInterface public void snapshot(String s){result.complete(s);}@JavascriptInterface public void error(String s){result.completeExceptionally(new Exception("Fixture bridge error"));}}
 static String run(Context c)throws Exception{WebView web=new WebView(c);Bridge bridge=new Bridge();web.addJavascriptInterface(bridge,"VidLoaderToken");web.getSettings().setUserAgentString(SnapTubePoToken.UA);String html=Files.readString(new java.io.File(Context.assets,"youtube/botguard.html").toPath(),StandardCharsets.UTF_8);web.loadDataWithBaseURL("https://www.youtube.com",html,"text/html","UTF-8",null);try{
 EngineContractTests.check(eval(web,"location.origin+'|'+typeof require+'|'+typeof process").equals("\"https://www.youtube.com|undefined|undefined\""),"BotGuard isolated origin and no Node");
 String interpreter="window.fixtureVM={a:function(program,cb){cb(function(done,args){args[2].push(function(integrity){return function(binding){return new Uint8Array([binding[0],integrity[0],7]);};});done('snapshot-fixture');},function(){},function(){},function(){});return [function(){}];}};";
 JSONObject challenge=new JSONObject().put("globalName","fixtureVM").put("program","local-program").put("interpreterJavascript",new JSONObject().put("privateDoNotAccessOrElseSafeScriptWrappedValue",interpreter));
 eval(web,"runBotGuard("+challenge+").then(function(r){window.webPoSignalOutput=r.webPoSignalOutput;VidLoaderToken.snapshot(String(r.botguardResponse));}).catch(function(e){VidLoaderToken.error(String(e));});true");
 EngineContractTests.check(bridge.result.get(10,TimeUnit.SECONDS).equals("snapshot-fixture"),"BotGuard snapshot Java callback");EngineContractTests.check(eval(web,"Array.prototype.join.call(obtainPoToken(webPoSignalOutput,new Uint8Array([4]),new Uint8Array([65])),',')").equals("\"65,4,7\""),"BotGuard token binding bytes");
 return "Original BotGuard HTML in isolated HTTPS Chromium: no Node, async Java snapshot bridge and content-binding token bytes (synthetic VM)";
 }finally{web.destroy();}}
}