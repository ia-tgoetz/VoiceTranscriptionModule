import { TARGET_RATE, downmix, floatToInt16, resampleLinear } from "./pcm";

/**
 * Where the recorder listens.
 * - microphone: getUserMedia (echo cancellation on - it deliberately removes speaker playback)
 * - tab:        audio of a shared browser tab, window, or screen (getDisplayMedia)
 * - mixed:      microphone AND shared tab audio, kept as two separate tracks so the transcript can label
 *               each source ("[Microphone] ..." / "[System audio] ...")
 */
export type AudioSource = "microphone" | "tab" | "mixed";

/** Gateway track names (RecordingSession.MICROPHONE / SYSTEM). */
export type TrackName = "microphone" | "system";

export function needsDisplayAudio(source: AudioSource): boolean {
    return source === "tab" || source === "mixed";
}

/** Tracks to declare when starting a recording; empty = single unlabelled source. */
export function tracksFor(source: AudioSource): TrackName[] {
    return source === "mixed" ? ["microphone", "system"] : [];
}

export interface AudioCaptureOptions {
    /** MediaRecorder timeslice: how often ondataavailable fires (and a chunk is uploaded). */
    chunkIntervalMs: number;
    source: AudioSource;
    /** Required for "tab"/"mixed": a stream from AudioCapture.requestDisplayAudio(). Not owned - never stopped here. */
    displayStream?: MediaStream;
    /**
     * Receives each new slice of 16 kHz mono PCM16 for one track. `track` is undefined for single-source
     * recordings. `final` is true exactly once per track, after stop().
     */
    onPcm: (pcm: Int16Array, final: boolean, track?: TrackName) => Promise<void>;
    onError: (error: Error) => void;
}

const PREFERRED_TYPES = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/webm", "audio/mp4"];

/**
 * Records one MediaStream and turns it into 16 kHz PCM16 slices.
 *
 * MediaRecorder emits compressed slices (webm/opus in Chromium/Firefox, mp4/aac in Safari) and only the
 * first slice carries the container header, so slices can't be decoded independently. On every
 * `ondataavailable` we decode the *cumulative* recording with `decodeAudioData`, resample it to 16 kHz
 * mono with an OfflineAudioContext, and hand on only the samples not sent before. The gateway therefore
 * receives plain PCM16 and never has to bundle a native audio decoder. If a browser can't decode a partial
 * container (some Safari versions), partial uploads are skipped and the final decode after stop() still
 * delivers the complete audio.
 */
class TrackRecorder {
    private recorder: MediaRecorder;
    private context = new AudioContext();
    private blobs: Blob[] = [];
    private mimeType: string;
    private samplesSent = 0;
    private chain: Promise<void> = Promise.resolve();
    private stopped: Promise<void>;

    constructor(
        stream: MediaStream,
        private readonly emit: (pcm: Int16Array, final: boolean) => Promise<void>,
        private readonly onError: (error: Error) => void,
    ) {
        const preferred = PREFERRED_TYPES.find((t) => MediaRecorder.isTypeSupported(t)) || "";
        this.recorder = new MediaRecorder(stream, preferred ? { mimeType: preferred } : undefined);
        this.mimeType = this.recorder.mimeType || preferred;
        this.recorder.ondataavailable = (event: BlobEvent) => {
            if (event.data && event.data.size > 0) {
                this.blobs.push(event.data);
                this.enqueue(false);
            }
        };
        this.stopped = new Promise<void>((resolve) => {
            this.recorder.onstop = () => resolve();
        });
    }

    start(timesliceMs: number): void {
        this.recorder.start(timesliceMs);
    }

    async stop(): Promise<void> {
        if (this.recorder.state !== "inactive") {
            this.recorder.stop(); // fires a last ondataavailable, then onstop
            await this.stopped;
        }
        this.enqueue(true);
        await this.chain;
        await this.context.close().catch(() => undefined);
    }

    abort(): void {
        if (this.recorder.state !== "inactive") {
            this.recorder.ondataavailable = null;
            this.recorder.stop();
        }
        this.context.close().catch(() => undefined);
        this.blobs = [];
    }

    /** Decodes are serialised so slices are uploaded in order. */
    private enqueue(final: boolean): void {
        this.chain = this.chain
            .then(() => this.decodeAndEmit(final))
            .catch((e) => this.onError(e instanceof Error ? e : new Error(String(e))));
    }

    private async decodeAndEmit(final: boolean): Promise<void> {
        if (this.blobs.length === 0) {
            if (final) {
                await this.emit(new Int16Array(0), true);
            }
            return;
        }
        const data = await new Blob(this.blobs, { type: this.mimeType }).arrayBuffer();
        let decoded: AudioBuffer;
        try {
            decoded = await this.context.decodeAudioData(data);
        } catch (e) {
            if (final) {
                throw new Error("The browser could not decode the recorded audio.");
            }
            return; // partial container not decodable yet; the next slice (or the final decode) will catch up
        }
        const pcm16k = await toMono16k(decoded);
        const fresh = pcm16k.subarray(Math.min(this.samplesSent, pcm16k.length));
        this.samplesSent = Math.max(this.samplesSent, pcm16k.length);
        if (fresh.length > 0 || final) {
            await this.emit(floatToInt16(fresh), final);
        }
    }
}

/**
 * Captures the configured source(s). Microphone-only and tab-only recordings use one TrackRecorder;
 * "mixed" runs two in parallel (microphone + system) that start in the same tick, so their sample
 * positions line up and the gateway can order the two transcripts by time.
 */
export class AudioCapture {
    private micStream?: MediaStream;
    private recorders: TrackRecorder[] = [];

    constructor(private readonly options: AudioCaptureOptions) {}

    static isSupported(source: AudioSource = "microphone"): string | null {
        if (typeof window === "undefined" || !window.isSecureContext) {
            return "Audio capture requires HTTPS (or http://localhost).";
        }
        if (!navigator.mediaDevices) {
            return "This browser does not expose media devices (navigator.mediaDevices).";
        }
        if (source !== "tab" && !navigator.mediaDevices.getUserMedia) {
            return "This browser does not expose a microphone.";
        }
        if (needsDisplayAudio(source) && !(navigator.mediaDevices as any).getDisplayMedia) {
            return "This browser can't capture tab or system audio. Use Chrome or Edge on a desktop.";
        }
        if (typeof MediaRecorder === "undefined") {
            return "This browser does not support MediaRecorder.";
        }
        return null;
    }

    /**
     * Opens the browser's share picker. Must run inside a user gesture (click / key press).
     *
     * Chromium requires a video track for getDisplayMedia, so one is requested at 1 fps and kept alive (it is
     * never recorded): stopping it can end the share. Audio only comes along if the user enables
     * "Also share tab audio" (or "Also share system audio" for a whole screen on Windows).
     */
    static async requestDisplayAudio(): Promise<MediaStream> {
        const constraints: any = {
            video: { frameRate: 1, width: { max: 640 }, height: { max: 360 } },
            audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, suppressLocalAudioPlayback: false },
            selfBrowserSurface: "exclude",
            systemAudio: "include",
            surfaceSwitching: "include",
            preferCurrentTab: false,
        };
        const stream: MediaStream = await (navigator.mediaDevices as any).getDisplayMedia(constraints);
        if (stream.getAudioTracks().length === 0) {
            stream.getTracks().forEach((t) => t.stop());
            throw new Error(
                "No audio was shared. Share a browser tab and turn on \"Also share tab audio\" " +
                    "(or share an entire screen with \"Also share system audio\" on Windows).",
            );
        }
        return stream;
    }

    async start(): Promise<void> {
        const { source, displayStream, onPcm, onError } = this.options;
        if (needsDisplayAudio(source) && (!displayStream || displayStream.getAudioTracks().length === 0)) {
            throw new Error("No shared tab audio is available.");
        }
        if (source !== "tab") {
            // Echo cancellation also keeps the speakers' copy of the tab audio out of the microphone track,
            // so in "mixed" mode the video isn't transcribed twice (once per track).
            this.micStream = await navigator.mediaDevices.getUserMedia({
                audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
            });
        }
        const system = displayStream ? new MediaStream(displayStream.getAudioTracks()) : undefined;
        if (source === "microphone") {
            this.recorders = [new TrackRecorder(this.micStream!, (pcm, final) => onPcm(pcm, final), onError)];
        } else if (source === "tab") {
            this.recorders = [new TrackRecorder(system!, (pcm, final) => onPcm(pcm, final), onError)];
        } else {
            this.recorders = [
                new TrackRecorder(this.micStream!, (pcm, final) => onPcm(pcm, final, "microphone"), onError),
                new TrackRecorder(system!, (pcm, final) => onPcm(pcm, final, "system"), onError),
            ];
        }
        this.recorders.forEach((r) => r.start(this.options.chunkIntervalMs));
    }

    /** Stops capturing and resolves after every track's final PCM slice has been delivered. */
    async stop(): Promise<void> {
        const done = Promise.all(this.recorders.map((r) => r.stop()));
        await done;
        this.releaseMicrophone();
    }

    /** Stops without delivering anything else. */
    abort(): void {
        this.recorders.forEach((r) => r.abort());
        this.recorders = [];
        this.releaseMicrophone();
    }

    /** Releases what this capture owns: the microphone. The shared display stream is the caller's. */
    private releaseMicrophone(): void {
        this.micStream?.getTracks().forEach((t) => t.stop());
        this.micStream = undefined;
    }
}

async function toMono16k(buffer: AudioBuffer): Promise<Float32Array> {
    const length = Math.ceil(buffer.duration * TARGET_RATE);
    if (typeof OfflineAudioContext !== "undefined" && length > 0) {
        const offline = new OfflineAudioContext(1, length, TARGET_RATE);
        const source = offline.createBufferSource();
        source.buffer = buffer;
        source.connect(offline.destination); // the destination's single channel downmixes for us
        source.start();
        const rendered = await offline.startRendering();
        return rendered.getChannelData(0);
    }
    const channels: Float32Array[] = [];
    for (let c = 0; c < buffer.numberOfChannels; c++) {
        channels.push(buffer.getChannelData(c));
    }
    return resampleLinear(downmix(channels), buffer.sampleRate, TARGET_RATE);
}
