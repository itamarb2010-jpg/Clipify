package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Desktop-audio capture via WASAPI loopback — records whatever is playing to any render endpoint
 * (headphones, speakers, a virtual cable output) with no Stereo Mix, no rerouting and nothing for
 * the user to install, because WASAPI loopback is built into Windows.
 *
 * <p>Neither the bundled FFmpeg (no WASAPI demuxer) nor Java Sound (no render loopback) can do this,
 * so a tiny C# helper does. Its source ships in the jar; on first use it's compiled with the C#
 * compiler that ships with every Windows install (.NET Framework's {@code csc.exe}) into a ~9&nbsp;KB
 * exe cached in {@code clipify/bin/}. The helper writes raw s16le/48k/stereo PCM to stdout, which
 * {@link AudioCapture} reads exactly like a microphone line. Fail-safe: if anything is missing, this
 * returns nothing and desktop capture is simply skipped.
 */
public final class WasapiLoopback {

	private static final String SOURCE_RESOURCE = "/assets/clipify/bin/WasapiLoopback.cs";
	private static volatile Path cachedExe;

	private WasapiLoopback() {
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	/** Compiles (once) and returns the loopback helper exe, or {@code null} if unavailable. */
	public static synchronized Path ensureHelper(Path binDir) {
		Path cached = cachedExe;
		if (cached != null && Files.isRegularFile(cached)) {
			return cached;
		}
		if (!isWindows()) {
			return null;
		}
		try {
			Path exe = binDir.resolve("WasapiLoopback.exe");
			if (Files.isRegularFile(exe) && Files.size(exe) > 0) {
				cachedExe = exe;
				return exe;
			}
			Path csc = findCsc();
			if (csc == null) {
				ClipifyLog.LOGGER.warn("Clipify audio: no C# compiler (csc.exe) found — PC-audio capture unavailable");
				return null;
			}
			Files.createDirectories(binDir);
			Path src = binDir.resolve("WasapiLoopback.cs");
			try (InputStream in = WasapiLoopback.class.getResourceAsStream(SOURCE_RESOURCE)) {
				if (in == null) {
					ClipifyLog.LOGGER.warn("Clipify audio: WASAPI helper source missing from the jar");
					return null;
				}
				Files.copy(in, src, StandardCopyOption.REPLACE_EXISTING);
			}
			Process p = new ProcessBuilder(csc.toString(), "-nologo", "-optimize", "-platform:x64",
					"-out:" + exe.toAbsolutePath(), src.toAbsolutePath().toString())
					.redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(60, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			if (p.exitValue() != 0 || !Files.isRegularFile(exe)) {
				ClipifyLog.LOGGER.warn("Clipify audio: could not compile the WASAPI helper: {}", out.strip());
				return null;
			}
			ClipifyLog.LOGGER.info("Clipify audio: compiled WASAPI loopback helper -> {}", exe);
			cachedExe = exe;
			return exe;
		} catch (Exception e) {
			ClipifyLog.LOGGER.warn("Clipify audio: WASAPI helper setup failed", e);
			return null;
		}
	}

	/** Starts a loopback capture of {@code renderDevice} (a render-endpoint name, or null = default). */
	public static Process startCapture(Path exe, String renderDevice) throws IOException {
		String arg = (renderDevice == null || renderDevice.isBlank()) ? "default" : renderDevice;
		Process p = new ProcessBuilder(exe.toString(), arg).start();
		// Drain stderr (mix-format line + any errors) so it can't block, and log it at debug.
		Thread t = new Thread(() -> {
			try (InputStream err = p.getErrorStream()) {
				byte[] b = err.readAllBytes();
				if (b.length > 0) {
					ClipifyLog.LOGGER.debug("[wasapi] {}", new String(b, StandardCharsets.UTF_8).strip());
				}
			} catch (IOException ignored) {
				// process gone
			}
		}, "Clipify-wasapi-log");
		t.setDaemon(true);
		t.start();
		return p;
	}

	private static Path findCsc() {
		String windir = System.getenv("WINDIR");
		if (windir == null || windir.isBlank()) {
			windir = "C:\\Windows";
		}
		for (String arch : List.of("Framework64", "Framework")) {
			Path base = Path.of(windir, "Microsoft.NET", arch);
			if (!Files.isDirectory(base)) {
				continue;
			}
			try (var stream = Files.list(base)) {
				List<Path> versions = stream.filter(Files::isDirectory)
						.filter(d -> d.getFileName().toString().startsWith("v4"))
						.sorted(Comparator.reverseOrder())
						.toList();
				for (Path v : versions) {
					Path csc = v.resolve("csc.exe");
					if (Files.isRegularFile(csc)) {
						return csc;
					}
				}
			} catch (IOException ignored) {
				// try the next arch
			}
		}
		return null;
	}
}
