package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import javax.sound.sampled.TargetDataLine;

/**
 * A tiny live input-level meter for the Audio settings "Mic Test": it opens the selected microphone
 * with {@link AudioCapture#openCaptureLine} and continuously exposes a smoothed 0–1 loudness so the
 * settings screen's waveform reflects your real voice. Pure Java, used from the UI on both builds.
 */
public final class MicMeter {

	private volatile TargetDataLine line;
	private volatile Thread thread;
	private volatile boolean running;
	private volatile float level;
	private volatile String error;

	/** Opens {@code device} (or the default when Auto) and starts metering. Returns false on failure. */
	public boolean start(String device) {
		stop();
		try {
			TargetDataLine l = AudioCapture.openCaptureLine(device, true);
			if (l == null) {
				error = "No such input device";
				return false;
			}
			l.open(AudioCapture.FORMAT, 8192);
			this.line = l;
			this.error = null;
			this.running = true;
			this.thread = new Thread(this::loop, "Clipify-mic-test");
			this.thread.setDaemon(true);
			this.thread.start();
			return true;
		} catch (Exception e) {
			error = e.getMessage();
			ClipifyLog.LOGGER.warn("Clipify audio: mic test could not open '{}': {}", device, e.toString());
			return false;
		}
	}

	private void loop() {
		byte[] buf = new byte[4096];
		try {
			line.start();
			while (running) {
				int n = line.read(buf, 0, buf.length);
				if (n <= 0) {
					continue;
				}
				long sum = 0;
				int count = 0;
				for (int i = 0; i + 1 < n; i += 2) {
					int s = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
					sum += (long) s * s;
					count++;
				}
				double rms = count > 0 ? Math.sqrt((double) sum / count) : 0;
				// Sensitive: a quiet mic still moves the meter (the recorder boosts it further anyway).
				float target = (float) Math.min(1.0, rms / 3000.0);
				level = level * 0.5f + target * 0.5f;
			}
		} catch (Exception e) {
			ClipifyLog.LOGGER.debug("Clipify audio: mic test loop ended", e);
		} finally {
			try {
				line.stop();
				line.close();
			} catch (Exception ignored) {
				// Already gone.
			}
		}
	}

	public float level() {
		return level;
	}

	public boolean isRunning() {
		return running;
	}

	public String error() {
		return error;
	}

	public void stop() {
		running = false;
		Thread t = thread;
		if (t != null) {
			try {
				t.join(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		thread = null;
		level = 0f;
	}
}
