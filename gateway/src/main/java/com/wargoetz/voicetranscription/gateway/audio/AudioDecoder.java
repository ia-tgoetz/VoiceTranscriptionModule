package com.wargoetz.voicetranscription.gateway.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Converts uploaded audio chunks into Whisper's input format: 16 kHz, mono, signed 16-bit PCM.
 *
 * <p>Two upload formats are accepted:</p>
 * <ul>
 *   <li>{@code audio/L16} / {@code application/octet-stream}: raw little-endian PCM16, 16 kHz, mono. This is
 *   what the Perspective component sends (it decodes MediaRecorder output in the browser).</li>
 *   <li>{@code audio/wav}: RIFF/WAVE, PCM 8/16/24/32-bit or IEEE float 32, any rate and channel count. Handy
 *   for scripted clients and the test-suite.</li>
 * </ul>
 * Compressed codecs (webm/opus, mp4/aac) are deliberately not decoded on the gateway: that would mean
 * bundling ffmpeg-class native code in a gateway JVM for no gain, since every browser can decode them.
 */
public final class AudioDecoder {
    public static final int TARGET_RATE = 16_000;

    private AudioDecoder() {
    }

    /** Decodes {@code body} according to {@code contentType} into 16 kHz mono PCM16. */
    public static short[] decode(String contentType, byte[] body) throws AudioFormatException {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("audio/wav") || type.startsWith("audio/x-wav") || type.startsWith("audio/wave")
            || looksLikeWav(body)) {
            return decodeWav(body);
        }
        if (type.isEmpty() || type.startsWith("application/octet-stream") || type.startsWith("audio/l16")
            || type.startsWith("audio/pcm")) {
            int rate = parseIntParam(type, "rate", TARGET_RATE);
            int channels = parseIntParam(type, "channels", 1);
            short[] pcm = pcm16le(body);
            return resample(downmix(pcm, channels), rate, TARGET_RATE);
        }
        throw new AudioFormatException("Unsupported Content-Type '" + contentType
            + "'. Send audio/L16 (16 kHz mono PCM16LE) or audio/wav.");
    }

    static boolean looksLikeWav(byte[] body) {
        return body.length >= 12
            && new String(body, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
            && new String(body, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
    }

    static short[] pcm16le(byte[] body) throws AudioFormatException {
        if (body.length % 2 != 0) {
            throw new AudioFormatException("PCM16 payload has an odd byte count (" + body.length + ")");
        }
        short[] out = new short[body.length / 2];
        ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    static short[] decodeWav(byte[] body) throws AudioFormatException {
        if (!looksLikeWav(body)) {
            throw new AudioFormatException("Not a RIFF/WAVE file");
        }
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(12);
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        while (buf.remaining() >= 8) {
            String id = new String(body, buf.position(), 4, StandardCharsets.US_ASCII);
            buf.position(buf.position() + 4);
            long size = Integer.toUnsignedLong(buf.getInt());
            int start = buf.position();
            if (id.equals("fmt ")) {
                format = Short.toUnsignedInt(buf.getShort());
                channels = Short.toUnsignedInt(buf.getShort());
                rate = buf.getInt();
                buf.getInt();   // byte rate
                buf.getShort(); // block align
                bits = Short.toUnsignedInt(buf.getShort());
                if (format == 0xFFFE && size >= 40) {
                    buf.position(start + 24);
                    format = Short.toUnsignedInt(buf.getShort()); // WAVE_FORMAT_EXTENSIBLE sub-format
                }
            } else if (id.equals("data")) {
                if (format < 0) {
                    throw new AudioFormatException("WAV 'data' chunk precedes 'fmt ' chunk");
                }
                // Streaming writers put 0 / 0xFFFFFFFF here; clamp to what was actually sent.
                int len = (int) Math.min(size, buf.remaining());
                short[] pcm = samplesToPcm16(body, start, len, format, bits);
                return resample(downmix(pcm, channels), rate, TARGET_RATE);
            }
            long next = start + size + (size & 1); // chunks are word-aligned
            if (next > body.length) {
                break;
            }
            buf.position((int) next);
        }
        throw new AudioFormatException("WAV file has no 'data' chunk");
    }

    private static short[] samplesToPcm16(byte[] body, int offset, int len, int format, int bits)
        throws AudioFormatException {
        ByteBuffer b = ByteBuffer.wrap(body, offset, len).order(ByteOrder.LITTLE_ENDIAN);
        if (format == 1 && bits == 16) {
            short[] out = new short[len / 2];
            b.asShortBuffer().get(out);
            return out;
        }
        if (format == 1 && bits == 8) {
            short[] out = new short[len];
            for (int i = 0; i < len; i++) {
                out[i] = (short) (((body[offset + i] & 0xFF) - 128) << 8);
            }
            return out;
        }
        if (format == 1 && bits == 24) {
            short[] out = new short[len / 3];
            for (int i = 0; i < out.length; i++) {
                int p = offset + i * 3;
                out[i] = (short) ((body[p + 2] << 8) | (body[p + 1] & 0xFF));
            }
            return out;
        }
        if (format == 1 && bits == 32) {
            short[] out = new short[len / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = (short) (b.getInt() >> 16);
            }
            return out;
        }
        if (format == 3 && bits == 32) {
            short[] out = new short[len / 4];
            for (int i = 0; i < out.length; i++) {
                out[i] = floatToPcm16(b.getFloat());
            }
            return out;
        }
        throw new AudioFormatException("Unsupported WAV encoding (format=" + format + ", bits=" + bits + ")");
    }

    static short[] downmix(short[] interleaved, int channels) {
        if (channels <= 1) {
            return interleaved;
        }
        short[] mono = new short[interleaved.length / channels];
        for (int i = 0; i < mono.length; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += interleaved[i * channels + c];
            }
            mono[i] = (short) (sum / channels);
        }
        return mono;
    }

    /**
     * Linear-interpolation resampler. Adequate for speech recognition (Whisper's own front end low-passes
     * via the mel filterbank); not intended for hi-fi audio.
     */
    static short[] resample(short[] in, int fromRate, int toRate) {
        if (fromRate == toRate || in.length == 0) {
            return in;
        }
        int outLen = (int) ((long) in.length * toRate / fromRate);
        short[] out = new short[outLen];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int idx = (int) pos;
            double frac = pos - idx;
            int a = in[Math.min(idx, in.length - 1)];
            int b = in[Math.min(idx + 1, in.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * frac);
        }
        return out;
    }

    public static float[] toFloat(short[] pcm, int from, int to) {
        float[] out = new float[to - from];
        for (int i = from; i < to; i++) {
            out[i - from] = pcm[i] / 32768f;
        }
        return out;
    }

    static short floatToPcm16(float f) {
        float clamped = Math.max(-1f, Math.min(1f, f));
        return (short) Math.round(clamped * 32767f);
    }

    private static int parseIntParam(String contentType, String name, int fallback) {
        for (String part : contentType.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(name)) {
                try {
                    return Integer.parseInt(kv[1].trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /** Thrown for payloads that cannot be turned into PCM; mapped to HTTP 415 by the routes. */
    public static class AudioFormatException extends Exception {
        public AudioFormatException(String message) {
            super(message);
        }
    }
}
