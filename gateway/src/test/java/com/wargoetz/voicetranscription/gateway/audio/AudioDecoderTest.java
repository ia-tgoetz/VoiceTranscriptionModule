package com.wargoetz.voicetranscription.gateway.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AudioDecoderTest {

    @Test
    void rawPcm16IsPassedThrough() throws Exception {
        byte[] body = pcmBytes(new short[] {1, -2, 300, Short.MAX_VALUE});
        short[] out = AudioDecoder.decode("audio/L16;rate=16000;channels=1", body);
        assertArrayEquals(new short[] {1, -2, 300, Short.MAX_VALUE}, out);
    }

    @Test
    void rawPcmAtOtherRateIsResampled() throws Exception {
        short[] in = new short[48_000];
        short[] out = AudioDecoder.decode("audio/L16;rate=48000", pcmBytes(in));
        assertEquals(16_000, out.length);
    }

    @Test
    void oddByteCountIsRejected() {
        assertThrows(AudioDecoder.AudioFormatException.class,
            () -> AudioDecoder.decode("application/octet-stream", new byte[] {1, 2, 3}));
    }

    @Test
    void compressedTypesAreRejected() {
        assertThrows(AudioDecoder.AudioFormatException.class,
            () -> AudioDecoder.decode("audio/webm;codecs=opus", new byte[] {0x1A, 0x45}));
    }

    @Test
    void stereo44kWavIsDownmixedAndResampled() throws Exception {
        int frames = 44_100;
        short[] interleaved = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            interleaved[i * 2] = 1000;
            interleaved[i * 2 + 1] = 3000;
        }
        short[] out = AudioDecoder.decode("audio/wav", wav(1, 2, 44_100, 16, pcmBytes(interleaved)));
        assertEquals(16_000, out.length);
        assertEquals(2000, out[500]);
    }

    @Test
    void float32WavIsConverted() throws Exception {
        ByteBuffer b = ByteBuffer.allocate(16_000 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16_000; i++) {
            b.putFloat(0.5f);
        }
        short[] out = AudioDecoder.decode("audio/wav", wav(3, 1, 16_000, 32, b.array()));
        assertEquals(16_000, out.length);
        assertEquals(16384, out[10], 1);
    }

    @Test
    void wavDetectedWithoutContentType() throws Exception {
        short[] out = AudioDecoder.decode("application/octet-stream", wav(1, 1, 16_000, 16, pcmBytes(new short[] {5, 6})));
        assertArrayEquals(new short[] {5, 6}, out);
    }

    @Test
    void toFloatScales() {
        float[] f = AudioDecoder.toFloat(new short[] {0, 16384, -32768}, 0, 3);
        assertEquals(0f, f[0]);
        assertEquals(0.5f, f[1]);
        assertEquals(-1f, f[2]);
    }

    static byte[] pcmBytes(short[] samples) {
        ByteBuffer b = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    static byte[] wav(int format, int channels, int rate, int bits, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + data.length)
            .put("WAVE".getBytes(StandardCharsets.US_ASCII))
            .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
            .putShort((short) format).putShort((short) channels).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((short) (channels * bits / 8)).putShort((short) bits)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(data.length);
        out.writeBytes(h.array());
        out.writeBytes(data);
        return out.toByteArray();
    }
}
