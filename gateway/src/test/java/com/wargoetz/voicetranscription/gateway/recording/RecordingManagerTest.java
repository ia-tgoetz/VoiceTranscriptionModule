package com.wargoetz.voicetranscription.gateway.recording;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.wargoetz.voicetranscription.gateway.config.TranscriptionSettings;
import com.wargoetz.voicetranscription.gateway.web.CallerIdentity;
import com.wargoetz.voicetranscription.gateway.whisper.WhisperService.Segment;
import org.junit.jupiter.api.Test;

class RecordingManagerTest {
    private static final CallerIdentity WHO =
        new CallerIdentity("jdoe", List.of("Operator"), "10.0.0.5", "UA", CallerIdentity.TYPE_WEB, "perspective:x");

    private static RecordingSession single() {
        return new RecordingSession("r1", WHO, "en", 16_000 * 60);
    }

    private static RecordingSession mixed() {
        return new RecordingSession("r2", WHO, "en", 16_000 * 60,
            List.of(RecordingSession.MICROPHONE, RecordingSession.SYSTEM));
    }

    @Test
    void shortWindowKeepsEverythingTentative() {
        RecordingSession s = single();
        RecordingSession.Track t = s.track(null);
        s.append(new short[16_000 * 5], 0);
        RecordingManager.applyPartial(t, List.of(new Segment(0, 200, " Hello"), new Segment(200, 400, " world")),
            t.length());
        assertEquals("Hello world", s.partialText());
        assertEquals(0, t.committedOffset);
        assertTrue(t.committed.isEmpty());
    }

    @Test
    void longWindowCommitsAllButLastSegment() {
        RecordingSession s = single();
        RecordingSession.Track t = s.track(null);
        s.append(new short[16_000 * 20], 0);
        RecordingManager.applyPartial(t, List.of(
            new Segment(0, 500, " First part."),
            new Segment(500, 1200, " Second part."),
            new Segment(1200, 2000, " Still talking")), t.length());
        assertEquals(2, t.committed.size());
        assertEquals(1200 * 160, t.committedOffset);
        assertEquals("First part. Second part. Still talking", s.partialText());

        // Next pass only sees audio after the committed offset; its segments are relative to that offset.
        s.append(new short[16_000 * 15], 1);
        RecordingManager.applyPartial(t, List.of(
            new Segment(0, 900, " Still talking about the pump."),
            new Segment(900, 1700, " Done")), t.length());
        assertEquals("First part. Second part. Still talking about the pump. Done", s.partialText());
        assertEquals(1200 * 160 + 900 * 160, t.committedOffset);
        // Absolute time of the third committed piece = 12 s offset + 0 s.
        assertEquals(12.0, t.committed.get(2).start(), 1e-9);
    }

    @Test
    void singleTrackIsUnlabelled() {
        RecordingSession s = single();
        assertFalse(s.labelled());
        assertEquals(List.of(RecordingSession.DEFAULT_TRACK), s.trackNames());
    }

    @Test
    void mixedTracksAreMergedByTimeAndLabelled() {
        RecordingSession s = mixed();
        assertTrue(s.labelled());
        RecordingSession.Track mic = s.track(RecordingSession.MICROPHONE);
        RecordingSession.Track sys = s.track(RecordingSession.SYSTEM);
        mic.append(new short[16_000 * 10], 0, s.maxSamples);
        sys.append(new short[16_000 * 10], 0, s.maxSamples);
        // System audio plays 0-3 s, the operator talks 3.5-6 s, the video resumes 6.5-8 s.
        RecordingManager.applyPartial(sys, List.of(
            new Segment(0, 300, " The quick brown fox"), new Segment(650, 800, " jumps over the dog.")), sys.length());
        RecordingManager.applyPartial(mic, List.of(
            new Segment(350, 450, " Checking pump two,"), new Segment(450, 600, " it is running hot.")), mic.length());

        assertEquals("[System audio] The quick brown fox\n"
                + "[Microphone] Checking pump two, it is running hot.\n"
                + "[System audio] jumps over the dog.",
            s.partialText());
        List<RecordingSession.Turn> turns = s.currentTurns();
        assertEquals(List.of("system", "microphone", "system"), turns.stream().map(RecordingSession.Turn::source).toList());
        assertEquals("Microphone", turns.get(1).label());
    }

    @Test
    void timestampsPutEachSegmentOnItsOwnLine() {
        RecordingSession s = new RecordingSession("r3", WHO, "en", 16_000 * 600,
            List.of(RecordingSession.MICROPHONE, RecordingSession.SYSTEM), true);
        RecordingSession.Track mic = s.track(RecordingSession.MICROPHONE);
        RecordingSession.Track sys = s.track(RecordingSession.SYSTEM);
        mic.append(new short[16_000 * 10], 0, s.maxSamples);
        sys.append(new short[16_000 * 10], 0, s.maxSamples);
        RecordingManager.applyPartial(sys, List.of(new Segment(0, 300, " The quick brown fox.")), sys.length());
        RecordingManager.applyPartial(mic, List.of(
            new Segment(520, 600, " Checking pump two."), new Segment(700, 900, " It is running hot.")), mic.length());
        assertEquals("[00:00] [System audio] The quick brown fox.\n"
                + "[00:05] [Microphone] Checking pump two.\n"
                + "[00:07] [Microphone] It is running hot.",
            s.partialText());
        assertEquals(5.2, s.currentTurns().get(1).start(), 1e-9);
    }

    @Test
    void timestampsOnSingleSource() {
        RecordingSession s = new RecordingSession("r4", WHO, "en", 16_000 * 60, List.of(), true);
        RecordingSession.Track t = s.track(null);
        s.append(new short[16_000 * 5], 0);
        RecordingManager.applyPartial(t, List.of(new Segment(0, 200, " Hello."), new Segment(250, 400, " World.")),
            t.length());
        assertEquals("[00:00] Hello.\n[00:02] World.", s.partialText());
        assertEquals("1:01:05", RecordingSession.clock(3665.9));
    }

    @Test
    void silentTrackContributesNothing() {
        RecordingSession s = mixed();
        RecordingSession.Track sys = s.track(RecordingSession.SYSTEM);
        sys.append(new short[16_000 * 4], 0, s.maxSamples);
        RecordingManager.applyPartial(sys, List.of(new Segment(0, 300, " Only the video.")), sys.length());
        assertEquals("[System audio] Only the video.", s.partialText());
    }

    @Test
    void duplicateSequenceNumbersArePerTrack() {
        RecordingSession s = mixed();
        RecordingSession.Track mic = s.track(RecordingSession.MICROPHONE);
        RecordingSession.Track sys = s.track(RecordingSession.SYSTEM);
        assertTrue(mic.append(new short[100], 0, s.maxSamples));
        assertTrue(sys.append(new short[100], 0, s.maxSamples));  // same seq, other track: accepted
        assertFalse(mic.append(new short[100], 0, s.maxSamples));
        assertTrue(mic.append(new short[100], 1, s.maxSamples));
        assertEquals(200, mic.length());
        assertEquals(100, sys.length());
    }

    @Test
    void maxLengthIsEnforced() {
        RecordingSession s = new RecordingSession("r1", WHO, "en", 1000);
        s.append(new short[800], 0);
        s.append(new short[800], 1);
        assertEquals(1000, s.length());
        assertTrue(s.limitReached());
    }

    @Test
    void silenceGate() {
        assertTrue(RecordingManager.rms(new short[16_000]) < RecordingManager.SILENCE_RMS);
        short[] hiss = new short[16_000];
        for (int i = 0; i < hiss.length; i++) {
            hiss[i] = (short) ((i % 2 == 0 ? 1 : -1) * 20); // about -64 dBFS: still "silent"
        }
        assertTrue(RecordingManager.rms(hiss) < RecordingManager.SILENCE_RMS);
        short[] speech = new short[16_000];
        for (int i = 0; i < speech.length; i++) {
            speech[i] = (short) (Math.sin(i / 10.0) * 3000); // about -24 dBFS
        }
        assertFalse(RecordingManager.rms(speech) < RecordingManager.SILENCE_RMS);
    }

    @Test
    void effectiveLanguage() {
        TranscriptionSettings.Whisper fixed = new TranscriptionSettings.Whisper(null, "de", false, 0, 0, 0, 0, 0, null, null, null);
        TranscriptionSettings.Whisper auto = new TranscriptionSettings.Whisper(null, "de", true, 0, 0, 0, 0, 0, null, null, null);
        assertEquals("de", RecordingManager.effectiveLanguage("", fixed));
        assertEquals("auto", RecordingManager.effectiveLanguage("", auto));
        assertEquals("fr", RecordingManager.effectiveLanguage("fr", auto));
        assertEquals("de", RecordingManager.effectiveLanguage("French!", fixed));
    }
}
