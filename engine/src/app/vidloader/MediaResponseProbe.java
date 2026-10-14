package app.vidloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded response verification before an observed address becomes a download choice. */
public final class MediaResponseProbe {
  private MediaResponseProbe() {}

  public static String classify(String declared, byte[] prefix) {
    String type = declared == null ? "" : declared.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    String text = new String(prefix, StandardCharsets.UTF_8).replace("\ufeff", "").trim();
    String lower = text.toLowerCase(Locale.ROOT);
    if (lower.startsWith("<!doctype html") || lower.startsWith("<html") || lower.startsWith("<head") || lower.startsWith("<body") || type.equals("text/html") || type.equals("application/json")) return "";
    if (ManifestEnvelope.unwrap(text).trim().startsWith("#EXTM3U")) return "application/vnd.apple.mpegurl";
    if (lower.startsWith("<mpd") || (lower.startsWith("<?xml") && lower.contains("<mpd"))) return "application/dash+xml";
    if (prefix.length >= 12 && prefix[4]=='f' && prefix[5]=='t' && prefix[6]=='y' && prefix[7]=='p') return "video/mp4";
    if (prefix.length >= 4 && (prefix[0]&255)==0x1a && (prefix[1]&255)==0x45 && (prefix[2]&255)==0xdf && (prefix[3]&255)==0xa3) return "video/webm";
    if (type.startsWith("video/") || type.startsWith("audio/") || type.contains("mpegurl") || type.equals("application/dash+xml")) return type.endsWith("/unknown") ? "" : type;
    return "";
  }

  public static MediaCandidate inspect(MediaCandidate candidate, Map<String, String> context) throws IOException {
    return inspect(candidate,context,false);
  }

  public static MediaCandidate inspect(MediaCandidate candidate,Map<String,String> context,boolean resolveRedirect) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>(context);
    headers.put("Range", "bytes=0-8191");
    HttpURLConnection connection = HttpMedia.open(candidate.url, headers);
    try {
      int status = connection.getResponseCode();
      if (status != 200 && status != 206) throw new IOException("Media address returned HTTP " + status);
      HttpMedia.ensureIdentityEncoding(connection);
      ByteArrayOutputStream prefix = new ByteArrayOutputStream();
      try (InputStream input = connection.getInputStream()) {
        byte[] buffer = new byte[2048];
        while (prefix.size() < 8192) {
          int count = input.read(buffer, 0, Math.min(buffer.length, 8192-prefix.size()));
          if (count < 0) break;
          prefix.write(buffer, 0, count);
        }
      }
      String type = classify(connection.getContentType(), prefix.toByteArray());
      if (type.isEmpty()) return null;
      MediaCandidate result = MediaParser.fromUrl(resolveRedirect?connection.getURL().toString():candidate.url, type, candidate.title, candidate.source);
      if (result == null) return null;
      if (result.kind == MediaCandidate.Kind.DIRECT) result = new MediaCandidate(result.url, result.title, result.mime, candidate.width, candidate.height, result.source);
      result.thumbnailUrl = candidate.thumbnailUrl; result.durationMs = candidate.durationMs;
      result.qualityHint=candidate.qualityHint;
      result.referer = candidate.referer; result.origin = candidate.origin; result.userAgent = candidate.userAgent;
      result.contentLength = candidate.contentLength;
      String range = connection.getHeaderField("Content-Range");
      if (result.kind == MediaCandidate.Kind.DIRECT) {
        if (status == 200) result.contentLength = connection.getContentLengthLong();
        else if (range != null && range.matches("bytes \\d+-\\d+/\\d+")) result.contentLength = Long.parseLong(range.substring(range.lastIndexOf('/')+1));
      }
      result.responseVerified = true;
      return result;
    } finally { connection.disconnect(); }
  }
}
