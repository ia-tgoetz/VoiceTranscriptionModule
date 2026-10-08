package com.wargoetz.voicetranscription.gateway.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.wargoetz.voicetranscription.gateway.config.TranscriptionSettings;
import com.wargoetz.voicetranscription.gateway.llm.LlmClient;
import com.wargoetz.voicetranscription.gateway.web.CallerIdentity;
import org.junit.jupiter.api.Test;

class TranscriptDocumentTest {

    @Test
    void matchesRequiredSchemaExactly() {
        CallerIdentity who = new CallerIdentity("jdoe", List.of("Operator", "Administrator"), "192.168.1.50",
            "Mozilla/5.0", CallerIdentity.TYPE_WEB, "perspective:abc");
        Map<String, Object> doc = TranscriptDocument.build("2026-10-07T13:54:00Z",
            "The quick brown fox jumps over the lazy dog.", "en", "Brief summary.", who);

        assertEquals(List.of("timestamp", "transcript", "language", "summary", "user", "device"),
            List.copyOf(doc.keySet()));
        assertEquals(List.of("username", "roles"), List.copyOf(((Map<?, ?>) doc.get("user")).keySet()));
        assertEquals(List.of("clientAddress", "userAgent", "sessionType"),
            List.copyOf(((Map<?, ?>) doc.get("device")).keySet()));

        JsonObject json = TranscriptDocument.toJson("6703f0c2a1b2c3d4e5f60718", doc);
        assertEquals("{\"id\":\"6703f0c2a1b2c3d4e5f60718\",\"timestamp\":\"2026-10-07T13:54:00Z\","
                + "\"transcript\":\"The quick brown fox jumps over the lazy dog.\",\"language\":\"en\","
                + "\"summary\":\"Brief summary.\",\"user\":{\"username\":\"jdoe\","
                + "\"roles\":[\"Operator\",\"Administrator\"]},\"device\":{\"clientAddress\":\"192.168.1.50\","
                + "\"userAgent\":\"Mozilla/5.0\",\"sessionType\":\"Perspective Web\"}}",
            json.toString());
    }

    @Test
    void storedIdIsNormalised() {
        JsonObject stored = new JsonObject();
        JsonObject oid = new JsonObject();
        oid.addProperty("$oid", "6703f0c2a1b2c3d4e5f60718");
        stored.add("_id", oid);
        stored.addProperty("transcript", "x");
        JsonObject out = TranscriptDocument.fromStored(stored);
        assertEquals("6703f0c2a1b2c3d4e5f60718", out.get("id").getAsString());
        assertEquals(false, out.has("_id"));
        assertEquals("6703f0c2a1b2c3d4e5f60718", MongoService.idToString("BsonObjectId{value=6703f0c2a1b2c3d4e5f60718}"));
    }

    @Test
    void timestampIsSecondPrecisionUtc() {
        assertEquals("2026-10-07T13:54:00Z",
            TranscriptDocument.timestamp(Instant.parse("2026-10-07T13:54:00.987654Z")));
    }

    @Test
    void promptTemplating() {
        assertEquals("Hi jdoe (en)", LlmClient.renderPrompt("Hi {{username}} ({{language}})",
            Map.of("username", "jdoe", "language", "en")));
    }

    @Test
    void settingsDefaultsAreSane() {
        TranscriptionSettings s = TranscriptionSettings.DEFAULT;
        assertEquals("", s.mongo().connector());
        assertEquals("transcriptions", s.mongo().collection());
        assertEquals(3000, s.whisper().chunkIntervalMs());
        assertEquals("https://api.openai.com/v1", s.llm().endpointUrl());
        assertEquals(false, TranscriptionSettings.isValidCollectionName("system.users"));
        assertEquals(java.util.Optional.empty(), TranscriptionSettings.validationMessage(s));
    }

    @Test
    void invalidSettingsAreReported() {
        TranscriptionSettings bad = new TranscriptionSettings(
            new TranscriptionSettings.Mongo("VoiceMongo", "system.users"), null,
            new TranscriptionSettings.Llm(true, "custom", "ftp://x", "", null, null, 0, 0.2));
        String msg = TranscriptionSettings.validationMessage(bad).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(msg.contains("mongo.collection"), msg);
        org.junit.jupiter.api.Assertions.assertTrue(msg.contains("llm.endpointUrl"), msg);
        org.junit.jupiter.api.Assertions.assertTrue(msg.contains("llm.model"), msg);
    }
}
