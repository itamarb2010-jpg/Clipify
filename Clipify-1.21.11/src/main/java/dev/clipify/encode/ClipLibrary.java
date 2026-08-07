package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filesystem + FFmpeg operations backing the Clips screen: listing recordings, probing a clip's
 * length, grabbing a thumbnail frame, and trimming. All Minecraft-free so both builds share it, and
 * every FFmpeg call is blocking — callers run it off the render thread.
 */
public final class ClipLibrary {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+\\.\\d+)");

	/** One item on disk — a clip ({@code .mp4}) or a screenshot ({@code .png}). */
	public record Clip(Path file, long sizeBytes, long modifiedMillis, boolean screenshot) {
		public String name() {
			return file.getFileName().toString();
		}
	}

	private ClipLibrary() {
	}

	/** Every {@code .mp4} clip in {@code dir}, newest first. */
	public static List<Clip> list(Path dir) {
		return listDir(dir, ".mp4", false);
	}

	/** Every {@code .png} screenshot in {@code dir}, newest first. */
	public static List<Clip> listScreenshots(Path dir) {
		return listDir(dir, ".png", true);
	}

	/** Clips and screenshots merged, newest first. */
	public static List<Clip> listAll(Path clipsDir, Path screenshotsDir) {
		List<Clip> all = new ArrayList<>(list(clipsDir));
		all.addAll(listScreenshots(screenshotsDir));
		all.sort(Comparator.comparingLong(Clip::modifiedMillis).reversed());
		return all;
	}

	private static List<Clip> listDir(Path dir, String ext, boolean screenshot) {
		List<Clip> clips = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return clips;
		}
		try (var stream = Files.list(dir)) {
			for (Path p : stream.toList()) {
				String n = p.getFileName().toString().toLowerCase();
				if (n.endsWith(ext) && Files.isRegularFile(p)) {
					clips.add(new Clip(p, safeSize(p), p.toFile().lastModified(), screenshot));
				}
			}
		} catch (IOException e) {
			ClipifyLog.LOGGER.warn("Could not list {} in {}", ext, dir, e);
		}
		clips.sort(Comparator.comparingLong(Clip::modifiedMillis).reversed());
		return clips;
	}

	private static long safeSize(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			return 0;
		}
	}

	/** Length of a clip in seconds, or 0 if it can't be determined. */
	public static double durationSeconds(Path ffmpeg, Path file) {
		if (ffmpeg == null) {
			return 0;
		}
		try {
			Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-i", file.toString())
					.redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			p.waitFor(20, TimeUnit.SECONDS);
			Matcher m = DURATION.matcher(out);
			if (m.find()) {
				return Integer.parseInt(m.group(1)) * 3600
						+ Integer.parseInt(m.group(2)) * 60
						+ Double.parseDouble(m.group(3));
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
		}
		return 0;
	}

	/**
	 * A single PNG frame at {@code atSeconds}, scaled so it's at most {@code maxWidth}px wide (never
	 * upscaled past the source) using the high-quality Lanczos scaler, or null on failure.
	 */
	public static byte[] thumbnailPng(Path ffmpeg, Path file, double atSeconds, int maxWidth) {
		if (ffmpeg == null) {
			return null;
		}
		try {
			Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
					"-ss", format(Math.max(0, atSeconds)), "-i", file.toString(),
					"-frames:v", "1", "-vf", "scale=min(" + maxWidth + "\\,iw):-2:flags=lanczos",
					"-f", "image2pipe", "-vcodec", "png", "pipe:1")
					.start();
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			p.getInputStream().transferTo(png);
			if (!p.waitFor(20, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			byte[] bytes = png.toByteArray();
			return bytes.length > 0 ? bytes : null;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return null;
		}
	}

	/**
	 * Trims {@code src} to [{@code start}, {@code end}] seconds into {@code dest}, re-encoding for a
	 * frame-accurate cut — at the source's own resolution and CRF 18, so a trimmed clip is not a
	 * visibly worse clip. Blocking.
	 *
	 * @throws IOException if FFmpeg fails
	 */
	public static void trim(Path ffmpeg, Path src, double start, double end, Path dest) throws IOException {
		double duration = end - start;
		if (duration <= 0.05) {
			throw new IOException("The trim range is empty.");
		}
		List<String> cmd = List.of(
				ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-ss", format(start), "-i", src.toString(), "-t", format(duration),
				"-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
				"-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-y", dest.toString());
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(3, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new IOException("Trim timed out.");
			}
			if (p.exitValue() != 0) {
				ClipifyLog.LOGGER.error("Trim failed:\n{}", out);
				throw new IOException("FFmpeg could not trim the clip (exit " + p.exitValue() + ").");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Trim was interrupted.");
		}
	}

	/**
	 * Builds the shared (link) copy of a clip: the {@code watermark} PNG overlaid bottom-right, at the
	 * clip's own resolution and frame rate. The video is re-encoded because an overlay can't be
	 * stream-copied, but at CRF 18 — visually indistinguishable from the source, so the link looks
	 * like the recording rather than a shrunken version of it. Audio is stream-copied and the source
	 * file is never touched. Blocking.
	 *
	 * @throws IOException if FFmpeg fails
	 */
	public static void shareCopy(Path ffmpeg, Path src, Path watermark, Path dest) throws IOException {
		List<String> cmd = List.of(
				ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-i", src.toString(), "-i", watermark.toString(),
				"-filter_complex", "[0:v][1:v]overlay=W-w-16:H-h-14[out]",
				"-map", "[out]", "-map", "0:a:0?",
				"-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
				"-c:a", "copy", "-movflags", "+faststart", "-y", dest.toString());
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(3, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new IOException("Watermark timed out.");
			}
			if (p.exitValue() != 0) {
				ClipifyLog.LOGGER.error("Share-copy (watermark) failed:\n{}", out);
				throw new IOException("FFmpeg could not build the share copy (exit " + p.exitValue() + ").");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Watermark was interrupted.");
		}
	}

	/**
	 * Renames {@code file} to {@code newBaseName} (its extension is preserved), stripping any
	 * characters that aren't legal in a filename. Returns the new path.
	 *
	 * @throws IOException if the name is empty after cleaning, or a different file already uses it
	 */
	public static Path rename(Path file, String newBaseName) throws IOException {
		String current = file.getFileName().toString();
		int dot = current.lastIndexOf('.');
		String ext = dot >= 0 ? current.substring(dot) : "";
		String safe = sanitizeFileName(newBaseName);
		if (safe.isEmpty()) {
			throw new IOException("Please enter a name.");
		}
		Path dest = file.resolveSibling(safe + ext);
		if (dest.equals(file)) {
			return file;
		}
		if (Files.exists(dest)) {
			throw new IOException("A file named \"" + safe + ext + "\" already exists.");
		}
		Files.move(file, dest);
		return dest;
	}

	/** Strips characters illegal in a filename (on any OS) and trims surrounding spaces/dots. */
	public static String sanitizeFileName(String name) {
		String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "").trim();
		while (cleaned.endsWith(".")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
		}
		return cleaned;
	}

	/** A fresh timestamped output path in the same folder, for "save as copy". */
	public static Path newClipPath(Path dir, String suffix) {
		String base = "clipify-" + LocalDateTime.now().format(STAMP) + (suffix == null ? "" : suffix);
		Path candidate = dir.resolve(base + ".mp4");
		int i = 2;
		while (Files.exists(candidate) && i < 1000) {
			candidate = dir.resolve(base + "-" + i + ".mp4");
			i++;
		}
		return candidate;
	}

	/** Replaces {@code target} with {@code temp} atomically where possible. */
	public static void replace(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailed) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static boolean deleteQuietly(Path file) {
		try {
			return Files.deleteIfExists(file);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Deletes a clip, retrying briefly. On Windows a just-closed FFmpeg process can hold the file open
	 * for a moment, so a single delete may fail even though the handle is about to be released. Must be
	 * called off the render thread (it sleeps between attempts).
	 */
	public static boolean deleteWithRetry(Path file) {
		for (int i = 0; i < 20; i++) {
			if (deleteQuietly(file) || !Files.exists(file)) {
				return true;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return !Files.exists(file);
	}

	private static String format(double seconds) {
		return String.format(java.util.Locale.ROOT, "%.3f", seconds);
	}
}
