package app.vidloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A media resource discovered in a page or an adaptive-stream manifest. */
public final class MediaCandidate {
  public enum Kind {
    DIRECT,
    SABR,
    HLS_MASTER,
    HLS_MEDIA,
    DASH
  }

  public final String url, title, mime, source, codecs, audioGroup, audioUrl;
  public final String initUrl, audioInitUrl, unsupportedReason, manifestUrl;
  public final int width, height;
  public final long bandwidth;
  public final Kind kind;
  public final boolean drm;
  public String thumbnailUrl = "";
  public long contentLength = -1, durationMs = 0, audioBandwidth = 0;
  public String referer = "", origin = "", userAgent = "";
  public boolean responseVerified;
  public boolean extractedMetadata;
  public long youtubeChunkSize,youtubeAudioChunkSize,audioContentLength=-1;
  public String youtubeAudioFormatId="";
  public double framesPerSecond;
  public boolean qualityHint;
  public boolean mp3Output;
  public int mp3Bitrate=128;
  public String youtubeFormatId="";
  public String youtubeAudioTrack="",youtubeAudioTrackName="";
  public boolean youtubeDefaultAudio;
  public String youtubeSabr="";
  public String youtubeCaptions="[]";
  public final java.util.Map<String,String> extractorHeaders=new java.util.LinkedHashMap<>();
  public final java.util.Map<String,String> audioExtractorHeaders=new java.util.LinkedHashMap<>();

  /** Ordered media fragments, excluding initialization data. Empty for direct/HLS resources. */
  public final List<String> segments, audioSegments;

  public MediaCandidate(
      String url, String title, String mime, int width, int height, String source) {
    this(
        url,
        title,
        mime,
        width,
        height,
        source,
        Kind.DIRECT,
        0,
        "",
        "",
        "",
        false,
        "",
        Collections.<String>emptyList(),
        "",
        Collections.<String>emptyList(),
        "");
  }

  public MediaCandidate(
      String url,
      String title,
      String mime,
      int width,
      int height,
      String source,
      Kind kind,
      long bandwidth,
      String codecs,
      String audioGroup,
      String audioUrl,
      boolean drm,
      String initUrl,
      List<String> segments,
      String audioInitUrl,
      List<String> audioSegments,
      String unsupportedReason) {
    this(
        url,
        title,
        mime,
        width,
        height,
        source,
        kind,
        bandwidth,
        codecs,
        audioGroup,
        audioUrl,
        drm,
        initUrl,
        segments,
        audioInitUrl,
        audioSegments,
        unsupportedReason,
        "");
  }

  public MediaCandidate(
      String url,
      String title,
      String mime,
      int width,
      int height,
      String source,
      Kind kind,
      long bandwidth,
      String codecs,
      String audioGroup,
      String audioUrl,
      boolean drm,
      String initUrl,
      List<String> segments,
      String audioInitUrl,
      List<String> audioSegments,
      String unsupportedReason,
      String manifestUrl) {
    this.url = value(url);
    this.title = value(title);
    this.mime = value(mime);
    this.width = width;
    this.height = height;
    this.source = value(source);
    this.kind = kind;
    this.bandwidth = bandwidth;
    this.codecs = value(codecs);
    this.audioGroup = value(audioGroup);
    this.audioUrl = value(audioUrl);
    this.drm = drm;
    this.initUrl = value(initUrl);
    this.audioInitUrl = value(audioInitUrl);
    this.segments = immutable(segments);
    this.audioSegments = immutable(audioSegments);
    this.unsupportedReason = value(unsupportedReason);
    this.manifestUrl = value(manifestUrl);
  }

  public boolean isDownloadable() {
    return !drm && unsupportedReason.isEmpty();
  }

  public String qualityLabel() {
    if(mp3Output)return "MP3";
    if (height > 0 && !mime.startsWith("image/")) return qualityPixels() + "p";
    if (mime.startsWith("audio/"))
      return bandwidth > 0 ? (bandwidth / 1000) + " kbps audio" : "Audio";
    if (mime.startsWith("image/")) return width > 0 ? width + " × " + height : "Image";
    return bandwidth > 0 ? (bandwidth / 1000) + " kbps" : "Original";
  }

  public int qualityPixels() {
    return width > 0 && height > 0 ? Math.min(width, height) : height;
  }

  private static String value(String text) {
    return text == null ? "" : text;
  }

  private static List<String> immutable(List<String> values) {
    return Collections.unmodifiableList(
        new ArrayList<>(values == null ? Collections.<String>emptyList() : values));
  }
}
