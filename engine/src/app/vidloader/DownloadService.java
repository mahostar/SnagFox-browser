package app.vidloader;


import android.content.*;

import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.json.JSONObject;

/**
 * Persistent, user-controlled download queue; no analytics, remote configuration or ad
 * initialization.
 */
public final class DownloadService extends Context {
  public static final String ADD = "add", PAUSE = "pause", RESUME = "resume", CANCEL = "cancel", RECONFIGURE="reconfigure";
  private final ExecutorService pool = Executors.newFixedThreadPool(3);
  private final ConcurrentHashMap<String, Worker> workers = new ConcurrentHashMap<>();
  private DownloadStore store;
  private volatile boolean destroyed;
  private static final String CHANNEL = "downloads";

  private static DownloadService instance;
  public static void command(Context c,String action,String id){if(instance==null)instance=new DownloadService();instance.control(action,id);}
  public static void add(Context c,DownloadStore.Task task){if(instance==null)instance=new DownloadService();instance.store.put(task);instance.schedule();}
  public DownloadService(){store=DownloadStore.get(this);instance=this;for(DownloadStore.Task t:store.all())if(t.interruptedRunnable){t.state="queued";t.interruptedRunnable=false;}store.save();schedule();}
  private synchronized void control(String action,String id){DownloadStore.Task t=store.find(id);if(t!=null){Worker w=workers.get(id);synchronized(t){if(!t.state.equals("completed")&&(PAUSE.equals(action)||CANCEL.equals(action))){t.state=CANCEL.equals(action)?"cancelled":"paused";t.interruptedRunnable=false;if(w!=null)w.stop();}else if(RESUME.equals(action)&&!t.state.equals("completed")&&(w==null||w.stopped)){t.state="queued";t.error="";}}store.save();}schedule();}
  private synchronized void schedule(){if(destroyed)return;int max=Math.max(1,Math.min(3,getSharedPreferences("settings",0).getInt("parallel",2)));for(DownloadStore.Task t:store.all()){if(workers.size()>=max)break;if(!t.state.equals("queued")||workers.containsKey(t.id))continue;Worker w=new Worker(t);workers.put(t.id,w);t.active=true;pool.execute(w);}}
  private boolean wifi(){return true;}private void stopIfIdle(){}private void update(DownloadStore.Task t){store.save();}
  public synchronized void onDestroy(){destroyed=true;for(Worker w:workers.values()){w.task.interruptedRunnable=w.task.state.equals("downloading");w.stop();}pool.shutdownNow();store.save();}
  private final class Worker implements Runnable {
    final DownloadStore.Task task;
    volatile boolean stopped;
    volatile HttpURLConnection active;
    final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    final RecoveryGate recovery = new RecoveryGate();
    final ConcurrentHashMap<HttpURLConnection,RecoveryGate.Lease> leases = new ConcurrentHashMap<>();
    final ThreadLocal<String> requestUrl = new ThreadLocal<>();
    
    volatile boolean immutableFragments;
    long lastUpdate;

    Worker(DownloadStore.Task task) {
      this.task = task;
    }

    void stop() {
      stopped = true;
      HttpURLConnection c = active;
      if (c != null) closeConnection(c);
      for (HttpURLConnection connection : connections) closeConnection(connection);
    }

    void check() throws IOException {
      if (stopped
          || destroyed
          || Thread.currentThread().isInterrupted()
          || task.state.equals("paused")
          || task.state.equals("cancelled")) throw new InterruptedIOException("Paused");

    }

    @Override
    public void run() {

      try {
        synchronized (task) {
          check();
          if (!task.state.equals("queued")) return;
          task.state = "downloading";
          task.error = "";
          task.phase = "downloading";
          task.phaseProgress = 0;
        }

        update(task);
        File output;
        int recoveryRounds = 0;
        for (;;) {
          try {
            if(task.kind.equals("SABR"))output=sabr();
            else if (task.kind.startsWith("HLS")) output = hls();
            else if (task.kind.equals("DASH") && !task.segments.isEmpty()) output = dash();
            else if (!task.audioUrl.isEmpty() || !task.audioSegments.isEmpty()) output = directWithAudio();
            else output = direct();
            if(task.mime.equals("application/x-subrip")){
              try(InputStream input=new FileInputStream(output)){byte[] prefix=new byte[(int)Math.min(output.length(),16384)];int count=input.read(prefix);String preview=count<0?"":new String(prefix,0,count,StandardCharsets.UTF_8);if(!preview.contains("-->"))throw new IOException("Subtitle server returned no SRT cues");}
            }
            if(task.mp3Output){
              File mp3=new File(store.work(task),"audio-output.mp3");
              Mp3Export.convert(output,mp3,task.mp3Bitrate,this::check,this::stage);
              output=mp3;task.mime="audio/mpeg";
              task.name=task.name.replaceFirst("(?i)\\.(?:mp4|webm|mkv|m4a|ts|m2ts|mp3)$","")+".mp3";
            }
            break;
          } catch (IOException interruption) {
            check();
            if(task.kind.equals("SABR")&&interruption instanceof DownloadPolicy.HttpFailure){int status=((DownloadPolicy.HttpFailure)interruption).status;if((status==401||status==403||status==410)&&refreshYoutubeSource())continue;}
            if (!DownloadPolicy.retryable(interruption)) throw interruption;
            int limit = getSharedPreferences("settings",0).getInt("automatic_resume_rounds",3);
            if (recoveryRounds++ >= limit) {
              task.state = "paused";
              task.error = "Server still unavailable. Partial download saved; Resume will continue it.";
              update(task);
              return;
            }
            task.retryAt = System.currentTimeMillis() + Math.max(30000,DownloadPolicy.retryDelay(recoveryRounds,interruption));
            task.error = "Temporary interruption; automatically resuming saved download";
            update(task);
            while (System.currentTimeMillis() < task.retryAt) { check(); Thread.sleep(100); }
          }
        }
        synchronized (task) {
          check();
          if (!task.state.equals("downloading"))
            throw new InterruptedIOException("Download stopped before completion");
          if (!output.isFile() || output.length() == 0)
            throw new IOException("The completed media file is empty");
          Files.move(
              output.toPath(), store.file(task).toPath(), StandardCopyOption.REPLACE_EXISTING);
          task.bytes = store.file(task).length();
          task.total = task.bytes;
          task.state = "completed";
          task.phase = "completed";
          task.phaseProgress = 100;
          task.error = "";
          task.retryAt = 0;
          task.cookie = "";
        }
        update(task);
        if (task.exportedUri.isEmpty()) {
          try {
            task.exportedUri = MediaExport.copy(DownloadService.this, task, false).toString();
            store.save();

          } catch (IOException e) {
            task.error = "Saved in Finished. Public folder copy failed: " + e.getMessage();
            store.save();
          }
        }
        try {
          removeTree(store.work(task));
          Files.deleteIfExists(store.partial(task).toPath());
        } catch (IOException ignored) {
        }
      } catch (Exception e) {
        synchronized (task) {
          if (!stopped && task.state.equals("downloading")) {
            task.state = "failed";
            task.error = task.youtubePage.isEmpty()?(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()):YoutubeResolver.safeError(e);
            if(!task.youtubePage.isEmpty())YoutubeDiagnostics.record(DownloadService.this,"failed",task.error);
          }
        }
        store.save();
      } finally {
        HttpURLConnection c = active;
        if (c != null) closeConnection(c);
        active = null;
        for (HttpURLConnection connection : connections) closeConnection(connection);
        connections.clear();

        synchronized (DownloadService.this) {
          workers.remove(task.id, this);
          task.active = false;
          schedule();
        }
      }
    }

    long speedTime, speedBytes, speedPublished;
    synchronized void progress(int count) {
      task.bytes += count;
      long now = SystemClock.elapsedRealtime();
      if (speedTime == 0 || task.bytes<speedBytes) { speedTime = now; speedBytes = task.bytes;speedPublished=now;task.bytesPerSecond=0; }
      // om0.H/f and xx1.a: publish ~1 s, keep an averaging anchor for ~6 s.
      // Use actual bytes/second; do not copy the original's rounded KB conversion
      // or its randomized secondary speed presentation.
      if (now - speedPublished >= 1000) {
        task.bytesPerSecond = Math.max(0, (task.bytes - speedBytes) * 1000 / (now - speedTime));
        speedPublished=now;
        if(now-speedTime>6000){speedTime=now;speedBytes=task.bytes;}
      }
      if (System.currentTimeMillis() - lastUpdate > 800) {
        lastUpdate = System.currentTimeMillis();
        update(task);
      }
    }

    HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
      check();
      requestUrl.set(url);
      while (System.currentTimeMillis() < task.retryAt) {
        check();
        try { Thread.sleep(100); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Paused"); }
      }
      RecoveryGate.Lease lease = recovery.acquire(url,this::check);
      try {
      HttpURLConnection connection = HttpMedia.open(
          url,
          headers,
          c -> {
            active = c;
            connections.add(c);
            check();
          });
      if(!task.youtubePage.isEmpty())YoutubeDiagnostics.record(DownloadService.this,"connect","status="+connection.getResponseCode()+" ranged="+headers.containsKey("Range"));
      leases.put(connection,lease);
      return connection;
      } catch (IOException | RuntimeException error) { lease.close(); throw error; }
    }

    void closeConnection(HttpURLConnection connection) {
      if (connection == null) return;
      connection.disconnect();
      connections.remove(connection);
      RecoveryGate.Lease lease = leases.remove(connection);
      if (lease != null) lease.close();
    }

    void connectionSucceeded(HttpURLConnection connection) {
      RecoveryGate.Lease lease = leases.get(connection);
      if (lease != null) lease.success();
    }

    boolean strongEtag(String value) {
      return value != null
          && value.startsWith("\"")
          && value.endsWith("\"")
          && !value.startsWith("W/");
    }

    void mediaResponse(HttpURLConnection connection) throws IOException {
      HttpMedia.ensureIdentityEncoding(connection);
      String type = connection.getContentType();
      if (type != null
          && (type.toLowerCase(Locale.ROOT).contains("text/html")
              || type.toLowerCase(Locale.ROOT).contains("application/json")))
        throw new IOException(
            "Server returned a web page or error instead of media. Refresh and select the video"
                + " again.");
    }

    File direct() throws Exception {
      if(!task.youtubePage.isEmpty()&&YoutubeChunks.applies(task.url))return retryTransfer(()->youtubeChunkFile(task.url,store.partial(task),task.youtubeChunkSize,0));
      return retryTransfer(() -> directOnce());
    }

    <T> T retryTransfer(Callable<T> transfer) throws Exception {
      int retries = Math.max(0, Math.min(10, getSharedPreferences("settings", 0).getInt("retry_times", DownloadPolicy.DEFAULT_RETRIES)));
      for (int attempt = 0; ; attempt++) {
        check();
        try { return transfer.call(); }
        catch (IOException error) {
          check();
          if(error instanceof DownloadPolicy.HttpFailure && !task.youtubePage.isEmpty() && rangeValidator.isEmpty()
              && (((DownloadPolicy.HttpFailure)error).url.isEmpty()||((DownloadPolicy.HttpFailure)error).url.equals(task.url)||((DownloadPolicy.HttpFailure)error).url.equals(task.audioUrl)||task.url.equals(requestUrl.get())||task.audioUrl.equals(requestUrl.get()))){
            int status=((DownloadPolicy.HttpFailure)error).status;
            if((status==401||status==403||status==410)&&refreshYoutubeSource())continue;
          }
          int budget = DownloadPolicy.retryBudget(error,retries);
          if (attempt >= budget || !DownloadPolicy.retryable(error)) throw error;
          long delay = DownloadPolicy.retryDelay(attempt,error);
          String failedUrl = error instanceof DownloadPolicy.HttpFailure && !((DownloadPolicy.HttpFailure)error).url.isEmpty() ? ((DownloadPolicy.HttpFailure)error).url : Optional.ofNullable(requestUrl.get()).orElse(task.url);
          boolean overloaded = error instanceof DownloadPolicy.HttpFailure && (((DownloadPolicy.HttpFailure)error).status == 503 || ((DownloadPolicy.HttpFailure)error).status == 429);
          long deadline = recovery.defer(failedUrl,delay,overloaded);
          task.retryAt = Math.max(task.retryAt,deadline);
          task.error = "Server temporarily unavailable; retry " + (attempt + 1) + "/" + budget;
          update(task);
          while (System.currentTimeMillis() < deadline) { check(); Thread.sleep(100); }
        }
      }
    }

    int youtubeRefreshes;
    synchronized boolean refreshYoutubeSource()throws Exception {
      if(youtubeRefreshes>=2 || !YoutubeMedia.isFeaturePage(task.youtubePage) || !(task.kind.equals("DIRECT")||task.kind.equals("SABR")))return false;
      int phase=youtubeRefreshes++;task.error="Refreshing expired YouTube source";update(task);check();
      List<MediaCandidate> formats;
      try{formats=YoutubeResolver.resolve(DownloadService.this,task.youtubePage,true,status->{task.error=status;update(task);});}catch(Exception unavailable){return false;}
      check();MediaCandidate selected=null;
      if(task.youtubeFormatId.startsWith("caption:"))for(MediaCandidate video:formats){org.json.JSONArray captions=new org.json.JSONArray(video.youtubeCaptions);for(int i=0;i<captions.length();i++){org.json.JSONObject caption=captions.optJSONObject(i);if(caption!=null&&task.youtubeFormatId.equals(caption.optString("id"))){selected=YoutubeCaptions.output(caption,video);break;}}if(selected!=null)break;}
      for(MediaCandidate candidate:formats){
        if(task.youtubeFormatId.startsWith("caption:"))break;
        if(!candidate.kind.name().equals(task.kind))continue;
        boolean audio=candidate.mime.startsWith("audio/");
        if((task.mp3Output||task.mime.startsWith("audio/"))!=audio)continue;
        if(!task.youtubeFormatId.isEmpty()&&task.youtubeFormatId.equals(candidate.youtubeFormatId)){selected=candidate;break;}
        if(selected==null&&task.mp3Output&&task.youtubeFormatId.isEmpty()&&task.bytes==0)selected=candidate;
      }
      if(selected==null||(selected.url.equals(task.url)&&selected.audioUrl.equals(task.audioUrl)&&selected.youtubeSabr.equals(task.youtubeSabr)))return false;
      if(task.bytes>0&&YoutubeChunks.applies(task.url)&&!YoutubeChunks.identity(task.url).equals(YoutubeChunks.identity(selected.url))){
        if(!YoutubeChunks.sameSavedSource(store.partial(task),selected.url,selected.extractorHeaders,youtubeTransport(0)))return false;
        // A changed signed address with byte-equal saved media may reuse the durable prefix.
        YoutubeChunks.rebind(store.partial(task),selected.url);
      }
      if(!task.youtubeAudioFormatId.isEmpty()&&!task.youtubeAudioFormatId.equals(selected.youtubeAudioFormatId))return false;
      task.youtubeSabr=selected.youtubeSabr;task.url=selected.url;task.audioUrl=selected.audioUrl;task.youtubeFormatId=selected.youtubeFormatId;task.youtubeAudioFormatId=selected.youtubeAudioFormatId;task.youtubeChunkSize=selected.youtubeChunkSize;task.youtubeAudioChunkSize=selected.youtubeAudioChunkSize;
      task.extractorHeaders.clear();task.extractorHeaders.putAll(selected.extractorHeaders);task.audioExtractorHeaders.clear();task.audioExtractorHeaders.putAll(selected.audioExtractorHeaders);
      task.userAgent=selected.userAgent;task.referer=selected.referer;task.cookie=Optional.ofNullable(android.webkit.CookieManager.getInstance().getCookie(task.url)).orElse("");
      store.save();return true;
    }

    File sabr()throws Exception {
      YoutubeDiagnostics.record(DownloadService.this,"sabr","begin selected="+task.youtubeFormatId);
      YoutubeSabr.Output tracks=YoutubeSabr.fetch(task.youtubeSabr,new File(store.work(task),"sabr"),new YoutubeSabr.Transport(){
        public HttpURLConnection post(String url,byte[] body)throws IOException{return postSabr(url,body);}
        public void close(HttpURLConnection connection){closeConnection(connection);}
        public void check()throws IOException{Worker.this.check();}
        public void position(long bytes,long total){task.bytes=bytes;task.total=total;update(task);}
        public void received(int count){progress(count);}
        public String token(boolean refresh)throws Exception{return SnapTubePoToken.mint(DownloadService.this,YoutubeResolver.id(task.youtubePage),refresh);}
        public void stage(String phase,long done,long total)throws IOException{Worker.this.stage(phase,done,total);}
      });
      check();if(tracks.video==null){if(tracks.audio==null)throw new IOException("SABR produced no audio");return tracks.audio;}
      File result=new File(store.work(task),task.mime.contains("webm")?"sabr-output.webm":"sabr-output.mp4");packageTracks(tracks.video,tracks.audio,result);return result;
    }

    HttpURLConnection postSabr(String url,byte[] body)throws IOException {
      check();requestUrl.set(url);URL endpoint=new URL(url);
      if(!endpoint.getProtocol().equals("https")||endpoint.getUserInfo()!=null||!(endpoint.getHost().equals("googlevideo.com")||endpoint.getHost().endsWith(".googlevideo.com")))throw new IOException("Unexpected SABR endpoint");
      RecoveryGate.Lease lease=recovery.acquire(url,this::check);
      HttpURLConnection connection=(HttpURLConnection)endpoint.openConnection();
      try{
        connection.setConnectTimeout(15000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);
        connection.setRequestProperty("Accept","application/vnd.yt-ump");connection.setRequestProperty("Content-Type","application/x-protobuf");connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Accept-Language","*");connection.setRequestProperty("User-Agent","Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
        active=connection;connections.add(connection);leases.put(connection,lease);check();try(OutputStream output=connection.getOutputStream()){output.write(body);}return connection;
      }catch(IOException|RuntimeException error){closeConnection(connection);lease.close();throw error;}
    }

    File youtubeChunkFile(String url,File file,long chunk,long base)throws Exception {
      return YoutubeChunks.fetch(url,HttpMedia.headers(task,url),file,chunk,youtubeTransport(base));
    }

    YoutubeChunks.Transport youtubeTransport(long base){
      return new YoutubeChunks.Transport(){
        public HttpURLConnection open(String address,Map<String,String> headers)throws IOException{return Worker.this.open(address,headers);}
        public void close(HttpURLConnection connection){closeConnection(connection);}
        public void check()throws IOException{Worker.this.check();}
        public void position(long bytes,long total){task.bytes=base+bytes;task.total=total<0?-1:base+total;update(task);}
        public void received(int bytes){progress(bytes);}
      };
    }

    File directOnce() throws Exception {
      File partial = store.partial(task);
      if (!partial.exists() && !parallelRejected) {
        try {
          File parallel = parallelDirect(partial);
          if (parallel != null) return parallel;
        } catch (RangeRejectedException ignored) {
          parallelRejected = true;
          task.bytes = 0;
          task.etag = "";
        }
      }
      long offset = partial.exists() ? partial.length() : 0;
      // Appending without a strong validator can join bytes from two different resource versions.
      if (offset > 0 && !strongEtag(task.etag)) offset = 0;
      Map<String, String> headers = HttpMedia.headers(task, task.url);
      if (offset > 0) {
        headers.put("Range", "bytes=" + offset + "-");
        headers.put("If-Range", task.etag);
      }
      active = open(task.url, headers);
      int code = active.getResponseCode();
      check();
      boolean append = offset > 0 && code == 206;
      if (code == 416 && offset > 0) {
        String r = active.getHeaderField("Content-Range");
        if (r != null
            && r.equals("bytes */" + offset)
            && !task.etag.isEmpty()
            && task.etag.equals(active.getHeaderField("ETag"))) {
          closeConnection(active);
          active = null;
          check();
          return partial;
        }
        closeConnection(active);
        active = null;
        Files.deleteIfExists(partial.toPath());
        task.etag = "";
        return direct();
      }
      if (code != 200 && code != 206) {
        DownloadPolicy.HttpFailure error = DownloadPolicy.HttpFailure.from(active);
        closeConnection(active); active = null;
        throw error;
      }
      String returnedEtag = Optional.ofNullable(active.getHeaderField("ETag")).orElse("");
      if (append && !task.etag.equals(returnedEtag)) {
        closeConnection(active);
        active = null;
        Files.deleteIfExists(partial.toPath());
        task.etag = "";
        return direct();
      }
      long responseLength = active.getContentLengthLong();
      if (code == 206) {
        String r = active.getHeaderField("Content-Range");
        Matcher m = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matcher(r == null ? "" : r);
        if (!m.matches() || Long.parseLong(m.group(1)) != (append ? offset : 0))
          throw new IOException("Server returned an inconsistent resume range");
        long end = Long.parseLong(m.group(2));
        responseLength = Math.addExact(Math.subtractExact(end, append ? offset : 0), 1);
        if (responseLength <= 0
            || (active.getContentLengthLong() >= 0
                && active.getContentLengthLong() != responseLength))
          throw new IOException("Invalid HTTP range length");
        task.total = m.group(3).equals("*") ? -1 : Long.parseLong(m.group(3));
        if (task.total <= end)
          throw new IOException("Server did not provide the complete resource length");
      } else {
        offset = 0;
        task.total = active.getContentLengthLong();
      }
      mediaResponse(active);
      long received = 0;
      try (InputStream in = active.getInputStream();
          FileOutputStream out = new FileOutputStream(partial, append)) {
        task.etag = returnedEtag;
        task.bytes = offset;
        byte[] b = new byte[65536];
        int n;
        while ((responseLength<0 || received<responseLength) && (n = in.read(b)) != -1) {
          check();
          if (responseLength >= 0 && n > responseLength - received)
            throw new IOException("Server sent bytes beyond the promised range");
          out.write(b, 0, n);
          received += n;
          progress(n);
        }
        out.getFD().sync();
      } finally {
        closeConnection(active);
        active = null;
      }
      if (responseLength >= 0 && received != responseLength)
        throw new EOFException("Incomplete HTTP response");
      if (task.total >= 0 && partial.length() != task.total)
        throw new EOFException("Transfer ended before the complete file arrived");
      if (partial.length() == 0) throw new IOException("Server returned an empty media file");
      check();
      return partial;
    }

    boolean parallelRejected;
    final class RangeRejectedException extends IOException {
      RangeRejectedException(String message) { super(message); }
    }
    volatile String rangeValidator = "";
    final ConcurrentHashMap<String,Long> directOffsets=new ConcurrentHashMap<>();

    File parallelDirect(File partial) throws Exception {
      Map<String, String> headers = HttpMedia.headers(task, task.url);
      headers.put("Range", "bytes=0-0");
      HttpURLConnection probe = open(task.url, headers);
      long total;
      String validator;
      try {
        int status = probe.getResponseCode();
        if (status != 206) { parallelRejected = true; return null; }
        mediaResponse(probe);
        Matcher range = Pattern.compile("bytes 0-0/(\\d+)").matcher(Optional.ofNullable(probe.getHeaderField("Content-Range")).orElse(""));
        validator = Optional.ofNullable(probe.getHeaderField("ETag")).orElse("");
        if (!range.matches() || !strongEtag(validator)) { parallelRejected = true; return null; }
        total = Long.parseLong(range.group(1));
        if (DownloadPolicy.connections(total) == 1) { parallelRejected = true; return null; }
      } finally { closeConnection(probe); }
      int count = DownloadPolicy.connections(total);
      task.total = total;
      directOffsets.clear();
      task.etag = validator;
      rangeValidator = validator;
      File dir = new File(store.work(task), "direct-parts");
      dir.mkdirs();
      List<File> parts = new ArrayList<>();
      List<Callable<Void>> jobs = new ArrayList<>();
      long retainedTotal=0;
      long block = total / count;
      for (int index = 0; index < count; index++) {
        final long offset = index * block;
        final long length = index == count - 1 ? total - offset : block;
        File part = new File(dir, hash(task.url + ":" + validator + ":" + offset + ":" + length) + ".part");
        parts.add(part);
        long retained=DirectRangeCheckpoint.restore(part,task.url,validator,offset,length);
        directOffsets.put(part.getPath(),retained);
        retainedTotal+=retained;
        jobs.add(() -> { retryTransfer(()->{fetchDirectRange(part,offset,length);return null;});return null; });
      }
      task.bytes=retainedTotal;
      update(task); // Reconcile all saved connection offsets before any worker starts.
      try { runFragments(jobs); }
      finally { rangeValidator = ""; }
      check();
      stage("assembling",0,total);
      long assembled=0;
      try (FileOutputStream out = new FileOutputStream(partial)) {
        byte[] buffer=new byte[65536];
        for (File part : parts) try (InputStream in = new FileInputStream(part)) {
          int read;while((read=in.read(buffer))!=-1){check();out.write(buffer,0,read);assembled+=read;stage("assembling",assembled,total);}
        }
        out.getFD().sync();
      }
      if (partial.length() != total) throw new IOException("Parallel transfer length mismatch");
      check();
      return partial;
    }

    void fetchDirectRange(File part,long offset,long length) throws Exception {
      check();
      String validator=rangeValidator;
      long retained=DirectRangeCheckpoint.restore(part,task.url,validator,offset,length);
      long previous=directOffsets.getOrDefault(part.getPath(),0L);
      synchronized(this){task.bytes+=retained-previous;}
      directOffsets.put(part.getPath(),retained);
      if(retained==length)return;
      if(retained==0)Files.deleteIfExists(part.toPath());
      long start=Math.addExact(offset,retained),end=Math.addExact(offset,length-1);
      Map<String,String> headers=HttpMedia.headers(task,task.url);
      headers.put("Range","bytes="+start+"-"+end);headers.put("If-Range",validator);
      HttpURLConnection connection=open(task.url,headers);
      long written=retained;
      java.security.MessageDigest prefix=retained>0?DirectRangeCheckpoint.hashPrefix(part,retained):java.security.MessageDigest.getInstance("SHA-256");
      long committed=retained,lastCommit=android.os.SystemClock.elapsedRealtime();
      try {
        int code=connection.getResponseCode();check();
        if(code!=200 && code!=206)throw DownloadPolicy.HttpFailure.from(connection);
        if(code!=206 || !validator.equals(connection.getHeaderField("ETag"))
            || !("bytes "+start+"-"+end+"/"+task.total).equals(connection.getHeaderField("Content-Range")))
          throw new RangeRejectedException("Server changed or rejected the resumed connection range");
        mediaResponse(connection);
        long declared=connection.getContentLengthLong();
        if(declared>=0 && declared!=length-retained)throw new RangeRejectedException("Connection length differs from assigned range");
        try(FileOutputStream out=new FileOutputStream(part,retained>0)) {
          try(InputStream in=connection.getInputStream()) {
            byte[] buffer=new byte[65536];int count;
            while(written<length && (count=in.read(buffer))!=-1) {
              check();
              if(count>length-written)throw new IOException("Connection exceeded its assigned range");
              out.write(buffer,0,count);prefix.update(buffer,0,count);written+=count;progress(count);
              // Original aw1.b/c and jy1: >64 KiB AND >2 s, sync data before
              // committing the connection offset. Hash binding is our addition.
              long now=android.os.SystemClock.elapsedRealtime();
              if(written-committed>65536 && now-lastCommit>2000){
                out.getFD().sync();
                String sha;
                try{sha=DirectRangeCheckpoint.hex((java.security.MessageDigest)prefix.clone());}
                catch(CloneNotSupportedException unavailable){sha=DirectRangeCheckpoint.digest(part);}
                DirectRangeCheckpoint.save(part,task.url,validator,offset,length,written,sha);
                committed=written;lastCommit=now;
              }
            }
          } finally {out.getFD().sync();}
        }
        if(written!=length)throw new EOFException("Incomplete connection range");
        check();connectionSucceeded(connection);
      } finally {
        try {
          // Retain valid prefixes on pause and transient failure; never recount
          // or redownload the saved prefix on the next connection attempt.
          if(written>0)DirectRangeCheckpoint.save(part,task.url,validator,offset,length);
          directOffsets.put(part.getPath(),written);
        } finally {closeConnection(connection);connections.remove(connection);}
      }
    }

    File directWithAudio() throws Exception {
      File work = store.work(task), video = direct();
      task.total = -1;
      File audio;
      if (!task.audioSegments.isEmpty())
        audio = downloadDashTrack(task.audioInitUrl, task.audioSegments, new File(work, "audio"));
      else {
        audio = new File(work, "audio.media");
        if(!task.youtubePage.isEmpty()&&YoutubeChunks.applies(task.audioUrl)){
          final File audioFile=audio;final long videoBytes=video.length();retryTransfer(()->youtubeChunkFile(task.audioUrl,audioFile,task.youtubeAudioChunkSize,videoBytes));
        }else fetchPart(task.audioUrl, audio, -1, -1);
      }
      File output = new File(work,task.mime.contains("webm")?"packaged.webm":"packaged.mp4");
      check();
      packageTracks(video, audio, output);
      return output;
    }

    File hls() throws Exception {
      File work = store.work(task);
      task.completedSegments = 0;
      task.segmentCount = 0;
      restoreWorkParts(work);
      File video = downloadHlsTrack(task.url, new File(work, "video"));
      File audio =
          task.audioUrl.isEmpty() ? null : downloadHlsTrack(task.audioUrl, new File(work, "audio"));
      check();
      // Original ee3.e completes ordinary continuous HLS without an unconditional conversion.
      // Preserve the actual container instead of spending minutes re-muxing a finished stream.
      if (audio == null) {
        String container = containerOf(video);
        if (!container.isEmpty()) {
          stage("finalizing",0,1);
          task.mime = container.equals("ts") ? "video/mp2t" : "video/mp4";
          if(!task.mp3Output)task.name = task.name.replaceFirst("(?i)\\.(?:mp4|m4v|mov|mkv|webm|ts)$","") + "." + container;
          task.total = task.bytes;
          check();
          stage("finalizing",1,1);
          return video;
        }
      }
      File output = new File(work, "packaged.mp4");
      packageTracks(video, audio, output);
      check();
      return output;
    }

    File downloadHlsTrack(String url, File dir) throws Exception {
      dir.mkdirs();
      File snapshot = new File(dir,"playlist.snapshot");
      HttpMedia.TextResponse response = readSnapshot(snapshot,url);
      if (response == null) try {
        response =
            HttpMedia.fetchText(
                url,
                ManifestRequestPolicy.apply(HttpMedia.headers(task, url), url),
                8 * 1024 * 1024,
                c -> {
                  active = c;
                  check();
                });
      } finally {
        active = null;
      }
      check();
      HlsPlaylist p = HlsPlaylist.parse(response.text, response.url);
      immutableFragments = p.ended;
      if (p.ended) writeJson(snapshot,new JSONObject().put("url",url).put("base",response.url).put("text",response.text).put("sha256",hash(response.text)));
      if (task.durationMs <= 0) task.durationMs = Math.round(p.durationSeconds * 1000);
      List<File> parts = new ArrayList<>();
      Map<String, byte[]> keys = new HashMap<>();
      for (HlsPlaylist.Segment segment : p.segments) {
        String identity = segment.keyUrl + ":" + segment.keyGeneration;
        if (!segment.keyUrl.isEmpty() && !keys.containsKey(identity)) keys.put(identity, savedKey(dir,identity,segment.keyUrl));
      }
      List<Callable<Void>> jobs = new ArrayList<>();
      Set<String> scheduled = new HashSet<>();
      String previousInit = "";
      for (HlsPlaylist.Segment s : p.segments) {
        check();
        String initIdentity = s.initUrl + ":" + s.initOffset + ":" + s.initLength;
        if (!s.initUrl.isEmpty() && !initIdentity.equals(previousInit)) {
          File f = new File(dir, hash(initIdentity) + ".init");
          if (scheduled.add(f.getPath())) jobs.add(() -> { fetchPart(s.initUrl, f, s.initOffset, s.initLength); return null; });
          parts.add(f);
          previousInit = initIdentity;
        }
        String identity =
            s.url
                + ":"
                + s.offset
                + ":"
                + s.length
                + ":"
                + s.keyUrl
                + ":"
                + s.iv
                + ":"
                + s.sequence;
        File f = new File(dir, hash(identity) + ".segment");
        File raw = new File(dir, hash(identity) + ".raw");
        parts.add(s.keyUrl.isEmpty() ? raw : f);
        if (!scheduled.add(raw.getPath())) continue;
        jobs.add(() -> {
        fetchPart(s.url, raw, s.offset, s.length);
        if (!s.keyUrl.isEmpty()) {
          String keyIdentity = s.keyUrl + ":" + s.keyGeneration;
          byte[] key = keys.get(keyIdentity);
          byte[] iv = new byte[16];
          if (s.iv.isEmpty()) {
            long v = s.sequence;
            for (int j = 15; j >= 8; j--) {
              iv[j] = (byte) v;
              v >>>= 8;
            }
          } else {
            String hex = s.iv.replaceFirst("(?i)^0x", "");
            if (hex.length() > 32) throw new IOException("Invalid encryption IV");
            hex = String.format("%32s", hex).replace(' ', '0');
            for (int j = 0; j < 16; j++)
              iv[j] = (byte) Integer.parseInt(hex.substring(j * 2, j * 2 + 2), 16);
          }
          Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
          cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
          try (InputStream in =
                  new javax.crypto.CipherInputStream(new FileInputStream(raw), cipher);
              FileOutputStream out = new FileOutputStream(f + ".tmp")) {
            copy(in, out);
            out.getFD().sync();
          }
          check();
          Files.move(
              new File(f + ".tmp").toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        synchronized (task) { task.completedSegments = accounted.size(); }
        update(task);
        return null;
        });
      }
      // Restore committed bytes once, before scheduling; never count up from zero on resume.
      for (File part : parts) {
        File raw = part.getName().endsWith(".segment") ? new File(part.getPath().replace(".segment",".raw")) : part;
        if (!accounted.contains(raw.getPath()) && validSavedPart(raw) && accounted.add(raw.getPath())) restoredBytes += raw.length();
      }
      task.bytes = restoredBytes;
      task.segmentCount += scheduled.size();
      task.completedSegments = accounted.size();
      stage("downloading",0,1);
      update(task);
      runFragments(jobs);
      restoredBytes = task.bytes;
      File combined = new File(dir, "combined.media");
      return joinParts(parts,combined);
    }

    // Original wg1.d schedules HLS connection blocks in a six-worker executor.
    // Keep assembly order independent of completion order and join all writers before remuxing.
    void runFragments(List<Callable<Void>> jobs) throws Exception {
      ExecutorService fragments = Executors.newFixedThreadPool(Math.min(6, Math.max(1, jobs.size())));
      CompletionService<Void> completion = new ExecutorCompletionService<>(fragments);
      List<Future<Void>> pending = new ArrayList<>();
      boolean completed = false;
      try {
        for (Callable<Void> job : jobs) { check(); pending.add(completion.submit(job)); }
        for (int index = 0; index < pending.size(); index++) {
          try { completion.take().get(); }
          catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IOException("Fragment worker failed", cause);
          }
        }
        completed = true;
      } finally {
        if (!completed) {
          for (Future<Void> future : pending) future.cancel(true);
          for (HttpURLConnection connection : connections) closeConnection(connection);
        }
        fragments.shutdownNow();
        // Do not let a resumed task race writers from the previous attempt.
        boolean interrupted = false;
        for (;;) {
          try { if (fragments.awaitTermination(250, TimeUnit.MILLISECONDS)) break; }
          catch (InterruptedException error) { interrupted = true; }
          for (HttpURLConnection connection : connections) closeConnection(connection);
        }
        if (interrupted) Thread.currentThread().interrupt();
      }
      check();
    }

    byte[] readKey(String url) throws Exception {
      active = open(url, HttpMedia.headers(task, url));
      try {
        if (active.getResponseCode() != 200)
          throw DownloadPolicy.HttpFailure.from(active);
        HttpMedia.ensureIdentityEncoding(active);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        try (InputStream in = active.getInputStream()) {
          byte[] b = new byte[17];
          int n;
          while ((n = in.read(b)) != -1) {
            check();
            if (n > 16 - out.size()) throw new IOException("Invalid AES-128 key length");
            out.write(b, 0, n);
          }
        }
        if (out.size() != 16) throw new IOException("Invalid AES-128 key length");
        return out.toByteArray();
      } finally {
        closeConnection(active);
        active = null;
      }
    }

    File dash() throws Exception {
      File work = store.work(task);
      immutableFragments = true; // Finite static DASH recipe is persisted in the task.
      restoreWorkParts(work);
      task.completedSegments = accounted.size();
      task.segmentCount = task.segments.size()+task.audioSegments.size()+(task.initUrl.isEmpty()?0:1)+(task.audioInitUrl.isEmpty()?0:1);
      stage("downloading",0,1);
      File video = downloadDashTrack(task.initUrl, task.segments, new File(work, "video"));
      File audio = null;
      if (!task.audioSegments.isEmpty())
        audio = downloadDashTrack(task.audioInitUrl, task.audioSegments, new File(work, "audio"));
      else if (!task.audioUrl.isEmpty()) {
        audio = new File(work, "audio.media");
        fetchPart(task.audioUrl, audio, -1, -1);
      }
      check();
      File output = new File(work, "packaged.mp4");
      packageTracks(video, audio, output);
      check();
      return output;
    }

    File downloadDashTrack(String init, List<String> segments, File dir) throws Exception {
      dir.mkdirs();
      List<File> parts = new ArrayList<>();
      List<String> urls = new ArrayList<>();
      if (!init.isEmpty()) urls.add(init);
      urls.addAll(segments);
      for (String url : urls) {
        check();
        File f = new File(dir, hash(url) + ".segment");
        fetchPart(url, f, -1, -1);
        parts.add(f);
        task.completedSegments = accounted.size();
        update(task);
      }
      File combined = new File(dir, "combined.media");
      return joinParts(parts,combined);
    }

    void fetchPart(String url, File file, long offset, long length) throws Exception {
      retryTransfer(() -> { fetchPartOnce(url, file, offset, length); return null; });
    }

    void fetchPartOnce(String url, File file, long offset, long length) throws Exception {
      check();
      if (offset >= 0 && length <= 0) throw new IOException("Invalid fragment byte range");
      File metadata = new File(file + ".meta");
      boolean cached = false;
      String validator = "";
      if (file.isFile() && file.length() > 0 && metadata.isFile() && metadata.length() < 4096)
        try {
          JSONObject saved =
              new JSONObject(
                  new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
          validator = saved.optString("etag");
          cached =
              (strongEtag(validator) || immutableFragments)
                  && saved.optString("url").equals(url)
                  && saved.optLong("offset", -2) == offset
                  && saved.optLong("range", -2) == length
                  && saved.optLong("length") == file.length()
                  && (accounted.contains(file.getPath()) || saved.optString("sha256").equals(fileHash(file)));
        } catch (Exception ignored) {
          cached = false;
        }
      check();
      if (cached && immutableFragments) {
        if (accounted.add(file.getPath())) synchronized(this) { task.bytes += file.length(); }
        return;
      }
      Map<String, String> headers = HttpMedia.headers(task, url);
      if (cached) headers.put("If-None-Match", validator);
      if (offset >= 0)
        headers.put("Range", "bytes=" + offset + "-" + Math.addExact(offset, length - 1));
      String expectedValidator = rangeValidator;
      if (offset >= 0 && !expectedValidator.isEmpty()) headers.put("If-Range", expectedValidator);
      HttpURLConnection connection = open(url, headers);
      long received = 0;
      boolean committed = false;
      try {
        int code = connection.getResponseCode();
        check();
        if (code == 304 && cached) {
          synchronized (this) { task.bytes += file.length(); }
          return;
        }
        if (code != 200 && code != 206) throw DownloadPolicy.HttpFailure.from(connection);
        mediaResponse(connection);
        if (!expectedValidator.isEmpty() && !expectedValidator.equals(connection.getHeaderField("ETag")))
          throw new RangeRejectedException("Media changed during parallel transfer");
        if (offset >= 0) {
          String expected = "bytes " + offset + "-" + Math.addExact(offset, length - 1) + "/";
          String actual = connection.getHeaderField("Content-Range");
          if (code != 206 || actual == null || !actual.startsWith(expected))
            throw new RangeRejectedException("Server ignored the segment byte range");
        } else if (code == 206) {
          String r = connection.getHeaderField("Content-Range");
          Matcher m = Pattern.compile("bytes 0-(\\d+)/(\\d+)").matcher(r == null ? "" : r);
          if (!m.matches()
              || Math.addExact(Long.parseLong(m.group(1)), 1) != Long.parseLong(m.group(2)))
            throw new IOException("Server returned an incomplete media fragment");
        }
        File tmp = new File(file + ".tmp");
        try (InputStream in = connection.getInputStream();
            FileOutputStream out = new FileOutputStream(tmp)) {
          long promised = offset >= 0 ? length : connection.getContentLengthLong();
          byte[] b = new byte[65536];
          int n;
          while ((n = in.read(b)) != -1) {
            check();
            if (promised >= 0 && n > promised - received)
              throw new IOException("Fragment exceeded its promised length");
            out.write(b, 0, n);
            received += n;
            progress(n);
          }
          out.getFD().sync();
        }
        long expected = offset >= 0 ? length : connection.getContentLengthLong();
        if (expected >= 0 && received != expected)
          throw new EOFException("Incomplete media segment");
        if (received == 0) throw new IOException("Empty media segment");
        check();
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        JSONObject saved =
            new JSONObject()
                .put("url", url)
                .put("offset", offset)
                .put("range", length)
                .put("length", file.length())
                .put("etag", Optional.ofNullable(connection.getHeaderField("ETag")).orElse(""))
                .put("sha256", fileHash(file));
        File metadataTmp = new File(metadata + ".tmp");
        try (FileOutputStream out = new FileOutputStream(metadataTmp)) {
          out.write(saved.toString().getBytes(StandardCharsets.UTF_8));
          out.getFD().sync();
        }
        check();
        Files.move(metadataTmp.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING);
        connectionSucceeded(connection);
        accounted.add(file.getPath());
        committed = true;
      } finally {
        if (!committed && received > 0) synchronized (this) { task.bytes = Math.max(0, task.bytes - received); }
        closeConnection(connection);
        connections.remove(connection);
      }
    }

    final Set<String> accounted = ConcurrentHashMap.newKeySet();
    long restoredBytes;

    boolean validSavedPart(File file) throws Exception {
      File meta = new File(file + ".meta");
      if (!file.isFile() || !meta.isFile() || meta.length()>4096) return false;
      try {
        JSONObject j = new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8));
        return file.length()>0 && j.optLong("length")==file.length() && j.optString("sha256").equals(fileHash(file));
      } catch (org.json.JSONException invalid) { return false; }
    }

    HttpMedia.TextResponse readSnapshot(File file,String url) throws Exception {
      if (!file.isFile() || file.length()>16*1024*1024) return null;
      try {
        JSONObject j = new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        String text=j.getString("text");
        if (!j.optString("url").equals(url) || !j.optString("sha256").equals(hash(text))) return null;
        HlsPlaylist.parse(text,j.getString("base"));
        return new HttpMedia.TextResponse(text,j.getString("base"));
      } catch (org.json.JSONException | IllegalArgumentException invalid) { return null; }
    }

    void writeJson(File file,JSONObject value) throws Exception {
      File tmp=new File(file+".tmp");
      try(FileOutputStream out=new FileOutputStream(tmp)) { out.write(value.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
      check(); Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }

    byte[] savedKey(File dir,String identity,String url) throws Exception {
      File file=new File(dir,hash(identity)+".key.json");
      if(file.isFile() && file.length()<1024) try {
        JSONObject j=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        String encoded=j.getString("key");
        byte[] key=Base64.getDecoder().decode(encoded);
        if(key.length==16 && j.optString("sha256").equals(hash(encoded))) return key;
      } catch(org.json.JSONException | IllegalArgumentException invalid) { }
      byte[] key=retryTransfer(() -> readKey(url));
      String encoded=Base64.getEncoder().encodeToString(key);
      writeJson(file,new JSONObject().put("key",encoded).put("sha256",hash(encoded)));
      return key;
    }

    long phaseUpdate;
    void stage(String phase,long done,long total) throws IOException {
      check();
      int percent=total>0?(int)Math.min(100,Math.max(0,done*100/total)):0;
      long now=SystemClock.elapsedRealtime();
      boolean changed=!phase.equals(task.phase);
      int previous=task.phaseProgress;
      task.phase=phase;task.phaseProgress=percent;
      if(changed || now-phaseUpdate>=800 || (percent==100 && previous!=100)) { phaseUpdate=now; update(task); }
    }

    void restoreWorkParts(File work) throws Exception {
      accounted.clear();restoredBytes=0;
      stage("checking",0,1);
      List<File> saved=new ArrayList<>();
      for(File dir:new File[]{new File(work,"video"),new File(work,"audio")}) {
        File[] files=dir.listFiles();if(files==null)continue;
        for(File file:files)if(file.getName().matches("[a-f0-9]{64}\\.(?:raw|init|segment)"))saved.add(file);
      }
      File separate=new File(work,"audio.media");if(separate.isFile())saved.add(separate);
      for(int i=0;i<saved.size();i++) {
        File file=saved.get(i);
        if(validSavedPart(file) && accounted.add(file.getPath()))restoredBytes+=file.length();
        stage("checking",i+1,saved.size());
      }
      task.bytes=restoredBytes;
    }

    String containerOf(File file) throws IOException {
      byte[] head=new byte[576];int count;
      try(InputStream in=new FileInputStream(file)){count=in.read(head);}
      if(count>376 && head[0]==0x47 && head[188]==0x47 && head[376]==0x47)return "ts";
      if(count>=12 && head[4]=='f' && head[5]=='t' && head[6]=='y' && head[7]=='p')return "mp4";
      return "";
    }

    String joinedIdentity(List<File> parts) throws Exception {
      StringBuilder identity=new StringBuilder();
      for(File part:parts) {
        check();String digest="";File meta=new File(part+".meta");
        if(meta.isFile() && meta.length()<4096)try { digest=new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8)).optString("sha256"); }catch(org.json.JSONException ignored){}
        if(digest.isEmpty())digest=fileHash(part);
        identity.append(part.getName()).append(':').append(part.length()).append(':').append(digest).append(';');
      }
      return hash(identity.toString());
    }

    File joinParts(List<File> parts,File combined) throws Exception {
      stage("assembling",0,1);
      String identity=joinedIdentity(parts);long total=0;
      for(File part:parts)total=Math.addExact(total,part.length());
      File metadata=new File(combined+".joined.json");
      if(combined.isFile() && combined.length()==total) {
        if(metadata.isFile() && metadata.length()<4096)try {
          JSONObject saved=new JSONObject(new String(Files.readAllBytes(metadata.toPath()),StandardCharsets.UTF_8));
          if(identity.equals(saved.optString("inputs")) && saved.optString("sha256").equals(fileHash(combined))) { stage("assembling",total,total);return combined; }
        } catch(org.json.JSONException ignored){}
        // Adopt an older completed assembly only after comparing its full content.
        MessageDigest expected=MessageDigest.getInstance("SHA-256");long scanned=0;
        byte[] data=new byte[65536];
        for(File part:parts)try(InputStream in=new FileInputStream(part)) {
          int n;while((n=in.read(data))!=-1){check();expected.update(data,0,n);scanned+=n;stage("assembling",scanned,total);}
        }
        String digest=hexDigest(expected.digest());
        if(digest.equals(fileHash(combined))) {
          writeJson(metadata,new JSONObject().put("inputs",identity).put("sha256",digest));return combined;
        }
      }
      File tmp=new File(combined+".tmp");MessageDigest digest=MessageDigest.getInstance("SHA-256");long done=0;
      try(FileOutputStream out=new FileOutputStream(tmp)) {
        byte[] data=new byte[65536];
        for(File part:parts)try(InputStream in=new FileInputStream(part)) {
          int n;while((n=in.read(data))!=-1){check();out.write(data,0,n);digest.update(data,0,n);done+=n;stage("assembling",done,total);}
        }
        out.getFD().sync();
      }
      check();Files.move(tmp.toPath(),combined.toPath(),StandardCopyOption.REPLACE_EXISTING);
      writeJson(metadata,new JSONObject().put("inputs",identity).put("sha256",hexDigest(digest.digest())));
      return combined;
    }

    String hexDigest(byte[] digest) {
      StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format("%02x",value));return result.toString();
    }

    void packageTracks(File video,File audio,File output) throws Exception {
      stage("preparing",0,1);
      String identity=hash(fileHash(video)+":"+(audio==null?"":fileHash(audio)));
      File ready=new File(output+".ready.json");
      if(output.isFile() && ready.isFile() && ready.length()<4096)try {
        JSONObject saved=new JSONObject(new String(Files.readAllBytes(ready.toPath()),StandardCharsets.UTF_8));
        if(identity.equals(saved.optString("inputs")) && output.length()==saved.optLong("length") && fileHash(output).equals(saved.optString("sha256"))) {stage("finalizing",1,1);return;}
      }catch(org.json.JSONException ignored){}
      MediaRemuxer.toContainer(video,audio,output,this::check,this::stage,!task.youtubePage.isEmpty()&&task.mime.contains("webm"));
      check();
      try(RandomAccessFile durable=new RandomAccessFile(output,"rw")){durable.getFD().sync();}
      writeJson(ready,new JSONObject().put("inputs",identity).put("length",output.length()).put("sha256",fileHash(output)));
    }

    String fileHash(File file) throws Exception {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = new FileInputStream(file)) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) {
          check();
          digest.update(b, 0, n);
        }
      }
      StringBuilder result = new StringBuilder();
      for (byte b : digest.digest()) result.append(String.format("%02x", b));
      return result.toString();
    }

    void copy(InputStream in, OutputStream out) throws IOException {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) {
        check();
        out.write(b, 0, n);
      }
    }
  }

  private static String hash(String text) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder b = new StringBuilder();
    for (byte x : digest) b.append(String.format("%02x", x));
    return b.toString();
  }

  static void removeTree(File f) throws IOException {
    if (!Files.isSymbolicLink(f.toPath())) {
      File[] children = f.listFiles();
      if (children != null) for (File c : children) removeTree(c);
    }
    Files.deleteIfExists(f.toPath());
  }
}
