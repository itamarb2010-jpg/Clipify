package dev.clipify.encode;

import dev.clipify.ClipifyLog;
import dev.clipify.capture.CaptureMath;
import dev.clipify.capture.CapturedFrame;
import dev.clipify.capture.FramePool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rolling buffer itself: one long-lived FFmpeg process encoding captured frames straight into
 * a fixed ring of short MPEG-TS segments on disk.
 *
 * <p>Nothing is ever kept in RAM beyond the handful of pooled frames in flight, and FFmpeg's
 * {@code segment_wrap} recycles filenames so disk use is capped at roughly
 * {@code bitrate * (duration + 2s)} regardless of how long the session runs. Saving a clip is then
 * just a stream copy of the trailing segments, which is why it is near-instant and why encoding
 * never has to stop.
 */
public final class SegmentRecorder {

	/** Frames handed over but not yet written. Bounded — see {@link FramePool}. */
	private static final int QUEUE_CAPACITY = 8;
	private static final int WRITE_CHUNK = 256 * 1024;
	/** Longest run of duplicated frames we will emit to cover a stall before resynchronising. */
	private static final int MAX_DUPLICATE_FRAMES_FACTOR = 2;

	public record Layout(Path directory, Path listFile, String pattern, int wrap, int segmentSeconds) {}

	private final Path ffmpeg;
	private final Path segmentDir;
	private final int width;
	private final int height;
	private final int fps;
	private final int segmentSeconds;
	private final int wrap;
	private final FramePool pool;
	private final VideoEncoder encoder;
	private final int bitrateKbps;

	private final ArrayBlockingQueue<CapturedFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private final AtomicBoolean running = new AtomicBoolean();
	/**
	 * Wall clock ({@link System#nanoTime}) of output timeline 0 — the instant the frame that FFmpeg
	 * encoded as its first frame was captured, kept up to date through the CFR remapper's rebases.
	 * Segment {@code t} in the list therefore starts at {@code origin + t}, which is what lets a save
	 * cut the audio that belongs to the video it is about to assemble. {@link Long#MIN_VALUE} until
	 * the first frame is written.
	 */
	private final AtomicLong timelineOriginNanos = new AtomicLong(Long.MIN_VALUE);
	private final AtomicLong framesWritten = new AtomicLong();
	private final AtomicLong framesDuplicated = new AtomicLong();
	private final AtomicLong framesDroppedQueueFull = new AtomicLong();
	private final Deque<String> recentLog = new ArrayDeque<>();

	private volatile Process process;
	private volatile Thread writerThread;
	private volatile Thread logThread;
	private volatile String failure;

	public SegmentRecorder(Path ffmpeg, Path segmentDir, int width, int height, int fps,
			int segmentSeconds, int wrap, int bitrateKbps, VideoEncoder encoder, FramePool pool) {
		this.ffmpeg = ffmpeg;
		this.segmentDir = segmentDir;
		this.width = width;
		this.height = height;
		this.fps = fps;
		this.segmentSeconds = segmentSeconds;
		this.wrap = wrap;
		this.bitrateKbps = bitrateKbps;
		this.encoder = encoder;
		this.pool = pool;
	}

	public Layout layout() {
		return new Layout(segmentDir, segmentDir.resolve("segments.csv"), "seg%03d.ts", wrap, segmentSeconds);
	}

	public boolean isRunning() {
		return running.get() && process != null && process.isAlive();
	}

	public String failure() {
		return failure;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public VideoEncoder encoder() {
		return encoder;
	}

	/** @see #timelineOriginNanos */
	public long timelineOriginNanos() {
		return timelineOriginNanos.get();
	}

	// ----------------------------------------------------------------- start

	public boolean start() throws IOException {
		Files.createDirectories(segmentDir);
		// A stale ring from a previous run would otherwise be picked up by the first save.
		clearDirectory(segmentDir);

		List<String> cmd = new ArrayList<>(List.of(
				ffmpeg.toAbsolutePath().toString(),
				"-hide_banner", "-loglevel", "warning", "-nostdin",
				"-f", "rawvideo",
				"-pix_fmt", "bgra",
				"-s", width + "x" + height,
				"-framerate", Integer.toString(fps),
				"-i", "pipe:0",
				"-an"));
		cmd.addAll(encoder.arguments(bitrateKbps, fps * segmentSeconds, segmentSeconds));
		cmd.addAll(List.of(
				"-f", "segment",
				"-segment_time", Integer.toString(segmentSeconds),
				"-segment_format", "mpegts",
				"-segment_wrap", Integer.toString(wrap),
				"-reset_timestamps", "1",
				"-segment_list", segmentDir.resolve("segments.csv").toAbsolutePath().toString(),
				"-segment_list_type", "csv",
				"-segment_list_size", Integer.toString(wrap),
				"-segment_list_flags", "+live",
				segmentDir.resolve("seg%03d.ts").toAbsolutePath().toString()));

		ClipifyLog.LOGGER.info("Starting replay buffer: {}x{} @{}fps, {} kbps, {}, ring of {}x{}s",
				width, height, fps, bitrateKbps, encoder, wrap, segmentSeconds);
		ClipifyLog.LOGGER.debug("FFmpeg command: {}", String.join(" ", cmd));

		Process p = new ProcessBuilder(cmd)
				.redirectErrorStream(true)
				.start();
		this.process = p;
		this.failure = null;
		running.set(true);

		logThread = new Thread(this::pumpLog, "Clipify-ffmpeg-log");
		logThread.setDaemon(true);
		logThread.start();

		writerThread = new Thread(this::writerLoop, "Clipify-encoder-writer");
		writerThread.setDaemon(true);
		writerThread.setPriority(Thread.NORM_PRIORITY + 1);
		writerThread.start();
		return true;
	}

	// ---------------------------------------------------------------- offer

	/**
	 * Hands a frame to the encoder. Never blocks; if the encoder is behind, the frame is dropped
	 * and its buffer returned to the pool immediately.
	 */
	public void offer(CapturedFrame frame) {
		if (!running.get()) {
			pool.release(frame.pixels());
			return;
		}
		if (!queue.offer(frame)) {
			framesDroppedQueueFull.incrementAndGet();
			pool.release(frame.pixels());
		}
	}

	// ---------------------------------------------------------------- writer

	private void writerLoop() {
		Process p = process;
		OutputStream out = p.getOutputStream();
		byte[] scratch = new byte[WRITE_CHUNK];
		ByteBuffer retained = null;
		long originNanos = Long.MIN_VALUE;
		long lastIndex = -1L;
		int maxDuplicates = fps * MAX_DUPLICATE_FRAMES_FACTOR;

		try {
			while (running.get() || !queue.isEmpty()) {
				CapturedFrame frame = queue.poll(100, TimeUnit.MILLISECONDS);
				if (frame == null) {
					if (!p.isAlive()) {
						break;
					}
					continue;
				}

				// Map the wall-clock capture time onto a constant-frame-rate timeline so the clip
				// plays back at real speed even though Minecraft's frame rate fluctuates. A long
				// stall (world load, alt-tab, window drag) rebases the timeline instead of emitting
				// a flood of duplicate frames.
				CaptureMath.Step step = CaptureMath.nextFrame(
						originNanos, lastIndex, frame.captureNanos(), fps, maxDuplicates);
				originNanos = step.originNanos();
				timelineOriginNanos.set(originNanos);

				for (long i = 0; i < step.duplicates() && retained != null; i++) {
					writeFrame(out, retained, scratch);
					framesDuplicated.incrementAndGet();
				}
				lastIndex = step.targetIndex();

				writeFrame(out, frame.pixels(), scratch);
				framesWritten.incrementAndGet();

				// Keep this frame around so the next gap can be filled by repeating it.
				if (retained != null) {
					pool.release(retained);
				}
				retained = frame.pixels();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException e) {
			if (running.get()) {
				failure = "FFmpeg stopped accepting frames: " + e.getMessage();
				ClipifyLog.LOGGER.error("Replay buffer writer failed", e);
			}
		} finally {
			if (retained != null) {
				pool.release(retained);
			}
			drainQueueToPool();
			try {
				out.close();
			} catch (IOException ignored) {
				// Process is going away anyway.
			}
		}
	}

	private static void writeFrame(OutputStream out, ByteBuffer buf, byte[] scratch) throws IOException {
		buf.position(0);
		int remaining = buf.limit();
		while (remaining > 0) {
			int n = Math.min(scratch.length, remaining);
			buf.get(scratch, 0, n);
			out.write(scratch, 0, n);
			remaining -= n;
		}
		buf.position(0);
	}

	private void drainQueueToPool() {
		CapturedFrame f;
		while ((f = queue.poll()) != null) {
			pool.release(f.pixels());
		}
	}

	private void pumpLog() {
		Process p = process;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				synchronized (recentLog) {
					recentLog.addLast(line);
					while (recentLog.size() > 40) {
						recentLog.removeFirst();
					}
				}
				ClipifyLog.LOGGER.debug("[ffmpeg] {}", line);
			}
		} catch (IOException ignored) {
			// Stream closed on shutdown.
		}
		int exit = -1;
		try {
			exit = p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (running.get() && exit != 0) {
			failure = "FFmpeg exited with code " + exit + ": " + lastLogLines();
			ClipifyLog.LOGGER.error("Replay buffer FFmpeg exited unexpectedly ({}). Recent output:\n{}",
					exit, lastLogLines());
			running.set(false);
		}
	}

	public String lastLogLines() {
		synchronized (recentLog) {
			return String.join("\n", recentLog);
		}
	}

	// ------------------------------------------------------------------ stop

	/** Stops the encoder and waits briefly for FFmpeg to flush. Safe to call more than once. */
	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}
		Thread writer = writerThread;
		if (writer != null) {
			try {
				writer.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		Process p = process;
		if (p != null) {
			try {
				if (!p.waitFor(3, TimeUnit.SECONDS)) {
					p.destroy();
					if (!p.waitFor(2, TimeUnit.SECONDS)) {
						p.destroyForcibly();
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				p.destroyForcibly();
			}
		}
		drainQueueToPool();
		ClipifyLog.LOGGER.info("Replay buffer stopped ({} frames written, {} duplicated, {} dropped)",
				framesWritten.get(), framesDuplicated.get(), framesDroppedQueueFull.get());
	}

	private static void clearDirectory(Path dir) {
		try (var stream = Files.list(dir)) {
			for (Path p : stream.toList()) {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// A leftover file we cannot remove is harmless; the ring overwrites it.
				}
			}
		} catch (IOException ignored) {
			// Directory was just created; nothing to clear.
		}
	}

	// ----------------------------------------------------------------- stats

	public long framesWritten() {
		return framesWritten.get();
	}

	public long framesDuplicated() {
		return framesDuplicated.get();
	}

	public long framesDroppedQueueFull() {
		return framesDroppedQueueFull.get();
	}
}
