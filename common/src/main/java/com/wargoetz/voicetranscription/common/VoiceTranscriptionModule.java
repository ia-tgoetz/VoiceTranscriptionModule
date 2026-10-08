package com.wargoetz.voicetranscription.common;

import java.util.Set;

import com.inductiveautomation.perspective.common.api.BrowserResource;

/**
 * Identifiers shared by the Gateway and Designer scopes.
 */
public final class VoiceTranscriptionModule {
    /** Must match {@code ignitionModule.id} in the root build.gradle.kts. */
    public static final String MODULE_ID = "com.wargoetz.voicetranscription";

    /**
     * Mount alias returned by {@code GatewayHook.getMountPathAlias()}. Static files are served from
     * {@code /res/voicetranscription/*} and route handlers from {@code /data/voicetranscription/*}.
     */
    public static final String URL_ALIAS = "voicetranscription";

    /** Prefix for every REST route mounted by the gateway hook. */
    public static final String API_PREFIX = "/api/v1";

    public static final String COMPONENT_CATEGORY = "Input";

    /** JS + CSS the browser loads before it renders any of this module's Perspective components. */
    public static final Set<BrowserResource> BROWSER_RESOURCES = Set.of(
        new BrowserResource(
            "voicetranscription-js",
            String.format("/res/%s/VoiceTranscription.js", URL_ALIAS),
            BrowserResource.ResourceType.JS),
        new BrowserResource(
            "voicetranscription-css",
            String.format("/res/%s/VoiceTranscription.css", URL_ALIAS),
            BrowserResource.ResourceType.CSS));

    private VoiceTranscriptionModule() {
    }
}
