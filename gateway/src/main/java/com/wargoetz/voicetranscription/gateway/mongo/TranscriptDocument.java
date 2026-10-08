package com.wargoetz.voicetranscription.gateway.mongo;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.wargoetz.voicetranscription.gateway.web.CallerIdentity;

/**
 * Builds the stored record. Field names, nesting, and order follow the required schema exactly:
 * <pre>
 * { timestamp, transcript, language, summary,
 *   user:   { username, roles[] },
 *   device: { clientAddress, userAgent, sessionType } }
 * </pre>
 * MongoDB adds {@code _id}; nothing else is added. The document is a plain insertion-ordered
 * {@code Map} so it can be handed to the MongoDB Connector without sharing driver classes.
 */
public final class TranscriptDocument {
    private TranscriptDocument() {
    }

    /** ISO-8601 UTC, second precision, e.g. {@code 2026-10-07T13:54:00Z}. */
    public static String timestamp(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    public static Map<String, Object> build(String timestamp, String transcript, String language, String summary,
                                            CallerIdentity who) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", who.username());
        user.put("roles", List.copyOf(who.roles()));

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("clientAddress", who.clientAddress());
        device.put("userAgent", who.userAgent());
        device.put("sessionType", who.sessionType());

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("timestamp", timestamp);
        doc.put("transcript", transcript);
        doc.put("language", language);
        doc.put("summary", summary);
        doc.put("user", user);
        doc.put("device", device);
        return doc;
    }

    /** JSON view of a freshly built document for API responses. */
    @SuppressWarnings("unchecked")
    public static JsonObject toJson(String id, Map<String, Object> doc) {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        out.addProperty("timestamp", (String) doc.get("timestamp"));
        out.addProperty("transcript", (String) doc.get("transcript"));
        out.addProperty("language", (String) doc.get("language"));
        out.addProperty("summary", (String) doc.get("summary"));

        Map<String, Object> u = (Map<String, Object>) doc.get("user");
        JsonObject user = new JsonObject();
        user.addProperty("username", (String) u.get("username"));
        JsonArray roles = new JsonArray();
        ((List<String>) u.get("roles")).forEach(roles::add);
        user.add("roles", roles);
        out.add("user", user);

        Map<String, Object> d = (Map<String, Object>) doc.get("device");
        JsonObject device = new JsonObject();
        device.addProperty("clientAddress", (String) d.get("clientAddress"));
        device.addProperty("userAgent", (String) d.get("userAgent"));
        device.addProperty("sessionType", (String) d.get("sessionType"));
        out.add("device", device);
        return out;
    }

    /** Normalises a document read back through the connector: {@code _id} becomes a plain {@code id}. */
    public static JsonObject fromStored(JsonObject stored) {
        JsonObject out = new JsonObject();
        JsonElement rawId = stored.get("_id");
        if (rawId != null && !rawId.isJsonNull()) {
            out.addProperty("id", rawId.isJsonObject() && rawId.getAsJsonObject().has("$oid")
                ? rawId.getAsJsonObject().get("$oid").getAsString()
                : MongoService.idToString(rawId.isJsonPrimitive() ? rawId.getAsString() : rawId.toString()));
        }
        for (Map.Entry<String, JsonElement> e : stored.entrySet()) {
            if (!e.getKey().equals("_id")) {
                out.add(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
