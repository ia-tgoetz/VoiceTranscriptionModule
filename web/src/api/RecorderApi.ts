/**
 * HTTP client for the module's gateway routes. Audio never travels over Perspective's websocket property
 * sync: each slice is a separate POST, which keeps the websocket free for tag/property traffic and lets
 * the gateway apply per-request size limits.
 *
 * Every request carries the Perspective session id + project name. The gateway uses them to look up the
 * caller's live session (inside the caller's own HTTP session) for the authoritative username, roles,
 * host, and user agent; the custom headers also force a CORS preflight, which blocks CSRF.
 */

export const API_BASE = "/data/voicetranscription/api/v1";

export interface ClientConfig {
    chunkIntervalMs: number;
    maxRecordingSeconds: number;
    defaultLanguage: string;
    autoDetect: boolean;
    sampleRate: number;
    whisperReady: boolean;
    llmEnabled: boolean;
}

/** Consecutive transcript text from one source (only meaningful for multi-track recordings). */
export interface Turn {
    source: string;
    label: string;
    text: string;
    /** Seconds from the start of the recording. */
    start: number;
}

export interface StartResponse {
    recordingId: string;
    tracks: string[];
    language: string;
    chunkIntervalMs: number;
    maxRecordingSeconds: number;
}

export interface ChunkResponse {
    partialTranscript?: string;
    turns?: Turn[];
    track: string;
    durationSeconds: number;
    transcribed: boolean;
    limitReached: boolean;
    /** The chunk carried no audible signal (gateway-side RMS gate). */
    silent?: boolean;
}

export interface StopResponse {
    transcript: string;
    turns: Turn[];
    language: string;
    durationSeconds: number;
}

export interface SubmittedDocument {
    id: string;
    timestamp: string;
    transcript: string;
    language: string;
    summary: string;
    user: { username: string; roles: string[] };
    device: { clientAddress: string; userAgent: string; sessionType: string };
    durationSeconds?: number;
    summaryError?: string;
}

export class ApiError extends Error {
    constructor(message: string, readonly status: number) {
        super(message);
    }
}

export class RecorderApi {
    constructor(private readonly sessionId: string, private readonly projectName: string) {}

    clientConfig(): Promise<ClientConfig> {
        return this.request("GET", "/client-config");
    }

    /** `tracks`: e.g. ["microphone", "system"] to transcribe two sources separately (labelled transcript). */
    /**
     * @param timeOffsetSeconds added to every timestamp, so a recording appended to an existing log keeps
     *                          counting from when the log began
     */
    start(language: string, tracks: string[] = [], timestamps = false, timeOffsetSeconds = 0): Promise<StartResponse> {
        return this.request("POST", "/recordings", { language, tracks, timestamps, timeOffsetSeconds });
    }

    chunk(recordingId: string, seq: number, pcm: Int16Array, track?: string): Promise<ChunkResponse> {
        // Copy into a standalone ArrayBuffer: pcm may be a view into a larger buffer.
        const body = pcm.slice().buffer;
        const headers: Record<string, string> = {
            "Content-Type": "audio/L16;rate=16000;channels=1",
            "X-Chunk-Seq": String(seq),
        };
        if (track) {
            headers["X-Audio-Track"] = track;
        }
        return this.request("POST", `/recordings/${encodeURIComponent(recordingId)}/chunks`, body, headers);
    }

    stop(recordingId: string): Promise<StopResponse> {
        return this.request("POST", `/recordings/${encodeURIComponent(recordingId)}/stop`);
    }

    submit(recordingId: string, transcript: string): Promise<SubmittedDocument> {
        return this.request("POST", `/recordings/${encodeURIComponent(recordingId)}/submit`, { transcript });
    }

    cancel(recordingId: string): Promise<unknown> {
        return this.request("DELETE", `/recordings/${encodeURIComponent(recordingId)}`);
    }

    private async request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
        const init: RequestInit = {
            method,
            credentials: "same-origin",
            headers: {
                Accept: "application/json",
                "X-Perspective-Session-Id": this.sessionId,
                "X-Perspective-Project": this.projectName,
                ...headers,
            },
        };
        if (body instanceof ArrayBuffer) {
            init.body = body;
        } else if (body !== undefined) {
            (init.headers as Record<string, string>)["Content-Type"] = "application/json";
            init.body = JSON.stringify(body);
        }
        const res = await fetch(API_BASE + path, init);
        const text = await res.text();
        let json: any = {};
        try {
            json = text ? JSON.parse(text) : {};
        } catch {
            json = { error: text || res.statusText };
        }
        if (!res.ok) {
            throw new ApiError(json.error || `HTTP ${res.status}`, res.status);
        }
        return json as T;
    }
}
