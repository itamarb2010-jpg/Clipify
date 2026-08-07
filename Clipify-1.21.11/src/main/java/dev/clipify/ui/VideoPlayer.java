package dev.clipify.ui;

import dev.clipify.ClipifyLog;
import dev.clipify.encode.ClipAudioPlayer;
import dev.clipify.encode.ClipLibrary;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * In-game video playback for the clip editor. FFmpeg decodes the clip to a stream of PNG frames on a
 * background thread; each frame is decoded to a {@link NativeImage} off the render thread and handed
 * to a single reusable {@link NativeImageBackedTexture} (created once from the first frame, then
 * {@code setImage}+{@code upload} per frame — no per-frame texture churn).
 *
 * <p>Two sources feed the one texture, never at the same time: a <b>streaming decoder</b> while
 * playing, and a coalescing <b>single-frame scrubber</b> while paused (dragging the playhead). The
 * screen calls {@link #tick()} once per frame on the render thread and reads {@link #textureId()} /
 * {@link #texWidth()} / {@link #texHeight()} to draw.
 *
 * <p>Both decode at the size the frame is actually drawn at ({@link #setDisplayWidth}), capped at the
 * clip's own resolution: that is the point where the preview stops being distinguishable from the
 * file itself, and it keeps the picture from changing sharpness when you press play.
 */
public final class VideoPlayer {

	/** Decode-width bounds in real pixels, and the step they snap to so a resize can't thrash the decoder. */
	private static final int MIN_WIDTH = 640;
	private static final int MAX_WIDTH = 3840;
	private static final int WIDTH_STEP = 160;
	private static final double DECODE_FPS = 30.0;
	private static final long MAX_PNG_BYTES = 64L * 1024 * 1024;

	private final MinecraftClient client;
	private final Path ffmpeg;
	private final Path file;
	private final double duration;
	private final Identifier textureId;
	private final ClipAudioPlayer audio;

	private NativeImageBackedTexture texture;
	private int texW;
	private int texH;
	/** Clip time of the frame currently on the texture, for {@link #hasFrameNear}. */
	private double shownTime = Double.NaN;

	/** Decode width in real pixels; see {@link #setDisplayWidth}. */
	private volatile int decodeWidth = 1280;

	// Playback clock (render-thread owned).
	private boolean playing;
	private double clock;
	private long lastNanos;

	// Streaming decoder.
	private volatile Process streamProc;
	private Thread streamThread;
	// Lookahead of ~1/3 s at the decode rate. Deliberately short: frames are now decoded at full
	// display size, so each one is several MB of native memory and the decoder runs far ahead of
	// playback anyway.
	private final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(10);
	private volatile long streamGen;

	// Single-frame scrubber (coalescing: only the latest requested time matters).
	private final Object scrubLock = new Object();
	private double scrubPending = Double.NaN;
	private volatile long scrubGen;
	private Thread scrubThread;

	private volatile boolean closed;

	private record Frame(long gen, double pts, NativeImage image) {
	}

	public VideoPlayer(MinecraftClient client, Path ffmpeg, Path file, double duration) {
		this.client = client;
		this.ffmpeg = ffmpeg;
		this.file = file;
		this.duration = Math.max(0.01, duration);
		this.textureId = Identifier.of("clipify", "video_" + Long.toHexString(System.nanoTime()));
		this.audio = new ClipAudioPlayer(ffmpeg, file);
		startScrubWorker();
		requestScrub(0); // show the first frame immediately
	}

	// ------------------------------------------------------------------ public API

	public boolean isPlaying() {
		return playing;
	}

	public double time() {
		return clock;
	}

	public double duration() {
		return duration;
	}

	public Identifier textureId() {
		return textureId;
	}

	public boolean hasFrame() {
		return texture != null;
	}

	/** True if the frame on screen is the one for {@code t} (within {@code tolerance} seconds). */
	public boolean hasFrameNear(double t, double tolerance) {
		return texture != null && !Double.isNaN(shownTime) && Math.abs(shownTime - t) <= tolerance;
	}

	/**
	 * How wide the frame is drawn on screen, in real pixels — decoding is matched to it (never above
	 * the clip's own width), so the preview shows the clip's real detail without wasting work on
	 * pixels the GPU would only throw away. Safe to call every frame; it acts only on a real change.
	 * Render thread.
	 */
	public void setDisplayWidth(int pixels) {
		int snapped = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH,
				((pixels + WIDTH_STEP - 1) / WIDTH_STEP) * WIDTH_STEP));
		if (snapped == decodeWidth || closed) {
			return;
		}
		decodeWidth = snapped;
		if (playing) {
			startStream(clock); // pick the new size up straight away
		} else {
			requestScrub(clock);
		}
	}

	public int texWidth() {
		return texW;
	}

	public int texHeight() {
		return texH;
	}

	public void togglePlay() {
		if (playing) {
			pause();
		} else {
			play();
		}
	}

	public void play() {
		if (closed || playing) {
			return;
		}
		if (clock >= duration - 0.02) {
			clock = 0; // replay from the start
		}
		playing = true;
		lastNanos = System.nanoTime();
		startStream(clock);
		audio.play(clock);
	}

	public void pause() {
		if (!playing) {
			return;
		}
		playing = false;
		stopStream();
		audio.stop();
		requestScrub(clock); // snap the paused frame to the exact clock position
	}

	/** Move the playhead (from a scrub drag). Pauses playback and previews the frame at {@code t}. */
	public void seek(double t) {
		clock = clamp(t, 0, duration);
		if (playing) {
			playing = false;
			stopStream();
		}
		audio.stop();
		requestScrub(clock);
	}

	/** Render thread, once per frame: advances the clock while playing and updates the texture. */
	public void tick() {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1_000_000_000.0;
		lastNanos = now;

		if (playing) {
			clock += dt;
			if (clock >= duration) {
				clock = duration;
				pause();
			}
			drainStreamToClock();
		}
	}

	public void close() {
		closed = true;
		stopStream();
		audio.close();
		synchronized (scrubLock) {
			scrubLock.notifyAll();
		}
		if (texture != null) {
			client.getTextureManager().destroyTexture(textureId);
			texture.close();
			texture = null;
		}
	}

	// ------------------------------------------------------------- streaming decode

	private void startStream(double fromTime) {
		stopStream();
		final long gen = ++streamGen;
		final double start = clamp(fromTime, 0, duration);
		Process p;
		try {
			p = new ProcessBuilder(
					ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
					"-ss", fmt(start), "-i", file.toString(),
					"-an", "-vf", "scale=min(" + decodeWidth + "\\,iw):-2:flags=lanczos",
					"-r", String.valueOf((int) DECODE_FPS),
					// Fast (not small) PNGs: these frames travel a few centimetres down a pipe, so
					// spending CPU on compression only costs frame rate.
					"-compression_level", "1",
					"-f", "image2pipe", "-vcodec", "png", "pipe:1")
					.start();
		} catch (IOException e) {
			ClipifyLog.LOGGER.warn("Clipify: could not start video decoder", e);
			return;
		}
		streamProc = p;
		streamThread = new Thread(() -> runStream(p, gen, start), "Clipify-video");
		streamThread.setDaemon(true);
		streamThread.start();
	}

	private void runStream(Process p, long gen, double start) {
		int idx = 0;
		try (InputStream in = p.getInputStream()) {
			while (!closed && gen == streamGen) {
				byte[] png = readOnePng(in);
				if (png == null) {
					break; // EOF
				}
				NativeImage image;
				try {
					image = NativeImage.read(png);
				} catch (IOException e) {
					continue;
				}
				double pts = start + idx / DECODE_FPS;
				idx++;
				Frame frame = new Frame(gen, pts, image);
				// Block until there's room, but bail out promptly on seek/close.
				while (!closed && gen == streamGen) {
					if (frames.offer(frame)) {
						frame = null;
						break;
					}
					try {
						Thread.sleep(3);
					} catch (InterruptedException e) {
						break;
					}
				}
				if (frame != null) {
					frame.image().close(); // never queued (seek/close) — free it
				}
			}
		} catch (IOException ignored) {
			// process ended
		} finally {
			p.destroyForcibly();
		}
	}

	private void stopStream() {
		streamGen++; // invalidate the current generation
		Process p = streamProc;
		streamProc = null;
		if (p != null) {
			p.destroyForcibly();
		}
		Thread t = streamThread;
		streamThread = null;
		if (t != null) {
			t.interrupt();
		}
		// Discard any queued frames (all generations) and free their native images.
		Frame f;
		while ((f = frames.poll()) != null) {
			f.image().close();
		}
	}

	/** Render thread: show the newest queued frame whose PTS has been reached; free the ones we skip. */
	private void drainStreamToClock() {
		NativeImage toShow = null;
		double toShowTime = 0;
		Frame f;
		while ((f = frames.peek()) != null) {
			if (f.gen() != streamGen) {
				frames.poll();
				f.image().close();
				continue;
			}
			if (f.pts() <= clock + 1.0e-6) {
				frames.poll();
				if (toShow != null) {
					toShow.close();
				}
				toShow = f.image();
				toShowTime = f.pts();
			} else {
				break;
			}
		}
		if (toShow != null) {
			display(toShow, toShowTime);
		}
	}

	// ----------------------------------------------------------- single-frame scrub

	private void startScrubWorker() {
		scrubThread = new Thread(this::scrubLoop, "Clipify-scrub");
		scrubThread.setDaemon(true);
		scrubThread.start();
	}

	private void requestScrub(double t) {
		synchronized (scrubLock) {
			scrubPending = t;
			scrubGen++;
			scrubLock.notifyAll();
		}
	}

	private void scrubLoop() {
		while (!closed) {
			double t;
			long gen;
			synchronized (scrubLock) {
				while (Double.isNaN(scrubPending) && !closed) {
					try {
						scrubLock.wait();
					} catch (InterruptedException e) {
						return;
					}
				}
				if (closed) {
					return;
				}
				t = scrubPending;
				gen = scrubGen;
				scrubPending = Double.NaN;
			}
			byte[] png = ClipLibrary.thumbnailPng(ffmpeg, file, t, decodeWidth);
			if (png == null) {
				continue;
			}
			NativeImage image;
			try {
				image = NativeImage.read(png);
			} catch (IOException e) {
				continue;
			}
			final double at = t;
			client.execute(() -> {
				// Drop if superseded, playing (stream owns the texture), or closed.
				if (closed || playing || gen != scrubGen) {
					image.close();
				} else {
					display(image, at);
				}
			});
		}
	}

	// ------------------------------------------------------------------- texture

	/** Render thread only. Adopts {@code img} as the current frame (freeing the previous one). */
	private void display(NativeImage img, double time) {
		if (closed) {
			img.close();
			return;
		}
		shownTime = time;
		if (texture == null) {
			texture = new LinearImageTexture(() -> "clipify-video", img);
			client.getTextureManager().registerTexture(textureId, texture);
			texW = img.getWidth();
			texH = img.getHeight();
		} else if (img.getWidth() == texW && img.getHeight() == texH) {
			texture.setImage(img); // closes the previous image
			texture.upload();
		} else {
			client.getTextureManager().destroyTexture(textureId);
			texture.close();
			texture = new LinearImageTexture(() -> "clipify-video", img);
			client.getTextureManager().registerTexture(textureId, texture);
			texW = img.getWidth();
			texH = img.getHeight();
		}
	}

	// -------------------------------------------------------------------- helpers

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static String fmt(double seconds) {
		return String.format(java.util.Locale.ROOT, "%.3f", seconds);
	}

	/** Reads exactly one PNG (by walking its chunks) from a concatenated image2pipe stream. */
	private static byte[] readOnePng(InputStream in) throws IOException {
		byte[] sig = new byte[8];
		if (readFully(in, sig, 8) < 8) {
			return null; // EOF
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
		out.write(sig, 0, 8);
		byte[] header = new byte[8]; // length(4) + type(4)
		while (true) {
			if (readFully(in, header, 8) < 8) {
				return null;
			}
			int len = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16)
					| ((header[2] & 0xff) << 8) | (header[3] & 0xff);
			if (len < 0 || len > MAX_PNG_BYTES) {
				return null; // corrupt/desynced
			}
			out.write(header, 0, 8);
			byte[] body = new byte[len + 4]; // chunk data + CRC
			if (readFully(in, body, body.length) < body.length) {
				return null;
			}
			out.write(body, 0, body.length);
			if (header[4] == 'I' && header[5] == 'E' && header[6] == 'N' && header[7] == 'D') {
				break;
			}
		}
		return out.toByteArray();
	}

	private static int readFully(InputStream in, byte[] buf, int n) throws IOException {
		int off = 0;
		while (off < n) {
			int r = in.read(buf, off, n - off);
			if (r < 0) {
				break;
			}
			off += r;
		}
		return off;
	}
}
