package com.wargoetz.voicetranscription.common.component;

import java.util.List;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;
import com.inductiveautomation.perspective.common.api.ComponentEventDescriptor;
import com.wargoetz.voicetranscription.common.VoiceTranscriptionModule;

/**
 * Descriptor for the Voice Recorder Perspective component. Registered on the Gateway (so sessions can
 * instantiate it) and in the Designer (so it appears on the component palette). The browser side
 * registers a ComponentMeta with the same {@link #COMPONENT_ID} in web/src/index.ts.
 */
public final class AudioRecorder {
    public static final String COMPONENT_ID = "voicetranscription.input.recorder";

    public static final JsonSchema SCHEMA =
        JsonSchema.parse(AudioRecorder.class.getResourceAsStream("/recorder.props.json"));

    public static final JsonSchema SUBMITTED_EVENT_SCHEMA =
        JsonSchema.parse(AudioRecorder.class.getResourceAsStream("/recorder.submitted.event.json"));

    public static final JsonSchema ERROR_EVENT_SCHEMA =
        JsonSchema.parse(AudioRecorder.class.getResourceAsStream("/recorder.error.event.json"));

    public static final ComponentDescriptor DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory(VoiceTranscriptionModule.COMPONENT_CATEGORY)
        .setId(COMPONENT_ID)
        .setModuleId(VoiceTranscriptionModule.MODULE_ID)
        .setSchema(SCHEMA)
        .setName("Voice Recorder")
        .setDefaultMetaName("VoiceRecorder")
        .addPaletteEntry("", "Voice Recorder",
            "Records speech, transcribes it on the gateway with Whisper, and stores it in MongoDB.", null, null)
        .setEvents(List.of(
            new ComponentEventDescriptor("onSubmitted",
                "Fired after a transcript is summarized and stored in MongoDB.", SUBMITTED_EVENT_SCHEMA),
            new ComponentEventDescriptor("onError",
                "Fired when recording, transcription, or submission fails.", ERROR_EVENT_SCHEMA)))
        .setResources(VoiceTranscriptionModule.BROWSER_RESOURCES)
        .build();

    private AudioRecorder() {
    }
}
