package com.wargoetz.voicetranscription.gateway.mongo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.inductiveautomation.connectors.mongodb.common.connector.ConnectorInfo;
import com.inductiveautomation.connectors.mongodb.gateway.scripting.GatewayMongoFunctions;
import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.wargoetz.voicetranscription.gateway.config.TranscriptionSettings;

/**
 * MongoDB access through Inductive Automation's <b>MongoDB Connector</b> module.
 *
 * <p>This module never opens its own MongoDB connection. Connections ("connectors") are configured once
 * under <i>Connections &gt; Service Connectors</i> and owned by the connector module - which handles hosts,
 * credentials (as gateway secrets), TLS, pooling, reconnects, and health status. We reuse a connector by name
 * via {@link GatewayMongoFunctions}, the same blocking implementation that backs {@code system.mongodb.*}
 * in gateway scripts. It is built from the platform's {@code ServiceConnectorManager}.</p>
 *
 * <p>Classloading: the connector module is a declared, required dependency of ours (root build.gradle.kts),
 * so its classes are visible to our module at runtime; we compile against its jars but never bundle them or
 * a Mongo driver. Only JDK and Ignition-platform types cross the boundary: documents go in as
 * {@code java.util.Map}, filters and results use Ignition's {@code JsonObject}.</p>
 */
public final class MongoService {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Mongo");

    private final GatewayMongoFunctions mongo;
    private volatile TranscriptionSettings.Mongo config = new TranscriptionSettings.Mongo(null, null);
    private volatile String lastError;

    public MongoService(GatewayContext context) {
        this.mongo = new GatewayMongoFunctions(context.getServiceConnectorManager());
    }

    public void configure(TranscriptionSettings.Mongo config) {
        this.config = config;
        this.lastError = null;
        if (isConfigured()) {
            log.infof("Using MongoDB connector '%s', collection '%s'", config.connector(), config.collection());
        }
    }

    public boolean isConfigured() {
        return !config.connector().isEmpty();
    }

    public String connector() {
        return config.connector();
    }

    public String lastError() {
        return lastError;
    }

    /** Connectors defined on this gateway: name, description, database. */
    public JsonArray listConnectors() {
        JsonArray out = new JsonArray();
        for (ConnectorInfo info : mongo.listConnectorInfo()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", info.getName());
            o.addProperty("description", info.getDescription());
            o.addProperty("database", info.getDatabase());
            out.add(o);
        }
        return out;
    }

    /** Database configured on the selected connector (for display), if the connector exists. */
    public Optional<String> database() {
        String name = config.connector();
        return mongo.listConnectorInfo().stream()
            .filter(i -> i.getName().equals(name))
            .map(ConnectorInfo::getDatabase)
            .findFirst();
    }

    /** Round-trips to the server; returns null when healthy, else the failure message. */
    public String ping() {
        if (!isConfigured()) {
            return lastError = "No MongoDB connector selected";
        }
        try {
            mongo.listCollectionNames(config.connector());
            return lastError = null;
        } catch (Exception e) {
            return lastError = describe(e);
        }
    }

    public List<String> listCollections(String connector) throws MongoException {
        try {
            return mongo.listCollectionNames(connector).stream()
                .filter(n -> !n.startsWith("system.")).sorted().toList();
        } catch (Exception e) {
            throw new MongoException(describe(e));
        }
    }

    /** Inserts one document; returns the new {@code _id} as a hex string. */
    public String insert(Map<String, Object> document) throws MongoException {
        if (!TranscriptionSettings.isValidCollectionName(config.collection())) {
            throw new MongoException("Refusing to write to collection '" + config.collection() + "'");
        }
        try {
            return idToString(mongo.insertOne(config.connector(), config.collection(), document, null));
        } catch (Exception e) {
            throw new MongoException(describe(e));
        }
    }

    public List<JsonObject> recent(int limit) throws MongoException {
        JsonObject sort = new JsonObject();
        sort.addProperty("timestamp", -1);
        return find(new JsonObject(), sort, limit);
    }

    public Optional<JsonObject> findById(String id) throws MongoException {
        // {$expr: {$eq: [{$toString: "$_id"}, id]}} - avoids needing an ObjectId type on our side.
        JsonArray eq = new JsonArray();
        JsonObject toString = new JsonObject();
        toString.addProperty("$toString", "$_id");
        eq.add(toString);
        eq.add(id);
        JsonObject expr = new JsonObject();
        expr.add("$eq", eq);
        JsonObject filter = new JsonObject();
        filter.add("$expr", expr);
        return find(filter, null, 1).stream().findFirst();
    }

    /**
     * Retention policy. {@code timestamp} is stored as an ISO-8601 UTC string (exactly as the schema
     * specifies), which sorts lexicographically, so a string range delete is correct.
     */
    public long purgeOlderThan(String isoCutoff) throws MongoException {
        JsonObject lt = new JsonObject();
        lt.addProperty("$lt", isoCutoff);
        JsonObject filter = new JsonObject();
        filter.add("timestamp", lt);
        try {
            return mongo.delete(config.connector(), config.collection(), filter, null, true).getDeleteCount();
        } catch (Exception e) {
            throw new MongoException(describe(e));
        }
    }

    private List<JsonObject> find(JsonObject filter, JsonObject sort, int limit) throws MongoException {
        try {
            return new ArrayList<>(mongo.find(config.connector(), config.collection(), filter, null, sort, null,
                limit, null, JsonObject.class));
        } catch (Exception e) {
            throw new MongoException(describe(e));
        }
    }

    /** insertOne returns whatever the connector's codecs produced for the id; normalise to the hex string. */
    static String idToString(Object id) {
        if (id == null) {
            return "";
        }
        if (id instanceof JsonObject o && o.has("$oid")) {
            return o.get("$oid").getAsString();
        }
        String s = String.valueOf(id);
        // e.g. BsonObjectId{value=6703f0c2...}
        int start = s.indexOf("value=");
        if (start >= 0 && s.endsWith("}")) {
            return s.substring(start + 6, s.length() - 1);
        }
        return s;
    }

    private static String describe(Exception e) {
        String msg = e.getMessage();
        if (e.getClass().getSimpleName().equals("TrialExpiredException")) {
            return "MongoDB Connector trial expired - license the module or restart the trial";
        }
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
    }

    /** Wraps every connector failure in one checked type; mapped to HTTP 502/503 by the routes. */
    public static class MongoException extends Exception {
        public MongoException(String message) {
            super(message);
        }
    }
}
