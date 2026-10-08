package com.wargoetz.voicetranscription.designer;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.wargoetz.voicetranscription.common.component.AudioRecorder;

/**
 * Designer-scope hook. Its only job is putting the Voice Recorder on the Perspective component palette;
 * all recording/transcription logic runs on the Gateway.
 */
public class DesignerHook extends AbstractDesignerModuleHook {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Designer");

    private DesignerComponentRegistry registry;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        registry = PerspectiveDesignerInterface.get(context).getDesignerComponentRegistry();
        registry.registerComponent(AudioRecorder.DESCRIPTOR);
        log.info("Registered Voice Recorder component on the Perspective palette.");
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            registry.removeComponent(AudioRecorder.COMPONENT_ID);
        }
    }
}
