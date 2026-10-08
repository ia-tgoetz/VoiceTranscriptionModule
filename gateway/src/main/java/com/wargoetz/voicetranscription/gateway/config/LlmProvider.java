package com.wargoetz.voicetranscription.gateway.config;

import java.util.Arrays;

/**
 * Supported OpenAI-compatible chat providers. Every option speaks {@code POST {endpoint}/chat/completions}
 * and {@code GET {endpoint}/models}; only the default base URL differs (the LiteLLM pattern).
 */
public enum LlmProvider {
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1"),
    GEMINI("gemini", "Google Gemini (OpenAI-compatible)", "https://generativelanguage.googleapis.com/v1beta/openai"),
    CUSTOM("custom", "Custom / LiteLLM / Ollama", "http://localhost:4000/v1");

    private final String id;
    private final String label;
    private final String defaultEndpoint;

    LlmProvider(String id, String label, String defaultEndpoint) {
        this.id = id;
        this.label = label;
        this.defaultEndpoint = defaultEndpoint;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String defaultEndpoint() {
        return defaultEndpoint;
    }

    public static LlmProvider fromId(String id) {
        return Arrays.stream(values()).filter(p -> p.id.equalsIgnoreCase(id)).findFirst().orElse(CUSTOM);
    }
}
