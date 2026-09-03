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
import java.util.List;
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
 *   <li>A pinned, SHA-256 verified download from a long-lived release URL, falling back to the
 *       upstream rolling release if that one has gone away.</li>
 * </ol>
 *
 * <p>All work happens off the render thread. Callers poll {@link #state()} / {@link #executable()}.
 */
public final class FfmpegProvider {

	/**
	 * BtbN auto-build tag the pinned checksums below belong to.
	 *
	 * <p>This must always be a <b>month-end</b> tag. BtbN keeps the last auto-build of each month
	 * indefinitely but prunes the daily ones after about two weeks, so pinning a daily tag makes
	 * every fresh install 404 the moment that release is deleted.
	 */
	private static final String BTBN_TAG = "autobuild-2026-07-31-14-10";
	private static final String BTBN_BASE =
			"https://github.com/BtbN/FFmpeg-Builds/releases/download/" + BTBN_TAG + "/";
	private static final String BTBN_STEM = "ffmpeg-n8.1.2-34-g9b6c8969e0-";

	/**
	 * BtbN's rolling release, which is never deleted. Its contents change, so there is nothing to
	 * pin — the expected hash is read from the {@code checksums.sha256} published beside the
	 * archive. Only used when the pinned tag above cannot be fetched, so a pruned or briefly
	 * unreachable release can never leave the mod unable to record.
	 */
	private static final String BTBN_LATEST_BASE =
			"https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/";
	private static final String BTBN_LATEST_STEM = "ffmpeg-n8.1-latest-";

	/**
	 * A download candidate: URL plus the SHA-256 the payload must hash to. A null {@code sha256}
	 * means the expected hash is read from the {@code checksums.sha256} next to the URL.
	 */
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

			List<Build> builds = pinnedBuilds();
			if (builds.isEmpty()) {
				return fail("No FFmpeg build is available for " + osName() + "/" + arch()
						+ ". Put an 'ffmpeg" + (isWindows() ? ".exe" : "") + "' binary in " + binDir);
			}

			Files.createDirectories(binDir);

			// Try each source in turn. A release can be pruned upstream or be briefly unreachable,
			// and one dead URL must not leave the mod permanently unable to record.
			Exception lastFailure = null;
			for (Build build : builds) {
				try {
					if (install(build, local)) {
						ClipifyLog.LOGGER.info("FFmpeg installed at {}", local);
						return succeed(local);
					}
					lastFailure = new IOException("the build from " + build.url() + " would not run");
				} catch (Exception e) {
					lastFailure = e;
				}
				ClipifyLog.LOGGER.warn("FFmpeg install from {} failed: {}", build.url(), lastFailure.toString());

				if (lastFailure instanceof InterruptedException) {
					// The game is shutting down; do not start another multi-hundred-MB download.
					Thread.currentThread().interrupt();
					break;
				}
			}

			return fail(downloadFailureMessage(lastFailure));
		} catch (Exception e) {
			ClipifyLog.LOGGER.error("FFmpeg setup failed", e);
			return fail(downloadFailureMessage(e));
		}
	}

	/**
	 * Downloads, verifies and unpacks a single candidate.
	 *
	 * @return true if {@code local} is now a working FFmpeg
	 * @throws IOException on a failed download or a checksum mismatch; the caller is free to try
	 *         the next candidate
	 */
	private boolean install(Build build, Path local) throws IOException, InterruptedException {
		Path archive = binDir.resolve("ffmpeg-download.tmp");
		Files.deleteIfExists(archive);

		String expected = build.sha256() != null ? build.sha256() : publishedSha256(build.url());

		downloadPercent = 0;
		state.set(State.DOWNLOADING);
		ClipifyLog.LOGGER.info("Downloading FFmpeg ({} MB) from {}", build.approxBytes() / 1_000_000, build.url());
		String actual = download(build.url(), archive, build.approxBytes());

		state.set(State.INSTALLING);
		if (!actual.equalsIgnoreCase(expected)) {
			Files.deleteIfExists(archive);
			throw new IOException("checksum verification failed (expected " + expected + ", got "
					+ actual + "); nothing was installed");
		}

		try {
			extractFfmpeg(archive, binDir);
		} finally {
			Files.deleteIfExists(archive);
		}

		if (!isWindows()) {
			try {
				local.toFile().setExecutable(true, true);
			} catch (SecurityException ignored) {
				// Best effort; isWorkingFfmpeg() below is the real gate.
			}
		}
		return isWorkingFfmpeg(local);
	}

	/** Something a player can act on, rather than a raw exception class name. */
	private String downloadFailureMessage(Exception cause) {
		String detail = "unknown error";
		if (cause != null) {
			detail = cause.getMessage() != null && !cause.getMessage().isBlank()
					? cause.getMessage()
					: cause.toString();
		}
		return "Could not download FFmpeg (" + detail + "). Check your internet connection, or put an "
				+ "'ffmpeg" + (isWindows() ? ".exe" : "") + "' binary in " + binDir + " and restart Minecraft.";
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
	 * Download candidates for this platform, best first, or empty if there are none.
	 *
	 * <p>The pinned checksums were taken from the release's published {@code checksums.sha256}
	 * (BtbN) or computed from the immutable versioned artifact (evermeet.cx). Each
	 * BtbN entry is followed by the same artifact on the rolling {@code latest} release, whose
	 * checksum is fetched at download time because its contents change.
	 */
	static List<Build> pinnedBuilds() {
		String arch = arch();
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");

		if (isWindows() && x64) {
			return List.of(
					new Build(BTBN_BASE + BTBN_STEM + "win64-gpl-8.1.zip",
							"cc4156d51387566ea8ba653fc3a04897bdf812fddf652428d9030bbf7ae24835", 167_402_338L),
					new Build(BTBN_LATEST_BASE + BTBN_LATEST_STEM + "win64-gpl-8.1.zip", null, 167_402_338L));
		}
		if (isLinux() && x64) {
			return List.of(
					new Build(BTBN_BASE + BTBN_STEM + "linux64-gpl-8.1.tar.xz",
							"09fc77be269c7053e438b7e96548e4af97604faf96a42c4a3c56a1ad74c22c0a", 124_900_000L),
					new Build(BTBN_LATEST_BASE + BTBN_LATEST_STEM + "linux64-gpl-8.1.tar.xz", null, 124_900_000L));
		}
		if (isLinux() && arm64) {
			return List.of(
					new Build(BTBN_BASE + BTBN_STEM + "linuxarm64-gpl-8.1.tar.xz",
							"177e40c91564dec3840096f3bf1ffe696b94330585972462cfc739fa29fe0e1a", 107_000_000L),
					new Build(BTBN_LATEST_BASE + BTBN_LATEST_STEM + "linuxarm64-gpl-8.1.tar.xz", null, 107_000_000L));
		}
		if (isMac()) {
			// evermeet.cx ships an x86_64 binary; it runs on Apple Silicon through Rosetta 2. Its
			// versioned archives are kept indefinitely, so there is nothing to fall back to.
			return List.of(new Build("https://evermeet.cx/ffmpeg/ffmpeg-8.1.2.zip",
					"e91df72a1ee7c26606f90dd2dd4dcccc6a75140ff9ea6fdd50faae828b82ba69", 26_037_786L));
		}
		return List.of();
	}

	// ------------------------------------------------------------- download

	private static HttpClient httpClient() {
		return HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(30))
				.build();
	}

	private static HttpRequest.Builder request(String url, Duration timeout) {
		return HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "Clipify/1.0 (+https://github.com/clipify/clipify)")
				.timeout(timeout)
				.GET();
	}

	/**
	 * Reads the expected hash for {@code archiveUrl} out of the {@code checksums.sha256} published
	 * next to it. Used for the rolling release, whose contents cannot be pinned ahead of time.
	 */
	private static String publishedSha256(String archiveUrl) throws IOException, InterruptedException {
		int slash = archiveUrl.lastIndexOf('/');
		String name = archiveUrl.substring(slash + 1);
		String listUrl = archiveUrl.substring(0, slash + 1) + "checksums.sha256";

		HttpResponse<String> response = httpClient()
				.send(request(listUrl, Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("HTTP " + response.statusCode() + " for " + listUrl);
		}

		for (String line : response.body().split("\n")) {
			// Lines look like: "<64 hex chars>  ffmpeg-....zip"; some tools prefix the name with '*'.
			String trimmed = line.strip();
			int sp = trimmed.indexOf(' ');
			if (sp <= 0) {
				continue;
			}
			String file = trimmed.substring(sp + 1).strip();
			if (file.startsWith("*")) {
				file = file.substring(1);
			}
			if (file.equals(name)) {
				return trimmed.substring(0, sp);
			}
		}
		throw new IOException(listUrl + " has no entry for " + name);
	}

	private String download(String url, Path dest, long approxBytes) throws IOException, InterruptedException {
		HttpClient client = httpClient();
		HttpRequest request = request(url, Duration.ofMinutes(30)).build();

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
