package com.wargoetz.voicetranscription.gateway.whisper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.inductiveautomation.ignition.common.util.LoggerEx;
import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;
import io.github.givimad.whisperjni.WhisperState;

/**
 * Owns the whisper.cpp native library, the loaded model, and a fixed pool of decoder states.
 *
 * <h2>Native-library rules inside an Ignition gateway</h2>
 * <ul>
 *   <li>A JNI library can be bound to exactly one classloader per JVM. 8.3 loads modules only at gateway
 *   startup, so the library is loaded once per gateway run; upgrading the module needs a restart anyway.</li>
 *   <li>whisper-jni extracts its bundled binary ({@code whisper-jni_full.dll} on Windows,
 *   {@code libwhisper-jni.so} + {@code libwhisper.so} + {@code libggml.so} on Linux) to {@code java.io.tmpdir}
 *   and {@code System.load}s it. Set {@code -Dio.github.givimad.whisperjni.libdir=/path} in
 *   {@code ignition.conf} to load an externally built (e.g. CUDA/AVX512) whisper.cpp instead.</li>
 *   <li>whisper-jni keeps contexts and states in global, unsynchronised {@code std::map}s. All native
 *   allocations therefore happen under the write lock (load/unload); transcriptions only <em>read</em> those
 *   maps and run concurrently under the read lock, one pooled {@link WhisperState} each.</li>
 *   <li>The native log proxy is disabled ({@code setLibraryLogger(null)}); see {@link LanguageDetector}.</li>
 * </ul>
 */
public final class WhisperService {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Whisper");

    /** whisper.cpp segment timestamps are in centiseconds; at 16 kHz that is 160 samples. */
    public static final int SAMPLES_PER_CENTISECOND = 160;

    private static volatile boolean libraryLoaded;
    private static volatile String libraryError;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Engine engine;
    private volatile String status = "not loaded";

    public record Segment(long startCs, long endCs, String text) {
    }

    public record Result(List<Segment> segments, long elapsedMs) {
        public String text() {
            StringBuilder sb = new StringBuilder();
            segments.forEach(s -> sb.append(s.text()));
            return sb.toString().trim();
        }
    }

    private record Engine(WhisperJNI jni, WhisperContext context, BlockingQueue<WhisperState> states,
                          Path modelPath, boolean multilingual) {
    }

    /**
     * (Re)loads the model. Blocks until in-flight transcriptions finish. Never throws.
     *
     * @param expectedSha256 lowercase hex SHA-256 the model file must match, or empty to skip the check
     */
    public void load(Path modelPath, int poolSize, String expectedSha256) {
        lock.writeLock().lock();
        try {
            closeEngine();
            if (!ensureLibrary()) {
                status = "native library failed to load: " + libraryError
                    + (String.valueOf(libraryError).contains("libgomp")
                        ? " - install the OpenMP runtime on the gateway host (Debian/Ubuntu: apt-get install libgomp1)"
                        : "");
                return;
            }
            if (!Files.isRegularFile(modelPath)) {
                status = "model file not found: " + modelPath;
                log.warnf("Whisper model not found at %s. Copy a ggml model there (see GUIDED_TOUR.md).",
                    modelPath);
                return;
            }
            if (!expectedSha256.isEmpty()) {
                String actual = sha256(modelPath);
                if (!actual.equals(expectedSha256)) {
                    status = "model checksum mismatch (expected " + expectedSha256 + ", file is " + actual + ")";
                    log.errorf("Refusing to load %s: SHA-256 %s does not match the configured %s", modelPath,
                        actual, expectedSha256);
                    return;
                }
            }
            long start = System.currentTimeMillis();
            WhisperJNI jni = new WhisperJNI();
            WhisperContext ctx = jni.initNoState(modelPath);
            BlockingQueue<WhisperState> states = new ArrayBlockingQueue<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                states.add(jni.initState(ctx));
            }
            boolean multilingual = jni.isMultilingual(ctx);
            engine = new Engine(jni, ctx, states, modelPath, multilingual);
            status = "ready";
            log.infof("Loaded Whisper model %s (%s, %d decoder states) in %d ms. %s", modelPath,
                multilingual ? "multilingual" : "English-only", poolSize, System.currentTimeMillis() - start,
                jni.getSystemInfo());
        } catch (Throwable t) {
            status = "model failed to load: " + t.getMessage();
            log.error("Failed to load Whisper model " + modelPath, t);
            closeEngine();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void shutdown() {
        lock.writeLock().lock();
        try {
            closeEngine();
            status = "stopped";
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isReady() {
        return engine != null;
    }

    public String status() {
        return status;
    }

    public boolean isMultilingual() {
        Engine e = engine;
        return e != null && e.multilingual();
    }

    /**
     * Transcribes 16 kHz mono float samples.
     *
     * @param language ISO code, or {@code "auto"} to let Whisper detect it (multilingual models only)
     * @param waitMs   how long to wait for a free decoder state; 0 = return empty immediately if busy
     * @return empty if the engine is not loaded or every decoder state is busy
     */
    public Optional<Result> transcribe(float[] samples, String language, int threads, long waitMs)
        throws TranscriptionException {
        if (samples.length < AudioConstants.MIN_SAMPLES) {
            return Optional.of(new Result(List.of(), 0));
        }
        lock.readLock().lock();
        try {
            Engine e = engine;
            if (e == null) {
                throw new TranscriptionException("Whisper is not ready: " + status);
            }
            WhisperState state = waitMs <= 0 ? e.states().poll() : e.states().poll(waitMs, TimeUnit.MILLISECONDS);
            if (state == null) {
                return Optional.empty();
            }
            try {
                long start = System.currentTimeMillis();
                WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
                params.nThreads = threads;
                params.language = e.multilingual() ? language : "en";
                params.translate = false;
                params.printProgress = false;
                params.printRealtime = false;
                params.printTimestamps = false;
                params.printSpecial = false;
                params.noContext = true;
                params.suppressBlank = true;
                params.suppressNonSpeechTokens = true;
                int rc = e.jni().fullWithState(e.context(), state, params, samples, samples.length);
                if (rc != 0) {
                    throw new TranscriptionException("whisper_full returned " + rc);
                }
                int n = e.jni().fullNSegmentsFromState(state);
                List<Segment> segments = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    segments.add(new Segment(
                        e.jni().fullGetSegmentTimestamp0FromState(state, i),
                        e.jni().fullGetSegmentTimestamp1FromState(state, i),
                        e.jni().fullGetSegmentTextFromState(state, i)));
                }
                long elapsed = System.currentTimeMillis() - start;
                warnIfSlow(elapsed, samples.length, threads, e.modelPath());
                return Optional.of(new Result(segments, elapsed));
            } finally {
                e.states().offer(state);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    private volatile long lastSlowWarn;

    /** Whisper slower than real time means streaming partials fall behind; say why, at most every 30 s. */
    private void warnIfSlow(long elapsedMs, int samples, int threads, Path model) {
        long audioMs = samples * 1000L / 16_000;
        long now = System.currentTimeMillis();
        if (elapsedMs > audioMs && now - lastSlowWarn > 30_000) {
            lastSlowWarn = now;
            log.warnf("Transcribing %d ms of audio took %d ms (slower than real time) with %s on %d thread(s); "
                + "use a smaller/quantized model, more threads or CPU cores, or check the CPU supports AVX2.",
                audioMs, elapsedMs, model.getFileName(), threads);
        }
    }

    static String sha256(Path file) throws java.io.IOException, java.security.NoSuchAlgorithmException {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    private void closeEngine() {
        Engine e = engine;
        engine = null;
        if (e == null) {
            return;
        }
        for (WhisperState s : e.states()) {
            try {
                s.close();
            } catch (Exception ex) {
                log.debug("Error freeing whisper state", ex);
            }
        }
        try {
            e.context().close();
        } catch (Exception ex) {
            log.debug("Error freeing whisper context", ex);
        }
    }

    private static synchronized boolean ensureLibrary() {
        if (libraryLoaded) {
            return true;
        }
        NativeRuntime.ensureLoaded();
        try {
            WhisperJNI.loadLibrary(msg -> log.debug(msg));
            // Disables the native->Java log proxy (see class docs) and whisper.cpp's default stderr logging.
            WhisperJNI.setLibraryLogger(null);
            libraryLoaded = true;
            libraryError = null;
            return true;
        } catch (Throwable t) {
            libraryError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.error("Unable to load the whisper.cpp native library for " + System.getProperty("os.name")
                + "/" + System.getProperty("os.arch"), t);
            return false;
        }
    }

    public static final class AudioConstants {
        /** whisper.cpp refuses input shorter than ~100 ms; we require a quarter second. */
        public static final int MIN_SAMPLES = 4000;

        private AudioConstants() {
        }
    }

    public static class TranscriptionException extends Exception {
        public TranscriptionException(String message) {
            super(message);
        }
    }
}
