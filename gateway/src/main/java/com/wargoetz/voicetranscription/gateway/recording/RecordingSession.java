package com.wargoetz.voicetranscription.gateway.recording;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.wargoetz.voicetranscription.gateway.web.CallerIdentity;
import com.wargoetz.voicetranscription.gateway.whisper.WhisperService.Segment;

/**
 * One in-progress recording, made of one or more audio <b>tracks</b>. A microphone-only or tab-only
 * recording has a single track; a "mixed" recording keeps the microphone and the system/tab audio as two
 * separate tracks so each is transcribed on its own and the transcript can say who said what:
 * <pre>
 * [Microphone] Checking pump two, it's running hot.
 * [System audio] The quick brown fox jumps over the lazy dog.
 * </pre>
 * With {@code timestamps} on, every Whisper segment gets its own line, prefixed with its offset from the
 * start of the recording: {@code [00:05] [Microphone] Checking pump two.}
 * Audio is held in memory only (16 kHz mono PCM16, 32 KB/s per track) and released as soon as the final
 * transcript is produced - raw audio is never written to disk or MongoDB.
 *
 * <p>Streaming transcription uses a per-track commit window: once a track's not-yet-committed audio exceeds
 * {@link RecordingManager#COMMIT_WINDOW_SAMPLES}, every Whisper segment except the last is committed (with
 * its absolute time) and the window start advances past it. Segments from all tracks are merged by start
 * time to build the transcript.</p>
 */
public final class RecordingSession {
    /** Track name used when the client doesn't declare tracks (single-source recording). */
    public static final String DEFAULT_TRACK = "default";
    public static final String MICROPHONE = "microphone";
    public static final String SYSTEM = "system";
    private static final double RATE = 16_000.0;

    final String id;
    final CallerIdentity identity;
    final String language;
    final Instant startedAt;
    final int maxSamples;
    final Map<String, Track> tracks;
    /** Prefix each line with [mm:ss] and put every segment on its own line. */
    final boolean timestamps;
    /** Added to every timestamp (a recording appended to an existing log keeps the log's clock). */
    volatile double timeOffset;
    private volatile long lastActivity = System.currentTimeMillis();

    // ---- guarded by all track locks (see RecordingManager.stop) ----
    String finalTranscript;
    String finalLanguage;
    List<Turn> finalTurns;

    /** A piece of transcribed text with its absolute position in the recording (seconds). */
    record TimedText(String track, double start, double end, String text) {
    }

    /** Consecutive text from one source; {@code start} = seconds from the start of the recording. */
    public record Turn(String source, String label, String text, double start) {
    }

    /** One audio source within the recording. */
    static final class Track {
        final String name;
        /** Serialises Whisper passes for this track; chunk uploads only {@code tryLock} it. */
        final ReentrantLock lock = new ReentrantLock();
        /** Background-noise level of this source (see NoiseFilter). */
        final com.wargoetz.voicetranscription.gateway.audio.NoiseFilter.FloorTracker floor =
            new com.wargoetz.voicetranscription.gateway.audio.NoiseFilter.FloorTracker();

        // ---- guarded by "this" ----
        private short[] audio = new short[16_000 * 10];
        private int length;
        private long lastSeq = -1;
        private boolean limitReached;

        // ---- guarded by lock ----
        final List<TimedText> committed = new ArrayList<>();
        List<TimedText> tentative = List.of();
        int committedOffset;
        int lastTranscribedLength;

        Track(String name) {
            this.name = name;
        }

        synchronized boolean append(short[] pcm, long seq, int maxSamples) {
            if (seq >= 0 && seq <= lastSeq) {
                return false;
            }
            if (seq >= 0) {
                lastSeq = seq;
            }
            if (audio == null) {
                return false; // already finalised
            }
            int n = Math.min(maxSamples - length, pcm.length);
            if (n < pcm.length) {
                limitReached = true;
            }
            if (length + n > audio.length) {
                audio = Arrays.copyOf(audio, Math.max(length + n, audio.length * 2));
            }
            System.arraycopy(pcm, 0, audio, length, n);
            length += n;
            return true;
        }

        synchronized int length() {
            return length;
        }

        synchronized boolean limitReached() {
            return limitReached;
        }

        synchronized short[] snapshot(int from, int to) {
            return audio == null ? new short[0] : Arrays.copyOfRange(audio, from, to);
        }

        synchronized void releaseAudio() {
            audio = null;
        }

        synchronized boolean isFinalised() {
            return audio == null;
        }

        /** Converts Whisper segments (centiseconds, relative to the window start) to absolute timed text. */
        List<TimedText> timed(List<Segment> segments, int windowStart) {
            List<TimedText> out = new ArrayList<>(segments.size());
            for (Segment seg : segments) {
                double start = (windowStart + seg.startCs() * 160.0) / RATE;
                double end = (windowStart + seg.endCs() * 160.0) / RATE;
                out.add(new TimedText(name, start, end, seg.text()));
            }
            return out;
        }
    }

    RecordingSession(String id, CallerIdentity identity, String language, int maxSamples, List<String> trackNames) {
        this(id, identity, language, maxSamples, trackNames, false);
    }

    RecordingSession(String id, CallerIdentity identity, String language, int maxSamples, List<String> trackNames,
                     boolean timestamps) {
        this.id = id;
        this.timestamps = timestamps;
        this.identity = identity;
        this.language = language;
        this.maxSamples = maxSamples;
        this.startedAt = Instant.now();
        Map<String, Track> t = new LinkedHashMap<>();
        for (String name : trackNames.isEmpty() ? List.of(DEFAULT_TRACK) : trackNames) {
            t.put(name, new Track(name));
        }
        this.tracks = java.util.Collections.unmodifiableMap(t);
    }

    RecordingSession(String id, CallerIdentity identity, String language, int maxSamples) {
        this(id, identity, language, maxSamples, List.of());
    }

    public String id() {
        return id;
    }

    public CallerIdentity identity() {
        return identity;
    }

    public String language() {
        return language;
    }

    /** Clamped to [0, 24 h]. */
    public void setTimeOffset(double seconds) {
        timeOffset = Double.isFinite(seconds) ? Math.max(0, Math.min(86_400, seconds)) : 0;
    }

    public List<String> trackNames() {
        return List.copyOf(tracks.keySet());
    }

    /** True when the transcript should name each source (more than one track). */
    public boolean labelled() {
        return tracks.size() > 1;
    }

    Track track(String name) {
        return name == null || name.isBlank() ? tracks.values().iterator().next() : tracks.get(name);
    }

    /** Appends to the default (first) track - convenience for single-track callers and tests. */
    boolean append(short[] pcm, long seq) {
        return track(null).append(pcm, seq, maxSamples);
    }

    int length() {
        return track(null).length();
    }

    boolean limitReached() {
        return tracks.values().stream().anyMatch(Track::limitReached);
    }

    boolean isFinalised() {
        return tracks.values().stream().allMatch(Track::isFinalised);
    }

    void releaseAudio() {
        tracks.values().forEach(Track::releaseAudio);
    }

    public double durationSeconds() {
        return tracks.values().stream().mapToInt(Track::length).max().orElse(0) / RATE;
    }

    long lastActivity() {
        return lastActivity;
    }

    void touch() {
        lastActivity = System.currentTimeMillis();
    }

    /** Current transcript: committed + tentative text of every track, merged by time. */
    String partialText() {
        if (finalTranscript != null) {
            return finalTranscript;
        }
        return render(currentTurns());
    }

    List<Turn> currentTurns() {
        if (finalTurns != null) {
            return finalTurns;
        }
        List<TimedText> all = new ArrayList<>();
        for (Track t : tracks.values()) {
            all.addAll(t.committed);
            all.addAll(t.tentative);
        }
        return turns(all);
    }

    /**
     * Groups time-ordered text into turns: consecutive pieces from the same source become one turn. With
     * timestamps on, every piece is its own turn so each line carries the time it was said.
     */
    List<Turn> turns(Collection<TimedText> pieces) {
        List<String> order = new ArrayList<>(tracks.keySet());
        List<TimedText> sorted = new ArrayList<>(pieces);
        sorted.sort(Comparator.comparingDouble(TimedText::start)
            .thenComparingInt(p -> order.indexOf(p.track())));
        List<Turn> turns = new ArrayList<>();
        String current = null;
        double currentStart = 0;
        StringBuilder text = new StringBuilder();
        for (TimedText p : sorted) {
            String piece = p.text() == null ? "" : p.text().trim();
            if (piece.isEmpty()) {
                continue;
            }
            if (!p.track().equals(current) || timestamps) {
                if (current != null) {
                    turns.add(new Turn(current, label(current), text.toString(), currentStart));
                }
                current = p.track();
                currentStart = p.start() + timeOffset;
                text.setLength(0);
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(piece);
        }
        if (current != null) {
            turns.add(new Turn(current, label(current), text.toString(), currentStart));
        }
        return turns;
    }

    /**
     * Labelled recordings: one "[Source] text" line per turn. Single-source recordings: plain text.
     * Timestamps: "[mm:ss] " in front of every line (and one line per segment).
     */
    String render(List<Turn> turns) {
        if (!labelled() && !timestamps) {
            return String.join(" ", turns.stream().map(Turn::text).toList()).trim();
        }
        return String.join("\n", turns.stream().map(t ->
            (timestamps ? "[" + clock(t.start()) + "] " : "") + (labelled() ? "[" + t.label() + "] " : "") + t.text())
            .toList());
    }

    /** mm:ss, or h:mm:ss past an hour. */
    static String clock(double seconds) {
        long s = Math.max(0, Math.round(Math.floor(seconds)));
        return s >= 3600
            ? String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
            : String.format("%02d:%02d", s / 60, s % 60);
    }

    public static String label(String track) {
        return switch (track) {
            case MICROPHONE -> "Microphone";
            case SYSTEM -> "System audio";
            default -> track;
        };
    }

    static String join(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (left.isEmpty()) {
            return right;
        }
        return right.isEmpty() ? left : left + " " + right;
    }
}
