package com.wargoetz.voicetranscription.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.inductiveautomation.ignition.common.util.LoggerEx;

/**
 * Seeds the Whisper models bundled in the module jar ({@code models/ggml-<name>.bin}) into the models
 * folder, so a fresh install transcribes without any manual model copy. An existing file is never
 * overwritten (operators may have replaced it with another model under the same name).
 */
final class ModelBootstrap {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription");
    /** Keep in sync with {@code bundledModelNames} in gateway/build.gradle.kts. */
    static final List<String> BUNDLED = List.of("tiny", "base");

    private ModelBootstrap() {
    }

    static void installBundledModels(Path dir) {
        for (String name : BUNDLED) {
            install("/models/ggml-" + name + ".bin", dir.resolve("ggml-" + name + ".bin"));
        }
    }

    private static void install(String resource, Path target) {
        if (Files.isRegularFile(target)) {
            return;
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = ModelBootstrap.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("No bundled " + resource + " in this build; copy a ggml model to " + target);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            log.infof("Installed bundled Whisper model to %s", target);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not install bundled Whisper model to " + target + ": " + e.getMessage());
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
