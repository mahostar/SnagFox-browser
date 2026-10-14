package app.vidloader;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Original zw0.l/a0, wm0.i and fg.o contracts; independent of Android and transport. */
public final class Fc2Playback {
  public static final String TOKEN_HEADER = "X-FC2-Video-Access-Token";
  private static final Pattern CONTENT = Pattern.compile("(?:^|/)content/([A-Za-z0-9]+)(?:/|$)");
  private Fc2Playback() {}

  private static URI endpoint(String value) {
    try {
      URI uri = URI.create(value);
      return "https".equalsIgnoreCase(uri.getScheme()) && "video.fc2.com".equalsIgnoreCase(uri.getHost())
          && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getRawUserInfo() == null ? uri : null;
    } catch (RuntimeException error) { return null; }
  }

  public static String contentId(String page) {
    URI uri = endpoint(page);
    if (uri == null) return "";
    Matcher match = CONTENT.matcher(uri.getRawPath());
    return match.find() ? match.group(1) : "";
  }

  public static boolean isContentPage(String page) { return !contentId(page).isEmpty(); }

  public static String playlistUrl(String page) {
    String id = contentId(page);
    return id.isEmpty() ? "" : "https://video.fc2.com/api/v3/videoplaylist/" + id;
  }

  public static boolean isPlaylistForPage(String page, String target) {
    String expected = playlistUrl(page);
    URI uri = endpoint(target);
    return !expected.isEmpty() && uri != null && uri.getRawFragment() == null
        && expected.equals("https://video.fc2.com" + uri.getRawPath());
  }

  public static boolean isSignedPlaybackUrl(String target) {
    URI uri = endpoint(target);
    if (uri == null || uri.getRawFragment() != null) return false;
    String path = uri.getRawPath(), query = uri.getRawQuery();
    return path.startsWith("/api/v3/videoplay/") && path.length() > "/api/v3/videoplay/".length()
        && query != null && query.startsWith("signature=") && query.length() > "signature=".length();
  }

  public enum ResponseKind { HLS, MP4, NONE }

  public static ResponseKind responseKind(String mime) {
    String type = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    if (type.equals("application/vnd.apple.mpegurl") || type.equals("application/x-mpegurl")) return ResponseKind.HLS;
    if (type.equals("video/mp4")) return ResponseKind.MP4;
    return ResponseKind.NONE;
  }

  /** Owned by one browser navigation; replace the Session when navigation ownership changes. */
  public static final class Session {
    public final String page, playlist;
    private String token = "";
    public Session(String page) {
      this.page = isContentPage(page) ? page : "";
      this.playlist = playlistUrl(this.page);
    }
    public synchronized boolean hasToken() { return !token.isEmpty(); }
    public synchronized boolean observe(String request, Map<String,String> observed) {
      if (page.isEmpty() || endpoint(request) == null || observed == null) return false;
      for (Map.Entry<String,String> entry : observed.entrySet()) {
        if (!TOKEN_HEADER.equalsIgnoreCase(entry.getKey())) continue;
        String value = entry.getValue();
        if (value == null || value.length() > 8192 || value.chars().anyMatch(c -> c < 32 || c == 127)) return false;
        boolean changed = !token.equals(value);
        token = value;
        return changed;
      }
      return false;
    }
    public synchronized Map<String,String> headersFor(String target, Map<String,String> context) {
      Map<String,String> headers = new LinkedHashMap<>();
      if (context != null) for (Map.Entry<String,String> entry : context.entrySet())
        if (!TOKEN_HEADER.equalsIgnoreCase(entry.getKey())) headers.put(entry.getKey(),entry.getValue());
      if (!token.isEmpty() && isPlaylistForPage(page,target)) headers.put(TOKEN_HEADER,token);
      return headers;
    }
    public synchronized void clear() { token = ""; }
  }

  public static final class Choice {
    public final String url;
    public final int originalHeight;
    public final boolean primary;
    private Choice(String url,int originalHeight,boolean primary) {
      this.url=url;this.originalHeight=originalHeight;this.primary=primary;
    }
  }

  /** Primary nq/sample must pass the caller's video-MIME probe before admitting hq/lq. */
  public static List<Choice> playlistChoices(String page, Map<String,String> playlist) {
    if (!isContentPage(page) || playlist == null) return Collections.emptyList();
    String key = playlist.containsKey("nq") ? "nq" : "sample";
    String primary = resolveChoice(page,playlist.get(key));
    if (primary.isEmpty()) return Collections.emptyList();
    List<Choice> choices = new ArrayList<>();
    choices.add(new Choice(primary,480,true));
    for (String quality : Arrays.asList("hq","lq")) {
      String url = resolveChoice(page,playlist.get(quality));
      if (!url.isEmpty() && choices.stream().noneMatch(choice -> choice.url.equals(url)))
        choices.add(new Choice(url,quality.equals("hq")?720:360,false));
    }
    return Collections.unmodifiableList(choices);
  }

  private static String resolveChoice(String page,String value) {
    if (value == null || value.isEmpty() || value.length() > 32768 || value.chars().anyMatch(c -> c < 32 || c == 127)) return "";
    try {
      URI uri = URI.create(value);
      if (!uri.isAbsolute()) {
        if (!value.startsWith("/") || value.startsWith("//")) return "";
        uri = URI.create("https://video.fc2.com").resolve(uri);
      }
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          && uri.getHost()!=null && uri.getRawUserInfo()==null ? uri.toString() : "";
    } catch (RuntimeException error) { return ""; }
  }
}
