package app.vidloader;

import java.util.*;

/** Presentation projection; signed URLs and transfer candidates are never rewritten. */
public final class MediaSelection {
  private MediaSelection() {}

  private static int durationGroup(long duration,List<Long> groups) {
    if(duration > 0) for(int i=0;i<groups.size();i++)
      if(Math.abs(duration-groups.get(i)) <= Math.max(2000,groups.get(i)/50)) return i;
    return groups.size();
  }

  public static List<MediaCandidate> choices(Collection<MediaCandidate> candidates, boolean images) {
    // Keep distinct-duration videos (for example a preroll and the feature) separate
    // before quality deduplication. A short high-resolution ad must not replace the feature.
    ArrayList<Long> durations = new ArrayList<>();
    if (!images) {
      ArrayList<MediaCandidate> timed = new ArrayList<>(candidates);
      timed.sort((a,b) -> Long.compare(b.durationMs,a.durationMs));
      for (MediaCandidate c : timed)
        if (c.durationMs > 0 && !c.mime.startsWith("audio/") && !c.mime.startsWith("image/")
            && durationGroup(c.durationMs,durations) == durations.size()) durations.add(c.durationMs);
    }
    boolean separate = durations.size() > 1 || (!durations.isEmpty() && durations.get(0) >= 120000);
    Set<String> components = new HashSet<>();
    Set<String> variants = new HashSet<>();
    boolean labelledVideo = false;
    boolean adaptiveVideo = false;
    for (MediaCandidate c : candidates) {
      if (c.kind != MediaCandidate.Kind.DIRECT && c.isDownloadable() && !c.mime.startsWith("audio/")) adaptiveVideo = true;
      components.addAll(c.segments);
      components.addAll(c.audioSegments);
      if (!c.initUrl.isEmpty()) components.add(c.initUrl);
      if (!c.audioInitUrl.isEmpty()) components.add(c.audioInitUrl);
      if (!c.audioUrl.isEmpty()) components.add(c.audioUrl);
      if (c.kind == MediaCandidate.Kind.HLS_MEDIA && c.height > 0) variants.add(c.url);
      if (c.isDownloadable() && !c.mime.startsWith("audio/") && !c.mime.startsWith("image/") && c.height > 0)
        labelledVideo = true;
    }
    LinkedHashMap<String, MediaCandidate> unique = new LinkedHashMap<>();
    for (MediaCandidate c : candidates) {
      if (c.mime.startsWith("image/") != images || components.contains(c.url)) continue;
      // A numbered HLS fragment is not a complete video, even if its parent playlist was missed.
      if (!images && c.kind == MediaCandidate.Kind.DIRECT && c.mime.equals("video/mp2t")
          && c.url.matches("(?i).*/(?:seg(?:ment)?[-_]?\\d+[^/?]*|chunk[-_]?\\d+[^/?]*)\\.ts(?:[?].*)?")) continue;
      if (!images && adaptiveVideo && c.kind == MediaCandidate.Kind.DIRECT
          && (c.mime.equals("video/mp2t") || c.mime.equals("video/iso.segment"))) continue;
      if (!images && c.height == 0 && !c.mime.startsWith("audio/")
          && (labelledVideo || variants.contains(c.url))) continue;
      // The original manual drawer represents a video's resolutions, not transports.
      String key = !images && c.height > 0 && !c.mime.startsWith("audio/")
          ? "quality|" + (separate ? durationGroup(c.durationMs,durations) : 0) + "|" + c.qualityPixels()
          : c.url + "|" + c.height + "|" + c.mime.startsWith("audio/");
      MediaCandidate old = unique.get(key);
      if (old == null || (!old.isDownloadable() && c.isDownloadable())
          || (old.kind != MediaCandidate.Kind.DIRECT && c.kind == MediaCandidate.Kind.DIRECT && c.isDownloadable())
          || (old.kind == c.kind && old.manifestUrl.isEmpty() && !c.manifestUrl.isEmpty())) unique.put(key, c);
    }
    ArrayList<MediaCandidate> result = new ArrayList<>(unique.values());
    result.sort((a, b) -> {
      int group = separate ? Integer.compare(durationGroup(a.durationMs,durations),durationGroup(b.durationMs,durations)) : 0;
      return group != 0 ? group : Integer.compare(b.qualityPixels(), a.qualityPixels());
    });
    return result;
  }
}
