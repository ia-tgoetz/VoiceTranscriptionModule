package com.wargoetz.voicetranscription.gateway.whisper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.inductiveautomation.ignition.common.util.LoggerEx;

/**
 * Makes the whisper.cpp native library loadable on hosts that lack its C/OpenMP runtime, so installing the
 * module never requires {@code apt install libgomp1} or the Visual C++ redistributable.
 *
 * <p>The runtime libraries ship inside the module jar under {@code native/<platform>/}. They are only
 * extracted and pre-loaded when the host does not already provide them: once a library with the right
 * name/SONAME is loaded into the process, the dynamic loader satisfies whisper-jni's dependency from it.
 * Everything here is best effort and never throws; if the preload is not enough the normal
 * {@code UnsatisfiedLinkError} is reported by {@link WhisperService}.</p>
 */
final class NativeRuntime {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription");

    /** Load order matters on Windows: msvcp140/vcomp140 import vcruntime140. */
    static final List<String> WINDOWS_DLLS = List.of("vcruntime140", "vcruntime140_1", "msvcp140", "vcomp140");
    static final String LINUX_LIB = "libgomp.so.1";

    private static final List<String> LINUX_LIB_DIRS = List.of(
        "/lib", "/lib64", "/usr/lib", "/usr/lib64",
        "/lib/x86_64-linux-gnu", "/usr/lib/x86_64-linux-gnu",
        "/lib/aarch64-linux-gnu", "/usr/lib/aarch64-linux-gnu",
        "/lib/arm-linux-gnueabihf", "/usr/lib/arm-linux-gnueabihf");

    private static boolean done;

    private NativeRuntime() {
    }

    /** Resource folder for this host, or {@code null} when nothing is bundled for it. */
    static String platformDir(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return arch.equals("amd64") || arch.equals("x86_64") ? "windows-amd64" : null;
        }
        if (os.contains("linux")) {
            if (arch.equals("amd64") || arch.equals("x86_64")) {
                return "linux-amd64";
            }
            if (arch.equals("aarch64") || arch.equals("arm64")) {
                return "linux-aarch64";
            }
            if (arch.startsWith("arm")) {
                return "linux-armhf";
            }
        }
        return null;
    }

    static synchronized void ensureLoaded() {
        if (done) {
            return;
        }
        done = true;
        String dir = platformDir(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        if (dir == null) {
            return;
        }
        try {
            if (dir.startsWith("windows")) {
                for (String dll : WINDOWS_DLLS) {
                    if (!hostProvidesDll(dll)) {
                        loadBundled(dir, dll + ".dll");
                    }
                }
            } else if (!hostProvidesLibgomp()) {
                loadBundled(dir, LINUX_LIB);
            }
        } catch (Throwable t) {
            log.warn("Bundled native runtime could not be preloaded: " + t);
        }
    }

    private static boolean hostProvidesDll(String name) {
        try {
            System.loadLibrary(name);
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    private static boolean hostProvidesLibgomp() {
        for (String d : LINUX_LIB_DIRS) {
            if (Files.exists(Path.of(d, LINUX_LIB))) {
                return true;
            }
        }
        String ld = System.getenv("LD_LIBRARY_PATH");
        if (ld != null) {
            for (String d : ld.split(":")) {
                if (!d.isBlank() && Files.exists(Path.of(d, LINUX_LIB))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void loadBundled(String platform, String file) throws IOException {
        try (InputStream in = NativeRuntime.class.getResourceAsStream("/native/" + platform + "/" + file)) {
            if (in == null) {
                log.warn("No bundled " + file + " for " + platform);
                return;
            }
            Path dir = Files.createTempDirectory("vt-native");
            Path target = dir.resolve(file);
            Files.copy(in, target);
            target.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            System.load(target.toString());
            log.infof("Host lacks %s; loaded the copy bundled with the module.", file);
        }
    }
}
