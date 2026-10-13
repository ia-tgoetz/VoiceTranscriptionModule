package com.wargoetz.voicetranscription.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelBootstrapTest {
    @Test
    void installsBundledModelsAndNeverOverwrites(@TempDir Path dir) throws IOException {
        Path models = dir.resolve("models");
        ModelBootstrap.installBundledModels(models);
        for (String name : ModelBootstrap.BUNDLED) {
            Path f = models.resolve("ggml-" + name + ".bin");
            assertTrue(Files.size(f) > 50_000_000L, f + " should be the real model");
        }
        Path custom = models.resolve("ggml-tiny.bin");
        Files.writeString(custom, "operator copy");
        ModelBootstrap.installBundledModels(models);
        assertEquals("operator copy", Files.readString(custom));
    }
}
