package com.wargoetz.voicetranscription.gateway.config;

import com.inductiveautomation.ignition.common.resourcecollection.ResourceType;
import com.inductiveautomation.ignition.gateway.config.ResourceTypeMeta;
import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.wargoetz.voicetranscription.gateway.audio.NoiseFilter;
import com.wargoetz.voicetranscription.common.VoiceTranscriptionModule;

/**
 * Gateway-wide module settings, persisted by the 8.3 configuration system as a singleton resource at
 * {@code data/config/resources/core/com.wargoetz.voicetranscription/settings/config.json}.
 *
 * <p>Secrets ({@link SecretConfig}) are never stored in plaintext: an "embedded" secret holds ciphertext
 * produced by the gateway's SystemEncryptionService, a "referenced" secret points at a Secret Provider.
 * Because settings are a config resource they are captured by gateway backups, deployment modes, and the
 * config file-system scan, exactly like built-in gateway settings.</p>
 */
public record TranscriptionSettings(Mongo mongo, Whisper whisper, Llm llm) {

    public static final String DEFAULT_MODEL_PATH = "voicetranscription/models/ggml-base.bin";

    public static final String DEFAULT_SYSTEM_PROMPT =
        "You are an assistant for industrial operations. Summarize the operator's spoken note in 1-3 "
            + "sentences. Lead with any equipment, fault, or action mentioned. Do not invent details.\n"
            + "Operator: {{username}} ({{roles}}). Language: {{language}}. Recorded: {{timestamp}}.";

    /**
     * MongoDB target. The connection itself (hosts, database, credentials, TLS, pooling) is owned by a
     * MongoDB Connector connection configured under Connections > Service Connectors; this module only
     * names which connector to use and which collection to write to.
     */
    public record Mongo(String connector, String collection) {
        public Mongo {
            connector = blankTo(connector, "");
            collection = blankTo(collection, "transcriptions");
        }
    }

    /**
     * Whisper engine + recording policy. {@code modelPath} is resolved against the gateway data dir when
     * relative. {@code retentionDays} = 0 keeps transcripts forever. {@code modelSha256}, when set, must match
     * the model file or it is not loaded: whisper.cpp crashes on some malformed model files
     * (CVE-2026-10298, CVE-2026-17513) and a native crash takes the whole gateway JVM down with it.
     */
    public record Whisper(
        String modelPath,
        String defaultLanguage,
        boolean autoDetect,
        int threads,
        int chunkIntervalMs,
        int retentionDays,
        int maxRecordingSeconds,
        int maxConcurrentTranscriptions,
        String modelSha256,
        String noiseFilter
    ) {
        public Whisper {
            modelPath = blankTo(modelPath, DEFAULT_MODEL_PATH);
            defaultLanguage = blankTo(defaultLanguage, "en").toLowerCase();
            threads = clamp(threads, 1, 32, 4);
            chunkIntervalMs = clamp(chunkIntervalMs, 500, 30_000, 3000);
            retentionDays = Math.max(0, retentionDays);
            maxRecordingSeconds = clamp(maxRecordingSeconds, 5, 1800, 300);
            maxConcurrentTranscriptions = clamp(maxConcurrentTranscriptions, 1, 16, 2);
            modelSha256 = blankTo(modelSha256, "").toLowerCase();
            noiseFilter = NoiseFilter.Level.fromId(blankTo(noiseFilter, "standard")).id();
        }
    }

    /** Optional summarizer. {@code apiKey} is a gateway secret (embedded ciphertext or provider reference). */
    public record Llm(
        boolean enabled,
        String provider,
        String endpointUrl,
        String model,
        String systemPrompt,
        SecretConfig apiKey,
        int timeoutSeconds,
        double temperature
    ) {
        public Llm {
            provider = LlmProvider.fromId(blankTo(provider, "openai")).id();
            endpointUrl = stripTrailingSlash(blankTo(endpointUrl, LlmProvider.fromId(provider).defaultEndpoint()));
            model = blankTo(model, "");
            systemPrompt = blankTo(systemPrompt, DEFAULT_SYSTEM_PROMPT);
            timeoutSeconds = clamp(timeoutSeconds, 5, 600, 60);
            temperature = (temperature < 0 || temperature > 2) ? 0.2 : temperature;
        }
    }

    public TranscriptionSettings {
        mongo = mongo != null ? mongo : new Mongo(null, null);
        whisper = whisper != null ? whisper : new Whisper(null, null, false, 0, 0, 0, 0, 0, null, null);
        llm = llm != null ? llm : new Llm(false, null, null, null, null, null, 0, 0.2);
    }

    public static final TranscriptionSettings DEFAULT = new TranscriptionSettings(null, null, null);

    public static final ResourceType RESOURCE_TYPE =
        new ResourceType(VoiceTranscriptionModule.MODULE_ID, "settings");

    public static final ResourceTypeMeta<TranscriptionSettings> META =
        ResourceTypeMeta.newBuilder(TranscriptionSettings.class)
            .resourceType(RESOURCE_TYPE)
            .singleton()
            .defaultConfig(DEFAULT)
            .categoryName("Offline Voice Transcription")
            .buildValidator(TranscriptionSettings::validate)
            .build();

    /**
     * Shared validation. Registered on {@link #META} (runs when the resource is edited through the gateway's
     * generic config API / file scans) and called explicitly by our own PUT /config route, because
     * {@code SingletonResourceHandler.updateResource()} does not run the resource-type validator.
     */
    public static void validate(TranscriptionSettings settings, ValidationErrors.Builder errors) {
        Mongo m = settings.mongo();
        errors.checkField(isValidCollectionName(m.collection()), "mongo.collection",
            "Collection names cannot be empty, start with 'system.', or contain $");
        String sha = settings.whisper().modelSha256();
        errors.checkField(sha.isEmpty() || sha.matches("[0-9a-f]{64}"), "whisper.modelSha256",
            "Must be empty or a 64-character hex SHA-256");
        errors.checkField(settings.whisper().defaultLanguage().matches("[a-z]{2,3}"),
            "whisper.defaultLanguage", "Use an ISO-639-1 code such as 'en'");
        Llm llm = settings.llm();
        if (llm.enabled()) {
            errors.checkField(llm.endpointUrl().startsWith("http://") || llm.endpointUrl().startsWith("https://"),
                "llm.endpointUrl", "Endpoint must be an http(s) URL");
            errors.requireNotBlank("llm.model", llm.model());
        }
    }

    /** Human-readable summary of validation failures, or empty when valid. */
    public static java.util.Optional<String> validationMessage(TranscriptionSettings settings) {
        ValidationErrors.Builder b = ValidationErrors.newBuilder();
        validate(settings, b);
        return b.buildIfNotEmpty().map(errors -> {
            java.util.List<String> parts = new java.util.ArrayList<>(errors.messages());
            errors.fieldMessages().forEach(f -> parts.add(f.fieldName() + ": " + String.join(", ", f.messages())));
            return String.join("; ", parts);
        });
    }

    public static boolean isValidCollectionName(String name) {
        return name != null && !name.isBlank() && name.length() < 120 && !name.startsWith("system.")
            && !name.contains("$") && !name.contains("\0");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static int clamp(int value, int min, int max, int fallbackWhenZero) {
        if (value == 0) {
            return fallbackWhenZero;
        }
        return Math.max(min, Math.min(max, value));
    }
}
