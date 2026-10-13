import * as React from "react";
import {
    Component,
    ComponentMeta,
    ComponentProps,
    PComponent,
    PropertyTree,
    makeLogger,
} from "@inductiveautomation/perspective-client";
import type { SizeObject } from "@inductiveautomation/perspective-common";
import { AudioCapture, AudioSource, TrackName, needsDisplayAudio, tracksFor } from "../audio/AudioCapture";
import { formatDuration, isEditableTarget, resolveChunkInterval } from "../audio/pcm";
import { RecorderApi, SubmittedDocument } from "../api/RecorderApi";
import { compose, marker, parseLine, pendingText } from "../transcript";

/** Must equal AudioRecorder.COMPONENT_ID on the Java side. */
export const COMPONENT_TYPE = "voicetranscription.input.recorder";

const logger = makeLogger("voicetranscription.VoiceRecorder");

type Status = "idle" | "recording" | "transcribing" | "ready" | "submitting" | "submitted" | "error";

export interface SourceColors {
    microphone: string;
    system: string;
    timestamp: string;
}

export interface VoiceRecorderProps {
    enabled: boolean;
    language: string;
    audioSource: AudioSource;
    showTimestamps: boolean;
    sourceColors: SourceColors;
    chunkIntervalMs: number;
    pushToTalk: { enabled: boolean; key: string };
    autoSubmit: boolean;
    showSummary: boolean;
    placeholder: string;
    transcript: string;
    status: Status;
}

interface State {
    status: Status;
    /** Everything in the box: earlier recordings, submit dividers, and the live recording. */
    transcript: string;
    error: string;
    summary: string;
    elapsed: number;
    /** Label of the tab/screen whose audio is currently shared, or "" when nothing is shared. */
    sharing: string;
    /** Non-fatal hint, e.g. "the shared tab is silent". */
    warning: string;
    /** Clear was clicked once; a second click within a few seconds clears the box. */
    confirmClear: boolean;
}

const DEFAULT_COLORS: SourceColors = { microphone: "#1d6fd8", system: "#c2410c", timestamp: "#6b7280" };

export class VoiceRecorder extends Component<ComponentProps<VoiceRecorderProps>, State> {
    state: State = {
        status: "idle", transcript: "", error: "", summary: "", elapsed: 0, sharing: "", warning: "", confirmClear: false,
    };

    private api?: RecorderApi;
    private capture?: AudioCapture;
    private recordingId?: string;
    /** Upload sequence number per track ("" = single-source recording). */
    private seq: Record<string, number> = {};
    private startedAt = 0;
    private timer?: number;
    private pushToTalkActive = false;
    private unmounted = false;
    /** Shared tab/screen stream for "tab"/"mixed" sources. Kept open across recordings so push-to-talk
     *  doesn't re-open the share picker every time; closed by "Stop sharing", the browser bar, or unmount. */
    private displayStream?: MediaStream;
    /** True between "start" and the capture actually running (share picker open, gateway round-trip). */
    private starting = false;
    /** Stop was requested (e.g. push-to-talk released) while still starting; honoured once started. */
    private pendingStop = false;
    /** Consecutive uploaded chunks the gateway reported as silent, per track. */
    private silentChunks: Record<string, number> = {};
    /** Box content when the current recording started; the recording's text is appended below it. */
    private baseText = "";
    /** When the log in the box began (first recording after a clear); timestamps count from here. */
    private logStartedAt = 0;
    private clearTimer?: number;
    /** Set when the component itself appended text (recording, stop, submit divider): scroll to the end. */
    private followTail = false;
    private readonly textareaRef = React.createRef<HTMLTextAreaElement>();
    private readonly backdropRef = React.createRef<HTMLDivElement>();

    componentDidMount(): void {
        this.setState({ transcript: this.props.props.transcript || "" });
        document.addEventListener("keydown", this.onKeyDown);
        document.addEventListener("keyup", this.onKeyUp);
        window.addEventListener("blur", this.onWindowBlur);
    }

    componentDidUpdate(prev: ComponentProps<VoiceRecorderProps>, prevState: State): void {
        // Accept external writes to props.transcript (a binding or script setting it, e.g. "" to clear) unless
        // we're mid-recording.
        const incoming = this.props.props.transcript || "";
        if (incoming !== (prev.props.transcript || "") && incoming !== this.state.transcript && !this.isBusy()
            && this.state.status !== "recording" && !this.starting) {
            if (!incoming) {
                this.logStartedAt = 0;
            }
            this.setState({ transcript: incoming });
        }
        if (this.state.transcript !== prevState.transcript) {
            const ta = this.textareaRef.current;
            if (ta && this.followTail) {
                ta.scrollTop = ta.scrollHeight; // show what was just added
            }
            this.followTail = false;
            this.syncScroll();
        }
    }

    componentWillUnmount(): void {
        this.unmounted = true;
        document.removeEventListener("keydown", this.onKeyDown);
        document.removeEventListener("keyup", this.onKeyUp);
        window.removeEventListener("blur", this.onWindowBlur);
        window.clearTimeout(this.clearTimer);
        this.stopTimer();
        this.capture?.abort();
        this.closeShare();
        if (this.recordingId && this.state.status !== "submitted") {
            this.getApi().cancel(this.recordingId).catch(() => undefined);
        }
    }

    // ------------------------------------------------------------------------------------------- actions

    private startRecording = async (): Promise<void> => {
        if (!this.props.props.enabled || this.isBusy() || this.state.status === "recording" || this.starting) {
            return;
        }
        const source = this.audioSource();
        const unsupported = AudioCapture.isSupported(source);
        if (unsupported) {
            this.fail("microphone", unsupported);
            return;
        }
        this.starting = true;
        this.pendingStop = false;
        if (needsDisplayAudio(source) && !this.hasLiveShare()) {
            // Must be the first await: the share picker needs the click/key-press's user activation.
            try {
                this.openShare(await AudioCapture.requestDisplayAudio());
            } catch (e) {
                this.starting = false;
                this.fail("microphone", errorMessage(e, "Sharing was cancelled or blocked."));
                return;
            }
        }
        if (this.recordingId) {
            // The previous recording's text stays in the box; only its server-side session is released.
            this.getApi().cancel(this.recordingId).catch(() => undefined);
            this.recordingId = undefined;
        }
        // Never clear the box: this recording is appended below what's already there.
        this.baseText = this.state.transcript.replace(/\s+$/, "");
        const now = Date.now();
        if (!this.baseText || !this.logStartedAt) {
            this.logStartedAt = now;
        }
        const timeOffset = this.props.props.showTimestamps ? (now - this.logStartedAt) / 1000 : 0;
        this.setStatus("recording");
        this.setState({ error: "", summary: "", elapsed: 0, warning: "", confirmClear: false });
        this.silentChunks = {};
        this.writeProp("error", "");
        try {
            const api = this.getApi();
            const started = await api.start((this.props.props.language || "").trim().toLowerCase(), tracksFor(source),
                this.props.props.showTimestamps, timeOffset);
            this.recordingId = started.recordingId;
            this.seq = {};
            // started.chunkIntervalMs is read from the gateway on every start, so a settings change applies
            // to the next recording without a page reload.
            const interval = resolveChunkInterval(this.props.props.chunkIntervalMs, started.chunkIntervalMs);
            this.capture = new AudioCapture({
                chunkIntervalMs: interval,
                source,
                displayStream: this.displayStream,
                onPcm: this.uploadPcm,
                onError: (e) => this.fail("upload", e.message),
            });
            await this.capture.start();
            this.startTimer();
            this.starting = false;
            if (this.pendingStop) {
                void this.stopRecording();
            }
        } catch (e) {
            this.starting = false;
            this.capture?.abort();
            this.capture = undefined;
            this.fail(this.recordingId ? "microphone" : "upload", errorMessage(e));
        }
    };

    private uploadPcm = async (pcm: Int16Array, _final: boolean, track?: TrackName): Promise<void> => {
        if (!this.recordingId || pcm.length === 0) {
            return;
        }
        const key = track || "";
        const seq = this.seq[key] || 0;
        this.seq[key] = seq + 1;
        const res = await this.getApi().chunk(this.recordingId, seq, pcm, track);
        this.trackSilence(key, Boolean(res.silent));
        if (res.partialTranscript !== undefined && this.state.status === "recording") {
            this.setTranscript(compose(this.baseText, res.partialTranscript), true);
        }
        if (res.limitReached && this.state.status === "recording") {
            logger.info(() => "Maximum recording length reached; stopping.");
            void this.stopRecording();
        }
    };

    private stopRecording = async (): Promise<void> => {
        if (this.starting) {
            this.pendingStop = true;
            return;
        }
        if (this.state.status !== "recording" || !this.capture) {
            return;
        }
        this.stopTimer();
        this.setState({ warning: "" });
        this.setStatus("transcribing");
        try {
            await this.capture.stop();
            this.capture = undefined;
            const result = await this.getApi().stop(this.recordingId!);
            this.setTranscript(compose(this.baseText, result.transcript), true);
            this.setStatus("ready");
            if (this.props.props.autoSubmit) {
                await this.submit();
            }
        } catch (e) {
            // Whatever was transcribed so far stays in the box.
            this.fail("transcribe", errorMessage(e));
        }
    };

    /** Saves the text below the last "Submitted" divider, then appends a new divider. Never clears the box. */
    private submit = async (): Promise<void> => {
        if (!this.canSubmit()) {
            return;
        }
        const text = pendingText(this.state.transcript);
        this.setStatus("submitting");
        try {
            const api = this.getApi();
            if (!this.recordingId) {
                // Typed text, or a recording whose session was already used: submit through a text-only recording.
                this.recordingId = (await api.start((this.props.props.language || "").trim().toLowerCase())).recordingId;
            }
            const doc: SubmittedDocument = await api.submit(this.recordingId, text);
            this.recordingId = undefined;
            this.setTranscript(compose(this.state.transcript, marker(new Date())), true);
            this.writeProp("lastSubmission", doc);
            this.setState({ summary: doc.summary || "", error: doc.summaryError ? `Summary unavailable: ${doc.summaryError}` : "" });
            this.setStatus("submitted");
            this.props.componentEvents.fireComponentEvent("onSubmitted", doc);
        } catch (e) {
            // Keep everything so the user can retry (e.g. MongoDB briefly unavailable).
            this.fail("submit", errorMessage(e), "ready");
        }
    };

    /** The only way the box is emptied from the UI: two clicks (Clear, then Confirm). */
    private clear = (): void => {
        if (!this.state.confirmClear) {
            this.setState({ confirmClear: true });
            window.clearTimeout(this.clearTimer);
            this.clearTimer = window.setTimeout(() => !this.unmounted && this.setState({ confirmClear: false }), 4000);
            return;
        }
        window.clearTimeout(this.clearTimer);
        this.stopTimer();
        this.capture?.abort();
        this.capture = undefined;
        if (this.recordingId) {
            this.getApi().cancel(this.recordingId).catch(() => undefined);
            this.recordingId = undefined;
        }
        this.baseText = "";
        this.logStartedAt = 0;
        this.setTranscript("");
        this.setState({ error: "", summary: "", elapsed: 0, warning: "", confirmClear: false });
        this.writeProp("error", "");
        this.setStatus("idle");
    };

    private canSubmit(): boolean {
        const s = this.state.status;
        return this.props.props.enabled && s !== "recording" && s !== "transcribing" && s !== "submitting"
            && !this.starting && pendingText(this.state.transcript).length > 0;
    }

    /**
     * Two silent chunks in a row (~6 s by default) almost always means a source isn't delivering audio. In
     * "mixed" mode one quiet source is normal (nobody talking, video paused), so warn only when both are.
     */
    private trackSilence(track: string, silent: boolean): void {
        this.silentChunks[track] = silent ? (this.silentChunks[track] || 0) + 1 : 0;
        if (this.unmounted || this.state.status !== "recording") {
            return;
        }
        const source = this.audioSource();
        const quiet = (t: string) => (this.silentChunks[t] || 0) >= 2;
        const allQuiet = source === "mixed" ? quiet("microphone") && quiet("system") : quiet("");
        if (allQuiet) {
            const warning = source === "microphone"
                ? "No sound from the microphone. Check that it is connected, selected, and not muted."
                : source === "tab"
                    ? "No sound from the shared tab. Make sure it is playing audio and that \"Also share tab audio\" " +
                      "was turned on when you shared it (Stop sharing, then record again to re-share)."
                    : "No sound from the microphone or the shared tab. Check the microphone, and that the tab is " +
                      "playing with \"Also share tab audio\" turned on.";
            if (warning !== this.state.warning) {
                this.setState({ warning });
            }
        } else if (this.state.warning) {
            this.setState({ warning: "" });
        }
    }

    // ------------------------------------------------------------------------------------ tab audio sharing

    private audioSource(): AudioSource {
        const s = this.props.props.audioSource;
        return s === "tab" || s === "mixed" ? s : "microphone";
    }

    private hasLiveShare(): boolean {
        return Boolean(this.displayStream && this.displayStream.getAudioTracks().some((t) => t.readyState === "live"));
    }

    private openShare(stream: MediaStream): void {
        this.displayStream = stream;
        const label = shareLabel(stream);
        // The user can end the share from the browser's own "Stop sharing" bar at any time.
        stream.getTracks().forEach((t) => t.addEventListener("ended", this.onShareEnded));
        this.setState({ sharing: label });
    }

    private onShareEnded = (): void => {
        if (!this.displayStream || this.hasLiveShare()) {
            return;
        }
        if (this.state.status === "recording") {
            void this.stopRecording();
        }
        this.closeShare();
    };

    private closeShare = (): void => {
        const stream = this.displayStream;
        this.displayStream = undefined;
        if (stream) {
            stream.getTracks().forEach((t) => {
                t.removeEventListener("ended", this.onShareEnded);
                t.stop();
            });
        }
        if (!this.unmounted) {
            this.setState({ sharing: "" });
        }
    };

    // --------------------------------------------------------------------------------------- push-to-talk

    private onKeyDown = (e: KeyboardEvent): void => {
        const ptt = this.props.props.pushToTalk;
        if (!ptt || !ptt.enabled || e.code !== (ptt.key || "Space") || e.repeat) {
            return;
        }
        if (isEditableTarget(e.target as HTMLElement) || this.isDesigner()) {
            return;
        }
        e.preventDefault();
        if (this.state.status !== "recording" && !this.isBusy() && !this.starting) {
            this.pushToTalkActive = true;
            void this.startRecording();
        }
    };

    private onKeyUp = (e: KeyboardEvent): void => {
        const ptt = this.props.props.pushToTalk;
        if (!this.pushToTalkActive || !ptt || e.code !== (ptt.key || "Space")) {
            return;
        }
        e.preventDefault();
        this.pushToTalkActive = false;
        void this.stopRecording();
    };

    /** Releasing the key while another window has focus never delivers keyup; treat blur as release. */
    private onWindowBlur = (): void => {
        if (this.pushToTalkActive) {
            this.pushToTalkActive = false;
            void this.stopRecording();
        }
    };

    // ------------------------------------------------------------------------------------------- helpers

    private getApi(): RecorderApi {
        if (!this.api) {
            const client: any = this.props.store.clientStore;
            this.api = new RecorderApi(String(client?.sessionId || ""), String(client?.projectName || ""));
        }
        return this.api;
    }

    private isDesigner(): boolean {
        const client: any = this.props.store.clientStore;
        return Boolean(client && client.isDesigner);
    }

    private isBusy(): boolean {
        return this.state.status === "transcribing" || this.state.status === "submitting";
    }

    private setStatus(status: Status): void {
        if (this.unmounted) {
            return;
        }
        this.setState({ status });
        this.writeProp("status", status);
    }

    private setTranscript(transcript: string, followTail = false): void {
        if (this.unmounted) {
            return;
        }
        this.followTail = this.followTail || followTail;
        this.setState({ transcript });
        this.writeProp("transcript", transcript);
    }

    private writeProp(path: string, value: unknown): void {
        try {
            this.props.store.props.write(path, value);
        } catch (e) {
            logger.warn(() => `Unable to write props.${path}: ${errorMessage(e)}`);
        }
    }

    private fail(stage: string, message: string, nextStatus: Status = "error"): void {
        logger.error(() => `[${stage}] ${message}`);
        this.stopTimer();
        if (nextStatus === "error") {
            this.capture?.abort();
            this.capture = undefined;
        }
        if (this.unmounted) {
            return;
        }
        this.setState({ error: message });
        this.writeProp("error", message);
        this.setStatus(nextStatus);
        this.props.componentEvents.fireComponentEvent("onError", { message, stage });
    }

    private startTimer(): void {
        this.startedAt = Date.now();
        this.timer = window.setInterval(() => this.setState({ elapsed: (Date.now() - this.startedAt) / 1000 }), 250);
    }

    private stopTimer(): void {
        if (this.timer !== undefined) {
            window.clearInterval(this.timer);
            this.timer = undefined;
        }
    }

    private onTranscriptEdited = (e: React.ChangeEvent<HTMLTextAreaElement>): void => {
        this.setTranscript(e.target.value);
    };

    /** Keeps the coloured backdrop aligned with the (transparent-text) textarea on top of it. */
    private syncScroll = (): void => {
        const ta = this.textareaRef.current;
        const bd = this.backdropRef.current;
        if (ta && bd) {
            bd.scrollTop = ta.scrollTop;
            bd.scrollLeft = ta.scrollLeft;
        }
    };

    private statusText(): string {
        const ptt = this.props.props.pushToTalk;
        const hint = ptt && ptt.enabled ? ` - or hold ${keyLabel(ptt.key)}` : "";
        const source = this.audioSource();
        const target = source === "tab" ? "the shared tab" : source === "mixed" ? "your microphone and the shared tab" : "";
        switch (this.state.status) {
            case "recording":
                return `Recording ${formatDuration(this.state.elapsed)}${ptt && ptt.enabled ? ` - release ${keyLabel(ptt.key)} or click to stop` : " - click to stop"}`;
            case "transcribing":
                return "Transcribing on the gateway...";
            case "ready":
                return "Edit if needed, record more, or submit (only text below the last divider is saved).";
            case "submitting":
                return "Saving...";
            case "submitted":
                return "Saved. Record again to keep adding to the log.";
            case "error":
                return "Something went wrong.";
            default:
                if (needsDisplayAudio(source) && !this.state.sharing) {
                    return `Click to choose a tab to record${hint}. Turn on "Also share tab audio" in the picker.`;
                }
                return target ? `Click to record ${target}${hint}.` : `Click the microphone to record${hint}.`;
        }
    }

    /** Same characters as the textarea, wrapped in coloured spans. */
    private renderHighlighted(text: string): React.ReactNode[] {
        const lines = text.split("\n");
        const out: React.ReactNode[] = lines.map((line, i) => (
            <React.Fragment key={i}>
                {parseLine(line).map((part, j) => {
                    if (part.kind === "text") {
                        return part.text;
                    }
                    const cls = part.kind === "label"
                        ? `vt-tx vt-tx--label vt-tx--${part.source}`
                        : `vt-tx vt-tx--${part.kind}`;
                    return <span key={j} className={cls}>{part.text}</span>;
                })}
                {i < lines.length - 1 ? "\n" : null}
            </React.Fragment>
        ));
        out.push("​"); // a trailing newline in a textarea still occupies a line
        return out;
    }

    render(): React.ReactNode {
        const { enabled, placeholder, showSummary } = this.props.props;
        const { status, transcript, error, summary, confirmClear } = this.state;
        const designer = this.isDesigner();
        const recording = status === "recording";
        const busy = this.isBusy();
        const editable = !recording && !busy && !this.starting;
        const colors = { ...DEFAULT_COLORS, ...(this.props.props.sourceColors || {}) };
        const colorVars = {
            "--vt-color-mic": colors.microphone,
            "--vt-color-system": colors.system,
            "--vt-color-timestamp": colors.timestamp,
        } as React.CSSProperties;
        return (
            <div {...this.props.emit({ classes: ["vt-recorder", `vt-recorder--${status}`] })}>
                <div className="vt-recorder__toolbar">
                    <button
                        type="button"
                        className={`vt-recorder__mic${recording ? " vt-recorder__mic--live" : ""}`}
                        aria-pressed={recording}
                        aria-label={recording ? "Stop recording" : "Start recording"}
                        disabled={!enabled || busy || designer}
                        onClick={recording ? this.stopRecording : this.startRecording}
                    >
                        <MicIcon />
                    </button>
                    <span className="vt-recorder__status">
                        {designer ? "Recording is available in a running Perspective session." : this.statusText()}
                    </span>
                </div>
                {this.state.sharing ? (
                    <div className="vt-recorder__share">
                        <span className="vt-recorder__share-label" title={this.state.sharing}>Sharing audio: {this.state.sharing}</span>
                        <button type="button" className="vt-recorder__link" onClick={this.closeShare} disabled={recording || this.starting}>
                            Stop sharing
                        </button>
                    </div>
                ) : null}
                <div className={`vt-recorder__editor${editable ? "" : " vt-recorder__editor--readonly"}`} style={colorVars}>
                    <div className="vt-recorder__backdrop" ref={this.backdropRef} aria-hidden="true">
                        {this.renderHighlighted(transcript)}
                    </div>
                    <textarea
                        ref={this.textareaRef}
                        className="vt-recorder__transcript"
                        value={transcript}
                        placeholder={placeholder}
                        readOnly={!editable}
                        spellCheck={false}
                        onChange={this.onTranscriptEdited}
                        onScroll={this.syncScroll}
                    />
                </div>
                <div className="vt-recorder__actions">
                    <button
                        type="button"
                        className={`vt-recorder__button${confirmClear ? " vt-recorder__button--danger" : ""}`}
                        onClick={this.clear}
                        disabled={busy || (!transcript && !recording)}
                        title="Empty the transcript box"
                    >
                        {confirmClear ? "Confirm clear" : "Clear"}
                    </button>
                    <button
                        type="button"
                        className="vt-recorder__button vt-recorder__button--primary"
                        onClick={this.submit}
                        disabled={!this.canSubmit()}
                    >
                        Submit
                    </button>
                </div>
                {showSummary && summary ? (
                    <div className="vt-recorder__summary">
                        <div className="vt-recorder__summary-title">Summary</div>
                        <div>{summary}</div>
                    </div>
                ) : null}
                {this.state.warning ? <div className="vt-recorder__warning" role="status">{this.state.warning}</div> : null}
                {error ? <div className="vt-recorder__error" role="alert">{error}</div> : null}
            </div>
        );
    }
}

function MicIcon(): React.ReactElement {
    return (
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true" focusable="false">
            <path
                fill="currentColor"
                d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"
            />
        </svg>
    );
}

/** "Browser tab" / "Window" / "Entire screen" instead of Chrome's internal web-contents-media-stream:// id. */
function shareLabel(stream: MediaStream): string {
    const video = stream.getVideoTracks()[0];
    const surface = video ? (video.getSettings() as any).displaySurface : undefined;
    if (surface === "browser") {
        return "Browser tab";
    }
    if (surface === "window") {
        return video.label ? `Window - ${video.label}` : "Window";
    }
    if (surface === "monitor") {
        return "Entire screen";
    }
    const raw = (video && video.label) || "";
    return raw && !raw.includes("://") ? raw : "Shared tab or screen";
}

function keyLabel(code: string): string {
    if (!code) {
        return "Space";
    }
    return code.replace(/^Key/, "").replace(/^Digit/, "");
}

function errorMessage(e: unknown, deniedMessage = "Microphone permission was denied."): string {
    if (e instanceof DOMException && e.name === "NotAllowedError") {
        return deniedMessage;
    }
    return e instanceof Error ? e.message : String(e);
}

/** Registered with Perspective's client-side ComponentRegistry in index.ts. */
export class VoiceRecorderMeta implements ComponentMeta {
    getComponentType(): string {
        return COMPONENT_TYPE;
    }

    getDefaultSize(): SizeObject {
        return { width: 360, height: 220 };
    }

    getViewComponent(): PComponent {
        return VoiceRecorder as unknown as PComponent;
    }

    getPropsReducer(tree: PropertyTree): VoiceRecorderProps {
        return {
            enabled: tree.readBoolean("enabled", true),
            language: tree.readString("language", ""),
            audioSource: tree.readString("audioSource", "microphone") as AudioSource,
            showTimestamps: tree.readBoolean("showTimestamps", true),
            sourceColors: {
                microphone: tree.readString("sourceColors.microphone", DEFAULT_COLORS.microphone),
                system: tree.readString("sourceColors.system", DEFAULT_COLORS.system),
                timestamp: tree.readString("sourceColors.timestamp", DEFAULT_COLORS.timestamp),
            },
            chunkIntervalMs: Number(tree.read("chunkIntervalMs", 0)) || 0,
            pushToTalk: {
                enabled: tree.readBoolean("pushToTalk.enabled", true),
                key: tree.readString("pushToTalk.key", "Space"),
            },
            autoSubmit: tree.readBoolean("autoSubmit", false),
            showSummary: tree.readBoolean("showSummary", true),
            placeholder: tree.readString("placeholder", ""),
            transcript: tree.readString("transcript", ""),
            status: tree.readString("status", "idle") as Status,
        };
    }
}
