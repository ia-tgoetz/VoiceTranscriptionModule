# OfflineVoiceTranscription — Ignition 8.3 module

Record speech in Perspective, transcribe it **on the gateway** with whisper.cpp (no cloud STT), optionally
summarize it with an OpenAI-compatible LLM, and store it in MongoDB through IA's **MongoDB Connector**.

* Module id `com.wargoetz.voicetranscription`, requires Ignition **8.3.0+**, Perspective, MongoDB Connector.
* Learning guide and every design decision: **[GUIDED_TOUR.md](GUIDED_TOUR.md)**.

## Quick start (local test gateway)

```bash
./docker/fetch-model.sh                       # Whisper ggml-tiny model -> docker/models/
./gradlew build                               # -> build/OfflineVoiceTranscription.unsigned.modl
                                              #    + signed build/OfflineVoiceTranscription.modl if sign.props exists
docker compose -f docker/docker-compose.yml up -d
# after each rebuild (re-installs through the gateway's module API, restarts, waits for ACTIVE):
python ../../shared/module-dev/install-modl.py build/OfflineVoiceTranscription.modl --url http://localhost:8090 --restart vt-gateway
# Gateway http://localhost:8090 (admin/password); demo page http://localhost:8090/data/perspective/client/VoiceDemo

cd tests && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt   # (bin/ on Linux/macOS)
.venv/Scripts/python -m pytest                # provisions connector + settings, then tests API, MongoDB, browser
```

Requirements: JDK 17, Node 18+ (npm on PATH), Docker. Browser tests use a local Edge/Chrome via Playwright
(`PW_BROWSER_CHANNEL`, default `msedge`).

## Install on a real gateway

1. Install the MongoDB Connector module; create a connector under *Connections > Service Connectors*. The
   database must already exist (the connector refuses to connect otherwise) — create it and a
   `timestamp` index on the target collection.
2. Copy a Whisper ggml model to `<ignition>/data/voicetranscription/models/` (default `ggml-base.bin`).
3. Install the **signed** `build/OfflineVoiceTranscription.modl` (signed with the WARGoetz certificate, the
   same one as ArchitectureBuilderReactFlow; the gateway asks once to trust it) and restart the gateway.
4. *Services > Voice Transcription > Settings*: pick the connector + collection, check the model path,
   optionally enable the LLM and store its API key (embedded secret or Secret Provider reference).
5. Drop **Voice Recorder** (palette category *Input*) on a Perspective view. Serve Perspective over HTTPS —
   browsers only allow microphone/tab capture in secure contexts.
6. The transcript box is a running log: recordings append, Submit saves only what's new since the last
   "Submitted" divider, and only **Clear** (confirmed) or writing `props.transcript` empties it. Labels and
   timestamps are colour-coded (`sourceColors`).
7. Background noise: *Services > Voice Transcription > Background noise filter* (Standard by default, Strict for
   loud plant floors) stops fans, hum and clicks from being transcribed as words.
8. To transcribe audio playing on the PC (a video, a meeting tab), set the component's `audioSource` to
   `tab` (or `mixed` for mic + tab — the transcript then labels each line `[Microphone]` or `[System audio]`;
   lines carry `[mm:ss]` timestamps unless `showTimestamps` is off). The user picks the tab in the browser's share dialog and must turn on
   **"Also share tab audio"**. Chrome/Edge desktop only. The demo page has a Source dropdown
   (`/data/perspective/client/VoiceDemo/source/tab` presets it).

## Commands

| Command | Purpose |
|---|---|
| `./gradlew build` | Build the `.modl` (runs Java unit tests and both webpack builds) |
| `./gradlew clean build` | Use after removing a dependency (the modl staging dir isn't cleaned otherwise) |
| `./gradlew securityAudit` | `npm audit` (high+) on both web packages + OWASP dependency-check (CVSS ≥ 7) |
| `cd web && npm test` | TypeScript unit tests (Node ≥ 22.6) |
| `cd tests && pytest` | Live-gateway suite; env `GATEWAY_URL`, `GATEWAY_USERNAME`, `GATEWAY_PASSWORD` |
