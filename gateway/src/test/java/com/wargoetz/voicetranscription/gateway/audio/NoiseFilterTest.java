package com.wargoetz.voicetranscription.gateway.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import com.wargoetz.voicetranscription.gateway.audio.NoiseFilter.Level;
import org.junit.jupiter.api.Test;

class NoiseFilterTest {
    private static float[] noise(int seconds, double amplitude, long seed) {
        Random r = new Random(seed);
        float[] out = new float[seconds * 16_000];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) ((r.nextDouble() * 2 - 1) * amplitude);
        }
        return out;
    }

    /** Noise with a 1.5 s "speech" burst (+20 dB tone with syllable-like amplitude modulation) at 2 s. */
    private static float[] noiseWithSpeech() {
        float[] w = noise(5, 0.02, 1);
        for (int i = 32_000; i < 56_000; i++) {
            double syllables = 0.6 + 0.4 * Math.sin(2 * Math.PI * 4 * i / 16_000.0);
            w[i] += (float) (Math.sin(2 * Math.PI * 220 * i / 16_000.0) * 0.3 * syllables);
        }
        return w;
    }

    private static boolean[] voiced(float[] w, Level level) {
        double[] frames = NoiseFilter.frameRms(w);
        NoiseFilter.FloorTracker t = new NoiseFilter.FloorTracker();
        return NoiseFilter.voiced(frames, t.floor(frames), level);
    }

    @Test
    void steadyNoiseIsNotSpeech() {
        for (double amp : new double[] {0.005, 0.03, 0.1}) {
            boolean[] v = voiced(noise(6, amp, 7), Level.STANDARD);
            assertFalse(NoiseFilter.hasSpeech(v, Level.STANDARD), "amplitude " + amp);
        }
    }

    @Test
    void clicksAreNotSpeech() {
        float[] w = noise(6, 0.002, 3);
        for (int c = 4_000; c < w.length; c += 5_000) {
            for (int j = 0; j < 200; j++) {
                w[c + j] += (float) (0.8 * Math.exp(-j / 40.0));
            }
        }
        assertFalse(NoiseFilter.hasSpeech(voiced(w, Level.STANDARD), Level.STANDARD));
    }

    @Test
    void speechOverNoiseIsDetectedWhereItIs() {
        float[] w = noiseWithSpeech();
        boolean[] v = voiced(w, Level.STANDARD);
        assertTrue(NoiseFilter.hasSpeech(v, Level.STANDARD));
        assertEquals(32_000 - 3200, NoiseFilter.leadingSilence(v), 320);
        assertTrue(NoiseFilter.voicedRatio(v, 32_000, 56_000) > 0.6);
        assertTrue(NoiseFilter.voicedRatio(v, 64_000, 80_000) < 0.05); // the noise-only tail
    }

    @Test
    void cleanAudioUsesAbsoluteSilenceThreshold() {
        float[] w = new float[16_000 * 3];
        for (int i = 16_000; i < 32_000; i++) {
            w[i] = (float) Math.sin(i / 7.0) * 0.05f;
        }
        boolean[] v = voiced(w, Level.STANDARD);
        assertTrue(NoiseFilter.hasSpeech(v, Level.STANDARD));
    }

    @Test
    void segmentDecisions() {
        assertTrue(NoiseFilter.keepSegment("The pump is hot.", 0.8, Level.STANDARD));
        assertFalse(NoiseFilter.keepSegment("Trust me, you should believe it.", 0.0, Level.STANDARD));
        assertFalse(NoiseFilter.keepSegment("Thank you.", 0.3, Level.STANDARD));      // noise phrase, weakly voiced
        assertTrue(NoiseFilter.keepSegment("Thank you.", 0.9, Level.STANDARD));       // really said
        assertFalse(NoiseFilter.keepSegment("Thank you.", 0.9, Level.STRICT));
        assertTrue(NoiseFilter.keepSegment("anything", 0.0, Level.OFF));
    }

    @Test
    void nonSpeechTagsAreRemoved() {
        assertEquals("", NoiseFilter.clean(" [BLANK_AUDIO]"));
        assertEquals("", NoiseFilter.clean(" (upbeat music) ♪"));
        assertEquals("Check the pump.", NoiseFilter.clean(" [MUSIC] Check the pump. *coughs*"));
        assertEquals("", NoiseFilter.clean(" . . "));
        assertTrue(NoiseFilter.isNoisePhrase("You, you, you."));
        assertFalse(NoiseFilter.isNoisePhrase("You need to check the valve."));
    }

    @Test
    void levelsParse() {
        assertEquals(Level.STRICT, Level.fromId("Strict"));
        assertEquals(Level.STANDARD, Level.fromId("bogus"));
        assertEquals("off", Level.fromId("off").id());
    }
}
