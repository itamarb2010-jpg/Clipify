package dev.clipify.encode;

import dev.clipify.ClipifyLog;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Captures the microphone and/or desktop audio with the Java Sound API into a rolling in-memory PCM
 * ring, entirely separate from the video FFmpeg process (so it can never stall the encoder). On save
 * the window of every source that matches the clip's video is gain-adjusted, mixed, and written to a
 * WAV that {@link ClipAssembler} muxes into the clip.
 *
 * <p>The ring is <b>anchored to the wall clock</b>: every byte in it corresponds to a known
 * {@link System#nanoTime} instant, and a source that stops delivering (WASAPI emits nothing at all
 * while the endpoint is idle) has the missing time filled with silence instead of having its later
 * audio slide forward. That is what lets a clip cut the exact stretch of sound that belongs to its
 * video — see {@link #writeClipWav}.
 *
 * <p>Pure Java — identical in both the 1.21.11 and 26.1.2 builds. It is <b>fail-safe</b>: a device
 * that will not open is skipped and logged, never propagated. Desktop audio can only be captured
 * from an endpoint that exposes a capture line — a virtual cable (Elgato Virtual Audio, VB-CABLE, …)
 * or "Stereo Mix"; a plain render endpoint (headphones/speakers) has no capture line and is skipped.
 */
public final class AudioCapture {

	public static final AudioFormat FORMAT = new AudioFormat(48000f, 16, 2, true, false);
	public static final int BYTES_PER_SEC = 48000 * 2 * 2; // rate * channels * bytesPerSample
	static final int FRAME_BYTES = 2 * 2;                  // channels * bytesPerSample

	private final int maxSeconds;
	private final Path binDir; // where the WASAPI helper exe is compiled/cached (clipify/bin)
	private final List<Source> sources = new ArrayList<>();
	private volatile Source micSource; // the mic line, for the live-level meter reused by the Mic Test
	private volatile boolean running;

	public AudioCapture(int maxSeconds, Path binDir) {
		this.maxSeconds = Math.max(5, maxSeconds);
		this.binDir = binDir;
	}

	/** Opens the enabled devices and starts capture threads. Returns false if nothing could be opened. */
	public boolean start(boolean mic, String micDevice, int micVol,
	                     boolean desktop, List<String> pcDevices, int desktopVol) {
		if (mic) {
			micSource = tryOpenMic(micDevice, micVol / 100.0); // slider %: 100 = unity, 200 = 2×
		}
		if (desktop && pcDevices != null) {
			for (String device : pcDevices) {
				tryOpenDesktop(device, desktopVol / 100.0);
			}
		}
		if (sources.isEmpty()) {
			return false;
		}
		running = true;
		for (Source s : sources) {
			s.startThread();
		}
		return true;
	}

	/** Microphone: a Java Sound capture line. */
	private Source tryOpenMic(String device, double gain) {
		try {
			TargetDataLine line = openCaptureLine(device, true);
			if (line == null) {
				ClipifyLog.LOGGER.warn("Clipify audio: no microphone line for '{}' — skipped", device);
				return null;
			}
			line.open(FORMAT, BYTES_PER_SEC / 5);
			Source source = new Source(line, null, () -> {
				try {
					line.stop();
					line.close();
				} catch (Exception ignored) {
					// already gone
				}
			}, gain, maxSeconds);
			sources.add(source);
			ClipifyLog.LOGGER.info("Clipify audio: capturing mic '{}' (gain {})", device, String.format("%.2f", gain));
			return source;
		} catch (Exception e) {
			ClipifyLog.LOGGER.warn("Clipify audio: could not open mic '{}': {}", device, e.toString());
			return null;
		}
	}

	/** PC audio: WASAPI loopback of the selected render endpoint (via the compiled helper). */
	private void tryOpenDesktop(String device, double gain) {
		try {
			Path exe = WasapiLoopback.ensureHelper(binDir);
			if (exe == null) {
				ClipifyLog.LOGGER.warn("Clipify audio: PC-audio capture unavailable (WASAPI helper not built)");
				return;
			}
			Process p = WasapiLoopback.startCapture(exe, device);
			Source source = new Source(null, p.getInputStream(), p::destroyForcibly, gain, maxSeconds);
			sources.add(source);
			ClipifyLog.LOGGER.info("Clipify audio: capturing PC audio '{}' via WASAPI (gain {})",
					device, String.format("%.2f", gain));
		} catch (Exception e) {
			ClipifyLog.LOGGER.warn("Clipify audio: could not start WASAPI capture of '{}': {}", device, e.toString());
		}
	}

	public boolean hasSources() {
		return !sources.isEmpty();
	}

	/** True if a microphone line is being captured (so the Mic Test can reuse its live level). */
	public boolean hasMic() {
		return micSource != null;
	}

	/** Live, gain-adjusted mic loudness 0–1 from the running capture — no second line needed. */
	public float micLevel() {
		Source m = micSource;
		return m != null ? m.level : 0f;
	}

	public void stop() {
		running = false;
		for (Source s : sources) {
			s.close();
		}
	}

	/**
	 * Writes the {@code seconds} of mixed audio that start at wall-clock {@code startNanos} (a
	 * {@link System#nanoTime} reading — the instant the clip's first video frame was captured) to
	 * {@code wav}. Stretches the window covers that a source has no data for — because it started
	 * late, stalled, or has not reached "now" yet — come out as silence, so everything that <em>is</em>
	 * there stays where it belongs on the timeline. Returns false when no source overlaps the window.
	 */
	public synchronized boolean writeClipWav(Path wav, long startNanos, double seconds) {
		int wantBytes = (int) Math.min(Integer.MAX_VALUE - 64L, (long) (seconds * BYTES_PER_SEC));
		wantBytes -= wantBytes % FRAME_BYTES;
		if (wantBytes <= 0) {
			return false;
		}
		List<byte[]> pcms = new ArrayList<>();
		for (Source s : sources) {
			byte[] pcm = s.snapshotWindow(startNanos, wantBytes);
			if (pcm != null) {
				pcms.add(pcm);
			}
		}
		if (pcms.isEmpty()) {
			return false;
		}
		// Every window is the same length and already aligned on the same instant, so mixing is a
		// straight sample-wise sum. One source needs no mixing pass at all.
		byte[] mixed = pcms.get(0);
		if (pcms.size() > 1) {
			for (int i = 0; i + 1 < mixed.length; i += 2) {
				int v = (short) ((mixed[i + 1] << 8) | (mixed[i] & 0xFF));
				for (int s = 1; s < pcms.size(); s++) {
					byte[] pcm = pcms.get(s);
					v += (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
				}
				v = Math.max(-32768, Math.min(32767, v));
				mixed[i] = (byte) (v & 0xFF);
				mixed[i + 1] = (byte) ((v >> 8) & 0xFF);
			}
		}
		try {
			writeWav(wav, mixed);
			return true;
		} catch (IOException e) {
			ClipifyLog.LOGGER.warn("Clipify audio: could not write clip WAV", e);
			return false;
		}
	}

	// ------------------------------------------------------------- device lookup

	/** Resolves a capture {@link TargetDataLine} for a device name (or the default when Auto). */
	public static TargetDataLine openCaptureLine(String device, boolean allowDefault) throws Exception {
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		if (device == null || device.isBlank() || device.equalsIgnoreCase("Auto")) {
			return allowDefault ? (TargetDataLine) AudioSystem.getLine(info) : null;
		}
		Mixer.Info mixerInfo = findCaptureMixer(device);
		if (mixerInfo == null) {
			return null;
		}
		Mixer mixer = AudioSystem.getMixer(mixerInfo);
		return mixer.isLineSupported(info) ? (TargetDataLine) mixer.getLine(info) : null;
	}

	private static Mixer.Info findCaptureMixer(String name) {
		DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
		List<Mixer.Info> caps = new ArrayList<>();
		for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
			if (AudioSystem.getMixer(mi).isLineSupported(info)) {
				caps.add(mi);
			}
		}
		for (Mixer.Info mi : caps) {
			if (mi.getName().equalsIgnoreCase(name)) {
				return mi;
			}
		}
		// Virtual cables expose the render endpoint as "… Input" and the capture as "… Output".
		if (name.toLowerCase(Locale.ROOT).contains("input")) {
			String swapped = name.replaceAll("(?i)input", "Output");
			for (Mixer.Info mi : caps) {
				if (mi.getName().equalsIgnoreCase(swapped)) {
					return mi;
				}
			}
		}
		String n = name.toLowerCase(Locale.ROOT);
		for (Mixer.Info mi : caps) {
			String l = mi.getName().toLowerCase(Locale.ROOT);
			if (l.contains(n) || n.contains(l)) {
				return mi;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------- WAV

	private static void writeWav(Path path, byte[] pcm) throws IOException {
		int dataLen = pcm.length;
		int byteRate = BYTES_PER_SEC;
		ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLen).put("WAVE".getBytes(StandardCharsets.US_ASCII));
		h.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2);
		h.putInt(48000).putInt(byteRate).putShort((short) FRAME_BYTES).putShort((short) 16);
		h.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLen);
		Files.createDirectories(path.getParent());
		try (OutputStream os = Files.newOutputStream(path)) {
			os.write(h.array());
			os.write(pcm);
		}
	}

	// ------------------------------------------------------------------ source

	private final class Source {
		private final TargetDataLine line;   // microphone path (nullable)
		private final InputStream in;        // WASAPI desktop path (nullable)
		private final Runnable closer;
		private final double gain;
		private final PcmRing ring;
		private Thread thread;
		private volatile float level; // live gain-adjusted loudness 0–1, for the Mic Test meter

		Source(TargetDataLine line, InputStream in, Runnable closer, double gain, int seconds) {
			this.line = line;
			this.in = in;
			this.closer = closer;
			this.gain = gain;
			this.ring = new PcmRing(seconds);
		}

		void startThread() {
			thread = new Thread(this::loop, "Clipify-audio-capture");
			thread.setDaemon(true);
			thread.start();
		}

		private void loop() {
			byte[] buf = new byte[BYTES_PER_SEC / 20]; // ~50 ms
			try {
				if (line != null) {
					line.start();
				}
				int n;
				while (running && (n = readChunk(buf)) > 0) {
					// What we just read ends "now" minus whatever is queued behind it (the device
					// buffer / the helper's pipe), which is what keeps the anchor honest when a
					// hitch leaves us reading a backlog.
					long endNanos = System.nanoTime() - PcmRing.nanosFor(backlogBytes());
					updateLevel(buf, n);
					ring.append(buf, n, endNanos);
				}
			} catch (Exception e) {
				ClipifyLog.LOGGER.debug("Clipify audio: capture loop ended", e);
			}
		}

		private int readChunk(byte[] buf) throws IOException {
			if (line != null) {
				return line.read(buf, 0, buf.length);
			}
			int n = in.read(buf, 0, buf.length);
			if (n <= 0) {
				return n;
			}
			// A pipe read can stop mid-frame; finish it so the ring never splits a stereo pair
			// (which would swap the channels for everything after it).
			int partial = n % FRAME_BYTES;
			if (partial != 0) {
				n += Math.max(0, in.readNBytes(buf, n, FRAME_BYTES - partial));
			}
			return n;
		}

		/** Captured but still unread bytes — the newest sample is that much older than right now. */
		private int backlogBytes() {
			try {
				return line != null ? line.available() : in.available();
			} catch (IOException e) {
				return 0;
			}
		}

		private void updateLevel(byte[] buf, int n) {
			long sum = 0;
			int count = 0;
			for (int i = 0; i + 1 < n; i += 2) {
				int s = (short) ((buf[i + 1] << 8) | (buf[i] & 0xFF));
				sum += (long) s * s;
				count++;
			}
			double rms = count > 0 ? Math.sqrt((double) sum / count) : 0;
			float target = (float) Math.min(1.0, rms * gain / 6000.0);
			level = level * 0.5f + target * 0.5f;
		}

		/** This source's slice of the clip's window, at its configured capture level. */
		byte[] snapshotWindow(long startNanos, int wantBytes) {
			byte[] pcm = ring.window(startNanos, wantBytes);
			if (pcm != null && gain != 1.0) {
				applyGain(pcm);
			}
			return pcm;
		}

		private void applyGain(byte[] pcm) {
			for (int i = 0; i + 1 < pcm.length; i += 2) {
				int s = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
				s = (int) Math.round(s * gain);
				s = Math.max(-32768, Math.min(32767, s));
				pcm[i] = (byte) (s & 0xFF);
				pcm[i + 1] = (byte) ((s >> 8) & 0xFF);
			}
		}

		void close() {
			try {
				closer.run(); // unblocks the read: stops the mic line / kills the WASAPI helper
			} catch (Exception ignored) {
				// Already gone.
			}
			Thread t = thread;
			if (t != null && t != Thread.currentThread()) {
				try {
					t.join(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}
}
