package com.wargoetz.voicetranscription.gateway.whisper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.inductiveautomation.ignition.common.util.LoggerEx;

/**
 * The whisper.cpp ggml models this module can use: lists the {@code *.bin} files in a folder and downloads
 * catalogued models (official {@code ggerganov/whisper.cpp} repository on Hugging Face) into the configured
 * models folder. Only catalogue ids are accepted - callers never supply a URL or a file name.
 *
 * <p>{@code .en} models are English-only (a little faster/more accurate for English); the others are
 * multilingual, which auto-detect and non-English transcription require. {@code q5} models are quantized:
 * about a third of the size for a small accuracy loss. Larger models need proportionally more gateway RAM.</p>
 */
public final class ModelCatalog {
    private static final LoggerEx log = LoggerEx.newBuilder().build("VoiceTranscription.Models");
    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";
    /** First four bytes of every ggml model file ("lmgg", little-endian magic 0x67676d6c). */
    private static final byte[] GGML_MAGIC = {'l', 'm', 'g', 'g'};

    public record Entry(String id, long approxMb, boolean multilingual, String note) {
        public String file() {
            return "ggml-" + id + ".bin";
        }
    }

    public record Installed(String name, long sizeBytes, boolean multilingual) {
    }

    public record Download(String id, String state, long bytesDone, long bytesTotal, String error, String sha256) {
    }

    public static final List<Entry> CATALOG = List.of(
        new Entry("tiny", 75, true, "Fastest, lowest accuracy"),
        new Entry("tiny-q5_1", 31, true, "Tiny, quantized"),
        new Entry("tiny.en", 75, false, "English only"),
        new Entry("base", 142, true, "Good speed/accuracy balance"),
        new Entry("base-q5_1", 57, true, "Base, quantized"),
        new Entry("base.en", 142, false, "English only"),
        new Entry("small", 466, true, "Noticeably more accurate"),
        new Entry("small-q5_1", 181, true, "Small, quantized"),
        new Entry("small.en", 466, false, "English only"),
        new Entry("medium", 1500, true, "High accuracy, ~5 GB RAM"),
        new Entry("medium-q5_0", 514, true, "Medium, quantized"),
        new Entry("medium.en", 1500, false, "English only"),
        new Entry("large-v3", 2900, true, "Best accuracy, ~10 GB RAM"),
        new Entry("large-v3-q5_0", 1100, true, "Large-v3, quantized"));

    private final Map<String, Download> downloads = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "voicetranscription-model-download");
        t.setDaemon(true);
        return t;
    });
    private volatile Supplier<Path> downloadTarget = () -> Path.of("models");

    /** Folder that downloads are written to (the saved {@code modelsDir}); evaluated per download. */
    public void setDownloadTarget(Supplier<Path> target) {
        this.downloadTarget = target;
    }

    public static Optional<Entry> find(String id) {
        return CATALOG.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** {@code *.bin} files directly inside {@code dir}, sorted by name. Empty if the folder does not exist. */
    public static List<Installed> list(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Installed> out = new ArrayList<>();
            files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".bin"))
                .sorted()
                .forEach(f -> {
                    String name = f.getFileName().toString();
                    long size = 0;
                    try {
                        size = Files.size(f);
                    } catch (IOException ignored) {
                        // listed with unknown size
                    }
                    out.add(new Installed(name, size, !name.toLowerCase().contains(".en")));
                });
            return out;
        } catch (IOException e) {
            log.warn("Cannot list " + dir + ": " + e.getMessage());
            return List.of();
        }
    }

    public Map<String, Download> downloads() {
        return downloads;
    }

    /**
     * Starts downloading a catalogue model in the background.
     *
     * @return the initial state, or empty if {@code id} is not in the catalogue
     * @throws IllegalStateException if it is already installed or downloading
     */
    public Optional<Download> startDownload(String id) {
        Optional<Entry> entry = find(id);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Path dir = downloadTarget.get();
        Path target = dir.resolve(entry.get().file());
        if (Files.isRegularFile(target)) {
            throw new IllegalStateException(entry.get().file() + " is already installed in " + dir);
        }
        Download starting = new Download(id, "downloading", 0, entry.get().approxMb() * 1_000_000L, null, null);
        Download current = downloads.compute(id,
            (k, cur) -> cur != null && cur.state().equals("downloading") ? cur : starting);
        if (current != starting) {
            throw new IllegalStateException(entry.get().file() + " is already downloading");
        }
        executor.execute(() -> download(entry.get(), dir, target));
        return Optional.of(starting);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void download(Entry entry, Path dir, Path target) {
        Path part = dir.resolve(entry.file() + ".part");
        try {
            Files.createDirectories(dir);
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + entry.file()))
                .timeout(Duration.ofHours(2)).GET().build();
            HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                throw new IOException("HTTP " + res.statusCode() + " from " + req.uri());
            }
            Optional<Long> length = res.headers().firstValueAsLong("Content-Length").stream().boxed().findFirst();
            long total = length.orElse(entry.approxMb() * 1_000_000L);
            if (dir.toFile().getUsableSpace() < total + (64L << 20)) {
                throw new IOException("Not enough free disk space in " + dir + " for " + entry.file());
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long done = 0;
            try (InputStream in = new DigestInputStream(res.body(), md);
                 OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    downloads.put(entry.id(), new Download(entry.id(), "downloading", done, total, null, null));
                }
            }
            if (length.isPresent() && done != total) {
                throw new IOException("Download truncated (" + done + " of " + total + " bytes)");
            }
            byte[] head = new byte[4];
            try (InputStream in = Files.newInputStream(part)) {
                if (in.readNBytes(head, 0, 4) != 4 || !Arrays.equals(head, GGML_MAGIC)) {
                    throw new IOException("Downloaded file is not a ggml model");
                }
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
            String sha = HexFormat.of().formatHex(md.digest());
            log.infof("Downloaded Whisper model %s (%d bytes, sha256 %s)", target, done, sha);
            downloads.put(entry.id(), new Download(entry.id(), "done", done, done, null, sha));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(entry, part, "interrupted");
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            log.warn("Download of " + entry.file() + " failed: " + e.getMessage());
            fail(entry, part, e.getMessage());
        }
    }

    private void fail(Entry entry, Path part, String message) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException ignored) {
            // best effort
        }
        downloads.put(entry.id(), new Download(entry.id(), "failed", 0, 0, message, null));
    }
}
