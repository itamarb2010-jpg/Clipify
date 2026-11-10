package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Plays a clip's audio track for the in-game editor. FFmpeg decodes the clip's audio to raw PCM from
 * a start offset, and a background thread streams it into a Java Sound {@link SourceDataLine}, which
 * paces playback at real time — so it stays in step with {@link VideoPlayer}'s own real-time clock
 * (both are started together from the same position).
 *
 * <p>Pure Java (no Minecraft), identical in both builds, and completely separate from Minecraft's
 * own OpenAL output. Fail-safe: a clip with no audio just produces nothing.
 */
public final class ClipAudioPlayer {

	private static final AudioFormat FORMAT = new AudioFormat(48000f, 16, 2, true, false);
	private static final int LINE_BUFFER_BYTES = 48000 * 2 * 2 / 6; // ~160 ms

	private final Path ffmpeg;
	private final Path file;

	private volatile Process proc;
	private volatile Thread thread;
	private volatile SourceDataLine line;
	private volatile boolean running;
	private volatile float volume = 1.0f;

	public ClipAudioPlayer(Path ffmpeg, Path file) {
		this.ffmpeg = ffmpeg;
		this.file = file;
	}

	public void setVolume(float v) {
		this.volume = Math.max(0f, Math.min(2f, v));
	}

	/** Starts playing the clip's audio from {@code fromTime} seconds. Safe to call repeatedly. */
	public synchronized void play(double fromTime) {
		stop();
		SourceDataLine l;
		Process p;
		try {
			l = AudioSystem.getSourceDataLine(FORMAT);
			l.open(FORMAT, LINE_BUFFER_BYTES);
			l.start();
			p = new ProcessBuilder(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
					"-ss", fmt(Math.max(0.0, fromTime)), "-i", file.toString(),
					"-vn", "-f", "s16le", "-acodec", "pcm_s16le", "-ar", "48000", "-ac", "2", "pipe:1")
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
		} catch (Exception e) {
			ClipifyLog.LOGGER.debug("Clipify: could not start clip audio playback", e);
			return;
		}
		this.line = l;
		this.proc = p;
		this.running = true;
		Thread t = new Thread(() -> pump(p, l), "Clipify-audio-play");
		t.setDaemon(true);
		t.start();
		this.thread = t;
	}

	private void pump(Process p, SourceDataLine l) {
		byte[] buf = new byte[8192];
		try (InputStream in = p.getInputStream()) {
			int n;
			while (running && (n = in.read(buf, 0, buf.length)) > 0) {
				if (volume != 1.0f) {
					applyVolume(buf, n, volume);
				}
				l.write(buf, 0, n); // blocks when the line is full → paced to real-time playback
			}
			if (running) {
				l.drain();
			}
		} catch (Exception ignored) {
			// Line closed or process killed on stop().
		} finally {
			try {
				l.stop();
				l.flush();
				l.close();
			} catch (Exception ignored) {
				// Already gone.
			}
			p.destroyForcibly();
		}
	}

	public synchronized void stop() {
		running = false;
		Process p = proc;
		proc = null;
		if (p != null) {
			p.destroyForcibly();
		}
		SourceDataLine l = line;
		line = null;
		if (l != null) {
			try {
				l.stop();
				l.flush();
				l.close();
			} catch (Exception ignored) {
				// Already gone.
			}
		}
		Thread t = thread;
		thread = null;
		if (t != null && t != Thread.currentThread()) {
			try {
				t.join(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public void close() {
		stop();
	}

	private static void applyVolume(byte[] pcm, int n, float vol) {
		for (int i = 0; i + 1 < n; i += 2) {
			int s = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
			s = Math.round(s * vol);
			s = Math.max(-32768, Math.min(32767, s));
			pcm[i] = (byte) (s & 0xFF);
			pcm[i + 1] = (byte) ((s >> 8) & 0xFF);
		}
	}

	private static String fmt(double seconds) {
		return String.format(Locale.ROOT, "%.3f", seconds);
	}
}
