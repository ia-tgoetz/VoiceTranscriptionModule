import React, { useCallback, useEffect, useState } from "react";
import { MongoConnector, SecretProvider, SecretState, SecretUpdate, Settings, Status, Transcript, request } from "./api";
import { CSS } from "./styles";

const LANGUAGES: Array<[string, string]> = [
    ["en", "English"], ["de", "German"], ["fr", "French"], ["es", "Spanish"], ["it", "Italian"],
    ["pt", "Portuguese"], ["nl", "Dutch"], ["sv", "Swedish"], ["pl", "Polish"], ["tr", "Turkish"],
    ["ru", "Russian"], ["ja", "Japanese"], ["zh", "Chinese"], ["ko", "Korean"], ["ar", "Arabic"], ["hi", "Hindi"],
];

const PROMPT_VARIABLES = ["username", "roles", "language", "timestamp", "clientAddress", "sessionType", "userAgent"];

type Section = keyof Pick<Settings, "mongo" | "whisper" | "llm">;

/**
 * Services > Voice Transcription > Settings. Rendered by the 8.3 gateway web UI (React 18 provided by the
 * gateway); talks only to this module's /data/voicetranscription/api/v1 routes.
 */
export default function SettingsPage(): React.ReactElement {
    const [settings, setSettings] = useState<Settings | null>(null);
    const [status, setStatus] = useState<Status | null>(null);
    const [providers, setProviders] = useState<SecretProvider[]>([]);
    const [connectors, setConnectors] = useState<MongoConnector[]>([]);
    const [collections, setCollections] = useState<string[]>([]);
    const [models, setModels] = useState<string[]>([]);
    const [recent, setRecent] = useState<Transcript[]>([]);
    const [apiKeyUpdate, setApiKeyUpdate] = useState<SecretUpdate>({ mode: "keep" });
    const [message, setMessage] = useState<{ kind: "ok" | "info" | "error"; text: string } | null>(null);
    const [saving, setSaving] = useState(false);
    const [loadingModels, setLoadingModels] = useState(false);
    /** The gateway only sends the saved API key to this endpoint (see POST /llm/models). */
    const [savedEndpoint, setSavedEndpoint] = useState("");

    const loadStatus = useCallback(() => {
        request<Status>("GET", "/status").then(setStatus).catch((e) => setMessage({ kind: "error", text: e.message }));
        request<{ transcripts: Transcript[] }>("GET", "/transcripts?limit=10")
            .then((r) => setRecent(r.transcripts))
            .catch(() => setRecent([]));
    }, []);

    useEffect(() => {
        request<Settings>("GET", "/config")
            .then((s) => {
                setSettings(s);
                setSavedEndpoint(s.llm.endpointUrl);
            })
            .catch((e) => setMessage({ kind: "error", text: e.message }));
        request<SecretProvider[]>("GET", "/secrets/providers").then(setProviders).catch(() => setProviders([]));
        request<MongoConnector[]>("GET", "/mongo/connectors").then(setConnectors).catch(() => setConnectors([]));
        loadStatus();
    }, [loadStatus]);

    if (!settings) {
        return (
            <div className="vt-page">
                <style>{CSS}</style>
                {message ? <div className="vt-msg vt-msg--error">{message.text}</div> : <p>Loading...</p>}
            </div>
        );
    }

    function update<S extends Section, K extends keyof Settings[S]>(section: S, key: K, value: Settings[S][K]) {
        setSettings((prev) => (prev ? { ...prev, [section]: { ...prev[section], [key]: value } } : prev));
    }

    const save = async () => {
        setSaving(true);
        setMessage(null);
        try {
            const body = {
                mongo: settings.mongo,
                whisper: settings.whisper,
                llm: { ...settings.llm, apiKey: apiKeyUpdate },
            };
            const saved = await request<Settings>("PUT", "/config", body);
            setSettings((prev) => ({ ...saved, providers: prev?.providers }));
            setSavedEndpoint(saved.llm.endpointUrl);
            setApiKeyUpdate({ mode: "keep" });
            setMessage({ kind: "ok", text: "Settings saved." });
            loadStatus();
        } catch (e) {
            setMessage({ kind: "error", text: (e as Error).message });
        } finally {
            setSaving(false);
        }
    };

    const browseCollections = async () => {
        try {
            const r = await request<{ collections: string[] }>(
                "GET",
                `/mongo/collections?connector=${encodeURIComponent(settings.mongo.connector)}`,
            );
            setCollections(r.collections);
            setMessage({ kind: "ok", text: `Found ${r.collections.length} collection(s) via '${settings.mongo.connector}'.` });
        } catch (e) {
            setMessage({ kind: "error", text: (e as Error).message });
        }
    };

    /** Lists the models of the provider/endpoint/key as currently entered in the form - no need to save first. */
    const loadModels = async (provider = settings.llm.provider, endpointUrl = settings.llm.endpointUrl) => {
        setLoadingModels(true);
        try {
            const r = await request<{ models: string[]; hidden: number; endpointUrl: string }>(
                "POST",
                "/llm/models",
                { provider, endpointUrl, apiKey: apiKeyUpdate },
            );
            setModels(r.models);
            const hidden = r.hidden ? ` (${r.hidden} embedding/speech/image model(s) hidden)` : "";
            setMessage({ kind: "ok", text: `Loaded ${r.models.length} chat model(s) from ${r.endpointUrl}${hidden}.` });
        } catch (e) {
            setModels([]);
            setMessage({ kind: "error", text: (e as Error).message });
        } finally {
            setLoadingModels(false);
        }
    };

    const changeProvider = (id: string) => {
        const preset = settings.providers?.find((p) => p.id === id);
        const endpointUrl = preset ? preset.defaultEndpoint : settings.llm.endpointUrl;
        setSettings((prev) => (prev ? { ...prev, llm: { ...prev.llm, provider: id, endpointUrl, model: "" } } : prev));
        setModels([]);
        // Load the list straight away when there is a key to use: a newly entered/referenced one, the saved one
        // (only valid for the saved endpoint), or none needed (custom/local endpoints such as Ollama).
        const haveKey = apiKeyUpdate.mode === "referenced"
            || (apiKeyUpdate.mode === "embedded" && apiKeyUpdate.value !== "")
            || (apiKeyUpdate.mode === "keep" && endpointUrl === savedEndpoint);
        if (haveKey || id === "custom") {
            void loadModels(id, endpointUrl);
        } else {
            setMessage({ kind: "info", text: `Enter the ${preset?.label ?? id} API key below, then click Load models.` });
        }
    };

    const m = settings.mongo;
    const selectedConnector = connectors.find((c) => c.name === m.connector);
    const w = settings.whisper;
    const l = settings.llm;

    return (
        <div className="vt-page">
            <style>{CSS}</style>
            <h1>Offline Voice Transcription</h1>

            <StatusCard status={status} onRefresh={loadStatus} />

            <section className="vt-card">
                <h2>MongoDB</h2>
                <p className="vt-hint">
                    Transcripts are written through the <b>MongoDB Connector</b> module. Hosts, database, credentials and TLS
                    belong to the connector connection - create or edit it under{" "}
                    <a href="/app/connections/services">Connections &gt; Service Connectors</a>.
                </p>
                <div className="vt-row">
                    <Field label="Connector" hint={selectedConnector ? `Database: ${selectedConnector.database}` : "Choose an existing MongoDB connector connection."}>
                        <select
                            value={m.connector}
                            onChange={(e) => {
                                update("mongo", "connector", e.target.value);
                                setCollections([]);
                            }}
                        >
                            <option value="">-- choose --</option>
                            {connectors.map((c) => <option key={c.name} value={c.name}>{c.name}{c.description ? ` - ${c.description}` : ""}</option>)}
                            {m.connector && !selectedConnector ? <option value={m.connector}>{m.connector} (missing)</option> : null}
                        </select>
                    </Field>
                    <Field label="Collection" hint="Pick an existing collection or type a new name; MongoDB creates it on first insert.">
                        <div className="vt-inline">
                            <input list="vt-collections" value={m.collection} onChange={(e) => update("mongo", "collection", e.target.value)} />
                            <datalist id="vt-collections">
                                {collections.map((c) => <option key={c} value={c} />)}
                            </datalist>
                            <button type="button" onClick={browseCollections} disabled={!m.connector}>Browse</button>
                        </div>
                    </Field>
                </div>
            </section>

            <section className="vt-card">
                <h2>Whisper (local speech-to-text)</h2>
                <Field label="Model path" hint="ggml model file on the gateway host. Relative paths resolve against the gateway data directory.">
                    <input value={w.modelPath} onChange={(e) => update("whisper", "modelPath", e.target.value)} />
                </Field>
                <Field label="Model SHA-256 (recommended)" hint="If set, the gateway refuses to load a model file whose hash differs. whisper.cpp can crash the gateway on malformed model files.">
                    <input value={w.modelSha256} onChange={(e) => update("whisper", "modelSha256", e.target.value.trim().toLowerCase())} placeholder="64 hex characters" />
                </Field>
                <div className="vt-row">
                    <Field label="Default language">
                        <select value={w.defaultLanguage} onChange={(e) => update("whisper", "defaultLanguage", e.target.value)}>
                            {LANGUAGES.map(([code, name]) => <option key={code} value={code}>{name} ({code})</option>)}
                        </select>
                    </Field>
                    <Field label="Auto-detect language" hint="Requires a multilingual model (not *.en).">
                        <label className="vt-check">
                            <input type="checkbox" checked={w.autoDetect} onChange={(e) => update("whisper", "autoDetect", e.target.checked)} /> Detect per recording
                        </label>
                    </Field>
                    <Field label="Background noise filter" hint="Stops fans, hum and clicks from being transcribed as words (Whisper invents text like 'Thank you.' on noise). Strict also drops very short or quiet phrases.">
                        <select value={w.noiseFilter} onChange={(e) => update("whisper", "noiseFilter", e.target.value as Settings["whisper"]["noiseFilter"])}>
                            <option value="standard">Standard (recommended)</option>
                            <option value="strict">Strict (noisy plant floor)</option>
                            <option value="off">Off</option>
                        </select>
                    </Field>
                </div>
                <div className="vt-row">
                    <NumberField label="Chunk interval (ms)" hint="How often the browser uploads audio for a partial transcript." value={w.chunkIntervalMs} min={500} max={30000} onChange={(v) => update("whisper", "chunkIntervalMs", v)} />
                    <NumberField label="Max recording (s)" value={w.maxRecordingSeconds} min={5} max={1800} onChange={(v) => update("whisper", "maxRecordingSeconds", v)} />
                    <NumberField label="Retention (days)" hint="Delete stored transcripts older than this. 0 keeps them forever." value={w.retentionDays} min={0} max={3650} onChange={(v) => update("whisper", "retentionDays", v)} />
                </div>
                <div className="vt-row">
                    <NumberField label="Threads per transcription" value={w.threads} min={1} max={32} onChange={(v) => update("whisper", "threads", v)} />
                    <NumberField label="Concurrent transcriptions" hint="Decoder states kept in memory; each simultaneous speaker uses one." value={w.maxConcurrentTranscriptions} min={1} max={16} onChange={(v) => update("whisper", "maxConcurrentTranscriptions", v)} />
                </div>
            </section>

            <section className="vt-card">
                <h2>LLM summary (optional)</h2>
                <label className="vt-check">
                    <input type="checkbox" checked={l.enabled} onChange={(e) => update("llm", "enabled", e.target.checked)} /> Summarize each transcript
                </label>
                <p className="vt-hint">This is the module's only outbound network call. Leave it off for a fully air-gapped install.</p>
                <div className="vt-row">
                    <Field label="Provider">
                        <select value={l.provider} onChange={(e) => changeProvider(e.target.value)}>
                            {(settings.providers || []).map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
                        </select>
                    </Field>
                    <Field label="Endpoint URL" hint="OpenAI-compatible base URL (…/v1).">
                        <input value={l.endpointUrl} onChange={(e) => update("llm", "endpointUrl", e.target.value)} />
                    </Field>
                </div>
                <Field label="Model" hint="Loaded from the provider when you pick it, or with Load models after entering the endpoint and key (no need to save first).">
                    <div className="vt-inline">
                        {models.length > 0 ? (
                            <select value={l.model} onChange={(e) => update("llm", "model", e.target.value)}>
                                <option value="">-- choose --</option>
                                {models.map((id) => <option key={id} value={id}>{id}</option>)}
                                {l.model && !models.includes(l.model) ? <option value={l.model}>{l.model} (not listed)</option> : null}
                            </select>
                        ) : (
                            <input value={l.model} onChange={(e) => update("llm", "model", e.target.value)} placeholder="gpt-4o-mini" />
                        )}
                        <button type="button" onClick={() => loadModels()} disabled={loadingModels}>
                            {loadingModels ? "Loading..." : "Load models"}
                        </button>
                    </div>
                </Field>
                <SecretEditor label="API key" state={l.apiKey} update={apiKeyUpdate} onChange={setApiKeyUpdate} providers={providers} />
                <Field label="System prompt" hint={`Variables: ${PROMPT_VARIABLES.map((v) => `{{${v}}}`).join(" ")}`}>
                    <textarea rows={6} value={l.systemPrompt} onChange={(e) => update("llm", "systemPrompt", e.target.value)} />
                </Field>
                <div className="vt-row">
                    <NumberField label="Timeout (s)" value={l.timeoutSeconds} min={5} max={600} onChange={(v) => update("llm", "timeoutSeconds", v)} />
                    <NumberField label="Temperature" value={l.temperature} min={0} max={2} step={0.1} onChange={(v) => update("llm", "temperature", v)} />
                </div>
            </section>

            <div className="vt-actions">
                {message ? <div className={`vt-msg vt-msg--${message.kind}`}>{message.text}</div> : <span />}
                <button type="button" className="vt-primary" onClick={save} disabled={saving}>{saving ? "Saving..." : "Save settings"}</button>
            </div>

            <section className="vt-card">
                <h2>Recent transcripts</h2>
                {recent.length === 0 ? (
                    <p className="vt-hint">None yet (or MongoDB is not reachable).</p>
                ) : (
                    <table className="vt-table">
                        <thead>
                            <tr><th>Time (UTC)</th><th>User</th><th>Lang</th><th>Transcript</th><th>Summary</th><th>Client</th></tr>
                        </thead>
                        <tbody>
                            {recent.map((t) => (
                                <tr key={t.id}>
                                    <td>{t.timestamp}</td>
                                    <td>{t.user.username}</td>
                                    <td>{t.language}</td>
                                    <td>{t.transcript}</td>
                                    <td>{t.summary}</td>
                                    <td>{t.device.sessionType}<br />{t.device.clientAddress}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </section>
        </div>
    );
}

function StatusCard({ status, onRefresh }: { status: Status | null; onRefresh: () => void }) {
    return (
        <section className="vt-card vt-status">
            <h2>Status <button type="button" onClick={onRefresh}>Refresh</button></h2>
            {!status ? <p>...</p> : (
                <ul>
                    <li><Dot ok={status.whisper.ready} /> Whisper: {status.whisper.status} <span className="vt-hint">({status.whisper.modelPath}{status.whisper.ready ? (status.whisper.multilingual ? ", multilingual" : ", English-only") : ""})</span></li>
                    <li><Dot ok={status.mongo.connected} /> MongoDB: {status.mongo.connected ? `connector '${status.mongo.connector}' -> ${status.mongo.database ?? "?"}.${status.mongo.collection}` : status.mongo.error || "no connector selected"}</li>
                    <li><Dot ok={status.llm.enabled} neutral={!status.llm.enabled} /> LLM: {status.llm.enabled ? `${status.llm.provider} / ${status.llm.model}` : "disabled"}</li>
                    <li>Open recordings: {status.openRecordings}</li>
                </ul>
            )}
        </section>
    );
}

function Dot({ ok, neutral }: { ok: boolean; neutral?: boolean }) {
    return <span className={`vt-dot ${neutral ? "vt-dot--neutral" : ok ? "vt-dot--ok" : "vt-dot--bad"}`} />;
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
    return (
        <div className="vt-field">
            <label>{label}</label>
            {children}
            {hint ? <div className="vt-hint">{hint}</div> : null}
        </div>
    );
}

function NumberField(props: { label: string; hint?: string; value: number; min: number; max: number; step?: number; onChange: (v: number) => void }) {
    return (
        <Field label={props.label} hint={props.hint}>
            <input
                type="number"
                value={props.value}
                min={props.min}
                max={props.max}
                step={props.step || 1}
                onChange={(e) => props.onChange(e.target.value === "" ? 0 : Number(e.target.value))}
            />
        </Field>
    );
}

/**
 * Secret field backed by the Ignition 8.3 Secrets API. The current value is never shown - the gateway
 * only reports whether it is embedded (encrypted in the config) or referenced (lives in a Secret Provider).
 */
function SecretEditor(props: {
    label: string;
    state: SecretState;
    update: SecretUpdate;
    onChange: (u: SecretUpdate) => void;
    providers: SecretProvider[];
}) {
    const { state, update, onChange, providers } = props;
    const current = !state.configured
        ? "not set"
        : state.mode === "referenced"
            ? `referenced: ${state.providerName} / ${state.secretName}`
            : "embedded (encrypted by the gateway)";
    const mode = update.mode;
    const provider = update.mode === "referenced" ? providers.find((p) => p.name === update.providerName) : undefined;
    return (
        <div className="vt-field vt-secret">
            <label>{props.label} <span className="vt-hint">- currently {current}</span></label>
            <div className="vt-inline">
                <select
                    value={mode}
                    onChange={(e) => {
                        const next = e.target.value;
                        if (next === "embedded") onChange({ mode: "embedded", value: "" });
                        else if (next === "referenced") onChange({ mode: "referenced", providerName: providers[0]?.name || "", secretName: "" });
                        else onChange({ mode: next as "keep" | "none" });
                    }}
                >
                    <option value="keep">Keep current</option>
                    <option value="embedded">Enter a new value</option>
                    <option value="referenced" disabled={providers.length === 0}>Use a Secret Provider</option>
                    <option value="none">Remove</option>
                </select>
                {update.mode === "embedded" ? (
                    <input type="password" autoComplete="new-password" value={update.value} onChange={(e) => onChange({ mode: "embedded", value: e.target.value })} />
                ) : null}
                {update.mode === "referenced" ? (
                    <>
                        <select value={update.providerName} onChange={(e) => onChange({ mode: "referenced", providerName: e.target.value, secretName: "" })}>
                            {providers.map((p) => <option key={p.name} value={p.name}>{p.name}</option>)}
                        </select>
                        <input
                            list={`vt-secrets-${props.label}`}
                            placeholder="secret name"
                            value={update.secretName}
                            onChange={(e) => onChange({ mode: "referenced", providerName: update.providerName, secretName: e.target.value })}
                        />
                        <datalist id={`vt-secrets-${props.label}`}>
                            {(provider?.secrets || []).map((s) => <option key={s} value={s} />)}
                        </datalist>
                    </>
                ) : null}
            </div>
        </div>
    );
}
