import { ComponentRegistry } from "@inductiveautomation/perspective-client";
import { VoiceRecorder, VoiceRecorderMeta } from "./components/VoiceRecorder";
import "./scss/recorder.scss";

// Exported so the component is reachable as window.VoiceTranscription.VoiceRecorder for debugging.
export { VoiceRecorder };

// The Java side registers the same component type (AudioRecorder.DESCRIPTOR) with the gateway and
// designer registries; this registers the React implementation with the browser runtime.
ComponentRegistry.register(new VoiceRecorderMeta());
