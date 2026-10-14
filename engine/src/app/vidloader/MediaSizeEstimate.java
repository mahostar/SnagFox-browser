package app.vidloader;

import java.net.HttpURLConnection;
import java.util.*;
import java.util.regex.*;

/** Bounded metadata requests; never downloads complete media merely to size a picker tile. */
public final class MediaSizeEstimate {
  private MediaSizeEstimate() {}

  public static boolean vkPage(String page){try{String host=new java.net.URI(page).getHost();return host!=null&&host.matches("(?i)(?:(?:m|www)\\.)?(?:vk|vkvideo)\\.(?:com|ru)");}catch(Exception invalid){return false;}}

  /** VK-only: independent VOD duration and multiple samples, no resolution-to-size assumption. */
  public static long vkBytes(MediaCandidate candidate,Map<String,String> headers)throws Exception {
    if(candidate.kind!=MediaCandidate.Kind.HLS_MEDIA)return bytes(candidate,headers);
    HttpMedia.TextResponse response=HttpMedia.fetchText(candidate.url,headers,2*1024*1024,null);
    HlsPlaylist playlist=HlsPlaylist.parse(response.text,response.url);
    if(!playlist.ended||playlist.segments.isEmpty())return -1;
    candidate.durationMs=Math.round(playlist.durationSeconds*1000);
    Set<Integer> samples=new LinkedHashSet<>(Arrays.asList(playlist.segments.size()/4,playlist.segments.size()/2,playlist.segments.size()*3/4));
    long sum=0;for(int index:samples){HlsPlaylist.Segment segment=playlist.segments.get(index);long length=segment.length>0?segment.length:rangedLength(segment.url,headers);if(length<=0)return -1;sum=Math.addExact(sum,length);}
    return withAudio(Math.round((double)sum/samples.size()*playlist.segments.size()),candidate,headers);
  }

  public static long bytes(MediaCandidate candidate, Map<String, String> headers) throws Exception {
    if (candidate.contentLength > 0) return candidate.contentLength;
    if (candidate.kind == MediaCandidate.Kind.DIRECT) return resourceLength(candidate.url, headers);
    if (candidate.kind == MediaCandidate.Kind.HLS_MEDIA) {
      HttpMedia.TextResponse response = HttpMedia.fetchText(candidate.url, headers, 2 * 1024 * 1024, null);
      HlsPlaylist playlist = HlsPlaylist.parse(response.text, response.url);
      if (!playlist.ended || playlist.segments.isEmpty()) return -1;
      // Original rf4.run case 7 samples the segment at floor(2*n/3) with bytes=0-0.
      HlsPlaylist.Segment representative = playlist.segments.get((playlist.segments.size() * 2) / 3);
      long representativeLength = representative.length > 0 ? representative.length : rangedLength(representative.url, headers);
      if (representativeLength > 0) return withAudio(originalHlsEstimate(representativeLength, playlist.segments.size()), candidate, headers);
      if (candidate.bandwidth > 0 && playlist.durationSeconds > 0)
        return withAudio(Math.round(candidate.bandwidth * playlist.durationSeconds / 8.0), candidate, headers);
      // Sample at most three segments when the playlist has no declared bitrate.
      Set<Integer> sample = new LinkedHashSet<>(Arrays.asList(0, playlist.segments.size() / 2, playlist.segments.size() - 1));
      long total = 0;
      for (int index : sample) {
        HlsPlaylist.Segment segment = playlist.segments.get(index);
        long length = segment.length > 0 ? segment.length : resourceLength(segment.url, headers);
        if (length <= 0) return -1;
        total = Math.addExact(total, length);
      }
      long result = Math.round((double)total / sample.size() * playlist.segments.size());
      return withAudio(result, candidate, headers);
    }
    if (candidate.kind == MediaCandidate.Kind.DASH && candidate.bandwidth > 0 && candidate.durationMs > 0)
      return Math.round((candidate.bandwidth + candidate.audioBandwidth) * (candidate.durationMs / 1000.0) / 8.0);
    return -1;
  }

  private static long withAudio(long video, MediaCandidate candidate, Map<String,String> headers) throws Exception {
    if (candidate.audioUrl.isEmpty()) return video;
    MediaCandidate audio = MediaParser.parseHls(HttpMedia.text(candidate.audioUrl, headers, 2 * 1024 * 1024),candidate.audioUrl,candidate.title).stream().findFirst().orElse(null);
    if (audio == null) return -1;
    long extra = bytes(audio, headers);
    return extra > 0 ? Math.addExact(video, extra) : -1;
  }

  public static long originalHlsEstimate(long segmentBytes, int segments) {
    long estimate = Math.multiplyExact(segmentBytes, segments);
    return estimate < 3221225472L && segments > 10 ? Math.addExact(estimate, estimate / 5) : estimate;
  }

  private static long rangedLength(String url, Map<String,String> headers) throws Exception {
    Map<String,String> range = new LinkedHashMap<>(headers);
    range.put("Range", "bytes=0-0");
    HttpURLConnection connection = HttpMedia.open(url, range);
    try {
      Matcher match = Pattern.compile("bytes 0-0/(\\d+)").matcher(Optional.ofNullable(connection.getHeaderField("Content-Range")).orElse(""));
      return connection.getResponseCode() == 206 && match.matches() ? Long.parseLong(match.group(1)) : -1;
    } finally { connection.disconnect(); }
  }

  private static long resourceLength(String url, Map<String, String> headers) throws Exception {
    HttpURLConnection connection = null;
    try {
      connection = HttpMedia.open(url, headers, c -> c.setRequestMethod("HEAD"));
      String type = Optional.ofNullable(connection.getContentType()).orElse("");
      if (connection.getResponseCode() / 100 == 2 && !type.contains("text/html") && connection.getContentLengthLong() > 0)
        return connection.getContentLengthLong();
      if (connection.getResponseCode() / 100 == 2 && !type.contains("text/html")) {
        try { long objectSize = Long.parseLong(Optional.ofNullable(connection.getHeaderField("X-Object-Size")).orElse("0")); if (objectSize > 0) return objectSize; }
        catch (NumberFormatException ignored) {}
      }
    } finally { if (connection != null) connection.disconnect(); }
    Map<String, String> range = new LinkedHashMap<>(headers);
    range.put("Range", "bytes=0-0");
    try {
      connection = HttpMedia.open(url, range);
      Matcher match = Pattern.compile("bytes 0-0/(\\d+)").matcher(Optional.ofNullable(connection.getHeaderField("Content-Range")).orElse(""));
      if (connection.getResponseCode() == 206 && match.matches()) return Long.parseLong(match.group(1));
      return -1;
    } finally { if (connection != null) connection.disconnect(); }
  }
}
