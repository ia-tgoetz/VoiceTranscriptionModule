# Guided Tour — OfflineVoiceTranscription (Ignition 8.3 SDK)

A walk through every architectural decision in this module, written as a learning guide to the Ignition
8.3 SDK. Read it top to bottom once; afterwards each section stands alone.

Everything here was verified against a running **Ignition 8.3.10** gateway (`docker/docker-compose.yml`)
and the **8.3.0 SDK jars** (inspected with `javap`, never guessed). Where the original build brief and
reality disagreed, §1 says so and explains what was done instead.

---

## 0. What the module does

```
 Browser (Perspective session)                     Ignition Gateway (JVM)                       Outside
 ─────────────────────────────                     ──────────────────────                       ───────
 Voice Recorder component                          OfflineVoiceTranscription module
  getUserMedia ─► MediaRecorder (webm/opus)        ┌──────────────────────────────────────┐
  every N ms: decode cumulative blob ─► 16 kHz     │ REST routes  /data/voicetranscription │
  PCM16 slice ──── HTTP POST (fetch) ────────────► │  recordings/:id/chunks  ─► RecordingManager
                ◄── partial transcript (JSON) ──── │      └─► WhisperService (whisper.cpp, JNI)
  stop ─► user edits transcript ─► submit ───────► │  recordings/:id/submit
                                                   │      ├─► LlmClient ── HTTPS (optional) ───────► OpenAI / Gemini / LiteLLM
                                                   │      └─► MongoService ─► MongoDB Connector ───► MongoDB
 Gateway web UI: Services > Voice Transcription    │  config, status, connectors, models ...       module (IA)
  React page (SystemJS module) ── fetch + CSRF ──► │  Settings = 8.3 config resource (+ Secrets API)
                                                   └──────────────────────────────────────┘
```

Speech-to-text is **fully local** (whisper.cpp on the gateway CPU, no network). The only optional egress is
the LLM summary. Raw audio is held in memory only and discarded after the final transcript.

Stored document (exactly the required schema; MongoDB adds `_id`):

```json
{ "timestamp": "2026-10-07T13:54:00Z", "transcript": "...", "language": "en", "summary": "...",
  "user":   { "username": "jdoe", "roles": ["Operator", "Administrator"] },
  "device": { "clientAddress": "192.168.1.50", "userAgent": "Mozilla/5.0...", "sessionType": "Perspective Web" } }
```

---

## 1. Where this build deviates from the brief — and why

| Brief said | What was built | Why |
|---|---|---|
| `id("io.ia.sdk.modl") version "0.3.2"` | `0.5.0` | 0.3.2 was never published to the Gradle plugin portal (0.3.0 → 0.4.x → 0.5.0). |
| `scopes.put("gateway","G")`, `"perspective" to "P"` | `projectScopes.putAll(":gateway" to "G", ":web" to "G", ...)` | The DSL field is `projectScopes`, keyed by **Gradle project path**. There is no `P` scope: Perspective runs *inside the Gateway*; the browser only renders. Component JS ships in a Gateway-scope jar. |
| "Directly bundle `mongodb-driver-sync`; manage `MongoClient` in startup/shutdown" | MongoDB access goes through IA's **MongoDB Connector** module | Changed at the project owner's request: reuse the connector's connections instead of opening a second one. See §7. |
| Audio routes at `/api/v1/REST/...` | `/data/voicetranscription/api/v1/...` | `mountRouteHandlers` routes always live under `/data/<module-id-or-alias>/`. "REST" read as a descriptor, not a path segment. |
| "Send MediaRecorder chunks" | MediaRecorder slices are decoded **in the browser** and sent as 16 kHz PCM16 | MediaRecorder slices are not independently decodable (only the first has the container header), and decoding Opus/AAC on the gateway would mean bundling native ffmpeg-class code into the JVM. Every browser already ships the decoder. See §11. |
| `language` reported for auto-detect | Whisper detects and decodes; a script/stop-word labeller sets the field | whisper-jni 1.7.1 exposes no `whisper_full_lang_id()`. Its native log proxy could be scraped, but it can crash the JVM under module classloaders. See §8. |
| Retention | Scheduled range delete on `timestamp` | `timestamp` must be an ISO string (schema), and MongoDB TTL indexes only work on BSON Dates. |
| Java 11/17 | Java 17 | Ignition 8.1.33+ and all of 8.3 are compiled for bytecode 61. |

---

## 2. Project layout and the Gradle build

```
Transcription Mongo Module/
├─ settings.gradle.kts         subprojects + IA Nexus repository
├─ build.gradle.kts            ignitionModule { } block, OWASP dependency-check, securityAudit
├─ gradle/libs.versions.toml   SDK + library versions (SDK pinned to 8.3.0 = our floor)
├─ common/                     module id, Perspective component descriptor + JSON schemas      (scope GD)
├─ gateway/                    GatewayHook, routes, Whisper, Mongo (via connector), LLM, settings (G)
├─ designer/                   DesignerHook: puts the component on the palette                  (D)
├─ web/                        Perspective component — React/TS → VoiceTranscription.js/.css    (G)
├─ web-ui/                     Gateway settings page — React/TS → SystemJS module               (G)
├─ module-init/                IA's MongoDB Connector .modl (runtime dependency + compile API)
├─ docker/                     disposable 8.3.10 + MongoDB + mock-LLM test stack, demo project
└─ tests/                      live-gateway pytest suite (API + real browser)
```

Key points in `build.gradle.kts`:

* **`requiredIgnitionVersion` is a floor, not a target.** We set `8.3.0` *and* compile against the 8.3.0 SDK
  (`libs.versions.toml`), so any newer-only API is a compile error here rather than a runtime
  `NoSuchMethodError` on a customer's 8.3.0 gateway.
* **`moduleDependencySpecs`** (8.3+) emits `<depends required="true">` in `module.xml`. We require
  Perspective (`GD`) and the MongoDB Connector (`G`). Required dependencies fault our module cleanly if
  missing *and* put the dependency's classes on our module's classpath.
* **`compileOnly` vs `modlImplementation`.** Ignition SDK jars are `compileOnly` (the gateway provides them).
  Anything that must ship inside the `.modl` is `modlImplementation` — here only `whisper-jni` and our two
  front-end jars. Run `unzip -l build/*.modl` after dependency changes; the plugin's staging directory is not
  cleaned between builds, so use `./gradlew clean build` when you *remove* a dependency.
* **The front ends are Gradle subprojects.** `web/` and `web-ui/` run `npm ci` + webpack via `Exec` tasks and
  add `build/generated-resources/` to their jar. Because their jars contain `mounted/...`, and the hook
  returns `getMountedResourceFolder() = "mounted"`, the gateway serves them at `/res/voicetranscription/...`.
* **UTF-8.** `options.encoding = "UTF-8"` for every `JavaCompile`. Without it, javac on Windows reads sources
  as windows-1252 and silently corrupts non-ASCII string literals (the language stop-words did exactly that).

Build: `./gradlew build` → `build/OfflineVoiceTranscription.unsigned.modl`.

---

## 3. The Gateway Hook lifecycle

`gateway/.../GatewayHook.java` extends `AbstractGatewayModuleHook`. In 8.3, **modules load only at gateway
startup** — install/upgrade means a restart — so the hook's lifecycle runs once per gateway run:

| Method | When | What we do |
|---|---|---|
| `setup(GatewayContext)` | Managers exist but aren't started | Build `ModuleServices` (settings handler, services) and register the web-UI nav entry. |
| `startup(LicenseState)` | Platform running | Start the settings handler, apply settings (asynchronously loads the Whisper model), register the Perspective component descriptor, start maintenance timers. |
| `mountRouteHandlers(RouteGroup)` | After `startup()` | Mount every REST route (§4). |
| `getMountPathAlias()` | — | `"voicetranscription"`: replaces the module id in `/res/<alias>/` and `/data/<alias>/`. |
| `getMountedResourceFolder()` | — | `"mounted"`: folder inside our jars served at `/res/voicetranscription/`. |
| `isFreeModule()` | — | `true`: no license required. |
| `shutdown()` | Gateway stop / module uninstall | Remove the component, stop timers, discard recordings, free native Whisper memory, stop the settings handler. |

Rule of thumb: register *with the platform* in `setup()`; *do work* in `startup()`; never block either one —
slow work (loading a 150 MB model) goes to a background executor.

---

## 4. REST routes with `mountRouteHandlers`

The 8.3 routing API (`com.inductiveautomation.ignition.gateway.dataroutes`):

```java
routes.newRoute("/api/v1/recordings/:id/chunks")   // :id -> req.getParameter("id")
    .method(HttpMethod.POST)
    .type(RouteGroup.TYPE_JSON)                    // return a Gson JsonElement; it is rendered for you
    .nocache()
    .accessControl(strategy)                       // who may call it (below)
    .handler((RequestContext req, HttpServletResponse res) -> ...)
    .mount();
```

`ApiRoutes.route(...)` wraps every handler so an `ApiException(status, message)` becomes
`res.setStatus(status)` + `{"error": message}`. Servlet types are **`jakarta.servlet`** in 8.3 (Servlet 6).

### Access control — three different callers

| Caller | Strategy | Notes |
|---|---|---|
| Admin in the gateway web UI | `AccessControlStrategy.or(PermissionType.getStrategies(READ / WRITE))` | `WRITE` strategies include a **CSRF check**: cookie-authenticated mutating requests must send `X-CSRF-Token`. A WRITE strategy can only be mounted on POST/PUT/DELETE (mount fails otherwise). |
| API token / scripted client | same `PermissionType` strategies | Header `X-Ignition-API-Token`; no CSRF. |
| Perspective component | `Routes.requireSession(EnumSet.of(Client, Designer))` OR'd with the permission strategies | `requireSession` checks the browser's HTTP session has a Perspective session bound under the `perspective-session` attribute. |

**CSRF token for a module page.** The built-in web UI keeps the token in its Redux store; a module-supplied
page can't reach that. The module exposes `GET /api/v1/csrf` → `WebUiSession.find(req).getCsrfToken()`
(a GET can't be abused cross-site — the attacker can't read the reply) and the page sends it on writes.

**Trusted identity for Perspective callers** (`IdentityResolver`). The component sends
`X-Perspective-Session-Id` and `X-Perspective-Project`. The gateway looks the session up *inside the caller's
own HTTP session* (`PerspectiveSessionCollection.findProjectSession(project)`), rejects mismatched ids
(403), and then reads username/roles from `session.getWebAuthStatus().getUser()` and `host`,
`device.userAgent`, `device.type` from the session's props tree — the same values scripts see under
`session.props`. Nothing identity-related is trusted from the request body. The custom headers also force a
CORS preflight, which blocks cross-site request forgery against these cookie-authenticated routes.

Recordings are bound to an owner key (`perspective:<sessionId>` or `api:<actor>`); another caller gets the
same 404 as a nonexistent id.

---

## 5. Configuration as an 8.3 config resource

Settings are a Java `record` (`TranscriptionSettings`) persisted by the 8.3 configuration system as a
**singleton resource** — `data/config/resources/core/com.wargoetz.voicetranscription/settings/config.json`.
That buys gateway backups, deployment modes, redundancy sync, and file-system scans for free.

```java
ResourceTypeMeta.newBuilder(TranscriptionSettings.class)
    .resourceType(new ResourceType(MODULE_ID, "settings"))
    .singleton()
    .defaultConfig(DEFAULT)
    .buildValidator(TranscriptionSettings::validate)
    .build();

SingletonResourceHandler.newBuilder(META).context(context).onChange(this::apply).build();
handler.startup();                  // in startup(); delivers the current value
handler.updateResource(next, actor) // from PUT /config; CompletableFuture
```

Lessons:

* Records with compact constructors make defaults robust: a missing field in JSON becomes `null`/`0`, and the
  constructor substitutes the default.
* **`updateResource()` does not run the type's validator.** Our PUT route calls
  `TranscriptionSettings.validationMessage()` itself (422 on failure). This was caught by the live tests: an
  unvalidated `system.users` collection was accepted until the explicit check was added.
* `onChange` fires on the config system's thread. Model loads take seconds, so `apply()` runs on our own
  single-thread executor and only touches subsystems whose settings actually changed.

---

## 6. Secrets Management API

LLM API keys are `SecretConfig` fields (`com.inductiveautomation.ignition.gateway.secrets`, new in 8.3,
replacing `EncodedStringField`). Two kinds:

* **Embedded** — the admin types the key; the gateway encrypts it with
  `context.getSystemEncryptionService().encryptToJson(Plaintext.fromString(key))` and only the JWE ciphertext
  is stored: `SecretConfig.embedded(jwe)`.
* **Referenced** — `SecretConfig.referenced(providerName, secretName)` points at a Secret Provider (Vault,
  Azure Key Vault, the internal provider...). Providers are listed via
  `context.getSecretProviderManager().getProviders()`.

Reading a secret, only at the moment of use, wiping the buffer afterwards:

```java
try (Plaintext p = Secret.create(context, config).getPlaintext()) {
    return p.getAsString(StandardCharsets.UTF_8);
}
```

The page never sees a secret: `GET /config` returns `{"configured":true,"mode":"embedded"}`; `PUT` accepts
`{"mode":"keep" | "none" | "embedded","value":... | "referenced","providerName":...,"secretName":...}`.
The live tests prove decryption end to end: the mock LLM returns 401 unless the gateway sent the right key.

---

## 7. MongoDB through the MongoDB Connector module

The module **does not open its own MongoDB connection**. Connections ("connectors") are configured once
under *Connections > Service Connectors* by IA's **MongoDB Connector** module, which owns hosts,
credentials (as gateway secrets), TLS, pooling, reconnects, and health checks. Our settings only name a
connector and a collection.

How one module uses another module's API:

1. **Declare the dependency** (`moduleDependencySpecs { register("com.inductiveautomation.connectors.mongodb") { scope = "G"; required = true } }`).
   Ignition then loads the connector first and lets our module classloader see its classes.
2. **Compile against its jars, never bundle them.** They aren't on a Maven repo, so `gateway/build.gradle.kts`
   extracts `mongodb-gateway-*.jar` / `mongodb-common-*.jar` / `bson-*.jar` from `module-init/MongoDB Connector-module.modl`
   into a `compileOnly` file tree.
3. **Use its public, blocking API**: `new GatewayMongoFunctions(context.getServiceConnectorManager())` — the
   same implementation behind `system.mongodb.*`:

   ```java
   mongo.listConnectorInfo();                                  // name, description, database
   mongo.listCollectionNames(connector);
   mongo.insertOne(connector, collection, Map<String,Object> doc, null);
   mongo.find(connector, collection, filter, projection, sort, options, limit, skip, JsonObject.class);
   mongo.delete(connector, collection, filter, null, true);
   ```
4. **Keep driver types out of the boundary.** Documents go in as `java.util.Map` (insertion-ordered, so the
   schema's field order survives); filters and results are Ignition's Gson `JsonObject`. No `org.bson`
   class crosses between classloaders, so we're immune to driver-version differences.

Gotchas found on a live gateway:

* **The connector refuses to connect if the database doesn't exist** ("Database doesn't exist [voice]"),
  even though MongoDB normally creates databases lazily. Create the database/collection first
  (`docker/mongo-init/init-voice.js` does it for the test stack, along with the `timestamp` index).
* The connector is a **licensed** module; unlicensed gateways run it in trial mode. Errors surface as
  `TrialExpiredException`, which `MongoService` translates into a readable message.
* A missing/faulted connector surfaces as `"Connector faulted. [name]"`; its health check (resource API
  `.../service-connector/<name>` → `healthchecks.status`) has the real cause.

Retention (`whisper.retentionDays`) is a range delete on the ISO `timestamp` string (it sorts
lexicographically), run on settings change and every 6 h.

---

## 8. Whisper via JNI inside a gateway

`WhisperService` uses [GiviMAD/whisper-jni](https://github.com/GiviMAD/whisper-jni) 1.7.1, whose jar bundles
whisper.cpp builds for Windows x64, Linux x64/arm64 (glibc ≥ 2.31), and macOS.

Native loading:

* `WhisperJNI.loadLibrary()` extracts the binary to `java.io.tmpdir` and `System.load`s it
  (`whisper-jni_full.dll` on Windows; `libwhisper-jni.so` + `libwhisper.so` + `libggml.so` on Linux).
  To use your own build (AVX-512, CUDA...), set `-Dio.github.givimad.whisperjni.libdir=/path` in
  `ignition.conf`.
* A JNI library binds to **one classloader per JVM**. 8.3 loads modules only at startup, so this is safe;
  never try to hot-reload the module.
* **No host prerequisites.** The Linux build links `libgomp.so.1` (OpenMP) and the Windows build links the VC++ runtime (`vcruntime140`, `vcruntime140_1`, `msvcp140`, `vcomp140`). `NativeRuntime` ships those under `native/<platform>/` (linux-amd64/aarch64/armhf, windows-amd64; sources and licences in `gateway/src/main/native/NOTICE.txt`) and pre-loads the bundled copy only when the host lacks it, before `WhisperJNI.loadLibrary()`. Verified on the 8.3.10 Linux image with `libgomp.so.1` removed; the Windows path is unit-tested for presence only.
* Windows' bundled build requires AVX2/FMA/F16C. Verified working on the Linux `inductiveautomation/ignition:8.3.10` image.

Concurrency — whisper-jni keeps contexts/states in **global, unsynchronised `std::map`s**:

* One `WhisperContext` (the model, loaded once with `initNoState`) is shared.
* A fixed pool of `WhisperState`s (`maxConcurrentTranscriptions`) is allocated **under a write lock** at load
  time; transcriptions only *read* the maps (`fullWithState`) under the read lock. Never `initState` per
  request.
* Chunk uploads `poll()` a state without waiting; if all are busy the partial is skipped (uploads never
  block). The final pass waits up to 120 s.

Why the language label isn't read from Whisper: `setLibraryLogger(...)` installs a native→Java log proxy that
calls `FindClass` from whatever thread logs. On a native worker thread under Ignition's module classloader
that returns null and the next JNI call crashes the **whole gateway JVM**. We call `setLibraryLogger(null)`
(the proxy becomes a no-op) and label auto-detected text with `LanguageDetector` (Unicode script, then
stop-word frequency, falling back to the configured default).

Air-gapped models: download a ggml model once (`docker/fetch-model.sh base`), verify it, and copy it to
`<ignition>/data/voicetranscription/models/`. Put its SHA-256 in **Model SHA-256**: the gateway then refuses
to load any other file (`sha256sum ggml-base.bin`). This matters because a malformed model can crash
whisper.cpp natively — and a native crash takes the whole gateway JVM down (see §15). Relative `modelPath`s resolve against the data directory.
`ggml-tiny.bin` (75 MB) is enough for tests; `ggml-base.bin` is the default; `*.en` models are English-only
(auto-detect needs a multilingual model).

---

## 9. Streaming partial transcripts: the commit window

Re-transcribing the whole recording on every chunk is O(n²). `RecordingManager` keeps a **commit window**:

* Each chunk appends PCM16 (16 kHz mono, 32 KB/s) to the recording's in-memory buffer.
* If ≥ 0.75 s of new audio arrived and no pass is running for this recording, Whisper decodes
  `[committedOffset, end)`.
* Once that window exceeds 12 s, every segment but the last is **committed**: appended to `committedText`, and
  `committedOffset` advances to that segment's end (`t1` centiseconds × 160 samples).
* Partial = committed text + the tentative last segment. Final (`/stop`) = committed + a full pass over the
  remainder, after which the audio is released.

Each pass therefore costs at most ~12–15 s of audio regardless of recording length. On the test gateway,
`ggml-tiny` returns a partial for each 3 s chunk in ~0.35 s.

**Multiple sources (tracks).** A recording declares its tracks at start (`{"tracks": ["microphone",
"system"]}`); each chunk names its track (`X-Audio-Track`). Every track has its own buffer, commit window,
silence gate and lock, so the microphone and the system audio are transcribed independently (in parallel
when two decoder states are free). Their segments are merged by **absolute time** and labelled:

```
[00:00] [System audio] The quick brown fox jumps over the lazy dog.
[00:05] [Microphone] Shift report for line 2.
[00:07] [Microphone] The main feed pump is running hot at 85 degrees.
```

Whisper stamps a window's first segment at ~0 s even when speech starts later, which mis-ordered sources;
each pass therefore trims leading silence (keeping a 0.2 s pre-roll) and offsets that window's timestamps by
the trimmed amount. With `timestamps` on (component prop `showTimestamps`, default on) every segment gets
its own `[mm:ss]` line relative to the recording start; off, consecutive text from one source is one line.
Responses also carry structured `turns` (`source`, `label`, `text`, `start`). Single-source recordings are
never labelled. The labelled/timestamped text is what's stored in `transcript` — the schema is unchanged.

**Background-noise filter** (`audio/NoiseFilter.java`, setting *Background noise filter*: Off / Standard /
Strict). Measured on ggml-tiny before the filter: 12 s of fan rush → "You you you you Thank you.", mains hum →
"you. you you", keyboard clicks → "Thanks for watching.", and speech over fan noise gained a fabricated tail
("…lazy dog. . Trust me, you should believe it, you know? you"). Three layers, all local:

1. **Energy VAD against an adaptive floor.** 20 ms frames; each track keeps the 10th percentile of its last
   ~60 s of frame levels as its noise floor, so a steady fan or hum *is* the floor. A frame is voice only if it
   is 8 dB (strict: 12 dB) above the floor and in a run of ≥ 60 ms (clicks are shorter).
2. **Window gate.** Whisper is never called on a window with < 250 ms (strict: 400 ms) of voice; a long
   noise-only stretch is dropped from the window so it can't grow.
3. **Segment filter.** Each returned segment is checked against the VAD over its own time span (±0.3 s);
   segments over noise are dropped, noise phrases ("thank you", "you", "thanks for watching"…) are dropped
   unless clearly voiced (always in strict), and `[BLANK_AUDIO]`, `(music)`, `*coughs*`, `♪` are stripped.

After: all nine noise cases (fan/hum/clicks at −45/−35/−25 dBFS) produce nothing; speech over noise keeps the
speech and loses the tail. The VAD also replaces the RMS check for the leading-silence trim. It is not a source
separator — speech buried in loud noise still needs a headset or the browser's noise suppression (on for the
microphone).

**Silence gate.** Whisper *hallucinates* on silence — a silent shared tab produced "you you you", fake
screen noise produced "I'm going to talk about the next couple of weeks". The gateway therefore never sends
a window whose RMS is below `SILENCE_RMS` (≈ −55 dBFS) to Whisper, flags each such chunk `"silent": true`,
and a recording with no audible audio ends with an empty transcript (submit → 422 "No speech was
detected"). The component turns two consecutive silent chunks into a source-specific hint.

Chunks carry `X-Chunk-Seq`; duplicates (a client retry) are ignored, so uploads are idempotent.

---

## 10. LLM summaries with `java.net.http.HttpClient`

`LlmClient` speaks the OpenAI-compatible API — `POST {endpoint}/chat/completions` and
`GET {endpoint}/models` — with no SDK to audit. One client covers OpenAI, Gemini
(`https://generativelanguage.googleapis.com/v1beta/openai`), LiteLLM, vLLM and Ollama (the LiteLLM pattern).

The system prompt is an admin-editable template with `{{username}} {{roles}} {{language}} {{timestamp}}
{{clientAddress}} {{sessionType}} {{userAgent}}`. A summarizer failure never loses a transcript: the
document is stored with `summary: ""` and the response carries `summaryError`.

---

## 11. The Perspective component

Three registrations for one component:

| Where | Code | Purpose |
|---|---|---|
| Gateway | `PerspectiveContext.get(ctx).getComponentRegistry().registerComponent(AudioRecorder.DESCRIPTOR)` | Sessions can instantiate it; props schema/events known to the gateway. |
| Designer | `PerspectiveDesignerInterface.get(ctx).getDesignerComponentRegistry().registerComponent(...)` | Palette entry (category "Input"). |
| Browser | `ComponentRegistry.register(new VoiceRecorderMeta())` in `web/src/index.ts` | The React implementation. |

The descriptor (`common/.../AudioRecorder.java`) carries the props JSON schema
(`recorder.props.json`), two events (`onSubmitted`, `onError`), and the `BrowserResource`s
(`/res/voicetranscription/VoiceTranscription.js` and `.css`) that Perspective loads before rendering.

Webpack details that matter: output is a UMD library; `react`, `react-dom`, `mobx`, `mobx-react` and
`@inductiveautomation/perspective-client` are **externals** (`React`, `PerspectiveClient`...) — bundling a
second React breaks hooks/context. The IA package is used for types only; nothing from it ships in our
bundle (relevant to §15).

Component behaviour (`VoiceRecorder.tsx`):

* **Audio source** (`props.audioSource`):

  | Value | API | Use |
  |---|---|---|
  | `microphone` (default) | `getUserMedia` with echo cancellation | Operator speaking. Echo cancellation *removes* speaker playback, so audio playing on the PC is never picked up in this mode. |
  | `tab` | `getDisplayMedia` | Audio of a browser tab, window or screen (a video, a web meeting). The user picks it in the browser's share dialog and must turn on **"Also share tab audio"** (for a whole screen on Windows: **"Also share system audio"**). Chrome/Edge desktop only; Firefox and Safari don't share audio. |
  | `mixed` | both, recorded as **two separate tracks** | Operator talking over a tab (e.g. narrating a video). The transcript says who said what. |

  `getDisplayMedia` must be called inside the click/key press (it's the first `await` in `startRecording`).
  Chromium insists on a video track; one is requested at 1 fps and kept alive but never recorded. The share
  stays open across recordings (shown as "Sharing audio: … · Stop sharing") so push-to-talk doesn't reopen
  the picker every time; it ends via "Stop sharing", the browser's own sharing bar, or unmount. If the user
  shares without audio, the component says so instead of recording silence.
* **Capture** (`AudioCapture.ts`): the chosen stream → `MediaRecorder.start(chunkIntervalMs)`, where the interval is the component's `chunkIntervalMs` if > 0, else the gateway value returned by the start request (re-read every recording), clamped to 500-30000 ms (`resolveChunkInterval`). On every
  `ondataavailable`, decode the *cumulative* blob with `decodeAudioData`, resample to 16 kHz mono with an
  `OfflineAudioContext`, and upload only the new samples as `audio/L16`. Partial containers that a browser
  can't decode are skipped; the final decode after `stop()` always delivers everything.
* **Running log, never auto-cleared.** Recordings are *appended* below what's in the box; the box is only
  emptied by **Clear** (two clicks: Clear → Confirm clear) or by writing `props.transcript` (e.g. `""` from a
  script/binding). Submit sends only the text below the last `──── Submitted hh:mm:ss ────` divider and then
  appends a new divider, so nothing is stored twice; text typed by hand can be submitted too (a text-only
  recording is opened for it). Timestamps keep counting across appended recordings: the client passes
  `timeOffsetSeconds` (seconds since the log began) when starting each recording.
* **Colour-coded labels.** The textarea's text is transparent and sits exactly on top of a backdrop `div` that
  renders the same characters with coloured spans (`[mm:ss]` grey, `[Microphone]` blue, `[System audio]`
  orange, dividers light grey; `props.sourceColors`). Both layers share font, padding, line-height, wrapping
  and `scrollbar-gutter: stable`, and scroll together, so the box stays a normal editable text field. Spans may
  change colour/background only — any width change (bold, padding) would misalign the layers.
* **Transcript layout**: `[mm:ss]` per line (`props.showTimestamps`, default on) and, in `mixed` mode,
  `[Microphone]` / `[System audio]` labels. The two sources are captured by two `MediaRecorder`s started in
  the same tick so their timelines line up. With speakers, the microphone's echo cancellation keeps the
  tab audio out of the microphone track (headphones avoid the question entirely). In `mixed`, a
  silence warning appears only when *both* sources are quiet.
* **Push-to-talk**: document-level `keydown`/`keyup` for `props.pushToTalk.key` (`KeyboardEvent.code`,
  default `Space`), ignored while typing in inputs; window blur counts as key release.
* **Editing**: the textarea is read-only while recording and editable before submit; edits are sent as
  `{"transcript": ...}` to `/submit`.
* **Props written back** (`store.props.write`): `transcript`, `status`, `error`, `lastSubmission` — bindable
  in the view (the demo binds a label to `props.status`).
* **Events**: `componentEvents.fireComponentEvent("onSubmitted", doc)` / `("onError", {message, stage})`.
* `getUserMedia` needs a **secure context**: HTTPS, or `http://localhost`. Over plain HTTP to an IP the
  component explains this instead of failing silently. Designer preview shows a notice (no microphone in the
  Designer's embedded browser).

---

## 12. The gateway settings page (8.3 React web UI)

The 8.3 gateway web UI is a React app that loads module pages as **SystemJS modules**:

```java
SystemJsModule page = new SystemJsModule("com.wargoetz.voicetranscription.config",
        "/res/voicetranscription/web-ui/voicetranscription-config.js");
context.getWebResourceManager().getNavigationModel().getServices()
    .addCategory("voicetranscription", c -> c.label("Voice Transcription")
        .requiredPermission(PermissionType.READ)
        .addPage("Settings", p -> p.position(0).mount("/services/voice-transcription",
                                                       "VoiceTranscriptionSettings", page)));
```

The second `mount` argument is the **export name** of the bundle (`web-ui/src/index.ts` exports
`VoiceTranscriptionSettings`). The bundle is UMD with `react`/`react-dom` external (supplied by the web UI).
Section getters exist for Home, Platform, Connections, Network, Services, Diagnostics; `GET
/data/app/navigation` shows the resulting tree (handy to verify routes).

The page uses plain React + fetch (no undocumented `@inductiveautomation/ignition-web-ui` components, which
have no published typings) and covers: status, MongoDB connector + collection browser, Whisper settings,
LLM provider/endpoint/model list/system prompt, API-key secret editor (embedded or Secret Provider), and the
latest transcripts.

**Model list.** Picking a provider fills in its default endpoint and immediately lists its models with
`POST /llm/models`, using the values *in the form* (provider, endpoint, API-key update) - nothing has to be
saved first. *Load models* does the same after editing the endpoint or key. Every preset is OpenAI-compatible,
so this is `GET {endpoint}/models` with `Authorization: Bearer <key>`; Gemini's `models/` prefix is stripped,
and ids that can't do chat/completions (embeddings, TTS, Whisper, DALL-E/image, moderation, realtime, legacy
completions) are dropped and counted in `hidden`. The saved key is only sent to the *saved* endpoint: switching
to another provider with "Keep current" key withholds it (the page asks for that provider's key instead), so a
form edit can't hand an OpenAI key to Google or to an arbitrary URL. Custom endpoints (Ollama, LiteLLM) are
tried without a key.

---

## 13. A disposable 8.3 test gateway

`docker/docker-compose.yml` runs `inductiveautomation/ignition:8.3.10` + `mongo:7` + a mock OpenAI server.
Things a fresh 8.3 container needs before a third-party module loads:

```yaml
environment:
  ACCEPT_IGNITION_EULA: "Y"
  GATEWAY_ADMIN_USERNAME: admin
  GATEWAY_ADMIN_PASSWORD: password
  DISABLE_QUICKSTART: "true"
  ACCEPT_MODULE_LICENSES: com.inductiveautomation.connectors.mongodb,com.wargoetz.voicetranscription
  ACCEPT_MODULE_CERTS: com.inductiveautomation.connectors.mongodb,com.wargoetz.voicetranscription
command: >
  -n vt-gateway -m 2048 --
  -Dignition.allowunsignedmodules=true
  -Dignition.modules.install.unattended=true
  -Dignition.modules.install.trust-unknown-certificates=true
volumes:
  - ../build/OfflineVoiceTranscription.unsigned.modl:/usr/local/bin/ignition/user-lib/modules/...
```

* Without the acceptance settings the gateway parks in commissioning with
  `Resources needing commissioning: modules`.
* Everything after `--` in `command` is passed to the JVM.
* Mount the whole `data/projects` folder, not a single project inside it — otherwise Docker creates
  `data/projects` as root and the gateway faults (`unable to create resource dir .../projects/.resources`).
* The bind mount only seeds the *first* install on a fresh volume. The gateway then pins that file path in
  `data/modules.json`; a file with another name is ignored (`The file for module ... is missing`). Upgrade
  through the gateway's module API instead - `shared/module-dev/install-modl.py` (upload → trust cert →
  install → restart → wait for ACTIVE), which is what the web UI's *Install or Upgrade a Module* does.
* Don't `docker cp` files into `data/` - they arrive owned by root and the gateway fails to write them
  (`AccessDeniedException: data/modules.json`).

---

## 14. Testing

| Layer | Command | What it proves |
|---|---|---|
| Java unit | `./gradlew :gateway:test` | Audio decoding/resampling, language labels, commit window, exact document schema, settings validation. |
| TS unit | `cd web && npm test` | PCM conversion, resampling, push-to-talk target filtering. |
| Live API | `cd tests && pytest` | Logs in with **username/password**, provisions a MongoDB connector (password encrypted via `/data/api/v1/encryption/encrypt`), configures the module, streams audio, checks partials, edits, LLM/secret round-trip, schema in MongoDB (via `pymongo`), retention, access control, CSRF. |
| Browser | `pytest test_browser.py` | Headless Edge with a **fake microphone** fed from a WAV: real WebRTC → MediaRecorder → upload path, push-to-talk, edit, submit, and username/roles from the logged-in Perspective session; **tab audio**: a second tab plays the WAV and `--auto-select-tab-capture-source-by-title` answers the share picker (Playwright's default `--mute-audio` must be removed or captured tab audio is silent); a silent tab shows the warning instead of hallucinated text; plus the settings page loading via SystemJS and saving with CSRF. |

Programmatic 8.3 login (`tests/gateway_client.py`): `GET /data/app/login` → IdP `/oidc/auth` → `authn/login?token=`
→ `POST authn/submit-challenge/basic {token, challenge:{username,password}}` → `POST authn/next-challenge`
→ resume `/oidc/auth?...&token=` → session cookie.

Environment variables: `GATEWAY_URL`, `GATEWAY_USERNAME`, `GATEWAY_PASSWORD` (plus `MONGO_URI`,
`MOCK_LLM_URL`, `VT_CONNECTOR`, `PW_BROWSER_CHANNEL`; see `tests/conftest.py`).

---

## 15. Security audit

`./gradlew securityAudit` = `npm audit --audit-level=high` on both web packages + OWASP
`dependencyCheckAggregate` (fails on CVSS ≥ 7) over everything bundled in the `.modl`.

* `npm audit` flagged 8 issues (3 high), all transitive through `@inductiveautomation/perspective-client`'s
  exact pins (`axios 1.19.0`, `lodash 4.17.21`, `moment`, `@babel/runtime`). npm's suggested fix (downgrade
  the IA package) is wrong. Fixed with `overrides` in `web/package.json`; both packages now report 0.
  Note none of that code reaches the browser — the IA package is an external used for types only.
* The `.modl` bundles only our jars + `whisper-jni`; the Mongo driver comes from the connector module, so
  its CVE lifecycle is IA's (update the connector module).
* **OWASP dependency-check 13 requires an NVD API key** (free, https://nvd.nist.gov/developers/request-an-api-key);
  without `NVD_API_KEY` the task now fails fast with that instruction. It is applied to `:gateway` because
  `dependencyCheckAggregate` trips Gradle 9's cross-project resolution lock. Suppressions go in
  `config/dependency-check-suppressions.xml`, each with a reason.
* Until a key is configured, the one bundled third-party artifact was checked against OSV.dev:
  `io.github.givimad:whisper-jni 1.7.1` has no advisories, but the **whisper.cpp it embeds** has four
  medium, local-vector CVEs with **no fixed release** (whisper-jni 1.7.1 is the latest):

  | CVE | Where | Exposure here | Mitigation |
  |---|---|---|---|
  | CVE-2025-14569 | `examples/common-whisper.cpp` WAV reader | Not compiled into the library path we call | none needed |
  | CVE-2026-10298 | `whisper_model_load` null deref | Malicious model file → native crash | Admin-only `modelPath` + **Model SHA-256 pin** |
  | CVE-2026-17513 | `ggml_ftype_to_ggml_type` assertion | Malicious model file → abort | same |
  | CVE-2026-17512 | `log_mel_spectrogram` OOB read | Audio path | Input normalised to 16 kHz float, ≥ 0.25 s enforced; track upstream fix and rebuild whisper-jni (or point `whisper-jni.libdir` at a patched whisper.cpp) when available |


---

## 16. Ship checklist

1. `./gradlew clean build securityAudit` and `pytest` against an 8.3 gateway of the *oldest* version you
   support (the floor in `requiredIgnitionVersion`).
2. Sign. `build.gradle.kts` reads `sign.props` (gitignored; `-PsignProps=<path>` to use another file):

   ```properties
   key.file=../ArchitectureBuilderReactFlow/private/module-keystore.pfx   # relative to sign.props
   key.pass=...
   cert.file=../ArchitectureBuilderReactFlow/private/certificates.p7b
   cert.alias=architecture-builder
   cert.pass=...
   ```

   and maps it onto the plugin's `ignition.signing.*` properties; `skipModlSigning` is on only when the file
   is missing. `./gradlew build` then writes both `OfflineVoiceTranscription.unsigned.modl` and the signed
   `OfflineVoiceTranscription.modl` (adds `signatures.properties` - a SHA256withRSA signature per jar and
   `module.xml` - and `certificates.p7b`). The certificate is the self-signed WARGoetz one (valid to
   2036-04-18), shared with ArchitectureBuilderReactFlow so a gateway that trusts one trusts both. A
   self-signed cert is fine for your own gateways; Exchange publication needs a CA-issued one.
   Verified on 8.3.10: a gateway *without* developer mode quarantines the unsigned build ("module is unsigned
   and developer mode is disabled") and installs the signed one after a one-time certificate acceptance.
3. Install the MongoDB Connector module first; create the connector and its database/collection.
4. Copy the Whisper model to `data/voicetranscription/models/`.
5. Install this module, restart, open *Services > Voice Transcription*, pick the connector, save.
6. Serve Perspective over HTTPS (microphone access requires a secure context).
7. Bump `version` in `build.gradle.kts` on every behavior change.

---

## Appendix — REST API (`/data/voicetranscription/api/v1`)

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/status` | READ | Whisper/Mongo/LLM health, open recordings |
| GET | `/csrf` | READ | CSRF header name + token for the settings page |
| GET / PUT | `/config` | READ / WRITE | Settings (secrets redacted / secret commands) |
| GET | `/mongo/connectors` | READ | MongoDB Connector connections |
| GET | `/mongo/collections?connector=` | READ | Collections via a connector |
| GET | `/secrets/providers` | READ | Secret Providers and their secret names |
| GET | `/llm/providers`, `/llm/models` | READ | Provider presets; models from the saved endpoint |
| POST | `/llm/models` | WRITE | Models for unsaved form values `{provider, endpointUrl, apiKey}`; saved key only to the saved endpoint |
| GET | `/transcripts?limit=`, `/transcripts/:id` | READ | Stored transcripts |
| GET | `/client-config` | session / READ | Chunk interval, limits, language defaults |
| POST | `/recordings` | session / WRITE | Start → `recordingId` |
| POST | `/recordings/:id/chunks` | session / WRITE | `audio/L16;rate=16000` or `audio/wav` → partial transcript |
| POST | `/recordings/:id/stop` | session / WRITE | Final transcript + language |
| POST | `/recordings/:id/submit` | session / WRITE | `{transcript?, summarize?}` → summary + MongoDB insert |
| DELETE | `/recordings/:id` | session / WRITE | Discard |
