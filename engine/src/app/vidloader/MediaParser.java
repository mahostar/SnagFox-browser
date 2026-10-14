package app.vidloader;

import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Independent, network-free parsing. A detected URL is a candidate, not proof of permission or
 * availability.
 */
public final class MediaParser {
  private static final int MAX_SEGMENTS = 20000;
  private static final Pattern ATTR =
      Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
  private static final Pattern TAG =
      Pattern.compile("<(video|audio|source|img|meta|a|link)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
  private static final Pattern JSON_URL =
      Pattern.compile(
          "[\"'](contentUrl|video_url|videoUrl|playbackUrl|playback_url|playUrl|play_url|downloadUrl|download_url|src|url|hls|dash|thumbnailUrl)[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern URL =
      Pattern.compile("(?:https?:)?//[^\\s\"'<>\\\\]+", Pattern.CASE_INSENSITIVE);

  private MediaParser() {}

  public static MediaCandidate fromUrl(String url, String mime, String title, String source) {
    String clean = resolve("", url);
    if (clean.isEmpty()) return null;
    String type = mime == null ? "" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    String extension = extension(clean);
    if (extension.equals("m3u8")
        || type.equals("application/vnd.apple.mpegurl")
        || type.equals("application/x-mpegurl")
        || type.equals("video/mpegurl")
        || type.equals("video/x-mpegurl")
        || type.equals("audio/mpegurl")
        || type.equals("audio/x-mpegurl")) {
      return candidate(
          clean,
          title,
          "application/vnd.apple.mpegurl",
          0,
          0,
          source,
          MediaCandidate.Kind.HLS_MASTER,
          0,
          "",
          "",
          "",
          false,
          "",
          Collections.<String>emptyList(),
          "",
          Collections.<String>emptyList(),
          "",
          clean);
    }
    if (extension.equals("mpd") || type.equals("application/dash+xml")) {
      return candidate(
          clean,
          title,
          "application/dash+xml",
          0,
          0,
          source,
          MediaCandidate.Kind.DASH,
          0,
          "",
          "",
          "",
          false,
          "",
          Collections.<String>emptyList(),
          "",
          Collections.<String>emptyList(),
          "",
          clean);
    }
    if (!(type.startsWith("video/") || type.startsWith("audio/") || type.startsWith("image/")))
      type = mimeForExtension(extension);
    if (type.isEmpty() || type.equals("image/svg+xml")) return null;
    return new MediaCandidate(clean, title, type, 0, 0, source);
  }

  public static List<MediaCandidate> extractFromText(String text, String baseUrl, String title) {
    if (text == null || text.isEmpty()) return Collections.emptyList();
    String decoded = decodeEscapes(text);
    LinkedHashMap<String, MediaCandidate> found = new LinkedHashMap<>();
    Matcher tags = TAG.matcher(decoded);
    while (tags.find()) {
      String tag = tags.group(1).toLowerCase(Locale.ROOT);
      Map<String, String> attrs = htmlAttributes(tags.group(2));
      String type = attrs.getOrDefault("type", "");
      if (type.isEmpty() && (tag.equals("video") || tag.equals("audio"))) type = tag + "/unknown";
      String key =
          attrs.getOrDefault("property", attrs.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
      if (tag.equals("meta")) {
        if (key.equals("og:video")
            || key.equals("og:video:url")
            || key.equals("og:video:secure_url")
            || key.equals("twitter:player:stream")) type = "video/unknown";
        else if (key.equals("og:audio")
            || key.equals("og:audio:url")
            || key.equals("og:audio:secure_url")) type = "audio/unknown";
        else if (key.equals("og:image")
            || key.equals("og:image:url")
            || key.equals("og:image:secure_url")
            || key.equals("twitter:image")) type = "image/unknown";
        else continue;
      }
      if (tag.equals("img")) type = "image/unknown";
      for (String name : new String[] {"src", "data-src", "data-video", "href", "content"}) {
        String value = attrs.get(name);
        if (value != null) add(found, resolve(baseUrl, value), type, title, "Page " + tag);
      }
      String srcset = attrs.get("srcset");
      if (srcset != null)
        for (String entry : srcset.split(",")) {
          String trimmed = entry.trim();
          if (!trimmed.isEmpty())
            add(
                found,
                resolve(baseUrl, trimmed.split("\\s+", 2)[0]),
                "image/unknown",
                title,
                "Page srcset");
        }
    }
    Matcher json = JSON_URL.matcher(decoded);
    while (json.find()) {
      String name = json.group(1).toLowerCase(Locale.ROOT);
      String type =
          name.equals("hls")
              ? "application/vnd.apple.mpegurl"
              : name.equals("dash")
                  ? "application/dash+xml"
                  : name.equals("thumbnailurl")
                      ? "image/unknown"
                      : (name.contains("video")
                              || name.contains("play")
                              || name.equals("contenturl"))
                          ? "video/unknown"
                          : "";
      add(found, resolve(baseUrl, json.group(2)), type, title, "Page metadata");
    }
    Matcher urls = URL.matcher(decoded);
    while (urls.find()) add(found, resolve(baseUrl, urls.group()), "", title, "Page URL");
    return new ArrayList<>(found.values());
  }

  public static List<MediaCandidate> parseHls(String text, String manifestUrl, String title) {
    text = ManifestEnvelope.unwrap(text);
    if (text == null || !text.replace("\ufeff", "").trim().startsWith("#EXTM3U"))
      return Collections.emptyList();
    String[] lines = text.replace("\r", "").split("\n");
    Map<String, Map<String, String>> audio = new LinkedHashMap<>();
    boolean drm = false;
    for (String raw : lines) {
      String line = raw.trim();
      if (line.startsWith("#EXT-X-MEDIA:")) {
        Map<String, String> attr = hlsAttributes(line.substring(line.indexOf(':') + 1));
        if (!"AUDIO".equals(attr.get("TYPE")) || !attr.containsKey("URI")) continue;
        String group = attr.getOrDefault("GROUP-ID", "");
        Map<String, String> previous = audio.get(group);
        if (previous == null
            || "YES".equals(attr.get("DEFAULT"))
            || (!"YES".equals(previous.get("DEFAULT")) && "YES".equals(attr.get("AUTOSELECT"))))
          audio.put(group, attr);
      }
      if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
        Map<String, String> key = hlsAttributes(line.substring(line.indexOf(':') + 1));
        String method = key.getOrDefault("METHOD", "NONE");
        String keyformat = key.getOrDefault("KEYFORMAT", "identity");
        if ((!method.equals("NONE") && !method.equals("AES-128"))
            || !keyformat.equals("identity")
            || key.getOrDefault("URI", "").startsWith("skd:")) drm = true;
      }
    }
    List<MediaCandidate> variants = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
      Map<String, String> attrs = hlsAttributes(line.substring(line.indexOf(':') + 1));
      String next = "";
      for (int n = i + 1; n < lines.length; n++)
        if (!lines[n].trim().isEmpty() && !lines[n].trim().startsWith("#")) {
          next = lines[n].trim();
          i = n;
          break;
        }
      String url = resolve(manifestUrl, next);
      if (url.isEmpty()) continue;
      String[] resolution =
          attrs.getOrDefault("RESOLUTION", "0x0").toLowerCase(Locale.ROOT).split("x");
      int width = intValue(resolution.length > 0 ? resolution[0] : "0", 0);
      int height = intValue(resolution.length > 1 ? resolution[1] : "0", 0);
      String group = attrs.getOrDefault("AUDIO", "");
      Map<String, String> track = audio.get(group);
      String audioUrl = track == null ? "" : resolve(manifestUrl, track.get("URI"));
      variants.add(
          candidate(
              url,
              title,
              "application/vnd.apple.mpegurl",
              width,
              height,
              "HLS variant",
              MediaCandidate.Kind.HLS_MEDIA,
              longValue(
                  attrs.getOrDefault("AVERAGE-BANDWIDTH", attrs.getOrDefault("BANDWIDTH", "0")), 0),
              attrs.getOrDefault("CODECS", ""),
              group,
              audioUrl,
              drm,
              "",
              Collections.<String>emptyList(),
              "",
              Collections.<String>emptyList(),
              drm ? "Protected HLS encryption is unsupported" : "",
              manifestUrl));
    }
    if (variants.isEmpty())
      variants.add(
          candidate(
              manifestUrl,
              title,
              "application/vnd.apple.mpegurl",
              0,
              0,
              "HLS playlist",
              MediaCandidate.Kind.HLS_MEDIA,
              0,
              "",
              "",
              "",
              drm,
              "",
              Collections.<String>emptyList(),
              "",
              Collections.<String>emptyList(),
              drm ? "Protected HLS encryption is unsupported" : "",
              manifestUrl));
    return variants;
  }

  /**
   * Static DASH only. Dynamic, DRM, unbounded timelines and unsupported indexes are explicit
   * failures.
   */
  public static List<MediaCandidate> parseDash(String xml, String manifestUrl, String title) {
    if (xml == null) return Collections.emptyList();
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setExpandEntityReferences(false);
      secureFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
      secureFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
      secureFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
      if (xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")
          || xml.toUpperCase(Locale.ROOT).contains("<!ENTITY")) return Collections.emptyList();
      Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
      Element mpd = doc.getDocumentElement();
      if (!name(mpd).equals("MPD")) return Collections.emptyList();
      String rootBase = childBase(manifestUrl, mpd);
      boolean dynamic = "dynamic".equals(mpd.getAttribute("type"));
      boolean rootDrm = hasChild(mpd, "ContentProtection");
      double mpdDuration = duration(mpd.getAttribute("mediaPresentationDuration"));
      List<Element> periods = children(mpd, "Period");
      List<MediaCandidate> output = new ArrayList<>();
      for (int periodIndex = 0; periodIndex < periods.size(); periodIndex++) {
        Element period = periods.get(periodIndex);
        String periodBase = childBase(rootBase, period);
        double seconds = duration(period.getAttribute("duration"));
        if (seconds <= 0 && periods.size() == 1) seconds = mpdDuration;
        if (seconds <= 0 && periodIndex + 1 < periods.size())
          seconds =
              duration(periods.get(periodIndex + 1).getAttribute("start"))
                  - duration(period.getAttribute("start"));
        List<MediaCandidate> video = new ArrayList<>(), audio = new ArrayList<>();
        for (Element adaptation : children(period, "AdaptationSet")) {
          String adaptationBase = childBase(periodBase, adaptation);
          for (Element rep : children(adaptation, "Representation")) {
            String base = childBase(adaptationBase, rep);
            String mime = inherited(rep, adaptation, "mimeType");
            String contentType = inherited(rep, adaptation, "contentType");
            String codecs = inherited(rep, adaptation, "codecs");
            if (mime.isEmpty())
              mime =
                  contentType.equals("audio")
                          || codecs.startsWith("mp4a")
                          || codecs.startsWith("opus")
                      ? "audio/mp4"
                      : "video/mp4";
            boolean drm =
                rootDrm
                    || hasChild(period, "ContentProtection")
                    || hasChild(adaptation, "ContentProtection")
                    || hasChild(rep, "ContentProtection");
            String reason =
                drm
                    ? "DRM protected DASH is unsupported"
                    : dynamic
                        ? "Live DASH is unsupported"
                        : periods.size() > 1 ? "Multi-period DASH is unsupported" : "";
            SegmentData data = segments(mpd, period, adaptation, rep, base, seconds);
            if (reason.isEmpty()) reason = data.reason;
            String url = data.segments.isEmpty() ? base : manifestUrl;
            if (data.segments.isEmpty() && url.equals(manifestUrl) && reason.isEmpty())
              reason = "DASH has no downloadable media address";
            MediaCandidate item =
                candidate(
                    url,
                    title,
                    mime,
                    intValue(inherited(rep, adaptation, "width"), 0),
                    intValue(inherited(rep, adaptation, "height"), 0),
                    "DASH representation " + rep.getAttribute("id"),
                    MediaCandidate.Kind.DASH,
                    longValue(rep.getAttribute("bandwidth"), 0),
                    codecs,
                    adaptation.getAttribute("id"),
                    "",
                    drm,
                    data.init,
                    data.segments,
                    "",
                    Collections.<String>emptyList(),
                    reason,
                    manifestUrl);
            item.durationMs = seconds > 0 ? Math.round(seconds * 1000) : 0;
            if (mime.startsWith("audio/")) audio.add(item);
            else if (mime.startsWith("video/")) video.add(item);
          }
        }
        MediaCandidate preferredAudio = null;
        for (MediaCandidate item : audio)
          if (preferredAudio == null
              || (item.isDownloadable()
                  && (!preferredAudio.isDownloadable()
                      || item.bandwidth > preferredAudio.bandwidth))) preferredAudio = item;
        for (MediaCandidate item : video) {
          if (preferredAudio == null) {
            output.add(item);
            continue;
          }
          String reason = item.unsupportedReason;
          if (reason.isEmpty() && !preferredAudio.isDownloadable())
            reason = preferredAudio.unsupportedReason;
          MediaCandidate combined = candidate(
                  item.url,
                  item.title,
                  item.mime,
                  item.width,
                  item.height,
                  item.source,
                  item.kind,
                  item.bandwidth,
                  item.codecs,
                  preferredAudio.audioGroup,
                  preferredAudio.segments.isEmpty() ? preferredAudio.url : "",
                  item.drm || preferredAudio.drm,
                  item.initUrl,
                  item.segments,
                  preferredAudio.initUrl,
                  preferredAudio.segments,
                  reason,
                  manifestUrl);
          combined.durationMs = item.durationMs;
          combined.audioBandwidth = preferredAudio.bandwidth;
          output.add(combined);
        }
        if (video.isEmpty()) output.addAll(audio);
      }
      return output;
    } catch (Exception ignored) {
      return Collections.emptyList();
    }
  }

  private static SegmentData segments(
      Element mpd, Element period, Element adaptation, Element rep, String base, double seconds) {
    SegmentData result = new SegmentData();
    Element[] hierarchy = {mpd, period, adaptation, rep};
    Element list = nearest(hierarchy, "SegmentList");
    Element template = nearest(hierarchy, "SegmentTemplate");
    Element segmentBase = nearest(hierarchy, "SegmentBase");
    if (segmentBase != null) {
      result.reason = "DASH byte-range indexes are unsupported";
      return result;
    }
    if (list != null) {
      Element init = first(list, "Initialization");
      if (init != null) {
        if (!init.getAttribute("range").isEmpty())
          result.reason = "DASH byte ranges are unsupported";
        result.init = resolve(base, init.getAttribute("sourceURL"));
      }
      for (Element url : children(list, "SegmentURL")) {
        if (!url.getAttribute("mediaRange").isEmpty() || !url.getAttribute("indexRange").isEmpty())
          result.reason = "DASH byte ranges are unsupported";
        String resolved = resolve(base, url.getAttribute("media"));
        if (resolved.isEmpty()) result.reason = "Invalid DASH segment URL";
        else result.segments.add(resolved);
        if (result.segments.size() > MAX_SEGMENTS) {
          result.reason = "DASH segment limit exceeded";
          result.segments.clear();
          break;
        }
      }
      if (result.segments.isEmpty() && result.reason.isEmpty())
        result.reason = "Empty DASH SegmentList";
      return result;
    }
    if (template == null) return result;
    Map<String, String> attrs = new LinkedHashMap<>();
    Element timeline = null;
    for (Element owner : hierarchy) {
      Element current = first(owner, "SegmentTemplate");
      if (current == null) continue;
      for (int i = 0; i < current.getAttributes().getLength(); i++) {
        Node attr = current.getAttributes().item(i);
        attrs.put(attr.getNodeName(), attr.getNodeValue());
      }
      if (first(current, "SegmentTimeline") != null) timeline = first(current, "SegmentTimeline");
    }
    String media = attrs.getOrDefault("media", "");
    String id = rep.getAttribute("id"), bandwidth = rep.getAttribute("bandwidth");
    long scale = longValue(attrs.getOrDefault("timescale", "1"), 1);
    long number = longValue(attrs.getOrDefault("startNumber", "1"), 1);
    long offset = longValue(attrs.getOrDefault("presentationTimeOffset", "0"), 0);
    if (scale <= 0 || number < 0 || media.isEmpty()) {
      result.reason = "Invalid DASH SegmentTemplate";
      return result;
    }
    result.init =
        resolve(
            base,
            expandTemplate(attrs.getOrDefault("initialization", ""), id, bandwidth, number, 0));
    if (timeline != null) {
      List<Element> entries = children(timeline, "S");
      long time = 0;
      for (int index = 0; index < entries.size(); index++) {
        Element entry = entries.get(index);
        if (entry.hasAttribute("t")) time = longValue(entry.getAttribute("t"), -1);
        long step = longValue(entry.getAttribute("d"), 0),
            repeats = longValue(entry.getAttribute("r"), 0);
        if (step <= 0 || time < 0 || repeats < -1) {
          result.reason = "Invalid DASH timeline";
          break;
        }
        if (repeats == -1) {
          long end =
              index + 1 < entries.size() && entries.get(index + 1).hasAttribute("t")
                  ? longValue(entries.get(index + 1).getAttribute("t"), -1)
                  : seconds > 0 ? (long) Math.ceil(seconds * scale) + offset : -1;
          if (end <= time) {
            result.reason = "Unbounded DASH timeline";
            break;
          }
          repeats = (end - time - 1) / step;
        }
        if (repeats >= MAX_SEGMENTS || result.segments.size() + repeats + 1 > MAX_SEGMENTS) {
          result.reason = "DASH segment limit exceeded";
          break;
        }
        for (long i = 0; i <= repeats; i++) {
          String url = resolve(base, expandTemplate(media, id, bandwidth, number++, time));
          if (url.isEmpty()) {
            result.reason = "Unsupported DASH URL template";
            break;
          }
          result.segments.add(url);
          if (time > Long.MAX_VALUE - step) {
            result.reason = "DASH timestamp overflow";
            break;
          }
          time += step;
        }
        if (!result.reason.isEmpty()) break;
      }
    } else {
      long step = longValue(attrs.getOrDefault("duration", "0"), 0);
      if (step <= 0 || seconds <= 0 || !Double.isFinite(seconds))
        result.reason = "DASH duration is unavailable";
      else {
        double countDouble = Math.ceil(seconds * scale / step);
        if (countDouble > MAX_SEGMENTS) result.reason = "DASH segment limit exceeded";
        else
          for (int i = 0; i < (int) countDouble; i++) {
            String url =
                resolve(base, expandTemplate(media, id, bandwidth, number + i, (long) i * step));
            if (url.isEmpty()) {
              result.reason = "Unsupported DASH URL template";
              break;
            }
            result.segments.add(url);
          }
      }
    }
    if (result.segments.isEmpty() && result.reason.isEmpty()) result.reason = "Empty DASH timeline";
    if (!result.reason.isEmpty()) result.segments.clear();
    return result;
  }

  private static String expandTemplate(
      String template, String id, String bandwidth, long number, long time) {
    if (template.isEmpty()) return "";
    String escaped = template.replace("$$", "\u0001");
    Matcher matcher =
        Pattern.compile("\\$(RepresentationID|Bandwidth|Number|Time)(?:%0(\\d+)d)?\\$")
            .matcher(escaped);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String key = matcher.group(1);
      String value =
          key.equals("RepresentationID")
              ? id
              : key.equals("Bandwidth")
                  ? bandwidth
                  : String.valueOf(key.equals("Number") ? number : time);
      if (matcher.group(2) != null) {
        int padding = intValue(matcher.group(2), 0);
        if (padding < 1 || padding > 20 || key.equals("RepresentationID")) return "";
        value = String.format(Locale.ROOT, "%0" + padding + "d", longValue(value, 0));
      }
      matcher.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(out);
    String expanded = out.toString();
    if (expanded.contains("$")) return "";
    return expanded.replace('\u0001', '$');
  }

  public static String resolve(String base, String reference) {
    if (reference == null || reference.trim().isEmpty()) return "";
    try {
      String decoded = htmlDecode(decodeEscapes(reference.trim())).replace(" ", "%20");
      URI value = new URI(decoded);
      if (!value.isAbsolute()) {
        if (base == null || base.isEmpty()) return "";
        // java.net.URI resolves query-only references against the directory, unlike RFC 3986.
        if (decoded.startsWith("?")) {
          String path = base.split("[?#]", 2)[0];
          value = new URI(path + decoded);
        } else value = new URI(base).resolve(value);
      }
      String scheme = value.getScheme();
      if (scheme == null
          || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          || value.getHost() == null
          || value.getUserInfo() != null) return "";
      // Fragment identifiers never select a different HTTP resource.
      String normalized = value.toASCIIString();
      int fragment = normalized.indexOf('#');
      return fragment >= 0 ? normalized.substring(0, fragment) : normalized;
    } catch (Exception ignored) {
      return "";
    }
  }

  private static void add(
      Map<String, MediaCandidate> found, String url, String mime, String title, String source) {
    MediaCandidate item = fromUrl(url, mime, title, source);
    if (item != null && !found.containsKey(item.url)) found.put(item.url, item);
  }

  private static MediaCandidate candidate(
      String url,
      String title,
      String mime,
      int width,
      int height,
      String source,
      MediaCandidate.Kind kind,
      long bandwidth,
      String codecs,
      String audioGroup,
      String audioUrl,
      boolean drm,
      String initUrl,
      List<String> segments,
      String audioInitUrl,
      List<String> audioSegments,
      String reason,
      String manifestUrl) {
    return new MediaCandidate(
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
        reason,
        manifestUrl);
  }

  private static String extension(String url) {
    try {
      String path = new URI(url).getPath().toLowerCase(Locale.ROOT);
      int dot = path.lastIndexOf('.');
      return dot >= 0 ? path.substring(dot + 1) : "";
    } catch (Exception ignored) {
      return "";
    }
  }

  private static String mimeForExtension(String extension) {
    switch (extension) {
      case "mp4":
      case "m4v":
      case "mov":
        return "video/mp4";
      case "webm":
        return "video/webm";
      case "mkv":
        return "video/x-matroska";
      case "avi":
        return "video/x-msvideo";
      case "flv":
        return "video/x-flv";
      case "3gp":
        return "video/3gpp";
      case "ts":
        return "video/mp2t";
      case "mp3":
        return "audio/mpeg";
      case "m4a":
        return "audio/mp4";
      case "aac":
        return "audio/aac";
      case "ogg":
      case "oga":
        return "audio/ogg";
      case "opus":
        return "audio/opus";
      case "wav":
        return "audio/wav";
      case "flac":
        return "audio/flac";
      case "jpg":
      case "jpeg":
        return "image/jpeg";
      case "png":
        return "image/png";
      case "gif":
        return "image/gif";
      case "webp":
        return "image/webp";
      case "avif":
        return "image/avif";
      default:
        return "";
    }
  }

  private static Map<String, String> htmlAttributes(String text) {
    Map<String, String> result = new LinkedHashMap<>();
    Matcher matcher = ATTR.matcher(text);
    while (matcher.find())
      result.put(
          matcher.group(1).toLowerCase(Locale.ROOT),
          htmlDecode(
              matcher.group(2) != null
                  ? matcher.group(2)
                  : matcher.group(3) != null ? matcher.group(3) : matcher.group(4)));
    return result;
  }

  private static Map<String, String> hlsAttributes(String text) {
    Map<String, String> result = new LinkedHashMap<>();
    Matcher matcher =
        Pattern.compile("([A-Z0-9-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,]*))").matcher(text);
    while (matcher.find())
      result.put(
          matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3).trim());
    return result;
  }

  private static String decodeEscapes(String text) {
    String result = text;
    for (int pass = 0; pass < 2; pass++) {
      StringBuilder out = new StringBuilder();
      for (int i = 0; i < result.length(); i++) {
        char c = result.charAt(i);
        if (c == '\\' && i + 1 < result.length()) {
          char next = result.charAt(i + 1);
          if (next == '/' || next == '\\' || next == '"' || next == '\'') {
            out.append(next);
            i++;
            continue;
          }
          if (next == 'u' && i + 5 < result.length()) {
            try {
              out.append((char) Integer.parseInt(result.substring(i + 2, i + 6), 16));
              i += 5;
              continue;
            } catch (NumberFormatException ignored) {
            }
          }
        }
        out.append(c);
      }
      if (out.toString().equals(result)) break;
      result = out.toString();
    }
    return result;
  }

  private static String htmlDecode(String text) {
    String result =
        text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">");
    Matcher matcher = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(result);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      try {
        String token = matcher.group(1);
        int value =
            Integer.parseInt(
                token.startsWith("x") ? token.substring(1) : token,
                token.startsWith("x") ? 16 : 10);
        matcher.appendReplacement(
            out, Matcher.quoteReplacement(new String(Character.toChars(value))));
      } catch (IllegalArgumentException ignored) {
        matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
      }
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static long longValue(String text, long fallback) {
    try {
      return Long.parseLong(text);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static int intValue(String text, int fallback) {
    try {
      return Integer.parseInt(text);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private static void secureFeature(
      DocumentBuilderFactory factory, String feature, boolean enabled) {
    try {
      factory.setFeature(feature, enabled);
    } catch (Exception ignored) {
    }
  }

  private static String inherited(Element rep, Element adaptation, String attr) {
    return rep.hasAttribute(attr) ? rep.getAttribute(attr) : adaptation.getAttribute(attr);
  }

  private static String name(Element element) {
    return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++)
      if (nodes.item(i) instanceof Element && name((Element) nodes.item(i)).equals(name))
        result.add((Element) nodes.item(i));
    return result;
  }

  private static Element first(Element parent, String name) {
    List<Element> result = children(parent, name);
    return result.isEmpty() ? null : result.get(0);
  }

  private static boolean hasChild(Element parent, String name) {
    return first(parent, name) != null;
  }

  private static Element nearest(Element[] hierarchy, String name) {
    Element result = null;
    for (Element owner : hierarchy) {
      Element candidate = first(owner, name);
      if (candidate != null) result = candidate;
    }
    return result;
  }

  private static String childBase(String base, Element parent) {
    Element value = first(parent, "BaseURL");
    return value == null ? base : resolve(base, value.getTextContent().trim());
  }

  private static double duration(String iso) {
    Matcher matcher =
        Pattern.compile(
                "P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?")
            .matcher(iso);
    if (!matcher.matches()) return 0;
    double total = 0;
    int[] factor = {86400, 3600, 60, 1};
    for (int i = 1; i <= 4; i++)
      if (matcher.group(i) != null) total += Double.parseDouble(matcher.group(i)) * factor[i - 1];
    return total;
  }

  private static final class SegmentData {
    String init = "", reason = "";
    final List<String> segments = new ArrayList<>();
  }
}
