package app.vidloader;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
/** Offline desktop boundary regressions; no Chromium or site access. */
public final class DesktopBridgeFixture {
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{try{
  check(Double.isNaN(new JSONObject().optDouble("fps")),"missing upstream FPS is NaN");boolean rejectedNonfinite=false;try{new JSONObject().put("framesPerSecond",Double.NaN);}catch(JSONException e){rejectedNonfinite=e.getMessage().contains("non-finite");}check(rejectedNonfinite,"reproduce original JSON failure");
  check(DesktopBridge.resourceCandidate("https://example.com/video.mp4?token=fixture","video/unknown","fixture","site_metadata").mime.equals("video/mp4"),"unknown MP4 hint inferred from supported path extension");
  check(DesktopBridge.resourceCandidate("https://example.com/audio.m4a","audio/unknown","fixture","site_metadata").mime.equals("audio/mp4"),"unknown M4A hint inferred from supported path extension");
  check(DesktopBridge.resourceCandidate("https://example.com/stream","video/unknown","fixture","site_metadata").mime.equals("video/unknown"),"extensionless unknown video remains unknown");
  check(DesktopBridge.resourceCandidate("https://example.com/stream?file=audio.m4a","audio/unknown","fixture","site_metadata").mime.equals("audio/unknown"),"query filename does not fabricate MIME");
  check(DesktopBridge.resourceCandidate("https://example.com/video.mp4","video/webm","fixture","Browser").mime.equals("video/webm"),"explicit known MIME preserved");
  MediaCandidate candidate=new MediaCandidate("https://example.com/audio.m4a","Audio","audio/mp4",0,0,"fixture");
  candidate.youtubeSabr="private playback recipe";candidate.extractorHeaders.put("x-goog-test","private header");
  for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){candidate.framesPerSecond=value;JSONObject result=DesktopBridge.serialize(candidate);check(result.isNull("framesPerSecond"),"nonfinite FPS must be null");check(!result.has("youtubeSabr")&&!result.has("extractorHeaders"),"private download recipes stay in Java");new JSONObject(result.toString());}
  for(double value:new double[]{0,24,29.97,59.94,120}){candidate.framesPerSecond=value;check(DesktopBridge.serialize(candidate).getDouble("framesPerSecond")==value,"finite FPS preserved");}
  check(candidate.youtubeSabr.equals("private playback recipe"),"serialization must not mutate original recipe");
  Field field=DesktopBridge.class.getDeclaredField("running");field.setAccessible(true);Map<?,?> running=(Map<?,?>)field.get(null);
  for(int i=0;i<1000;i++){String id="fixture-fast-"+i;Future<?> task=DesktopBridge.submitRequest(id,()->null);task.get(5,TimeUnit.SECONDS);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(running.containsKey(id)&&System.nanoTime()<until)Thread.yield();check(!running.containsKey(id),"completed request removed");}
  CountDownLatch started=new CountDownLatch(1),interrupted=new CountDownLatch(1);
  Future<?> blocking=DesktopBridge.submitRequest("fixture-cancel",()->{started.countDown();try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();throw e;}return null;});
  check(started.await(5,TimeUnit.SECONDS),"request starts");boolean duplicate=false;try{DesktopBridge.submitRequest("fixture-cancel",()->null);}catch(IllegalArgumentException e){duplicate=true;}check(duplicate,"duplicate request cannot replace running task");
  check(blocking.cancel(true),"cancel running request");check(interrupted.await(5,TimeUnit.SECONDS),"cancel interrupts worker");check(!running.containsKey("fixture-cancel"),"cancelled request removed");
  boolean badUrl=false;try{DesktopBridge.web("file:///private/file");}catch(java.io.IOException e){badUrl=true;}check(badUrl,"non-web media address rejected");
  Thread.currentThread().interrupt();boolean preserved=false;try{DesktopBridge.ask("fixture",new JSONObject());}catch(InterruptedException e){preserved=Thread.currentThread().isInterrupted();}finally{Thread.interrupted();}check(preserved,"platform interruption remains visible to caller");
  Field pendingField=DesktopBridge.class.getDeclaredField("pending");pendingField.setAccessible(true);Map<?,?> pending=(Map<?,?>)pendingField.get(null);check(pending.isEmpty(),"cancelled platform request released");
  java.util.concurrent.atomic.AtomicBoolean waitingInterrupt=new java.util.concurrent.atomic.AtomicBoolean();
  Thread platformWait=new Thread(()->{try{DesktopBridge.ask("fixture",new JSONObject());}catch(InterruptedException e){waitingInterrupt.set(Thread.currentThread().isInterrupted());}catch(Exception e){throw new RuntimeException(e);}},"fixture-platform-wait");platformWait.start();long pendingDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(pending.isEmpty()&&System.nanoTime()<pendingDeadline)Thread.yield();check(!pending.isEmpty(),"platform request registered");platformWait.interrupt();platformWait.join(5000);check(!platformWait.isAlive()&&waitingInterrupt.get(),"waiting platform request preserves interrupt");check(pending.isEmpty(),"interrupted platform request released");
  java.nio.file.Path fixtureRoot=java.nio.file.Files.createTempDirectory("snagfox-bridge-fixture-");android.content.Context.root=fixtureRoot.toFile();
  try{DownloadStore store=DownloadStore.get(new android.content.Context());DownloadStore.Task owned=new DownloadStore.Task();owned.id="rename-fixture";owned.name="original.mp4";owned.state="completed";owned.exportedUri="";store.put(owned);java.nio.file.Path original=store.file(owned).toPath(),collision=original.resolveSibling(owned.id+"-renamed.mp4");java.nio.file.Files.write(original,new byte[]{1,2,3});java.nio.file.Files.write(collision,new byte[]{9});
   boolean renameRejected=false;try{DesktopBridge.handle("rename",new JSONObject().put("id",owned.id).put("name","renamed.mp4").put("path",collision.toString()));}catch(java.nio.file.FileAlreadyExistsException e){renameRejected=true;}check(renameRejected,"rename collision rejected");check(owned.name.equals("original.mp4")&&owned.exportedUri.isEmpty(),"failed rename preserves metadata");check(java.nio.file.Files.readAllBytes(original)[0]==1&&java.nio.file.Files.readAllBytes(collision)[0]==9,"failed rename preserves both files");store.save();JSONArray saved=new JSONArray(java.nio.file.Files.readString(fixtureRoot.resolve("downloads.json")));check(saved.getJSONObject(0).getString("name").equals("original.mp4"),"failed rename cannot persist changed filename");
   java.nio.file.Files.delete(collision);DesktopBridge.handle("rename",new JSONObject().put("id",owned.id).put("name","renamed.mp4").put("path",collision.toString()));check(owned.name.equals("renamed.mp4")&&!java.nio.file.Files.exists(original)&&java.nio.file.Files.readAllBytes(collision)[0]==1,"successful rename commits metadata and file");
  }finally{try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(fixtureRoot)){for(java.nio.file.Path path:paths.sorted(Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
  System.out.println("PASS: finite JSON, private recipes, 1000 request completions, cancellation, duplicate identity, URL boundary, platform interruption, transactional rename collision/success, supported-extension unknown MIME inference");System.exit(0);
 }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}