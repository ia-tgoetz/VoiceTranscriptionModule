package com.wargoetz.voicetranscription.gateway;

import java.util.Optional;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import com.inductiveautomation.perspective.common.api.ComponentRegistry;
import com.inductiveautomation.perspective.gateway.api.PerspectiveContext;
import com.wargoetz.voicetranscription.common.VoiceTranscriptionModule;
import com.wargoetz.voicetranscription.common.component.AudioRecorder;
import com.wargoetz.voicetranscription.gateway.web.ApiRoutes;

/**
 * Gateway-scope entry point. Lifecycle, in the order Ignition 8.3 calls it:
 * <ol>
 *   <li>{@link #setup} - managers exist but are not started. Register things the platform must know about
 *   before startup: the settings resource handler and the gateway web-UI navigation entry.</li>
 *   <li>{@link #startup} - platform is running. Load settings, create the MongoClient, load the Whisper model
 *   (asynchronously), register the Perspective component, start maintenance timers.</li>
 *   <li>{@link #mountRouteHandlers} - called after startup; mounts our REST API under
 *   {@code /data/voicetranscription}.</li>
 *   <li>{@link #shutdown} - release everything: MongoClient pool threads, native Whisper memory, timers,
 *   and the component registration.</li>
 * </ol>
 */
public class GatewayHook extends AbstractGatewayModuleHook {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Gateway");

    /** The config page bundle built by :web-ui; exported React component name is the second mount() arg. */
    private static final SystemJsModule CONFIG_PAGE = new SystemJsModule(
        VoiceTranscriptionModule.MODULE_ID + ".config",
        "/res/" + VoiceTranscriptionModule.URL_ALIAS + "/web-ui/voicetranscription-config.js");

    private GatewayContext context;
    private ModuleServices services;
    private ComponentRegistry componentRegistry;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        this.services = new ModuleServices(context);

        // Gateway web UI: Services > Voice Transcription > Settings, rendered by our React bundle.
        context.getWebResourceManager().getNavigationModel().getServices()
            .addCategory("voicetranscription", category -> category
                .label("Voice Transcription")
                .requiredPermission(PermissionType.READ)
                .addPage("Settings", page -> page
                    .position(0)
                    .requiredPermission(PermissionType.READ)
                    .mount("/services/voice-transcription", "VoiceTranscriptionSettings", CONFIG_PAGE)));
    }

    @Override
    public void startup(LicenseState activationState) {
        services.startup();

        componentRegistry = PerspectiveContext.get(context).getComponentRegistry();
        componentRegistry.registerComponent(AudioRecorder.DESCRIPTOR);
        log.info("OfflineVoiceTranscription started.");
    }

    @Override
    public void shutdown() {
        if (componentRegistry != null) {
            componentRegistry.removeComponent(AudioRecorder.COMPONENT_ID);
        }
        if (services != null) {
            services.shutdown();
        }
        log.info("OfflineVoiceTranscription stopped.");
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        ApiRoutes.mount(routes, services);
    }

    /** Folder inside our jars served at /res/voicetranscription/ (component JS/CSS + config page bundle). */
    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of(VoiceTranscriptionModule.URL_ALIAS);
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }
}
