package com.wargoetz.voicetranscription.gateway.whisper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeRuntimeTest {
    @Test
    void mapsHostsToBundledPlatformFolders() {
        assertEquals("windows-amd64", NativeRuntime.platformDir("Windows 11", "amd64"));
        assertEquals("linux-amd64", NativeRuntime.platformDir("Linux", "x86_64"));
        assertEquals("linux-aarch64", NativeRuntime.platformDir("Linux", "aarch64"));
        assertEquals("linux-armhf", NativeRuntime.platformDir("Linux", "arm"));
        assertNull(NativeRuntime.platformDir("Mac OS X", "aarch64"));
    }

    @Test
    void everySupportedPlatformShipsItsRuntime() throws IOException {
        for (String dll : NativeRuntime.WINDOWS_DLLS) {
            assertBundled("windows-amd64", dll + ".dll");
        }
        for (String dir : new String[] {"linux-amd64", "linux-aarch64", "linux-armhf"}) {
            assertBundled(dir, NativeRuntime.LINUX_LIB);
        }
    }

    private static void assertBundled(String dir, String file) throws IOException {
        try (InputStream in = NativeRuntime.class.getResourceAsStream("/native/" + dir + "/" + file)) {
            assertNotNull(in, dir + "/" + file);
            assertTrue(in.readAllBytes().length > 10_000, dir + "/" + file + " is suspiciously small");
        }
    }

    @Test
    void listsOnlyBinFilesSortedAndFlagsEnglishOnly(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("ggml-small.bin"), new byte[3]);
        Files.write(dir.resolve("ggml-base.en.bin"), new byte[2]);
        Files.write(dir.resolve("notes.txt"), new byte[1]);
        Files.write(dir.resolve("ggml-large.bin.part"), new byte[1]);
        var models = ModelCatalog.list(dir);
        assertEquals(2, models.size());
        assertEquals("ggml-base.en.bin", models.get(0).name());
        assertTrue(!models.get(0).multilingual());
        assertTrue(models.get(1).multilingual());
        assertTrue(ModelCatalog.list(dir.resolve("missing")).isEmpty());
    }

    @Test
    void catalogRejectsUnknownIds() {
        assertTrue(ModelCatalog.find("tiny").isPresent());
        assertTrue(ModelCatalog.find("../../etc/passwd").isEmpty());
        assertTrue(new ModelCatalog().startDownload("nope").isEmpty());
    }
}
