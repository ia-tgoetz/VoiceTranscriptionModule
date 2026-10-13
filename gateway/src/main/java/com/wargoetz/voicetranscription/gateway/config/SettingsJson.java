package com.wargoetz.voicetranscription.gateway.config;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.wargoetz.voicetranscription.gateway.web.ApiException;

/**
 * Translates {@link TranscriptionSettings} to/from the JSON exchanged with the gateway config page.
 * Secret fields are always redacted on the way out (see {@link Secrets#describe}) and accept the
 * keep / none / embedded / referenced commands on the way in.
 */
public final class SettingsJson {
    private SettingsJson() {
    }

    public static JsonObject toJson(TranscriptionSettings s) {
        JsonObject mongo = new JsonObject();
        mongo.addProperty("connector", s.mongo().connector());
        mongo.addProperty("collection", s.mongo().collection());

        JsonObject whisper = new JsonObject();
        TranscriptionSettings.Whisper w = s.whisper();
        whisper.addProperty("modelPath", w.modelPath());
        whisper.addProperty("defaultLanguage", w.defaultLanguage());
        whisper.addProperty("autoDetect", w.autoDetect());
        whisper.addProperty("threads", w.threads());
        whisper.addProperty("chunkIntervalMs", w.chunkIntervalMs());
        whisper.addProperty("retentionDays", w.retentionDays());
        whisper.addProperty("maxRecordingSeconds", w.maxRecordingSeconds());
        whisper.addProperty("maxConcurrentTranscriptions", w.maxConcurrentTranscriptions());
        whisper.addProperty("modelSha256", w.modelSha256());
        whisper.addProperty("noiseFilter", w.noiseFilter());
        whisper.addProperty("modelsDir", w.modelsDir());

        JsonObject llm = new JsonObject();
        TranscriptionSettings.Llm l = s.llm();
        llm.addProperty("enabled", l.enabled());
        llm.addProperty("provider", l.provider());
        llm.addProperty("endpointUrl", l.endpointUrl());
        llm.addProperty("model", l.model());
        llm.addProperty("systemPrompt", l.systemPrompt());
        llm.add("apiKey", Secrets.describe(l.apiKey()));
        llm.addProperty("timeoutSeconds", l.timeoutSeconds());
        llm.addProperty("temperature", l.temperature());

        JsonObject out = new JsonObject();
        out.add("mongo", mongo);
        out.add("whisper", whisper);
        out.add("llm", llm);
        return out;
    }

    /** Merges a (possibly partial) update over {@code current}. Missing fields keep their current value. */
    public static TranscriptionSettings fromJson(GatewayContext context, JsonObject in, TranscriptionSettings current)
        throws ApiException {
        try {
            JsonObject m = obj(in, "mongo");
            TranscriptionSettings.Mongo cm = current.mongo();
            TranscriptionSettings.Mongo mongo = new TranscriptionSettings.Mongo(
                str(m, "connector", cm.connector()),
                str(m, "collection", cm.collection()));

            JsonObject w = obj(in, "whisper");
            TranscriptionSettings.Whisper cw = current.whisper();
            TranscriptionSettings.Whisper whisper = new TranscriptionSettings.Whisper(
                str(w, "modelPath", cw.modelPath()),
                str(w, "defaultLanguage", cw.defaultLanguage()),
                bool(w, "autoDetect", cw.autoDetect()),
                integer(w, "threads", cw.threads()),
                integer(w, "chunkIntervalMs", cw.chunkIntervalMs()),
                integer(w, "retentionDays", cw.retentionDays()),
                integer(w, "maxRecordingSeconds", cw.maxRecordingSeconds()),
                integer(w, "maxConcurrentTranscriptions", cw.maxConcurrentTranscriptions()),
                str(w, "modelSha256", cw.modelSha256()),
                str(w, "noiseFilter", cw.noiseFilter()),
                str(w, "modelsDir", cw.modelsDir()));

            JsonObject l = obj(in, "llm");
            TranscriptionSettings.Llm cl = current.llm();
            TranscriptionSettings.Llm llm = new TranscriptionSettings.Llm(
                bool(l, "enabled", cl.enabled()),
                str(l, "provider", cl.provider()),
                str(l, "endpointUrl", cl.endpointUrl()),
                str(l, "model", cl.model()),
                str(l, "systemPrompt", cl.systemPrompt()),
                Secrets.apply(context, l.get("apiKey"), cl.apiKey()),
                integer(l, "timeoutSeconds", cl.timeoutSeconds()),
                dbl(l, "temperature", cl.temperature()));

            return new TranscriptionSettings(mongo, whisper, llm);
        } catch (ClassCastException | IllegalStateException | NumberFormatException e) {
            throw new ApiException(400, "Malformed settings JSON: " + e.getMessage());
        }
    }

    /** Provider presets for the config page's dropdown. */
    public static JsonArray providers() {
        JsonArray out = new JsonArray();
        for (LlmProvider p : LlmProvider.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id());
            o.addProperty("label", p.label());
            o.addProperty("defaultEndpoint", p.defaultEndpoint());
            out.add(o);
        }
        return out;
    }

    private static JsonObject obj(JsonObject in, String key) {
        JsonElement e = in == null ? null : in.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static String str(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
    }

    private static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsInt();
    }

    private static double dbl(JsonObject o, String key, double fallback) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsDouble();
    }
}
