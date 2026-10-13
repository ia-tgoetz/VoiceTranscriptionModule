package com.wargoetz.voicetranscription.gateway.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.web.session.WebUiSession;
import com.inductiveautomation.perspective.gateway.api.SessionScope;
import com.inductiveautomation.perspective.gateway.comm.Routes;
import com.wargoetz.voicetranscription.common.VoiceTranscriptionModule;
import com.wargoetz.voicetranscription.gateway.ModuleServices;
import com.wargoetz.voicetranscription.gateway.audio.AudioDecoder;
import com.wargoetz.voicetranscription.gateway.config.Secrets;
import com.wargoetz.voicetranscription.gateway.config.SettingsJson;
import com.wargoetz.voicetranscription.gateway.config.TranscriptionSettings;
import com.wargoetz.voicetranscription.gateway.llm.LlmClient;
import com.wargoetz.voicetranscription.gateway.mongo.MongoService;
import com.wargoetz.voicetranscription.gateway.mongo.TranscriptDocument;
import com.wargoetz.voicetranscription.gateway.recording.RecordingManager;
import com.wargoetz.voicetranscription.gateway.recording.RecordingSession;
import com.wargoetz.voicetranscription.gateway.whisper.ModelCatalog;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Every REST endpoint of the module. {@code GatewayHook.mountRouteHandlers()} hands us a {@link RouteGroup}
 * rooted at {@code /data/voicetranscription} (the mount alias), so {@code /api/v1/status} below is served at
 * {@code /data/voicetranscription/api/v1/status}.
 *
 * <h2>Access control</h2>
 * <table>
 *   <tr><th>Routes</th><th>Who</th></tr>
 *   <tr><td>config, mongo (connectors/collections), secrets, llm, transcripts</td>
 *       <td>Gateway READ (GET) / WRITE (POST, PUT) permission - i.e. a logged-in gateway web-UI user
 *       (with {@code X-CSRF-Token} on writes) or an API token with the matching permission.</td></tr>
 *   <tr><td>recordings/*, client-config</td>
 *       <td>A live Perspective session (browser cookie + {@code X-Perspective-Session-Id}), <em>or</em>
 *       WRITE/READ permission for scripted clients and tests.</td></tr>
 * </table>
 */
public final class ApiRoutes {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Routes");
    private static final String API = VoiceTranscriptionModule.API_PREFIX;
    /** ~60 s of 16 kHz PCM16 per upload; the component sends a few seconds at a time. */
    static final int MAX_CHUNK_BYTES = 2 * 1024 * 1024;

    private final ModuleServices services;

    @FunctionalInterface
    private interface JsonHandler {
        JsonElement handle(RequestContext req, HttpServletResponse res) throws Exception;
    }

    private ApiRoutes(ModuleServices services) {
        this.services = services;
    }

    public static void mount(RouteGroup routes, ModuleServices services) {
        new ApiRoutes(services).mountAll(routes);
    }

    private void mountAll(RouteGroup routes) {
        // ---- admin: configuration -------------------------------------------------------------------------
        route(routes, "/status", HttpMethod.GET, admin(PermissionType.READ), this::status);
        route(routes, "/csrf", HttpMethod.GET, admin(PermissionType.READ), this::csrf);
        route(routes, "/config", HttpMethod.GET, admin(PermissionType.READ), this::getConfig);
        route(routes, "/config", HttpMethod.PUT, admin(PermissionType.WRITE), this::putConfig);
        route(routes, "/config", HttpMethod.POST, admin(PermissionType.WRITE), this::putConfig);
        route(routes, "/models", HttpMethod.GET, admin(PermissionType.READ), this::whisperModels);
        route(routes, "/models/download", HttpMethod.POST, admin(PermissionType.WRITE), this::downloadModel);
        route(routes, "/mongo/connectors", HttpMethod.GET, admin(PermissionType.READ),
            (req, res) -> services.mongo().listConnectors());
        route(routes, "/mongo/collections", HttpMethod.GET, admin(PermissionType.READ), this::collections);
        route(routes, "/secrets/providers", HttpMethod.GET, admin(PermissionType.READ),
            (req, res) -> Secrets.listProviders(services.context()));
        route(routes, "/llm/providers", HttpMethod.GET, admin(PermissionType.READ), (req, res) -> SettingsJson.providers());
        route(routes, "/llm/models", HttpMethod.GET, admin(PermissionType.READ), this::models);
        // WRITE, not READ: the gateway sends a key to a URL the caller chose, as saving the config would.
        route(routes, "/llm/models", HttpMethod.POST, admin(PermissionType.WRITE), this::previewModels);
        route(routes, "/transcripts", HttpMethod.GET, admin(PermissionType.READ), this::recentTranscripts);
        route(routes, "/transcripts/:id", HttpMethod.GET, admin(PermissionType.READ), this::transcript);

        // ---- recorder: Perspective component ---------------------------------------------------------------
        route(routes, "/client-config", HttpMethod.GET, recorder(PermissionType.READ), this::clientConfig);
        route(routes, "/recordings", HttpMethod.POST, recorder(PermissionType.WRITE), this::startRecording);
        route(routes, "/recordings/:id/chunks", HttpMethod.POST, recorder(PermissionType.WRITE), this::chunk);
        route(routes, "/recordings/:id/stop", HttpMethod.POST, recorder(PermissionType.WRITE), this::stop);
        route(routes, "/recordings/:id/submit", HttpMethod.POST, recorder(PermissionType.WRITE), this::submit);
        route(routes, "/recordings/:id", HttpMethod.DELETE, recorder(PermissionType.WRITE), this::cancel);
    }

    // ===================================================================================== admin handlers

    private JsonElement status(RequestContext req, HttpServletResponse res) {
        TranscriptionSettings s = services.settings();
        JsonObject out = new JsonObject();
        out.addProperty("moduleId", VoiceTranscriptionModule.MODULE_ID);

        JsonObject whisper = new JsonObject();
        whisper.addProperty("ready", services.whisper().isReady());
        whisper.addProperty("status", services.whisper().status());
        whisper.addProperty("modelPath", services.resolveModelPath(s.whisper().modelPath()).toString());
        whisper.addProperty("multilingual", services.whisper().isMultilingual());
        out.add("whisper", whisper);

        JsonObject mongo = new JsonObject();
        mongo.addProperty("configured", services.mongo().isConfigured());
        mongo.addProperty("connector", s.mongo().connector());
        mongo.addProperty("collection", s.mongo().collection());
        String err = services.mongo().ping();
        mongo.addProperty("connected", err == null);
        if (err != null) {
            mongo.addProperty("error", err);
        }
        services.mongo().database().ifPresent(db -> mongo.addProperty("database", db));
        out.add("mongo", mongo);

        JsonObject llm = new JsonObject();
        llm.addProperty("enabled", s.llm().enabled());
        llm.addProperty("provider", s.llm().provider());
        llm.addProperty("model", s.llm().model());
        out.add("llm", llm);

        out.addProperty("openRecordings", services.recordings().openCount());
        return out;
    }

    /** Lets the config page (a module-supplied React bundle) obtain the web-UI session's CSRF token. */
    private JsonElement csrf(RequestContext req, HttpServletResponse res) throws ApiException {
        JsonObject out = new JsonObject();
        out.addProperty("header", WebUiSession.CSRF_TOKEN_HEADER);
        out.addProperty("token", WebUiSession.find(req).map(WebUiSession::getCsrfToken).orElse(""));
        return out;
    }

    private JsonElement getConfig(RequestContext req, HttpServletResponse res) {
        JsonObject out = SettingsJson.toJson(services.settings());
        out.add("providers", SettingsJson.providers());
        return out;
    }

    private JsonElement putConfig(RequestContext req, HttpServletResponse res) throws Exception {
        JsonObject body = readJson(req);
        TranscriptionSettings next = SettingsJson.fromJson(services.context(), body, services.settings());
        Optional<String> invalid = TranscriptionSettings.validationMessage(next);
        if (invalid.isPresent()) {
            throw new ApiException(422, "Settings rejected: " + invalid.get());
        }
        try {
            // Persists through the config system: validated by TranscriptionSettings.META, written to
            // data/config, replicated to a redundant backup, and echoed back to us via onChange().
            services.settingsHandler().updateResource(next, req.getActor()).get();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ApiException(422, "Settings rejected: " + cause.getMessage());
        }
        services.applyNow(next);
        return SettingsJson.toJson(next);
    }

    /**
     * Whisper models: the {@code *.bin} files in {@code ?dir=} (defaults to the saved models folder, so the
     * page can browse a folder before saving it), the downloadable catalogue, and download progress.
     */
    private JsonElement whisperModels(RequestContext req, HttpServletResponse res) throws ApiException {
        String requested = Optional.ofNullable(req.getParameter("dir")).filter(d -> !d.isBlank())
            .orElse(services.settings().whisper().modelsDir());
        Path dir;
        try {
            dir = services.resolveModelPath(requested);
        } catch (InvalidPathException e) {
            throw new ApiException(400, "Invalid folder: " + requested);
        }
        JsonObject out = new JsonObject();
        out.addProperty("dir", requested);
        out.addProperty("resolvedDir", dir.toString());
        out.addProperty("exists", Files.isDirectory(dir));
        JsonArray files = new JsonArray();
        Set<String> installed = new HashSet<>();
        for (ModelCatalog.Installed f : ModelCatalog.list(dir)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", f.name());
            o.addProperty("sizeBytes", f.sizeBytes());
            o.addProperty("multilingual", f.multilingual());
            files.add(o);
            installed.add(f.name());
        }
        out.add("files", files);
        JsonArray catalog = new JsonArray();
        for (ModelCatalog.Entry e : ModelCatalog.CATALOG) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("file", e.file());
            o.addProperty("approxMb", e.approxMb());
            o.addProperty("multilingual", e.multilingual());
            o.addProperty("note", e.note());
            o.addProperty("installed", installed.contains(e.file()));
            ModelCatalog.Download d = services.models().downloads().get(e.id());
            if (d != null) {
                o.addProperty("downloadState", d.state());
                o.addProperty("bytesDone", d.bytesDone());
                o.addProperty("bytesTotal", d.bytesTotal());
                if (d.error() != null) {
                    o.addProperty("error", d.error());
                }
                if (d.sha256() != null) {
                    o.addProperty("sha256", d.sha256());
                }
            }
            catalog.add(o);
        }
        out.add("catalog", catalog);
        return out;
    }

    /** Body {@code {"id": "<catalogue id>"}}; downloads into the saved models folder in the background. */
    private JsonElement downloadModel(RequestContext req, HttpServletResponse res) throws ApiException {
        JsonObject body = readJsonOrEmpty(req);
        String id = body.has("id") && !body.get("id").isJsonNull() ? body.get("id").getAsString() : "";
        try {
            ModelCatalog.Download d = services.models().startDownload(id)
                .orElseThrow(() -> new ApiException(404, "Unknown model '" + id + "'"));
            JsonObject out = new JsonObject();
            out.addProperty("id", d.id());
            out.addProperty("state", d.state());
            return out;
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        }
    }

    /** Collections for {@code ?connector=} (defaults to the saved connector) so the page can browse before saving. */
    private JsonElement collections(RequestContext req, HttpServletResponse res) throws ApiException {
        String connector = Optional.ofNullable(req.getParameter("connector")).filter(c -> !c.isBlank())
            .orElse(services.settings().mongo().connector());
        if (connector.isBlank()) {
            throw new ApiException(409, "No MongoDB connector selected");
        }
        try {
            return collectionsJson(connector, services.mongo().listCollections(connector));
        } catch (MongoService.MongoException e) {
            throw new ApiException(502, "MongoDB error: " + e.getMessage());
        }
    }

    private static JsonObject collectionsJson(String connector, List<String> names) {
        JsonObject out = new JsonObject();
        out.addProperty("connector", connector);
        JsonArray arr = new JsonArray();
        names.forEach(arr::add);
        out.add("collections", arr);
        return out;
    }

    private JsonElement models(RequestContext req, HttpServletResponse res) throws ApiException {
        TranscriptionSettings.Llm cfg = services.settings().llm();
        try {
            return modelsJson(cfg, services.llm().listModels(cfg, Secrets.reveal(services.context(), cfg.apiKey())));
        } catch (LlmClient.LlmException e) {
            throw new ApiException(502, e.getMessage());
        }
    }

    /**
     * Models for the provider/endpoint/key currently in the settings form, before it is saved.
     * Body: {@code {"provider", "endpointUrl", "apiKey": <secret update as for PUT /config>}}; blank endpoint
     * = the provider's default. The saved key is only ever sent to the saved endpoint - for any other endpoint
     * the request must carry its own key (or none, e.g. a local Ollama), so switching provider can't leak it.
     */
    private JsonElement previewModels(RequestContext req, HttpServletResponse res) throws ApiException {
        JsonObject body = readJsonOrEmpty(req);
        TranscriptionSettings.Llm saved = services.settings().llm();
        String provider = body.has("provider") && !body.get("provider").isJsonNull()
            ? body.get("provider").getAsString() : saved.provider();
        String endpoint = body.has("endpointUrl") && !body.get("endpointUrl").isJsonNull()
            ? body.get("endpointUrl").getAsString().trim() : "";
        SecretConfig key = Secrets.apply(services.context(), body.get("apiKey"), saved.apiKey());
        TranscriptionSettings.Llm cfg = new TranscriptionSettings.Llm(saved.enabled(), provider, endpoint,
            saved.model(), saved.systemPrompt(), key, saved.timeoutSeconds(), saved.temperature());
        if (!cfg.endpointUrl().startsWith("http://") && !cfg.endpointUrl().startsWith("https://")) {
            throw new ApiException(400, "Endpoint must be an http(s) URL");
        }
        boolean withheld = key != null && key == saved.apiKey() && !cfg.endpointUrl().equals(saved.endpointUrl());
        if (withheld) {
            cfg = new TranscriptionSettings.Llm(cfg.enabled(), cfg.provider(), cfg.endpointUrl(), cfg.model(),
                cfg.systemPrompt(), null, cfg.timeoutSeconds(), cfg.temperature());
        }
        try {
            return modelsJson(cfg, services.llm().listModels(cfg, Secrets.reveal(services.context(), cfg.apiKey())));
        } catch (LlmClient.LlmException e) {
            String msg = e.getMessage();
            if (withheld && (msg.contains("HTTP 401") || msg.contains("HTTP 403"))) {
                msg = "The saved API key is only sent to the saved endpoint - enter the API key for this provider. ("
                    + msg + ")";
            }
            throw new ApiException(502, msg);
        }
    }

    /** {@code models} = chat-capable ids (what the summarizer can use); {@code hidden} = embeddings, TTS, etc. */
    private static JsonObject modelsJson(TranscriptionSettings.Llm cfg, List<String> ids) {
        JsonObject out = new JsonObject();
        out.addProperty("endpointUrl", cfg.endpointUrl());
        JsonArray arr = new JsonArray();
        ids.stream().filter(LlmClient::isChatModel).forEach(arr::add);
        out.add("models", arr);
        out.addProperty("hidden", ids.size() - arr.size());
        return out;
    }

    private JsonElement recentTranscripts(RequestContext req, HttpServletResponse res) throws ApiException {
        if (!services.mongo().isConfigured()) {
            throw new ApiException(409, "No MongoDB connector selected");
        }
        int limit = 20;
        try {
            limit = Math.max(1, Math.min(200, Integer.parseInt(Optional.ofNullable(req.getParameter("limit")).orElse("20"))));
        } catch (NumberFormatException ignored) {
            // keep default
        }
        JsonArray arr = new JsonArray();
        try {
            services.mongo().recent(limit).forEach(d -> arr.add(TranscriptDocument.fromStored(d)));
        } catch (MongoService.MongoException e) {
            throw new ApiException(502, "MongoDB error: " + e.getMessage());
        }
        JsonObject out = new JsonObject();
        out.add("transcripts", arr);
        return out;
    }

    private JsonElement transcript(RequestContext req, HttpServletResponse res) throws ApiException {
        return services.findTranscript(req.getParameter("id"))
            .orElseThrow(() -> new ApiException(404, "Transcript not found"));
    }

    // ================================================================================== recorder handlers

    private JsonElement clientConfig(RequestContext req, HttpServletResponse res) {
        TranscriptionSettings s = services.settings();
        JsonObject out = new JsonObject();
        out.addProperty("chunkIntervalMs", s.whisper().chunkIntervalMs());
        out.addProperty("maxRecordingSeconds", s.whisper().maxRecordingSeconds());
        out.addProperty("defaultLanguage", s.whisper().defaultLanguage());
        out.addProperty("autoDetect", s.whisper().autoDetect());
        out.addProperty("sampleRate", AudioDecoder.TARGET_RATE);
        out.addProperty("whisperReady", services.whisper().isReady());
        out.addProperty("llmEnabled", s.llm().enabled());
        return out;
    }

    private JsonElement startRecording(RequestContext req, HttpServletResponse res) throws Exception {
        CallerIdentity who = IdentityResolver.resolve(req);
        JsonObject body = readJsonOrEmpty(req);
        String language = body.has("language") && !body.get("language").isJsonNull()
            ? body.get("language").getAsString().trim().toLowerCase() : "";
        List<String> tracks = new ArrayList<>();
        if (body.has("tracks") && body.get("tracks").isJsonArray()) {
            body.getAsJsonArray("tracks").forEach(e -> tracks.add(e.getAsString().trim().toLowerCase()));
        }
        boolean timestamps = body.has("timestamps") && body.get("timestamps").getAsBoolean();
        RecordingSession s = services.recordings().start(who, language, tracks, timestamps);
        if (body.has("timeOffsetSeconds") && !body.get("timeOffsetSeconds").isJsonNull()) {
            s.setTimeOffset(body.get("timeOffsetSeconds").getAsDouble());
        }
        TranscriptionSettings.Whisper w = services.settings().whisper();
        res.setStatus(201);
        JsonObject out = new JsonObject();
        out.addProperty("recordingId", s.id());
        out.addProperty("language", s.language());
        out.addProperty("sampleRate", AudioDecoder.TARGET_RATE);
        out.addProperty("chunkIntervalMs", w.chunkIntervalMs());
        out.addProperty("maxRecordingSeconds", w.maxRecordingSeconds());
        JsonArray trackNames = new JsonArray();
        s.trackNames().forEach(trackNames::add);
        out.add("tracks", trackNames);
        out.addProperty("username", who.username());
        out.addProperty("sessionType", who.sessionType());
        return out;
    }

    private JsonElement chunk(RequestContext req, HttpServletResponse res) throws Exception {
        CallerIdentity who = IdentityResolver.resolve(req);
        RecordingSession s = services.recordings().get(req.getParameter("id"), who);
        long seq = parseLong(req.getRequest().getHeader("X-Chunk-Seq"), -1);
        byte[] body = readBytes(req, MAX_CHUNK_BYTES);
        short[] pcm;
        try {
            pcm = AudioDecoder.decode(req.getRequest().getContentType(), body);
        } catch (AudioDecoder.AudioFormatException e) {
            throw new ApiException(415, e.getMessage());
        }
        String track = req.getRequest().getHeader("X-Audio-Track");
        RecordingManager.ChunkResult r = services.recordings().appendChunk(s, track, pcm, seq);
        JsonObject out = new JsonObject();
        out.addProperty("recordingId", s.id());
        out.addProperty("seq", seq);
        out.addProperty("track", r.track());
        out.addProperty("durationSeconds", r.durationSeconds());
        out.addProperty("transcribed", r.transcribed());
        out.addProperty("duplicate", r.duplicate());
        out.addProperty("limitReached", r.limitReached());
        out.addProperty("silent", r.silent());
        out.addProperty("whisperMs", r.whisperMs());
        if (r.partialTranscript() != null) {
            out.addProperty("partialTranscript", r.partialTranscript());
            out.add("turns", turnsJson(r.turns()));
        }
        return out;
    }

    private JsonElement stop(RequestContext req, HttpServletResponse res) throws Exception {
        CallerIdentity who = IdentityResolver.resolve(req);
        RecordingSession s = services.recordings().get(req.getParameter("id"), who);
        RecordingManager.StopResult r = services.recordings().stop(s);
        JsonObject out = new JsonObject();
        out.addProperty("recordingId", s.id());
        out.addProperty("transcript", r.transcript());
        out.add("turns", turnsJson(r.turns()));
        out.addProperty("language", r.language());
        out.addProperty("durationSeconds", r.durationSeconds());
        return out;
    }

    private JsonElement submit(RequestContext req, HttpServletResponse res) throws Exception {
        CallerIdentity who = IdentityResolver.resolve(req);
        RecordingSession s = services.recordings().get(req.getParameter("id"), who);
        JsonObject body = readJsonOrEmpty(req);
        String edited = body.has("transcript") && !body.get("transcript").isJsonNull()
            ? body.get("transcript").getAsString() : null;
        boolean summarize = !body.has("summarize") || body.get("summarize").getAsBoolean();
        res.setStatus(201);
        return services.submit(s, edited, summarize);
    }

    private JsonElement cancel(RequestContext req, HttpServletResponse res) throws Exception {
        CallerIdentity who = IdentityResolver.resolve(req);
        RecordingSession s = services.recordings().get(req.getParameter("id"), who);
        services.recordings().remove(s.id());
        JsonObject out = new JsonObject();
        out.addProperty("cancelled", s.id());
        return out;
    }

    /** Per-source turns, e.g. [{"source":"microphone","label":"Microphone","text":"..."}]. */
    private static JsonArray turnsJson(List<RecordingSession.Turn> turns) {
        JsonArray arr = new JsonArray();
        for (RecordingSession.Turn t : turns) {
            JsonObject o = new JsonObject();
            o.addProperty("source", t.source());
            o.addProperty("label", t.label());
            o.addProperty("text", t.text());
            o.addProperty("start", Math.round(t.start() * 100) / 100.0);
            arr.add(o);
        }
        return arr;
    }

    // ============================================================================================ plumbing

    private static AccessControlStrategy admin(PermissionType permission) {
        return AccessControlStrategy.or(PermissionType.getStrategies(permission));
    }

    /** A Perspective client/designer session, or the given gateway permission (API token / admin). */
    private static AccessControlStrategy recorder(PermissionType permission) {
        List<AccessControlStrategy> any = new ArrayList<>();
        any.add(Routes.requireSession(EnumSet.of(SessionScope.Client, SessionScope.Designer)));
        any.addAll(PermissionType.getStrategies(permission));
        return AccessControlStrategy.or(any);
    }

    private static void route(RouteGroup routes, String path, HttpMethod method, AccessControlStrategy access,
                              JsonHandler handler) {
        routes.newRoute(API + path)
            .method(method)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .accessControl(access)
            .handler((req, res) -> {
                try {
                    return handler.handle(req, res);
                } catch (ApiException e) {
                    res.setStatus(e.status());
                    return error(e.getMessage());
                } catch (Exception e) {
                    log.error("Unhandled error in " + method + " " + path, e);
                    res.setStatus(500);
                    return error("Internal error: " + e.getMessage());
                }
            })
            .mount();
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message == null ? "error" : message);
        return o;
    }

    private static JsonObject readJson(RequestContext req) throws ApiException {
        JsonObject o = readJsonOrEmpty(req);
        if (o.size() == 0) {
            throw new ApiException(400, "Expected a JSON object body");
        }
        return o;
    }

    private static JsonObject readJsonOrEmpty(RequestContext req) throws ApiException {
        try {
            String body = req.readBody();
            if (body == null || body.isBlank()) {
                return new JsonObject();
            }
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject()) {
                throw new ApiException(400, "Expected a JSON object body");
            }
            return e.getAsJsonObject();
        } catch (IOException e) {
            throw new ApiException(400, "Unable to read body: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new ApiException(400, "Malformed JSON: " + e.getMessage());
        }
    }

    private static byte[] readBytes(RequestContext req, int max) throws ApiException {
        try (InputStream in = req.getRequest().getInputStream()) {
            byte[] data = in.readNBytes(max + 1);
            if (data.length > max) {
                throw new ApiException(413, "Chunk exceeds " + max + " bytes; upload more often");
            }
            if (data.length == 0) {
                throw new ApiException(400, "Empty audio chunk");
            }
            return data;
        } catch (IOException e) {
            throw new ApiException(400, "Unable to read audio: " + e.getMessage());
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
