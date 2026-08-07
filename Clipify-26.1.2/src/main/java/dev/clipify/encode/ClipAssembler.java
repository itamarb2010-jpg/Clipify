package dev.clipify.encode;

import dev.clipify.ClipifyLog;

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

/**
 * Turns the trailing part of the on-disk segment ring into a finished MP4.
 *
 * <p>Everything here runs off the render thread. The first thing it does is <b>snapshot</b> the
 * segments it needs by copying them aside: the live recorder keeps writing and recycling ring
 * filenames the whole time, and without a snapshot a long save could have a segment overwritten
 * underneath it. After the copy the assembly is completely decoupled from the running buffer,
 * which is what lets recording continue uninterrupted.
 *
 * <p>The MP4 is produced with {@code -c copy}: the segments are already H.264 with a keyframe on
 * every boundary, so there is no re-encode and a 30-second clip lands in well under a second.
 */
public final class ClipAssembler {

	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	public record Result(Path file, double seconds) {}

	/** Raised with a message that is safe to show the player. */
	public static final class ClipException extends Exception {
		public ClipException(String message) {
			super(message);
		}

		public ClipException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Supplies the clip's audio once the video window is known: write the {@code seconds} of sound
	 * that begin at wall-clock {@code startNanos} (a {@link System#nanoTime} reading) to {@code wav},
	 * or return false if there is none. Implemented by {@link AudioCapture#writeClipWav}.
	 */
	@FunctionalInterface
	public interface AudioTrack {
		boolean write(Path wav, long startNanos, double seconds);
	}

	private final Path ffmpeg;

	public ClipAssembler(Path ffmpeg) {
		this.ffmpeg = ffmpeg;
	}

	/**
	 * @param audio            supplies the sound for the video window that is chosen here, or null
	 * @param videoOriginNanos {@link SegmentRecorder#timelineOriginNanos()} — the wall clock the
	 *                         segment list's timestamps are measured from, so the audio can be cut
	 *                         from the exact instant the assembled video starts
	 */
	public Result assemble(SegmentRecorder.Layout layout, int requestedSeconds, Path outputDir, Path workRoot,
			AudioTrack audio, long videoOriginNanos) throws ClipException {
		List<Segment> completed = readSegmentList(layout);
		Path inProgress = findInProgressSegment(layout, completed);

		if (completed.isEmpty() && inProgress == null) {
			throw new ClipException("The replay buffer is still filling up — try again in a few seconds.");
		}

		double partialSeconds = estimatePartialSeconds(completed, inProgress, layout.segmentSeconds());
		List<Segment> chosen = chooseTrailing(completed, requestedSeconds - partialSeconds);
		double inProgressStart = completed.isEmpty() ? 0.0 : completed.get(completed.size() - 1).end();

		Path workDir = workRoot.resolve("clip-" + System.nanoTime());
		try {
			Files.createDirectories(workDir);
			Snapshot snapshot = snapshot(chosen, inProgress, inProgressStart, workDir);
			if (snapshot.parts().isEmpty()) {
				throw new ClipException("No replay data was available to save.");
			}

			Path concatFile = writeConcatFile(workDir, snapshot.parts());
			Files.createDirectories(outputDir);
			Path target = uniqueOutputFile(outputDir);
			double seconds = chosen.stream().mapToDouble(Segment::seconds).sum() + partialSeconds;

			Path audioWav = writeAudio(audio, videoOriginNanos, snapshot.startTime(),
					seconds + layout.segmentSeconds(), workDir);
			if (audioWav != null) {
				// One FFmpeg pass: concat the video ring (stream-copy) AND mux the captured audio over
				// it. Avoids writing the ~75 MB video a second time (the old concat→video-only→mux did
				// two full passes + two faststart rewrites).
				concatMux(concatFile, audioWav, target);
			} else {
				runConcat(concatFile, target);
			}

			if (!Files.isRegularFile(target) || Files.size(target) == 0L) {
				throw new ClipException("FFmpeg produced an empty file.");
			}
			return new Result(target, seconds);
		} catch (IOException e) {
			throw new ClipException("Could not write the clip: " + e.getMessage(), e);
		} finally {
			deleteRecursivelyQuietly(workDir);
		}
	}

	// ------------------------------------------------------------- selection

	private record Segment(Path file, double start, double end) {
		double seconds() {
			return Math.max(0.0, end - start);
		}
	}

	/** Reads FFmpeg's csv segment list: {@code file,startTime,endTime} in chronological order. */
	private static List<Segment> readSegmentList(SegmentRecorder.Layout layout) throws ClipException {
		Path list = layout.listFile();
		if (!Files.isRegularFile(list)) {
			return List.of();
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(list, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ClipException("Could not read the replay buffer index: " + e.getMessage(), e);
		}

		List<Segment> out = new ArrayList<>(lines.size());
		for (String line : lines) {
			String[] parts = line.split(",");
			if (parts.length < 3) {
				continue;
			}
			try {
				Path file = resolveSegment(layout.directory(), parts[0].trim());
				if (Files.isRegularFile(file) && Files.size(file) > 0L) {
					out.add(new Segment(file, Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())));
				}
			} catch (NumberFormatException | IOException ignored) {
				// Half-written final line while FFmpeg is appending; skip it.
			}
		}
		return out;
	}

	private static Path resolveSegment(Path dir, String entry) {
		Path p = Path.of(entry);
		return p.isAbsolute() ? p : dir.resolve(entry);
	}

	/**
	 * The segment FFmpeg is writing right now. It holds the most recent — and usually most
	 * interesting — moments, so it is worth including even though it is incomplete; MPEG-TS
	 * tolerates a truncated tail and the concat demuxer just uses what is there.
	 */
	private static Path findInProgressSegment(SegmentRecorder.Layout layout, List<Segment> completed) {
		int nextIndex = 0;
		if (!completed.isEmpty()) {
			String last = completed.get(completed.size() - 1).file().getFileName().toString();
			String digits = last.replaceAll("\\D+", "");
			if (digits.isEmpty()) {
				return null;
			}
			nextIndex = (Integer.parseInt(digits) + 1) % layout.wrap();
		}
		Path candidate = layout.directory().resolve(String.format(layout.pattern(), nextIndex));
		try {
			if (!Files.isRegularFile(candidate) || Files.size(candidate) == 0L) {
				return null;
			}
			if (!completed.isEmpty()) {
				Path newestCompleted = completed.get(completed.size() - 1).file();
				// Guard against picking up a stale ring file that has not been recycled yet.
				if (Files.getLastModifiedTime(candidate).compareTo(Files.getLastModifiedTime(newestCompleted)) < 0) {
					return null;
				}
			}
			return candidate;
		} catch (IOException e) {
			return null;
		}
	}

	/** Estimates the partial segment's length from its size relative to finished segments. */
	private static double estimatePartialSeconds(List<Segment> completed, Path inProgress, int segmentSeconds) {
		if (inProgress == null) {
			return 0.0;
		}
		try {
			long partialSize = Files.size(inProgress);
			if (completed.isEmpty()) {
				return 0.0;
			}
			long total = 0;
			int counted = 0;
			for (int i = completed.size() - 1; i >= 0 && counted < 4; i--, counted++) {
				total += Files.size(completed.get(i).file());
			}
			if (counted == 0 || total == 0) {
				return 0.0;
			}
			double average = (double) total / counted;
			return Math.min(segmentSeconds, segmentSeconds * (partialSize / average));
		} catch (IOException e) {
			return 0.0;
		}
	}

	/** Newest-first walk back through the ring until the requested number of seconds is covered. */
	private static List<Segment> chooseTrailing(List<Segment> completed, double neededSeconds) {
		List<Segment> chosen = new ArrayList<>();
		double total = 0.0;
		for (int i = completed.size() - 1; i >= 0 && total < neededSeconds; i--) {
			Segment s = completed.get(i);
			chosen.add(s);
			total += s.seconds();
		}
		chosen.sort(Comparator.comparingDouble(Segment::start));
		return chosen;
	}

	// ------------------------------------------------------------- assembly

	/**
	 * @param parts     the copied segments, in play order
	 * @param startTime where the first of them begins on the recorder's timeline — the anchor the
	 *                  audio window is cut from, so it has to come from the segment that actually
	 *                  survived the copy rather than the one we set out to take
	 */
	private record Snapshot(List<Path> parts, double startTime) {}

	private static Snapshot snapshot(List<Segment> chosen, Path inProgress, double inProgressStart, Path workDir) {
		List<Path> parts = new ArrayList<>(chosen.size() + 1);
		double startTime = inProgressStart;
		int index = 0;
		for (Segment s : chosen) {
			Path dest = workDir.resolve(String.format("part%04d.ts", index++));
			try {
				Files.copy(s.file(), dest, StandardCopyOption.REPLACE_EXISTING);
				if (parts.isEmpty()) {
					startTime = s.start();
				}
				parts.add(dest);
			} catch (IOException e) {
				// The ring recycled this segment while we were copying. Everything after it is
				// still valid, so drop it and carry on with a slightly shorter clip.
				ClipifyLog.LOGGER.warn("Skipping segment {} that was recycled during save", s.file().getFileName());
			}
		}
		if (inProgress != null) {
			Path dest = workDir.resolve(String.format("part%04d.ts", index));
			try {
				Files.copy(inProgress, dest, StandardCopyOption.REPLACE_EXISTING);
				if (Files.size(dest) > 0L) {
					parts.add(dest);
				}
			} catch (IOException e) {
				ClipifyLog.LOGGER.debug("In-progress segment could not be snapshotted", e);
			}
		}
		return new Snapshot(parts, startTime);
	}

	/**
	 * Cuts the sound for the video that was just snapshotted. Segment timestamps and the audio ring
	 * share one wall clock, so the window starts at {@code origin + startTime} — no guessing from
	 * durations, and no drift when a source stalls.
	 *
	 * <p>{@code seconds} is deliberately generous (the in-progress segment's length is only estimated
	 * from its size): running past the video costs a little silence at the end of the WAV, which
	 * {@code -shortest} drops, while coming up short would cut the clip's last moments off.
	 *
	 * @return the WAV, or null when there is no audio to add
	 */
	private static Path writeAudio(AudioTrack audio, long videoOriginNanos, double startTime, double seconds,
			Path workDir) {
		if (audio == null || videoOriginNanos == Long.MIN_VALUE) {
			return null;
		}
		Path wav = workDir.resolve("audio.wav");
		long startNanos = videoOriginNanos + (long) (startTime * 1_000_000_000L);
		try {
			if (audio.write(wav, startNanos, seconds + 1.0) && Files.size(wav) > 44L) {
				return wav;
			}
		} catch (IOException e) {
			ClipifyLog.LOGGER.warn("Clip audio could not be prepared; saving without sound", e);
		}
		return null;
	}

	private static Path writeConcatFile(Path workDir, List<Path> parts) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (Path p : parts) {
			// The concat demuxer treats ' as a quote character, so escape it.
			String name = p.getFileName().toString().replace("'", "'\\''");
			sb.append("file '").append(name).append("'\n");
		}
		Path concat = workDir.resolve("concat.txt");
		Files.writeString(concat, sb.toString(), StandardCharsets.UTF_8);
		return concat;
	}

	private void runConcat(Path concatFile, Path target) throws ClipException {
		List<String> cmd = List.of(
				ffmpeg.toAbsolutePath().toString(),
				"-hide_banner", "-loglevel", "error", "-nostdin",
				"-f", "concat", "-safe", "0",
				"-i", concatFile.toAbsolutePath().toString(),
				"-c", "copy",
				"-movflags", "+faststart",
				"-y", target.toAbsolutePath().toString());
		try {
			Process p = new ProcessBuilder(cmd)
					.directory(concatFile.getParent().toFile())
					.redirectErrorStream(true)
					.start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(2, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new ClipException("Timed out while writing the clip.");
			}
			if (p.exitValue() != 0) {
				ClipifyLog.LOGGER.error("Clip assembly failed:\n{}", out);
				throw new ClipException("FFmpeg could not assemble the clip (exit " + p.exitValue() + ").");
			}
			if (!out.isBlank()) {
				ClipifyLog.LOGGER.debug("[ffmpeg concat] {}", out.strip());
			}
		} catch (IOException e) {
			throw new ClipException("Could not run FFmpeg: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ClipException("Interrupted while saving the clip.");
		}
	}

	/**
	 * One-pass concat + audio mux: the video ring is stream-copied straight from the concat list while
	 * the captured audio WAV is muxed over it. The WAV already begins on the video's first frame (see
	 * {@link #writeAudio}) and runs past its end, so the streams simply start together and
	 * {@code -shortest} trims the spare tail.
	 */
	private void concatMux(Path concatFile, Path wav, Path target) throws ClipException {
		List<String> cmd = List.of(
				ffmpeg.toAbsolutePath().toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-f", "concat", "-safe", "0", "-i", concatFile.toAbsolutePath().toString(),
				"-i", wav.toAbsolutePath().toString(),
				"-map", "0:v:0", "-map", "1:a:0",
				"-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
				"-shortest", "-movflags", "+faststart",
				"-y", target.toAbsolutePath().toString());
		try {
			Process p = new ProcessBuilder(cmd)
					.directory(concatFile.getParent().toFile())
					.redirectErrorStream(true)
					.start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!p.waitFor(2, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new ClipException("Timed out while saving the clip.");
			}
			if (p.exitValue() != 0) {
				ClipifyLog.LOGGER.error("Clip assembly (concat+mux) failed:\n{}", out);
				throw new ClipException("FFmpeg could not assemble the clip (exit " + p.exitValue() + ").");
			}
			if (!out.isBlank()) {
				ClipifyLog.LOGGER.debug("[ffmpeg concat+mux] {}", out.strip());
			}
		} catch (IOException e) {
			throw new ClipException("Could not run FFmpeg: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ClipException("Interrupted while saving the clip.");
		}
	}

	private static Path uniqueOutputFile(Path outputDir) {
		String stamp = LocalDateTime.now().format(FILE_STAMP);
		Path candidate = outputDir.resolve("clipify-" + stamp + ".mp4");
		int suffix = 2;
		while (Files.exists(candidate) && suffix < 1000) {
			candidate = outputDir.resolve("clipify-" + stamp + "-" + suffix + ".mp4");
			suffix++;
		}
		return candidate;
	}

	private static void deleteRecursivelyQuietly(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// Cleaned up on the next startup sweep instead.
				}
			});
		} catch (IOException ignored) {
			// Same.
		}
	}
}
