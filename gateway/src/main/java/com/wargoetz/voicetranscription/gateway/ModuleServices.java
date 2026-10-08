package com.wargoetz.voicetranscription.gateway;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.config.SingletonResourceHandler;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.wargoetz.voicetranscription.gateway.config.Secrets;
import com.wargoetz.voicetranscription.gateway.config.TranscriptionSettings;
import com.wargoetz.voicetranscription.gateway.llm.LlmClient;
import com.wargoetz.voicetranscription.gateway.mongo.MongoService;
import com.wargoetz.voicetranscription.gateway.mongo.TranscriptDocument;
import com.wargoetz.voicetranscription.gateway.recording.RecordingManager;
import com.wargoetz.voicetranscription.gateway.recording.RecordingSession;
import com.wargoetz.voicetranscription.gateway.web.ApiException;
import com.wargoetz.voicetranscription.gateway.whisper.WhisperService;

/**
 * Everything the GatewayHook starts and the REST routes use. One instance per gateway run.
 */
public final class ModuleServices {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription");

    /** Recordings with no traffic for this long are discarded (browser closed mid-recording, etc.). */
    static final long RECORDING_IDLE_MS = TimeUnit.MINUTES.toMillis(10);

    final GatewayContext context;
    final SingletonResourceHandler<TranscriptionSettings> settingsHandler;
    final WhisperService whisper = new WhisperService();
    final MongoService mongo;
    final LlmClient llm = new LlmClient();
    final RecordingManager recordings;

    /** Model loads can take seconds; never do them on the config/gateway thread. */
    private final ExecutorService configExecutor =
        Executors.newSingleThreadExecutor(r -> daemon(r, "voicetranscription-config"));
    private final ScheduledExecutorService maintenance =
        Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voicetranscription-maintenance"));

    private volatile TranscriptionSettings applied;

    ModuleServices(GatewayContext context) {
        this.context = context;
        this.mongo = new MongoService(context);
        this.settingsHandler = SingletonResourceHandler.newBuilder(TranscriptionSettings.META)
            .context(context)
            .onChange(s -> configExecutor.execute(() -> apply(s)))
            .build();
        this.recordings = new RecordingManager(whisper, this::settings);
    }

    void startup() {
        settingsHandler.startup();
        TranscriptionSettings initial = settingsHandler.getResource();
        configExecutor.execute(() -> apply(initial));
        maintenance.scheduleWithFixedDelay(() -> recordings.sweep(RECORDING_IDLE_MS), 1, 1, TimeUnit.MINUTES);
        maintenance.scheduleWithFixedDelay(this::runRetention, 5, 360, TimeUnit.MINUTES);
    }

    void shutdown() {
        maintenance.shutdownNow();
        configExecutor.shutdownNow();
        recordings.clear();
        settingsHandler.shutdown();
        whisper.shutdown();
    }

    public TranscriptionSettings settings() {
        TranscriptionSettings s = applied;
        return s != null ? s : settingsHandler.getResource();
    }

    public SingletonResourceHandler<TranscriptionSettings> settingsHandler() {
        return settingsHandler;
    }

    public GatewayContext context() {
        return context;
    }

    public WhisperService whisper() {
        return whisper;
    }

    public MongoService mongo() {
        return mongo;
    }

    public LlmClient llm() {
        return llm;
    }

    public RecordingManager recordings() {
        return recordings;
    }

    /** Applies settings synchronously (used by the config route so its response reflects the new state). */
    public void applyNow(TranscriptionSettings next) {
        apply(next);
    }

    /** Applies a settings change, touching only the subsystems whose settings actually changed. */
    synchronized void apply(TranscriptionSettings next) {
        TranscriptionSettings prev = applied;
        applied = next;
        if (prev == null || !Objects.equals(prev.mongo(), next.mongo())) {
            mongo.configure(next.mongo());
        }
        TranscriptionSettings.Whisper w = next.whisper();
        if (prev == null || !prev.whisper().modelPath().equals(w.modelPath())
            || !prev.whisper().modelSha256().equals(w.modelSha256())
            || prev.whisper().maxConcurrentTranscriptions() != w.maxConcurrentTranscriptions()) {
            whisper.load(resolveModelPath(w.modelPath()), w.maxConcurrentTranscriptions(), w.modelSha256());
        }
        if (prev == null || prev.whisper().retentionDays() != w.retentionDays()) {
            runRetention();
        }
    }

    public Path resolveModelPath(String modelPath) {
        Path p = Path.of(modelPath);
        return p.isAbsolute() ? p : context.getSystemManager().getDataDir().toPath().toAbsolutePath().resolve(p).normalize();
    }

    void runRetention() {
        int days = settings().whisper().retentionDays();
        if (days <= 0 || !mongo.isConfigured()) {
            return;
        }
        try {
            String cutoff = TranscriptDocument.timestamp(Instant.now().minus(days, ChronoUnit.DAYS));
            long deleted = mongo.purgeOlderThan(cutoff);
            if (deleted > 0) {
                log.infof("Retention: deleted %d transcript(s) older than %s", deleted, cutoff);
            }
        } catch (MongoService.MongoException | RuntimeException e) {
            log.warn("Retention purge failed: " + e.getMessage());
        }
    }

    /**
     * Final step of a recording: final Whisper pass, optional LLM summary, MongoDB insert. On a MongoDB
     * failure the recording (and its final transcript) is kept so the client can retry the submit.
     */
    public JsonObject submit(RecordingSession session, String editedTranscript, boolean summarize)
        throws ApiException {
        RecordingManager.StopResult stop = recordings.stop(session);
        String transcript = editedTranscript != null && !editedTranscript.isBlank()
            ? editedTranscript.trim() : stop.transcript();
        if (transcript.isBlank()) {
            throw new ApiException(422, "No speech was detected; check the audio source and record again");
        }
        if (!mongo.isConfigured()) {
            throw new ApiException(503, "No MongoDB connector selected in the module settings");
        }

        String timestamp = TranscriptDocument.timestamp(Instant.now());
        String summary = "";
        String summaryError = null;
        TranscriptionSettings.Llm llmCfg = settings().llm();
        if (summarize && llmCfg.enabled()) {
            try {
                Map<String, String> vars = new LinkedHashMap<>();
                vars.put("username", session.identity().username());
                vars.put("roles", String.join(", ", session.identity().roles()));
                vars.put("language", stop.language());
                vars.put("timestamp", timestamp);
                vars.put("clientAddress", session.identity().clientAddress());
                vars.put("sessionType", session.identity().sessionType());
                vars.put("userAgent", session.identity().userAgent());
                summary = llm.summarize(llmCfg, Secrets.reveal(context, llmCfg.apiKey()),
                    LlmClient.renderPrompt(llmCfg.systemPrompt(), vars), transcript);
            } catch (LlmClient.LlmException | ApiException e) {
                // The transcript is the primary record; a summarizer outage must not lose it.
                summaryError = e.getMessage();
                log.warn("Summary skipped: " + e.getMessage());
            }
        }

        Map<String, Object> doc =
            TranscriptDocument.build(timestamp, transcript, stop.language(), summary, session.identity());
        String id;
        try {
            id = mongo.insert(doc);
        } catch (MongoService.MongoException | RuntimeException e) {
            throw new ApiException(503, "MongoDB insert failed: " + e.getMessage());
        }
        recordings.remove(session.id());
        log.infof("Stored transcript %s via connector '%s' from %s (%.1fs audio, language=%s)", id,
            mongo.connector(), session.identity().username(), stop.durationSeconds(), stop.language());

        JsonObject out = TranscriptDocument.toJson(id, doc);
        out.addProperty("durationSeconds", stop.durationSeconds());
        if (summaryError != null) {
            out.addProperty("summaryError", summaryError);
        }
        return out;
    }

    public Optional<JsonObject> findTranscript(String id) throws ApiException {
        if (id == null || !id.matches("[0-9a-fA-F]{24}")) {
            return Optional.empty();
        }
        try {
            return mongo.findById(id).map(TranscriptDocument::fromStored);
        } catch (MongoService.MongoException e) {
            throw new ApiException(502, "MongoDB error: " + e.getMessage());
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

}
