package app.vidloader;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Single owner for the persistent queue. No URLs, cookies or history leave the device. */
public final class DownloadStore {
  public static final class Task {
    public String id = UUID.randomUUID().toString(), url = "", title = "", mime = "", name = "";
    public String referer = "", origin = "", userAgent = "", cookie = "";
    public String thumbnailUrl = "";
    public boolean viewed;
    public volatile long bytesPerSecond;
    public long durationMs;
    public volatile String state = "queued", error = "", etag = "";
    // Process-local recovery eligibility derived from the durable pre-crash
    // state. Explicitly paused/cancelled tasks never become runnable this way.
    public boolean interruptedRunnable;
    public String kind = "DIRECT", audioUrl = "", exportedUri = "";
    public String initUrl = "", audioInitUrl = "";
    public List<String> segments = new ArrayList<>(), audioSegments = new ArrayList<>();
    public volatile long bytes, total = -1;
    public long created = System.currentTimeMillis();
    public volatile int completedSegments;
    public volatile String phase = "downloading";
    public volatile int phaseProgress;
    public volatile long retryAt;
    public final int[] displayProgress = new int[4];
    public volatile int segmentCount;

    /** In-memory worker ownership; file deletion must wait until this becomes false. */
    public volatile boolean active;

    public boolean vault;
    public boolean mp3Output;
    public int mp3Bitrate=128;
    public String youtubePage="",youtubeFormatId="",youtubeAudioFormatId="";
    public long youtubeChunkSize,youtubeAudioChunkSize;
    public String youtubeSabr="";
    public final Map<String,String> extractorHeaders=new LinkedHashMap<>(),audioExtractorHeaders=new LinkedHashMap<>();

    public Task() {}

    public Task(JSONObject j) {
      String storedId = j.optString("id", id);
      if (storedId.matches("[A-Za-z0-9_-]{1,80}")) id = storedId;
      url = j.optString("url");
      title = j.optString("title");
      mime = j.optString("mime");
      name = j.optString("name");
      referer = j.optString("referer");
      origin = j.optString("origin");
      thumbnailUrl = j.optString("thumbnailUrl");
      viewed = j.optBoolean("viewed");
      durationMs = j.optLong("durationMs");
      userAgent = j.optString("userAgent");
      cookie = j.optString("cookie");
      state = j.optString("state", "paused");
      error = j.optString("error");
      etag = j.optString("etag");
      kind = j.optString("kind", "DIRECT");
      audioUrl = j.optString("audioUrl");
      exportedUri = j.optString("exportedUri");
      bytes = j.optLong("bytes");
      total = j.optLong("total", -1);
      created = j.optLong("created", created);
      completedSegments = j.optInt("completedSegments");
      retryAt = j.optLong("retryAt");
      phase = j.optString("phase","downloading");
      phaseProgress = j.optInt("phaseProgress");
      vault = j.optBoolean("vault");
      mp3Output=j.optBoolean("mp3Output");mp3Bitrate=j.optInt("mp3Bitrate",192);
      youtubePage=j.optString("youtubePage");youtubeFormatId=j.optString("youtubeFormatId");youtubeSabr=j.optString("youtubeSabr");youtubeAudioFormatId=j.optString("youtubeAudioFormatId");youtubeChunkSize=j.optLong("youtubeChunkSize");youtubeAudioChunkSize=j.optLong("youtubeAudioChunkSize");
      readExtractorHeaders(j.optJSONObject("extractorHeaders"),extractorHeaders);readExtractorHeaders(j.optJSONObject("audioExtractorHeaders"),audioExtractorHeaders);
      initUrl = j.optString("initUrl");
      audioInitUrl = j.optString("audioInitUrl");
      JSONArray ss = j.optJSONArray("segments"), aa = j.optJSONArray("audioSegments");
      if (ss != null) for (int i = 0; i < ss.length(); i++) segments.add(ss.optString(i));
      if (aa != null) for (int i = 0; i < aa.length(); i++) audioSegments.add(aa.optString(i));
    }

    JSONObject json() {
      JSONObject j = new JSONObject();
      try {
        j.put("id", id)
            .put("url", url)
            .put("title", title)
            .put("mime", mime)
            .put("name", name)
            .put("referer", referer)
            .put("retryAt", retryAt)
            .put("phase",phase)
            .put("phaseProgress",phaseProgress)
            .put("origin", origin)
            .put("thumbnailUrl", thumbnailUrl)
            .put("viewed", viewed)
            .put("durationMs", durationMs)
            .put("userAgent", userAgent)
            .put("cookie", cookie)
            .put("state", state)
            .put("error", error)
            .put("etag", etag)
            .put("kind", kind)
            .put("audioUrl", audioUrl)
            .put("exportedUri", exportedUri)
            .put("bytes", bytes)
            .put("total", total)
            .put("created", created)
            .put("completedSegments", completedSegments)
            .put("vault", vault).put("mp3Output",mp3Output).put("mp3Bitrate",mp3Bitrate).put("youtubePage",youtubePage).put("youtubeFormatId",youtubeFormatId).put("youtubeSabr",youtubeSabr).put("youtubeAudioFormatId",youtubeAudioFormatId).put("youtubeChunkSize",youtubeChunkSize).put("youtubeAudioChunkSize",youtubeAudioChunkSize)
            .put("extractorHeaders",new JSONObject(extractorHeaders)).put("audioExtractorHeaders",new JSONObject(audioExtractorHeaders));
        j.put("initUrl", initUrl)
            .put("audioInitUrl", audioInitUrl)
            .put("segments", new JSONArray(segments))
            .put("audioSegments", new JSONArray(audioSegments));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      return j;
    }
  }

  static void readExtractorHeaders(JSONObject json,Map<String,String> target){
    if(json==null)return;Iterator<String> keys=json.keys();while(keys.hasNext()&&target.size()<32){String key=keys.next(),value=json.optString(key);String lower=key.toLowerCase(Locale.ROOT);
      if((lower.equals("user-agent")||lower.equals("referer")||lower.equals("origin")||lower.equals("accept")||lower.equals("accept-language")||lower.startsWith("x-youtube-")||lower.startsWith("x-goog-"))&&value.length()<=8192&&!value.contains("\r")&&!value.contains("\n"))target.put(key,value);
    }
  }

  public static String phaseLabel(String phase) {
    switch(phase) {
      case "checking": return "Checking saved video";
      case "assembling": return "Assembling video";
      case "preparing": return "Preparing video";
      case "saving": return "Saving video";
      case "finalizing": return "Finishing video";
      case "converting": return "Creating MP3";
      default:return "Downloading";
    }
  }

  private static DownloadStore instance;

  public static synchronized DownloadStore get(Context c) {
    if (instance == null) instance = new DownloadStore(c.getApplicationContext());
    return instance;
  }

  private final File database, media;
  private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();

  private DownloadStore(Context c) {
    database = new File(c.getFilesDir(), "downloads.json");
    media = new File(c.getFilesDir(), "media");
    media.mkdirs();
    if (database.exists())
      try {
        JSONArray a =
            new JSONArray(
                new String(Files.readAllBytes(database.toPath()), StandardCharsets.UTF_8));
        for (int i = 0; i < a.length(); i++) {
          Task t = new Task(a.getJSONObject(i));
          if (t.state.equals("downloading") || t.state.equals("queued")){t.interruptedRunnable=true;t.state = "paused";}
          tasks.put(t.id, t);
        }
      } catch (Exception e) {
        // Preserve damaged evidence instead of replacing it with an empty database.
        database.renameTo(
            new File(
                database.getParentFile(),
                "downloads-recovery-" + System.currentTimeMillis() + ".json"));
      }
  }

  public synchronized List<Task> all() {
    return new ArrayList<>(tasks.values());
  }

  public synchronized Task find(String id) {
    return tasks.get(id);
  }

  public synchronized void put(Task t) {
    if (!t.id.matches("[A-Za-z0-9_-]{1,80}"))
      throw new IllegalArgumentException("Invalid download identity");
    if (tasks.containsKey(t.id) && tasks.get(t.id) != t)
      throw new IllegalArgumentException("Download already exists");
    tasks.put(t.id, t);
    save();
  }

  public synchronized void remove(String id) {
    tasks.remove(id);
    save();
  }

  public File file(Task t) {
    return new File(media, t.id + "-" + safeName(t.name));
  }

  public File partial(Task t) {
    return new File(media, t.id + ".part");
  }

  public File work(Task t) {
    File f = new File(media, t.id + "-segments");
    f.mkdirs();
    return f;
  }

  public static String safeName(String s) {
    String n = (s == null ? "" : s).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    while (n.startsWith(".")) n = n.substring(1);
    return n.isEmpty() ? "download" : n.substring(0, Math.min(150, n.length()));
  }

  public synchronized void save() {
    JSONArray a = new JSONArray();
    for (Task t : tasks.values()) a.put(t.json());
    File tmp = new File(database.getParentFile(), "downloads.json.tmp");
    try {
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      Files.move(tmp.toPath(), database.toPath(), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IllegalStateException("Could not save download queue", e);
    }
  }
}
