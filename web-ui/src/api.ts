/**
 * Fetch helpers for the settings page. Requests ride on the admin's gateway web-UI session cookie; the
 * gateway's WRITE permission check also requires the session's CSRF token on mutating requests, which the
 * module exposes at GET /csrf (a GET can't be abused cross-site because the attacker can't read the reply).
 */

export const API_BASE = "/data/voicetranscription/api/v1";

export type SecretMode = "none" | "embedded" | "referenced";

export interface SecretState {
    configured: boolean;
    mode: SecretMode;
    providerName?: string;
    secretName?: string;
}

/** What the page sends for a secret field. */
export type SecretUpdate =
    | { mode: "keep" }
    | { mode: "none" }
    | { mode: "embedded"; value: string }
    | { mode: "referenced"; providerName: string; secretName: string };

export interface Settings {
    mongo: {
        /** Name of a MongoDB Connector connection (Connections > Service Connectors). */
        connector: string;
        collection: string;
    };
    whisper: {
        modelPath: string;
        defaultLanguage: string;
        autoDetect: boolean;
        threads: number;
        chunkIntervalMs: number;
        retentionDays: number;
        maxRecordingSeconds: number;
        maxConcurrentTranscriptions: number;
        modelSha256: string;
        noiseFilter: "off" | "standard" | "strict";
        modelsDir: string;
    };
    llm: {
        enabled: boolean;
        provider: string;
        endpointUrl: string;
        model: string;
        systemPrompt: string;
        apiKey: SecretState;
        timeoutSeconds: number;
        temperature: number;
    };
    providers?: Array<{ id: string; label: string; defaultEndpoint: string }>;
}

export interface Status {
    whisper: { ready: boolean; status: string; modelPath: string; multilingual: boolean };
    mongo: { configured: boolean; connected: boolean; error?: string; connector: string; database?: string; collection: string };
    llm: { enabled: boolean; provider: string; model: string };
    openRecordings: number;
}

export interface ModelFile {
    name: string;
    sizeBytes: number;
    multilingual: boolean;
}

export interface CatalogModel {
    id: string;
    file: string;
    approxMb: number;
    multilingual: boolean;
    note: string;
    installed: boolean;
    downloadState?: "downloading" | "done" | "failed";
    bytesDone?: number;
    bytesTotal?: number;
    error?: string;
    sha256?: string;
}

export interface ModelList {
    dir: string;
    resolvedDir: string;
    exists: boolean;
    files: ModelFile[];
    catalog: CatalogModel[];
}

export interface MongoConnector {
    name: string;
    description: string;
    database: string;
}

export interface SecretProvider {
    name: string;
    secrets: string[];
    listError?: string;
}

export interface Transcript {
    id: string;
    timestamp: string;
    transcript: string;
    language: string;
    summary: string;
    user: { username: string; roles: string[] };
    device: { clientAddress: string; userAgent: string; sessionType: string };
}

let csrf: { header: string; token: string } | undefined;

async function csrfHeaders(): Promise<Record<string, string>> {
    if (!csrf) {
        csrf = await request<{ header: string; token: string }>("GET", "/csrf");
    }
    return csrf.token ? { [csrf.header || "X-CSRF-Token"]: csrf.token } : {};
}

export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (method !== "GET") {
        Object.assign(headers, await csrfHeaders());
    }
    if (body !== undefined) {
        headers["Content-Type"] = "application/json";
    }
    const res = await fetch(API_BASE + path, {
        method,
        credentials: "same-origin",
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await res.text();
    let json: any = {};
    try {
        json = text ? JSON.parse(text) : {};
    } catch {
        json = { error: text || res.statusText };
    }
    if (!res.ok) {
        if (res.status === 403 && method !== "GET") {
            csrf = undefined; // token may have rotated with a re-login; refetch next time
        }
        throw new Error(json.error || `HTTP ${res.status}`);
    }
    return json as T;
}
