package com.wargoetz.voicetranscription.gateway.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.wargoetz.voicetranscription.gateway.config.TranscriptionSettings;

/**
 * Minimal OpenAI-compatible client on {@link java.net.http.HttpClient} - no SDK dependency to audit or
 * shade. Works against OpenAI, Gemini's OpenAI-compatible endpoint, LiteLLM, vLLM, and Ollama.
 *
 * <p>This is the only network egress in the module, and it is optional: with the LLM disabled the module
 * is fully air-gapped (Whisper runs locally, MongoDB is on the plant network).</p>
 */
public final class LlmClient {
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** Replaces {@code {{name}}} placeholders in the admin's system-prompt template. */
    public static String renderPrompt(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    public String summarize(TranscriptionSettings.Llm cfg, Optional<String> apiKey, String systemPrompt,
                            String transcript) throws LlmException {
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model());
        body.addProperty("temperature", cfg.temperature());
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", transcript));
        body.add("messages", messages);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(cfg.endpointUrl() + "/chat/completions"))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        apiKey.ifPresent(k -> req.header("Authorization", "Bearer " + k));

        JsonObject json = send(req.build());
        try {
            JsonElement content = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content");
            return content == null || content.isJsonNull() ? "" : content.getAsString().trim();
        } catch (RuntimeException e) {
            throw new LlmException("Unexpected chat/completions response shape");
        }
    }

    /**
     * Ids that can't serve chat/completions summaries: embeddings, speech, images, moderation, legacy
     * completions (OpenAI) and embedding/image/QA models (Gemini). {@code /models} lists them alongside chat models.
     */
    private static final java.util.regex.Pattern NON_CHAT = java.util.regex.Pattern.compile(
        "(?i).*(embed|tts|whisper|transcribe|dall-e|image|imagen|veo|sora|moderation|realtime|audio|babbage|davinci|^aqa).*");

    public static boolean isChatModel(String id) {
        return !NON_CHAT.matcher(id).matches();
    }

    public List<String> listModels(TranscriptionSettings.Llm cfg, Optional<String> apiKey) throws LlmException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(cfg.endpointUrl() + "/models"))
            .timeout(Duration.ofSeconds(Math.min(cfg.timeoutSeconds(), 30)))
            .GET();
        apiKey.ifPresent(k -> req.header("Authorization", "Bearer " + k));
        JsonObject json = send(req.build());
        List<String> ids = new ArrayList<>();
        if (json.has("data") && json.get("data").isJsonArray()) {
            for (JsonElement m : json.getAsJsonArray("data")) {
                if (m.isJsonObject() && m.getAsJsonObject().has("id")) {
                    // Gemini prefixes ids with "models/"; chat/completions accepts the bare id.
                    ids.add(m.getAsJsonObject().get("id").getAsString().replaceFirst("^models/", ""));
                }
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private JsonObject send(HttpRequest request) throws LlmException {
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException("LLM endpoint returned HTTP " + resp.statusCode() + ": " + abbreviate(resp.body()));
            }
            return JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (IOException e) {
            throw new LlmException("LLM request failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM request interrupted");
        } catch (IllegalStateException e) {
            throw new LlmException("LLM endpoint returned non-JSON");
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    public static class LlmException extends Exception {
        public LlmException(String message) {
            super(message);
        }
    }
}
