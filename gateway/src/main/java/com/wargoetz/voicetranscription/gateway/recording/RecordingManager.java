package com.wargoetz.voicetranscription.gateway.recording;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.wargoetz.voicetranscription.gateway.audio.AudioDecoder;
import com.wargoetz.voicetranscription.gateway.audio.NoiseFilter;
import com.wargoetz.voicetranscription.gateway.config.TranscriptionSettings;
import com.wargoetz.voicetranscription.gateway.web.ApiException;
import com.wargoetz.voicetranscription.gateway.web.CallerIdentity;
import com.wargoetz.voicetranscription.gateway.whisper.LanguageDetector;
import com.wargoetz.voicetranscription.gateway.whisper.WhisperService;
import com.wargoetz.voicetranscription.gateway.whisper.WhisperService.Segment;
import com.wargoetz.voicetranscription.gateway.whisper.WhisperService.TranscriptionException;

/**
 * Tracks in-memory recordings and drives incremental Whisper passes over them.
 */
public final class RecordingManager {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Recordings");

    /** Hard cap on concurrently open recordings (each may hold up to maxRecordingSeconds of audio). */
    public static final int MAX_OPEN_RECORDINGS = 64;
    /** Don't bother re-decoding until at least this much new audio arrived (0.75 s). */
    static final int MIN_NEW_SAMPLES = 12_000;
    /** Commit all-but-the-last segment once the uncommitted window exceeds 12 s. */
    static final int COMMIT_WINDOW_SAMPLES = 16_000 * 12;
    /** How long the final pass may wait for a free decoder state. */
    static final long FINAL_WAIT_MS = 120_000;
    /**
     * Below this RMS (about -55 dBFS) audio is treated as silence and never sent to Whisper: Whisper
     * "hallucinates" on silence ("you you you", "Thank you."). Typical causes: a shared tab that isn't
     * playing, tab audio not enabled in the share picker, or a muted microphone.
     */
    static final double SILENCE_RMS = 0.0018;

    private final Map<String, RecordingSession> sessions = new ConcurrentHashMap<>();
    private final WhisperService whisper;
    private final Supplier<TranscriptionSettings> settings;

    /** {@code silent}: this chunk carried no audible signal (lets the client warn about a silent source). */
    public record ChunkResult(String partialTranscript, List<RecordingSession.Turn> turns, String track,
                              boolean transcribed, boolean duplicate, double durationSeconds,
                              boolean limitReached, long whisperMs, boolean silent) {
    }

    public record StopResult(String transcript, List<RecordingSession.Turn> turns, String language,
                             double durationSeconds) {
    }

    /** Track names a client may declare; a recording has at most one of each. */
    static final List<String> ALLOWED_TRACKS = List.of(RecordingSession.MICROPHONE, RecordingSession.SYSTEM);

    public RecordingManager(WhisperService whisper, Supplier<TranscriptionSettings> settings) {
        this.whisper = whisper;
        this.settings = settings;
    }

    public RecordingSession start(CallerIdentity identity, String requestedLanguage) throws ApiException {
        return start(identity, requestedLanguage, List.of());
    }

    /**
     * @param trackNames empty for a single-source recording; e.g. {@code ["microphone", "system"]} to keep
     *                   two sources apart so the transcript is labelled per source
     */
    public RecordingSession start(CallerIdentity identity, String requestedLanguage, List<String> trackNames)
        throws ApiException {
        return start(identity, requestedLanguage, trackNames, false);
    }

    /** @param timestamps prefix transcript lines with [mm:ss] (one line per segment) */
    public RecordingSession start(CallerIdentity identity, String requestedLanguage, List<String> trackNames,
                                  boolean timestamps) throws ApiException {
        if (sessions.size() >= MAX_OPEN_RECORDINGS) {
            throw new ApiException(503, "Too many open recordings; try again shortly");
        }
        if (!whisper.isReady()) {
            throw new ApiException(503, "Whisper is not ready: " + whisper.status());
        }
        for (String t : trackNames) {
            if (!ALLOWED_TRACKS.contains(t)) {
                throw new ApiException(400, "Unknown track '" + t + "'; allowed: " + ALLOWED_TRACKS);
            }
        }
        if (trackNames.stream().distinct().count() != trackNames.size()) {
            throw new ApiException(400, "Duplicate track names");
        }
        TranscriptionSettings.Whisper w = settings.get().whisper();
        String language = effectiveLanguage(requestedLanguage, w);
        RecordingSession s = new RecordingSession(UUID.randomUUID().toString(), identity, language,
            w.maxRecordingSeconds() * AudioDecoder.TARGET_RATE, trackNames, timestamps);
        sessions.put(s.id(), s);
        log.debugf("Recording %s started by %s (%s, language=%s, tracks=%s)", s.id(), identity.username(),
            identity.sessionType(), language, s.tracks.keySet());
        return s;
    }

    static String effectiveLanguage(String requested, TranscriptionSettings.Whisper w) {
        if (requested != null && requested.matches("[a-z]{2,3}|auto")) {
            return requested;
        }
        return w.autoDetect() ? "auto" : w.defaultLanguage();
    }

    public RecordingSession get(String id, CallerIdentity caller) throws ApiException {
        RecordingSession s = id == null ? null : sessions.get(id);
        // Same 404 for "missing" and "someone else's" so ids can't be probed.
        if (s == null || !s.identity().ownerKey().equals(caller.ownerKey())) {
            throw new ApiException(404, "Recording not found");
        }
        s.touch();
        return s;
    }

    public ChunkResult appendChunk(RecordingSession s, short[] pcm, long seq) throws ApiException {
        return appendChunk(s, null, pcm, seq);
    }

    /** @param trackName null/blank = the recording's first track */
    public ChunkResult appendChunk(RecordingSession s, String trackName, short[] pcm, long seq) throws ApiException {
        RecordingSession.Track t = s.track(trackName);
        if (t == null) {
            throw new ApiException(400, "Recording has no track '" + trackName + "'; tracks: " + s.tracks.keySet());
        }
        if (t.isFinalised()) {
            throw new ApiException(409, "Recording already stopped");
        }
        boolean silent = rms(pcm) < SILENCE_RMS;
        boolean accepted = t.append(pcm, seq, s.maxSamples);
        if (accepted) {
            t.floor.add(pcm);
        }
        if (!t.lock.tryLock()) {
            // A pass is already running for this track; the next chunk will pick up the new audio.
            return result(s, t, null, false, !accepted, 0, silent);
        }
        try {
            int end = t.length();
            if (end - t.lastTranscribedLength < MIN_NEW_SAMPLES) {
                return result(s, t, s.partialText(), false, !accepted, 0, silent);
            }
            TranscriptionSettings.Whisper w = settings.get().whisper();
            NoiseFilter.Level level = NoiseFilter.Level.fromId(w.noiseFilter());
            float[] window = window(t, end);
            boolean[] voiced = voiced(t, window, level);
            if (!NoiseFilter.hasSpeech(voiced, level)) {
                // Silence or background noise only since the last commit: don't let Whisper invent text.
                t.lastTranscribedLength = end;
                t.tentative = List.of();
                boundWindow(t, end);
                return result(s, t, s.partialText(), false, !accepted, 0, silent);
            }
            int lead = NoiseFilter.leadingSilence(voiced);
            float[] speech = lead == 0 ? window : java.util.Arrays.copyOfRange(window, lead, window.length);
            Optional<WhisperService.Result> r = whisper.transcribe(speech, s.language(), w.threads(), 0);
            if (r.isEmpty()) {
                // All decoder states busy (other operators talking). Skip this partial; never block uploads.
                return result(s, t, s.partialText(), false, !accepted, 0, silent);
            }
            applyPartial(t, filter(r.get().segments(), voiced, lead, level), end, t.committedOffset + lead);
            return result(s, t, s.partialText(), true, !accepted, r.get().elapsedMs(), silent);
        } catch (TranscriptionException e) {
            throw new ApiException(500, e.getMessage());
        } finally {
            t.lock.unlock();
        }
    }

    private static ChunkResult result(RecordingSession s, RecordingSession.Track t, String partial,
                                      boolean transcribed, boolean duplicate, long whisperMs, boolean silent) {
        return new ChunkResult(partial, partial == null ? null : s.currentTurns(), t.name, transcribed, duplicate,
            s.durationSeconds(), s.limitReached(), whisperMs, silent);
    }

    /** Final pass over every track, merge, release the audio. Idempotent. */
    public StopResult stop(RecordingSession s) throws ApiException {
        List<RecordingSession.Track> tracks = List.copyOf(s.tracks.values());
        tracks.forEach(t -> t.lock.lock()); // declaration order on every path -> no lock-order inversion
        try {
            if (s.finalTranscript == null) {
                TranscriptionSettings.Whisper w = settings.get().whisper();
                NoiseFilter.Level level = NoiseFilter.Level.fromId(w.noiseFilter());
                List<RecordingSession.TimedText> all = new java.util.ArrayList<>();
                for (RecordingSession.Track t : tracks) {
                    all.addAll(t.committed);
                    int end = t.length();
                    float[] window = end > t.committedOffset ? window(t, end) : new float[0];
                    boolean[] voiced = voiced(t, window, level);
                    if (window.length > 0 && NoiseFilter.hasSpeech(voiced, level)) {
                        int lead = NoiseFilter.leadingSilence(voiced);
                        float[] speech = lead == 0 ? window : java.util.Arrays.copyOfRange(window, lead, window.length);
                        WhisperService.Result r = whisper.transcribe(speech, s.language(), w.threads(), FINAL_WAIT_MS)
                            .orElseThrow(() -> new ApiException(503, "Transcription capacity exhausted; retry"));
                        all.addAll(t.timed(filter(r.segments(), voiced, lead, level), t.committedOffset + lead));
                    }
                }
                s.finalTurns = s.turns(all);
                s.finalTranscript = s.render(s.finalTurns);
                String plain = String.join(" ", s.finalTurns.stream().map(RecordingSession.Turn::text).toList());
                s.finalLanguage = "auto".equals(s.language())
                    ? LanguageDetector.detect(plain, w.defaultLanguage())
                    : s.language();
                s.releaseAudio();
            }
            return new StopResult(s.finalTranscript, s.finalTurns, s.finalLanguage, s.durationSeconds());
        } catch (TranscriptionException e) {
            throw new ApiException(500, e.getMessage());
        } finally {
            tracks.forEach(t -> t.lock.unlock());
        }
    }

    public void remove(String id) {
        RecordingSession s = sessions.remove(id);
        if (s != null) {
            s.releaseAudio();
        }
    }

    /** Drops recordings nobody has touched for {@code idleMs}. */
    public int sweep(long idleMs) {
        long cutoff = System.currentTimeMillis() - idleMs;
        int removed = 0;
        for (RecordingSession s : List.copyOf(sessions.values())) {
            if (s.lastActivity() < cutoff) {
                remove(s.id());
                removed++;
            }
        }
        if (removed > 0) {
            log.infof("Discarded %d abandoned recording(s)", removed);
        }
        return removed;
    }

    public int openCount() {
        return sessions.size();
    }

    public void clear() {
        List.copyOf(sessions.keySet()).forEach(this::remove);
    }

    static double rms(short[] pcm) {
        if (pcm.length == 0) {
            return 0;
        }
        double sum = 0;
        for (short v : pcm) {
            double f = v / 32768.0;
            sum += f * f;
        }
        return Math.sqrt(sum / pcm.length);
    }

    static double rms(float[] samples) {
        if (samples.length == 0) {
            return 0;
        }
        double sum = 0;
        for (float f : samples) {
            sum += (double) f * f;
        }
        return Math.sqrt(sum / samples.length);
    }

    private static float[] window(RecordingSession.Track t, int end) {
        return AudioDecoder.toFloat(t.snapshot(t.committedOffset, end), 0, end - t.committedOffset);
    }


    private static boolean[] voiced(RecordingSession.Track t, float[] window, NoiseFilter.Level level) {
        double[] frames = NoiseFilter.frameRms(window);
        return NoiseFilter.voiced(frames, t.floor.floor(frames), level);
    }

    /**
     * Drops segments that sit on noise rather than voice, known noise phrases, and non-speech tags.
     *
     * @param lead samples trimmed from the window start; segment times are relative to the trimmed audio
     */
    static List<Segment> filter(List<Segment> segments, boolean[] voiced, int lead, NoiseFilter.Level level) {
        List<Segment> kept = new java.util.ArrayList<>(segments.size());
        for (Segment seg : segments) {
            String text = NoiseFilter.clean(seg.text());
            long from = lead + seg.startCs() * WhisperService.SAMPLES_PER_CENTISECOND;
            long to = lead + seg.endCs() * WhisperService.SAMPLES_PER_CENTISECOND;
            if (NoiseFilter.keepSegment(text, NoiseFilter.voicedRatio(voiced, from, to), level)) {
                kept.add(new Segment(seg.startCs(), seg.endCs(), " " + text));
            }
        }
        return kept;
    }

    /** A long stretch with no speech is never transcribed again: keep only its last 2 s as context. */
    private static void boundWindow(RecordingSession.Track t, int end) {
        if (end - t.committedOffset > COMMIT_WINDOW_SAMPLES) {
            t.committedOffset = Math.max(t.committedOffset, end - 2 * 16_000);
        }
    }

    static void applyPartial(RecordingSession.Track t, List<Segment> segments, int end) {
        applyPartial(t, segments, end, t.committedOffset);
    }

    /**
     * Commit-window bookkeeping for one track; see {@link RecordingSession}. Caller holds the track lock.
     *
     * @param windowStart sample index the segments' timestamps are relative to (committedOffset + trimmed lead)
     */
    static void applyPartial(RecordingSession.Track t, List<Segment> segments, int end, int windowStart) {
        List<RecordingSession.TimedText> timed = t.timed(segments, windowStart);
        int windowSamples = end - t.committedOffset;
        if (windowSamples > COMMIT_WINDOW_SAMPLES && segments.size() > 1) {
            t.committed.addAll(timed.subList(0, timed.size() - 1));
            long commitEnd = segments.get(segments.size() - 2).endCs() * WhisperService.SAMPLES_PER_CENTISECOND;
            t.committedOffset = (int) Math.min(end, windowStart + commitEnd);
            t.tentative = List.of(timed.get(timed.size() - 1));
        } else if (windowSamples > COMMIT_WINDOW_SAMPLES) {
            // Long window but at most one real segment (the rest was noise): keep the window bounded.
            t.tentative = timed;
            int keepFrom = segments.isEmpty() ? end - 2 * 16_000
                : (int) (windowStart + segments.get(0).startCs() * WhisperService.SAMPLES_PER_CENTISECOND) - 3200;
            t.committedOffset = Math.max(t.committedOffset, Math.min(end, keepFrom));
        } else {
            t.tentative = timed;
        }
        t.lastTranscribedLength = end;
    }
}
