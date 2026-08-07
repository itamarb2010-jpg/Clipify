package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Locates an FFmpeg executable, downloading and verifying a pinned static build when necessary.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code <gameDir>/clipify/bin/ffmpeg[.exe]} — a previous download, or one the user dropped
 *       in by hand.</li>
 *   <li>{@code ffmpeg} on {@code PATH} — a convenience only; the mod never <i>requires</i> it.</li>
 *   <li>A pinned, SHA-256 verified download from an immutable release URL.</li>
 * </ol>
 *
 * <p>All work happens off the render thread. Callers poll {@link #state()} / {@link #executable()}.
 */
public final class FfmpegProvider {

	/** Immutable BtbN auto-build tag that the pinned checksums below belong to. */
	private static final String BTBN_TAG = "autobuild-2026-07-22-13-36";
	private static final String BTBN_BASE =
			"https://github.com/BtbN/FFmpeg-Builds/releases/download/" + BTBN_TAG + "/";
	private static final String BTBN_STEM = "ffmpeg-n8.1.2-30-g45f1910444-";

	/** A pinned download: URL plus the SHA-256 the payload must hash to. */
	public record Build(String url, String sha256, long approxBytes) {}

	public enum State {
		/** Nothing has been attempted yet. */
		IDLE,
		/** Looking on disk / PATH. */
		SEARCHING,
		/** Fetching the pinned archive. */
		DOWNLOADING,
		/** Hashing / unpacking. */
		INSTALLING,
		/** {@link #executable()} is usable. */
		READY,
		/** {@link #errorMessage()} explains why not. */
		FAILED
	}

	private final Path binDir;
	private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
	private volatile Path executable;
	private volatile String errorMessage;
	private volatile int downloadPercent;
	private volatile Set<String> encoderNames = Set.of();

	public FfmpegProvider(Path gameDir) {
		this.binDir = gameDir.resolve("clipify").resolve("bin");
	}

	public State state() {
		return state.get();
	}

	public Path executable() {
		return executable;
	}

	public String errorMessage() {
		return errorMessage;
	}

	public int downloadPercent() {
		return downloadPercent;
	}

	/** Encoder names reported by {@code ffmpeg -encoders}; empty until {@link State#READY}. */
	public Set<String> encoderNames() {
		return encoderNames;
	}

	// ------------------------------------------------------------------ setup

	/**
	 * Blocking resolution. Intended to be called from a background thread.
	 *
	 * @param allowDownload when false, the pinned download step is skipped entirely
	 * @return true if {@link #executable()} is now usable
	 */
	public boolean resolve(boolean allowDownload) {
		try {
			state.set(State.SEARCHING);

			Path local = binDir.resolve(executableName());
			if (isWorkingFfmpeg(local)) {
				return succeed(local);
			}

			Path onPath = findOnPath();
			if (onPath != null) {
				ClipifyLog.LOGGER.info("Using FFmpeg found on PATH: {}", onPath);
				return succeed(onPath);
			}

			if (!allowDownload) {
				return fail("FFmpeg is not installed and automatic download is disabled. "
						+ "Put an 'ffmpeg" + (isWindows() ? ".exe" : "") + "' binary in " + binDir);
			}

			Build build = pinnedBuild();
			if (build == null) {
				return fail("No pinned FFmpeg build for " + osName() + "/" + arch()
						+ ". Put an 'ffmpeg" + (isWindows() ? ".exe" : "") + "' binary in " + binDir);
			}

			Files.createDirectories(binDir);
			Path archive = binDir.resolve("ffmpeg-download.tmp");
			Files.deleteIfExists(archive);

			state.set(State.DOWNLOADING);
			ClipifyLog.LOGGER.info("Downloading FFmpeg ({} MB) from {}", build.approxBytes() / 1_000_000, build.url());
			String actual = download(build.url(), archive, build.approxBytes());

			state.set(State.INSTALLING);
			if (!actual.equalsIgnoreCase(build.sha256())) {
				Files.deleteIfExists(archive);
				return fail("FFmpeg download failed checksum verification (expected "
						+ build.sha256() + ", got " + actual + "). Nothing was installed.");
			}

			extractFfmpeg(archive, binDir);
			Files.deleteIfExists(archive);

			if (!isWindows()) {
				try {
					local.toFile().setExecutable(true, true);
				} catch (SecurityException ignored) {
					// Best effort; isWorkingFfmpeg() below is the real gate.
				}
			}

			if (isWorkingFfmpeg(local)) {
				ClipifyLog.LOGGER.info("FFmpeg installed at {}", local);
				return succeed(local);
			}
			return fail("FFmpeg was downloaded and verified but would not run. See the log for details.");
		} catch (Exception e) {
			ClipifyLog.LOGGER.error("FFmpeg setup failed", e);
			return fail("FFmpeg setup failed: " + e);
		}
	}

	private boolean succeed(Path exe) {
		this.executable = exe;
		this.encoderNames = queryEncoders(exe);
		state.set(State.READY);
		return true;
	}

	private boolean fail(String message) {
		this.errorMessage = message;
		ClipifyLog.LOGGER.error("Clipify: {}", message);
		state.set(State.FAILED);
		return false;
	}

	// ------------------------------------------------------------- platform

	private static String osName() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
	}

	private static String arch() {
		return System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
	}

	public static boolean isWindows() {
		return osName().contains("win");
	}

	private static boolean isMac() {
		String os = osName();
		return os.contains("mac") || os.contains("darwin");
	}

	private static boolean isLinux() {
		return osName().contains("linux");
	}

	private static String executableName() {
		return isWindows() ? "ffmpeg.exe" : "ffmpeg";
	}

	/**
	 * The pinned build for this platform, or null if there is none.
	 *
	 * <p>Checksums were taken from the release's published {@code checksums.sha256} (BtbN) or
	 * computed from the immutable versioned artifact (evermeet.cx). See NOTICE.md.
	 */
	static Build pinnedBuild() {
		String arch = arch();
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");

		if (isWindows() && x64) {
			return new Build(BTBN_BASE + BTBN_STEM + "win64-gpl-8.1.zip",
					"f3c4d7c272390d18f57e5ee1e52466d28cbf728b923e188367e934456c061d51", 167_402_338L);
		}
		if (isLinux() && x64) {
			return new Build(BTBN_BASE + BTBN_STEM + "linux64-gpl-8.1.tar.xz",
					"4ad0d6eb98bde796841050cf12bf9428e188446bd518b245fb4aa02f25b633a0", 124_900_000L);
		}
		if (isLinux() && arm64) {
			return new Build(BTBN_BASE + BTBN_STEM + "linuxarm64-gpl-8.1.tar.xz",
					"369dac151ae4ebf752c789cc48fbb520a193665ba41463a39615777b7236222a", 107_000_000L);
		}
		if (isMac()) {
			// evermeet.cx ships an x86_64 binary; it runs on Apple Silicon through Rosetta 2.
			return new Build("https://evermeet.cx/ffmpeg/ffmpeg-8.1.2.zip",
					"e91df72a1ee7c26606f90dd2dd4dcccc6a75140ff9ea6fdd50faae828b82ba69", 26_037_786L);
		}
		return null;
	}

	// ------------------------------------------------------------- download

	private String download(String url, Path dest, long approxBytes) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "Clipify/1.0 (+https://github.com/clipify/clipify)")
				.timeout(Duration.ofMinutes(30))
				.GET()
				.build();

		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " for " + url);
		}

		long expected = response.headers().firstValueAsLong("content-length").orElse(approxBytes);
		MessageDigest digest = newSha256();

		try (InputStream raw = response.body();
			 DigestInputStream in = new DigestInputStream(raw, digest);
			 OutputStream out = Files.newOutputStream(dest)) {
			byte[] buf = new byte[1 << 16];
			long total = 0;
			int lastLogged = -1;
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				total += n;
				int pct = expected > 0 ? (int) Math.min(100, total * 100 / expected) : 0;
				downloadPercent = pct;
				if (pct / 10 != lastLogged / 10) {
					lastLogged = pct;
					ClipifyLog.LOGGER.info("Downloading FFmpeg… {}%", pct);
				}
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest newSha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	// ------------------------------------------------------------- extract

	private static void extractFfmpeg(Path archive, Path destDir) throws IOException, InterruptedException {
		String name = archive.getFileName().toString();
		if (name.endsWith(".tmp")) {
			// Decide by platform, since the temp name hides the real extension.
			name = isWindows() || isMac() ? "a.zip" : "a.tar.xz";
		}
		if (name.endsWith(".zip")) {
			extractFromZip(archive, destDir);
		} else {
			extractFromTarXz(archive, destDir);
		}
	}

	/** Pulls the single {@code ffmpeg}/{@code ffmpeg.exe} entry out of a zip, ignoring its folder. */
	private static void extractFromZip(Path archive, Path destDir) throws IOException {
		String wanted = executableName();
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				String base = entry.getName();
				int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
				base = slash >= 0 ? base.substring(slash + 1) : base;
				if (base.equals(wanted)) {
					Path out = destDir.resolve(wanted);
					Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
					return;
				}
			}
		}
		throw new IOException("Archive did not contain an entry named " + wanted);
	}

	/**
	 * Uses the system {@code tar} for {@code .tar.xz}. Every supported Linux distribution ships a
	 * tar with xz support; Java has no built-in xz decoder and pulling in commons-compress purely
	 * for this would bloat the jar.
	 */
	private static void extractFromTarXz(Path archive, Path destDir) throws IOException, InterruptedException {
		Path scratch = destDir.resolve("unpack");
		deleteRecursively(scratch);
		Files.createDirectories(scratch);

		Process p = new ProcessBuilder("tar", "-xJf", archive.toAbsolutePath().toString(),
				"-C", scratch.toAbsolutePath().toString())
				.redirectErrorStream(true)
				.start();
		p.getInputStream().readAllBytes();
		if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
			p.destroyForcibly();
			deleteRecursively(scratch);
			throw new IOException("`tar -xJf` failed; cannot unpack " + archive.getFileName());
		}

		try (var stream = Files.walk(scratch)) {
			Path found = stream.filter(Files::isRegularFile)
					.filter(f -> f.getFileName().toString().equals("ffmpeg"))
					.findFirst()
					.orElseThrow(() -> new IOException("Archive did not contain an 'ffmpeg' entry"));
			Files.copy(found, destDir.resolve("ffmpeg"), StandardCopyOption.REPLACE_EXISTING);
		} finally {
			deleteRecursively(scratch);
		}
	}

	private static void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			for (Path p : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	// ------------------------------------------------------------ validation

	private static Path findOnPath() {
		String pathEnv = System.getenv("PATH");
		if (pathEnv == null) {
			return null;
		}
		String exe = executableName();
		for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
			if (dir.isBlank()) {
				continue;
			}
			try {
				Path candidate = Path.of(dir.trim()).resolve(exe);
				if (isWorkingFfmpeg(candidate)) {
					return candidate;
				}
			} catch (Exception ignored) {
				// Malformed PATH entry — skip it.
			}
		}
		return null;
	}

	private static boolean isWorkingFfmpeg(Path candidate) {
		if (candidate == null || !Files.isRegularFile(candidate)) {
			return false;
		}
		try {
			Process p = new ProcessBuilder(candidate.toAbsolutePath().toString(), "-hide_banner", "-version")
					.redirectErrorStream(true)
					.start();
			String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(20, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0 && output.contains("ffmpeg version");
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return false;
		}
	}

	private static Set<String> queryEncoders(Path exe) {
		try {
			Process p = new ProcessBuilder(exe.toAbsolutePath().toString(), "-hide_banner", "-encoders")
					.redirectErrorStream(true)
					.start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(20, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return Set.of();
			}
			var names = new java.util.HashSet<String>();
			for (String line : out.split("\n")) {
				// Lines look like: " V....D h264_nvenc           NVIDIA NVENC H.264 encoder ..."
				String trimmed = line.strip();
				int sp = trimmed.indexOf(' ');
				if (sp <= 0 || trimmed.length() < sp + 2) {
					continue;
				}
				String rest = trimmed.substring(sp + 1).strip();
				int sp2 = rest.indexOf(' ');
				names.add(sp2 > 0 ? rest.substring(0, sp2) : rest);
			}
			return Set.copyOf(names);
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return Set.of();
		}
	}
}
