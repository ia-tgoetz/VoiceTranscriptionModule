package com.wargoetz.voicetranscription.gateway.audio;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps background noise from turning into words.
 *
 * <p>Whisper always produces <em>something</em>: given fan rush, mains hum, or keyboard clicks it writes
 * "Thank you.", "you you", "Thanks for watching." (measured on ggml-tiny; larger models do it less, never
 * zero). Three defences, all cheap and local:</p>
 * <ol>
 *   <li><b>Energy voice-activity detection against an adaptive noise floor.</b> 20 ms frames; the floor is a
 *   low percentile of the source's recent frame levels ({@link FloorTracker}), so a steady fan or hum <em>is</em>
 *   the floor. A frame is voiced only if it is {@code marginDb} above the floor (and above absolute silence),
 *   in a run of at least {@code minRunFrames} (clicks are shorter than a syllable).</li>
 *   <li><b>Window gate.</b> A window with less than {@code minVoicedMs} of voiced audio is never sent to Whisper.</li>
 *   <li><b>Segment filter.</b> Every Whisper segment is checked against the VAD over its own time span; segments
 *   over noise are dropped, as are known noise phrases ("thank you", "you") unless they were clearly voiced, and
 *   non-speech tags ({@code [BLANK_AUDIO]}, {@code (music)}, {@code *coughs*}, {@code ♪}).</li>
 * </ol>
 * This is not a source separator: speech <em>over</em> loud noise still needs a decent signal-to-noise ratio
 * (browser noise suppression on the microphone, or a headset).
 */
public final class NoiseFilter {
    public static final int FRAME = 320;            // 20 ms at 16 kHz
    public static final double SILENCE_RMS = 0.0018; // about -55 dBFS

    public enum Level {
        OFF("off", 0, 1, 0, 0, false),
        STANDARD("standard", 8.0, 3, 250, 0.12, false),
        STRICT("strict", 12.0, 4, 400, 0.25, true);

        final String id;
        final double marginDb;
        final int minRunFrames;
        final int minVoicedMs;
        final double minSegmentVoicedRatio;
        final boolean dropPhrasesAlways;

        Level(String id, double marginDb, int minRunFrames, int minVoicedMs, double minSegmentVoicedRatio,
              boolean dropPhrasesAlways) {
            this.id = id;
            this.marginDb = marginDb;
            this.minRunFrames = minRunFrames;
            this.minVoicedMs = minVoicedMs;
            this.minSegmentVoicedRatio = minSegmentVoicedRatio;
            this.dropPhrasesAlways = dropPhrasesAlways;
        }

        public String id() {
            return id;
        }

        public static Level fromId(String id) {
            for (Level l : values()) {
                if (l.id.equalsIgnoreCase(id == null ? "" : id.trim())) {
                    return l;
                }
            }
            return STANDARD;
        }
    }

    /** Phrases Whisper emits on non-speech audio (lower-case, punctuation stripped). */
    private static final Set<String> NOISE_PHRASES = Set.of(
        "you", "you you", "you you you", "thank you", "thank you very much", "thanks", "thanks for watching",
        "thank you for watching", "thanks for listening", "thank you for listening", "please subscribe",
        "subscribe", "bye", "bye bye", "so", "uh", "um", "hmm", "mm", "oh", "okay", "ok", "the end", "silence");

    private static final Pattern NON_SPEECH_TAG = Pattern.compile("\\[[^\\]]*]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫]+");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}' ]");

    private NoiseFilter() {
    }

    /** RMS of each 20 ms frame. */
    public static double[] frameRms(float[] samples) {
        int n = samples.length / FRAME;
        double[] out = new double[n];
        for (int f = 0; f < n; f++) {
            double sum = 0;
            for (int i = f * FRAME, end = i + FRAME; i < end; i++) {
                sum += (double) samples[i] * samples[i];
            }
            out[f] = Math.sqrt(sum / FRAME);
        }
        return out;
    }

    /** Voiced-frame mask: above floor + margin (and absolute silence), in runs of at least minRunFrames. */
    public static boolean[] voiced(double[] rms, double floor, Level level) {
        boolean[] v = new boolean[rms.length];
        if (level == Level.OFF) {
            for (int i = 0; i < rms.length; i++) {
                v[i] = rms[i] >= SILENCE_RMS;
            }
            return v;
        }
        double threshold = Math.max(SILENCE_RMS, floor * Math.pow(10, level.marginDb / 20.0));
        int i = 0;
        while (i < rms.length) {
            if (rms[i] < threshold) {
                i++;
                continue;
            }
            int j = i;
            while (j < rms.length && rms[j] >= threshold) {
                j++;
            }
            if (j - i >= level.minRunFrames) {
                Arrays.fill(v, i, j, true);
            }
            i = j;
        }
        return v;
    }

    public static int voicedMs(boolean[] v) {
        int n = 0;
        for (boolean b : v) {
            if (b) {
                n++;
            }
        }
        return n * 20;
    }

    /** True when the window has enough voice to be worth transcribing. */
    public static boolean hasSpeech(boolean[] v, Level level) {
        return voicedMs(v) >= Math.max(level.minVoicedMs, 20);
    }

    /** Index (in samples) of the first voiced frame minus a 0.2 s pre-roll; 0 when none. */
    public static int leadingSilence(boolean[] v) {
        for (int f = 0; f < v.length; f++) {
            if (v[f]) {
                return Math.max(0, f * FRAME - 3200);
            }
        }
        return 0;
    }

    /** Fraction of voiced frames within [startSample, endSample), padded by 0.3 s for timestamp slop. */
    public static double voicedRatio(boolean[] v, long startSample, long endSample) {
        long pad = 4800;
        int from = (int) Math.max(0, (startSample - pad) / FRAME);
        int to = (int) Math.min(v.length, (endSample + pad + FRAME - 1) / FRAME);
        if (to <= from) {
            return 0;
        }
        int n = 0;
        for (int f = from; f < to; f++) {
            if (v[f]) {
                n++;
            }
        }
        return (double) n / (to - from);
    }

    /** Removes non-speech annotations; returns "" when nothing speech-like remains. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String t = NON_SPEECH_TAG.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return NOT_WORD.matcher(t).replaceAll("").isBlank() ? "" : t;
    }

    public static boolean isNoisePhrase(String text) {
        String norm = NOT_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return norm.isEmpty() || NOISE_PHRASES.contains(norm);
    }

    /**
     * Decides whether a Whisper segment is real speech.
     *
     * @param voicedRatio fraction of voiced frames under the segment (see {@link #voicedRatio})
     */
    public static boolean keepSegment(String cleanedText, double voicedRatio, Level level) {
        if (cleanedText.isEmpty()) {
            return false;
        }
        if (level == Level.OFF) {
            return true;
        }
        if (voicedRatio < level.minSegmentVoicedRatio) {
            return false;
        }
        if (isNoisePhrase(cleanedText)) {
            return !level.dropPhrasesAlways && voicedRatio >= 0.5;
        }
        return true;
    }

    /**
     * Rolling noise-floor estimate for one audio source: the 10th percentile of the last ~60 s of 20 ms frame
     * levels. A steady background (fan, hum) becomes the floor; speech pauses keep it honest while talking.
     */
    public static final class FloorTracker {
        private final double[] ring = new double[3000];
        private int size;
        private int pos;

        public synchronized void add(short[] pcm) {
            for (int f = 0; f + FRAME <= pcm.length; f += FRAME) {
                double sum = 0;
                for (int i = f; i < f + FRAME; i++) {
                    double s = pcm[i] / 32768.0;
                    sum += s * s;
                }
                ring[pos] = Math.sqrt(sum / FRAME);
                pos = (pos + 1) % ring.length;
                size = Math.min(size + 1, ring.length);
            }
        }

        /** Floor from history, or from {@code window} when there's under 1 s of history. */
        public synchronized double floor(double[] window) {
            double[] src = size >= 50 ? Arrays.copyOf(ring, size) : window.clone();
            if (src.length == 0) {
                return 0;
            }
            Arrays.sort(src);
            return src[(int) (src.length * 0.10)];
        }
    }
}
