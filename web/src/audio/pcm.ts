/**
 * Pure PCM helpers (no DOM), unit-tested with `node --test`.
 */

export const TARGET_RATE = 16000;

/** Float [-1, 1] -> signed 16-bit, clamped. */
export function floatToInt16(samples: Float32Array): Int16Array {
    const out = new Int16Array(samples.length);
    for (let i = 0; i < samples.length; i++) {
        const s = Math.max(-1, Math.min(1, samples[i]));
        out[i] = s < 0 ? Math.round(s * 32768) : Math.round(s * 32767);
    }
    return out;
}

/** Averages interleaved channel arrays into one mono channel. */
export function downmix(channels: Float32Array[]): Float32Array {
    if (channels.length === 1) {
        return channels[0];
    }
    const len = channels[0].length;
    const out = new Float32Array(len);
    for (const ch of channels) {
        for (let i = 0; i < len; i++) {
            out[i] += ch[i] / channels.length;
        }
    }
    return out;
}

/**
 * Linear-interpolation resampler, used when OfflineAudioContext is unavailable. Speech-grade only.
 */
export function resampleLinear(input: Float32Array, fromRate: number, toRate: number): Float32Array {
    if (fromRate === toRate || input.length === 0) {
        return input;
    }
    const outLen = Math.floor((input.length * toRate) / fromRate);
    const out = new Float32Array(outLen);
    const step = fromRate / toRate;
    for (let i = 0; i < outLen; i++) {
        const pos = i * step;
        const idx = Math.floor(pos);
        const frac = pos - idx;
        const a = input[Math.min(idx, input.length - 1)];
        const b = input[Math.min(idx + 1, input.length - 1)];
        out[i] = a + (b - a) * frac;
    }
    return out;
}

/** Formats seconds as m:ss for the recording timer. */
export function formatDuration(seconds: number): string {
    const s = Math.max(0, Math.floor(seconds));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/** True when a keyboard event originates from somewhere the user is typing (push-to-talk must not fire). */
export function isEditableTarget(target: { tagName?: string; isContentEditable?: boolean } | null): boolean {
    if (!target) {
        return false;
    }
    const tag = (target.tagName || "").toUpperCase();
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable === true;
}
