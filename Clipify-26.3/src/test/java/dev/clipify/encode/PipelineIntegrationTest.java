package dev.clipify.encode;

import dev.clipify.capture.CapturedFrame;
import dev.clipify.capture.FramePool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end exercise of the real encode pipeline against an actual FFmpeg binary: synthetic RGBA
 * frames → {@link SegmentRecorder} (live ring of MPEG-TS segments) → {@link ClipAssembler}
 * (snapshot + stream-copy into an MP4). Verifies the finished file is a playable H.264 MP4 of about
 * the requested length.
 *
 * <p>Skipped automatically unless an FFmpeg binary is provided, so it never breaks a CI machine
 * that has none. Point it at one with {@code -Dclipify.ffmpeg=/path/to/ffmpeg} or by putting
 * {@code ffmpeg[.exe]} on {@code PATH}.
 */
class PipelineIntegrationTest {

	private static final int WIDTH = 320;
	private static final int HEIGHT = 240;
	private static final int FPS = 30;
	private static final int SEGMENT_SECONDS = 1;
	private static final int WRAP = 12;

	@Test
	void recordsARingAndAssemblesAPlayableClip(@TempDir Path tempDir) throws Exception {
		Path ffmpeg = locateFfmpeg();
		assumeTrue(ffmpeg != null, "No FFmpeg binary available; set -Dclipify.ffmpeg=<path> to run this test");

		Path segmentsDir = tempDir.resolve("segments");
		Path outputDir = tempDir.resolve("clips");
		Path workDir = tempDir.resolve("work");
		Files.createDirectories(workDir);

		int frameBytes = WIDTH * HEIGHT * 4;
		FramePool pool = new FramePool(frameBytes, 6);
		SegmentRecorder recorder = new SegmentRecorder(
				ffmpeg, segmentsDir, WIDTH, HEIGHT, FPS, SEGMENT_SECONDS, WRAP, 3000,
				VideoEncoder.SOFTWARE);

		assertTrue(recorder.start(), "recorder should start");
		try {
			feedFrames(recorder, pool, frameBytes, FPS * 6);
			assertTrue(recorder.isRunning(), "recorder should still be running: " + recorder.failure());

			ClipAssembler assembler = new ClipAssembler(ffmpeg);
			ClipAssembler.Result result = assembler.assemble(recorder.layout(), 3, outputDir, workDir,
					null, recorder.timelineOriginNanos());

			assertNotNull(result);
			assertTrue(Files.isRegularFile(result.file()), "clip file should exist");
			assertTrue(Files.size(result.file()) > 1000, "clip should be non-trivial in size");
			assertTrue(result.file().getFileName().toString().matches("clipify-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.mp4"),
					"filename should match the clipify-<timestamp>.mp4 pattern: " + result.file().getFileName());

			Probe probe = ffprobe(ffmpeg, result.file());
			assertTrue(probe.isH264(), "clip should be H.264, was: " + probe.codec);
			assertTrue(probe.width == WIDTH && probe.height == HEIGHT,
					"clip should be " + WIDTH + "x" + HEIGHT + ", was " + probe.width + "x" + probe.height);
			// Requested 3s; allow slack for the partial trailing segment and keyframe alignment.
			assertTrue(probe.durationSeconds >= 2.0 && probe.durationSeconds <= 5.0,
					"duration should be roughly 3s, was " + probe.durationSeconds);
		} finally {
			recorder.stop();
		}
	}

	/**
	 * The A/V sync contract: the audio the assembler asks for must be the audio that belongs to the
	 * video it assembled. The stand-in "microphone" here puts a single click at one known instant of
	 * the {@link System#nanoTime} clock, and the fed frames carry their index in their colour — so
	 * reading the clip's first frame back says which instant the video starts on, and the click has
	 * to turn up exactly that far into the sound.
	 */
	@Test
	void clipAudioIsCutFromTheSameInstantAsItsVideo(@TempDir Path tempDir) throws Exception {
		Path ffmpeg = locateFfmpeg();
		assumeTrue(ffmpeg != null, "No FFmpeg binary available; set -Dclipify.ffmpeg=<path> to run this test");

		Path outputDir = tempDir.resolve("clips");
		Path workDir = tempDir.resolve("work");
		Files.createDirectories(workDir);

		int frameBytes = WIDTH * HEIGHT * 4;
		FramePool pool = new FramePool(frameBytes, 6);
		SegmentRecorder recorder = new SegmentRecorder(
				ffmpeg, tempDir.resolve("segments"), WIDTH, HEIGHT, FPS, SEGMENT_SECONDS, WRAP, 3000,
				VideoEncoder.SOFTWARE);

		assertTrue(recorder.start(), "recorder should start");
		try {
			long start = feedFrames(recorder, pool, frameBytes, FPS * 6);
			// Mid-way through the footage a 3-second clip keeps, whichever way the window lands.
			long clickNanos = start + 4_500_000_000L;

			ClipAssembler assembler = new ClipAssembler(ffmpeg);
			ClipAssembler.Result result = assembler.assemble(recorder.layout(), 3, outputDir, workDir,
					(wav, from, seconds) -> writeClick(wav, from, seconds, clickNanos),
					recorder.timelineOriginNanos());

			// Where the clip's video really starts, read back from the picture rather than from the
			// timestamps under test: frame i was fed at start + i/FPS and painted with red = i.
			int firstFrame = firstFrameIndex(ffmpeg, result.file());
			assertTrue(firstFrame >= 0, "should be able to read the clip's first frame back");
			long videoStartNanos = start + (long) firstFrame * 1_000_000_000L / FPS;

			double clickAt = firstClickSeconds(ffmpeg, result.file());
			assertTrue(clickAt >= 0, "the clip should carry the muxed audio track");

			double expected = (clickNanos - videoStartNanos) / 1e9;
			assertTrue(Math.abs(clickAt - expected) < 0.35,
					"the click should be " + String.format("%.2f", expected) + "s into the clip to match its"
							+ " video, but landed at " + String.format("%.2f", clickAt) + "s");
		} finally {
			recorder.stop();
		}
	}

	/**
	 * The slow-PC contract: when only some frames reach the recorder, the clip loses frames, not
	 * time. One frame in five is fed here — the rest stand in for captures a struggling encoder made
	 * the grabber drop — and every picture that did get through has to hold the screen for exactly
	 * the five slots it covers, so the clip still plays at real speed and stays in step with its
	 * audio.
	 */
	@Test
	void missedFramesAreRepeatedSoTheClipStaysRealTime(@TempDir Path tempDir) throws Exception {
		Path ffmpeg = locateFfmpeg();
		assumeTrue(ffmpeg != null, "No FFmpeg binary available; set -Dclipify.ffmpeg=<path> to run this test");

		Path outputDir = tempDir.resolve("clips");
		Path workDir = tempDir.resolve("work");
		Files.createDirectories(workDir);

		final int keepEvery = 5;
		int frameBytes = WIDTH * HEIGHT * 4;
		FramePool pool = new FramePool(frameBytes, 6);
		SegmentRecorder recorder = new SegmentRecorder(
				ffmpeg, tempDir.resolve("segments"), WIDTH, HEIGHT, FPS, SEGMENT_SECONDS, WRAP, 3000,
				VideoEncoder.SOFTWARE);

		assertTrue(recorder.start(), "recorder should start");
		try {
			feedFrames(recorder, pool, frameBytes, FPS * 6, keepEvery);
			assertTrue(recorder.isRunning(), "recorder should still be running: " + recorder.failure());
			assertTrue(recorder.framesDuplicated() >= recorder.framesWritten() * (keepEvery - 1) - keepEvery,
					"every missed slot should be accounted for as a repeat: " + recorder.framesWritten()
							+ " written, " + recorder.framesDuplicated() + " repeated");

			ClipAssembler.Result result = new ClipAssembler(ffmpeg).assemble(recorder.layout(), 3, outputDir,
					workDir, null, recorder.timelineOriginNanos());
			assertNotNull(result);

			Probe probe = ffprobe(ffmpeg, result.file());
			assertTrue(probe.durationSeconds >= 2.0 && probe.durationSeconds <= 5.0,
					"a clip of sparse frames should still be roughly 3s long, was " + probe.durationSeconds);

			double[] shown = frameReds(ffmpeg, result.file());
			assertTrue(shown.length >= FPS * 2, "clip should hold a full-rate timeline, had " + shown.length + " frames");

			// The encoder shifts colours by a few levels, so the pictures are told apart by when the
			// colour moves rather than by its exact value. A picture that went missing would show up
			// here as its neighbour being held twice as long.
			int changes = 0;
			int runStart = -1;
			for (int k = 1; k < shown.length; k++) {
				if (Math.abs(shown[k] - shown[k - 1]) <= 1.0) {
					continue;
				}
				assertTrue(shown[k] > shown[k - 1], "pictures should appear in the order they were fed, but frame "
						+ k + " went from " + shown[k - 1] + " back to " + shown[k]);
				// The clip's first picture starts wherever the segment boundary fell, so only the
				// pictures after it have a full run to measure.
				if (runStart >= 0) {
					assertTrue(k - runStart == keepEvery, "each picture should be held for " + keepEvery
							+ " frames, but the one before frame " + k + " was held for " + (k - runStart));
				}
				runStart = k;
				changes++;
			}
			assertTrue(changes >= FPS * 2 / keepEvery, "expected the fed pictures to show up, saw " + changes + " changes");
		} finally {
			recorder.stop();
		}
	}

	// ------------------------------------------------------------------ helpers

	/** Feeds frames in real time, each painted with its own index, and returns the clock they started on. */
	private static long feedFrames(SegmentRecorder recorder, FramePool pool, int frameBytes, int totalFrames)
			throws InterruptedException {
		return feedFrames(recorder, pool, frameBytes, totalFrames, 1);
	}

	/** As above, but only every {@code keepEvery}-th frame is handed over; the others are never captured. */
	private static long feedFrames(SegmentRecorder recorder, FramePool pool, int frameBytes, int totalFrames,
			int keepEvery) throws InterruptedException {
		// Real-time feeding so FFmpeg rolls several segments. Colours shift over time, which also
		// makes any frame ordering bug visible in the output.
		long start = System.nanoTime();
		for (int i = 0; i < totalFrames; i++) {
			final ByteBuffer buf = i % keepEvery == 0 ? pool.acquire() : null;
			if (buf != null) {
				fill(buf, frameBytes, (byte) (i * 4), (byte) (i * 2), (byte) i);
				long ts = start + (long) i * 1_000_000_000L / FPS;
				recorder.offer(new CapturedFrame(buf, ts, () -> pool.release(buf)));
			}
			sleepUntil(start + (long) (i + 1) * 1_000_000_000L / FPS);
		}
		// Let the last full segment close before saving.
		Thread.sleep(1200);
		return start;
	}

	/** A stand-in capture source: silence, with 20 ms of tone at one instant of the nanoTime clock. */
	private static boolean writeClick(Path wav, long startNanos, double seconds, long clickNanos) {
		int frames = (int) (seconds * 48000);
		byte[] pcm = new byte[frames * 4];
		int at = (int) ((clickNanos - startNanos) * 48000 / 1_000_000_000L);
		for (int s = Math.max(0, at); s < Math.min(frames, at + 48000 / 50); s++) {
			short v = (short) ((s / 24) % 2 == 0 ? 20000 : -20000); // ~1 kHz square wave
			for (int ch = 0; ch < 2; ch++) {
				pcm[s * 4 + ch * 2] = (byte) (v & 0xFF);
				pcm[s * 4 + ch * 2 + 1] = (byte) ((v >> 8) & 0xFF);
			}
		}
		try (javax.sound.sampled.AudioInputStream in = new javax.sound.sampled.AudioInputStream(
				new java.io.ByteArrayInputStream(pcm), AudioCapture.FORMAT, frames)) {
			javax.sound.sampled.AudioSystem.write(in, javax.sound.sampled.AudioFileFormat.Type.WAVE, wav.toFile());
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** The index painted into the clip's first frame, or -1 if it could not be read. */
	private static int firstFrameIndex(Path ffmpeg, Path clip) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-i", clip.toString(), "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "bgra", "pipe:1")
				.start();
		byte[] raw = p.getInputStream().readAllBytes();
		p.waitFor(30, TimeUnit.SECONDS);
		if (raw.length < WIDTH * HEIGHT * 4) {
			return -1;
		}
		// Solid-colour frame: average the red channel, which the feeder set to the frame index.
		long sum = 0;
		int pixels = WIDTH * HEIGHT;
		for (int i = 0; i < pixels; i++) {
			sum += raw[i * 4 + 2] & 0xFF;
		}
		return (int) Math.round(sum / (double) pixels);
	}

	/** The average red level of every frame of the clip, in order; the feeder painted it with the frame index. */
	private static double[] frameReds(Path ffmpeg, Path clip) throws IOException, InterruptedException {
		// Solid-colour frames, so a 4x4 thumbnail of each is all that has to cross the pipe.
		Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-i", clip.toString(), "-vf", "scale=4:4", "-f", "rawvideo", "-pix_fmt", "bgra", "pipe:1")
				.start();
		byte[] raw = p.getInputStream().readAllBytes();
		p.waitFor(30, TimeUnit.SECONDS);
		int thumbBytes = 4 * 4 * 4;
		double[] reds = new double[raw.length / thumbBytes];
		for (int f = 0; f < reds.length; f++) {
			long sum = 0;
			for (int px = 0; px < 16; px++) {
				sum += raw[f * thumbBytes + px * 4 + 2] & 0xFF;
			}
			reds[f] = sum / 16.0;
		}
		return reds;
	}

	/** Seconds into the clip's audio where the first click lands, or -1 if there is none. */
	private static double firstClickSeconds(Path ffmpeg, Path clip) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-i", clip.toString(), "-map", "0:a:0", "-f", "s16le", "-ac", "2", "-ar", "48000", "pipe:1")
				.start();
		byte[] pcm = p.getInputStream().readAllBytes();
		p.waitFor(30, TimeUnit.SECONDS);
		for (int i = 0; i + 1 < pcm.length; i += 2) {
			int sample = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
			if (Math.abs(sample) > 8000) {
				return (i / 2) / 2 / 48000.0; // 16-bit interleaved stereo
			}
		}
		return -1;
	}

	private static void fill(ByteBuffer buf, int frameBytes, byte b, byte g, byte r) {
		buf.clear();
		// 26.3: the grabber delivers the renderer's RGBA bytes and the encoder swaps red/blue back,
		// so the stand-in frames are written the same way.
		for (int i = 0; i < frameBytes; i += 4) {
			buf.put(r).put(g).put(b).put((byte) 0xFF);
		}
		buf.flip();
	}

	private static void sleepUntil(long targetNanos) throws InterruptedException {
		long remaining = targetNanos - System.nanoTime();
		if (remaining > 0) {
			Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
		}
	}

	private record Probe(String codec, int width, int height, double durationSeconds) {
		boolean isH264() {
			return "h264".equalsIgnoreCase(codec);
		}
	}

	/**
	 * The editor's volume slider: the gain set in the editor is the gain in the saved file, and a
	 * muted save carries no audio track at all.
	 */
	@Test
	void savedClipCarriesTheEditorVolume(@TempDir Path tempDir) throws Exception {
		Path ffmpeg = locateFfmpeg();
		assumeTrue(ffmpeg != null, "No FFmpeg binary available; set -Dclipify.ffmpeg=<path> to run this test");

		// 4 s test clip with a quiet tone (low enough that doubling it cannot clip).
		Path src = tempDir.resolve("src.mp4");
		Process make = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
				"-f", "lavfi", "-i", "testsrc=size=" + WIDTH + "x" + HEIGHT + ":rate=" + FPS + ":duration=4",
				"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=4",
				"-af", "volume=0.1", "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-shortest",
				"-y", src.toString()).redirectErrorStream(true).start();
		make.getInputStream().readAllBytes();
		assertTrue(make.waitFor(60, TimeUnit.SECONDS) && make.exitValue() == 0, "test clip should be made");

		Path same = tempDir.resolve("same.mp4");
		Path loud = tempDir.resolve("loud.mp4");
		Path quiet = tempDir.resolve("quiet.mp4");
		Path muted = tempDir.resolve("muted.mp4");
		ClipLibrary.trim(ffmpeg, src, 0.5, 3.5, 1.0, same);
		ClipLibrary.trim(ffmpeg, src, 0.5, 3.5, 2.0, loud);
		ClipLibrary.trim(ffmpeg, src, 0.5, 3.5, 0.5, quiet);
		ClipLibrary.trim(ffmpeg, src, 0.5, 3.5, 0.0, muted);

		double base = meanVolumeDb(ffmpeg, same);
		assertTrue(Math.abs(meanVolumeDb(ffmpeg, loud) - base - 6.02) < 0.5, "200% should be +6 dB");
		assertTrue(Math.abs(meanVolumeDb(ffmpeg, quiet) - base + 6.02) < 0.5, "50% should be -6 dB");
		assertTrue(!streamInfo(ffmpeg, muted).contains("Audio:"), "a muted save should have no audio track");
		assertTrue(streamInfo(ffmpeg, muted).contains("Video:"), "a muted save should keep its video");
		Probe p = ffprobe(ffmpeg, muted);
		assertTrue(p.durationSeconds > 2.5 && p.durationSeconds < 3.5, "trim still applies when muted: " + p.durationSeconds);
	}

	private static String streamInfo(Path ffmpeg, Path file) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-i", file.toString())
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		p.waitFor(30, TimeUnit.SECONDS);
		return out;
	}

	private static double meanVolumeDb(Path ffmpeg, Path file) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", file.toString(),
				"-vn", "-af", "volumedetect", "-f", "null", "-").redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		p.waitFor(30, TimeUnit.SECONDS);
		var m = java.util.regex.Pattern.compile("mean_volume: (-?[0-9.]+) dB").matcher(out);
		assertTrue(m.find(), "volumedetect output: " + out);
		return Double.parseDouble(m.group(1));
	}

	private static Probe ffprobe(Path ffmpeg, Path file) throws IOException, InterruptedException {
		// Reuse ffmpeg itself rather than requiring ffprobe: parse the stderr it prints for an input.
		Process p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-i", file.toString())
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		p.waitFor(30, TimeUnit.SECONDS);

		String codec = "?";
		int w = 0, h = 0;
		double duration = 0;
		var durMatch = java.util.regex.Pattern.compile("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)").matcher(out);
		if (durMatch.find()) {
			duration = Integer.parseInt(durMatch.group(1)) * 3600
					+ Integer.parseInt(durMatch.group(2)) * 60
					+ Double.parseDouble(durMatch.group(3));
		}
		var vidMatch = java.util.regex.Pattern.compile("Video: (\\w+).*?(\\d{2,5})x(\\d{2,5})").matcher(out);
		if (vidMatch.find()) {
			codec = vidMatch.group(1);
			w = Integer.parseInt(vidMatch.group(2));
			h = Integer.parseInt(vidMatch.group(3));
		}
		return new Probe(codec, w, h, duration);
	}

	/** Resolves FFmpeg from the system property, then PATH. */
	private static Path locateFfmpeg() {
		String prop = System.getProperty("clipify.ffmpeg");
		if (prop != null && Files.isRegularFile(Path.of(prop))) {
			return Path.of(prop);
		}
		boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
		String exe = windows ? "ffmpeg.exe" : "ffmpeg";
		String pathEnv = System.getenv("PATH");
		if (pathEnv != null) {
			for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
				if (dir.isBlank()) {
					continue;
				}
				try {
					Path candidate = Path.of(dir.trim()).resolve(exe);
					if (Files.isRegularFile(candidate)) {
						return candidate;
					}
				} catch (RuntimeException ignored) {
					// A malformed PATH entry (Windows allows quotes/invalid chars); skip it.
				}
			}
		}
		return null;
	}
}
