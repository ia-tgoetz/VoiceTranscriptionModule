package com.wargoetz.voicetranscription.gateway.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.ManagedSecretProvider;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.wargoetz.voicetranscription.gateway.web.ApiException;

/**
 * Bridges the config page and the Ignition 8.3 Secrets Management API.
 *
 * <ul>
 *   <li><b>Embedded</b> - the plaintext the admin typed is encrypted with the gateway's
 *   {@code SystemEncryptionService} and only the ciphertext JSON is persisted
 *   ({@link SecretConfig#embedded(JsonElement)}).</li>
 *   <li><b>Referenced</b> - only a (provider, secret name) pair is persisted
 *   ({@link SecretConfig#referenced(String, String)}); the value lives in a Secret Provider such as
 *   HashiCorp Vault, Azure Key Vault, or the gateway's internal provider.</li>
 * </ul>
 * Plaintext is materialised only at the moment of use, via {@code Secret.create(...).getPlaintext()} in a
 * try-with-resources so the backing byte array is wiped, and it is never sent back to the browser.
 */
public final class Secrets {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Secrets");

    private Secrets() {
    }

    /** Decrypts / dereferences a secret for immediate use. Empty when unset or blank. */
    public static Optional<String> reveal(GatewayContext context, SecretConfig config) throws ApiException {
        if (config == null) {
            return Optional.empty();
        }
        try (Plaintext plaintext = Secret.create(context, config).getPlaintext()) {
            String value = plaintext.getAsString(StandardCharsets.UTF_8);
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (Exception e) {
            throw new ApiException(500, "Unable to read secret: " + e.getMessage());
        }
    }

    /** Redacted view for the config page: what kind of secret is configured, never its value. */
    public static JsonObject describe(SecretConfig config) {
        JsonObject out = new JsonObject();
        out.addProperty("configured", config != null);
        if (config == null) {
            out.addProperty("mode", "none");
        } else if (config.isReferenced()) {
            out.addProperty("mode", "referenced");
            out.addProperty("providerName", config.getAsReferenced().getProviderName());
            out.addProperty("secretName", config.getAsReferenced().getSecretName());
        } else {
            out.addProperty("mode", "embedded");
        }
        return out;
    }

    /**
     * Applies a secret update from the config page.
     * <pre>
     * absent / {"mode":"keep"}                          -> keep the existing secret
     * {"mode":"none"}                                    -> clear it
     * {"mode":"embedded","value":"sk-..."}               -> encrypt + embed
     * {"mode":"referenced","providerName":"vault","secretName":"openai"} -> reference a provider secret
     * </pre>
     */
    public static SecretConfig apply(GatewayContext context, JsonElement input, SecretConfig existing)
        throws ApiException {
        if (input == null || input.isJsonNull() || !input.isJsonObject()) {
            return existing;
        }
        JsonObject obj = input.getAsJsonObject();
        String mode = obj.has("mode") ? obj.get("mode").getAsString() : "keep";
        switch (mode) {
            case "keep":
                return existing;
            case "none":
                return null;
            case "embedded": {
                String value = obj.has("value") && !obj.get("value").isJsonNull() ? obj.get("value").getAsString() : "";
                if (value.isEmpty()) {
                    // Switching an existing embedded secret's form without retyping it keeps the old value.
                    if (existing != null && existing.isEmbedded()) {
                        return existing;
                    }
                    throw new ApiException(400, "A value is required for an embedded secret");
                }
                try (Plaintext plaintext = Plaintext.fromString(value, StandardCharsets.UTF_8)) {
                    return SecretConfig.embedded(context.getSystemEncryptionService().encryptToJson(plaintext));
                } catch (Exception e) {
                    throw new ApiException(500, "Unable to encrypt secret: " + e.getMessage());
                }
            }
            case "referenced": {
                String provider = optString(obj, "providerName");
                String name = optString(obj, "secretName");
                if (provider.isEmpty() || name.isEmpty()) {
                    throw new ApiException(400, "providerName and secretName are required for a referenced secret");
                }
                if (context.getSecretProviderManager().getProvider(provider).isEmpty()) {
                    throw new ApiException(400, "Secret provider '" + provider + "' does not exist");
                }
                return SecretConfig.referenced(provider, name);
            }
            default:
                throw new ApiException(400, "Unknown secret mode '" + mode + "'");
        }
    }

    /** Lists Secret Providers and (where the provider allows listing) their secret names. */
    public static JsonArray listProviders(GatewayContext context) {
        JsonArray out = new JsonArray();
        for (ManagedSecretProvider provider : context.getSecretProviderManager().getProviders()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", provider.getResource().name());
            JsonArray names = new JsonArray();
            List<String> secrets = new ArrayList<>();
            try {
                secrets.addAll(provider.list());
            } catch (Exception e) {
                log.debugf("Provider %s cannot list secrets: %s", provider.getResource().name(), e.getMessage());
                p.addProperty("listError", e.getMessage());
            }
            secrets.stream().sorted().forEach(names::add);
            p.add("secrets", names);
            out.add(p);
        }
        return out;
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString().trim() : "";
    }
}
