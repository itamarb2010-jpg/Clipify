package dev.clipify.encode;

import java.util.Arrays;

/**
 * A rolling buffer of s16le/48 kHz/stereo PCM <b>anchored to the wall clock</b>: every byte in it
 * corresponds to a known {@link System#nanoTime} instant, so a clip can ask for "the sound between
 * these two moments" and get audio that lines up with the video shot at the same moments.
 *
 * <p>Holding that property costs one thing: a source that stops delivering — WASAPI hands out
 * nothing at all while the endpoint is idle, and a device hiccup does the same — must have the
 * missing time filled with silence, or everything captured afterwards would slide forward by the
 * length of the hole and the clip's audio would run ahead of its picture.
 *
 * <p>Free of any device or Minecraft reference so the arithmetic can be unit-tested on a plain JVM,
 * the same way {@link dev.clipify.capture.CaptureMath} isolates the video side's frame pacing.
 */
final class PcmRing {

	/** Delivery jitter below this is ignored; a longer hole in the stream is filled with silence. */
	private static final long GAP_NANOS = 30_000_000L;

	private final byte[] ring;
	private final Object lock = new Object();
	private int writePos;
	/** Bytes ever written, silence fills included — this source's own timeline. */
	private long totalWritten;
	/** Wall clock of the newest byte held; the anchor that maps byte offsets onto nanoTime. */
	private long newestNanos = Long.MIN_VALUE;

	PcmRing(int seconds) {
		this.ring = new byte[AudioCapture.BYTES_PER_SEC * Math.max(1, seconds)];
	}

	/**
	 * Appends {@code n} bytes that were captured up to {@code endNanos}, filling any wall-clock hole
	 * before them with silence.
	 */
	void append(byte[] src, int n, long endNanos) {
		synchronized (lock) {
			if (newestNanos != Long.MIN_VALUE) {
				long gap = (endNanos - nanosFor(n)) - newestNanos;
				if (gap > GAP_NANOS) {
					writeSilence(bytesFor(gap));
				}
			}
			write(src, n);
			newestNanos = endNanos;
		}
	}

	/**
	 * The {@code wantBytes} of PCM starting at wall-clock {@code startNanos}, zero-padded wherever
	 * the ring has nothing for that instant (it started later, stalled, or has not got there yet).
	 *
	 * @return the window, or null when it misses the captured range entirely
	 */
	byte[] window(long startNanos, int wantBytes) {
		synchronized (lock) {
			if (newestNanos == Long.MIN_VALUE || totalWritten <= 0 || wantBytes <= 0) {
				return null;
			}
			// Where the window begins on this ring's own byte timeline.
			long startByte = totalWritten - bytesFor(newestNanos - startNanos);
			startByte -= Math.floorMod(startByte, (long) AudioCapture.FRAME_BYTES);
			long oldest = Math.max(0L, totalWritten - ring.length);
			long from = Math.max(startByte, oldest);
			long to = Math.min(startByte + wantBytes, totalWritten);
			if (to <= from) {
				return null; // nothing was captured anywhere in this window
			}
			byte[] out = new byte[wantBytes]; // whatever we don't fill stays silent
			int destOff = (int) (from - startByte);
			int len = (int) (to - from);
			int pos = (int) Math.floorMod(writePos - (totalWritten - from), (long) ring.length);
			int off = 0;
			while (off < len) {
				int chunk = Math.min(ring.length - pos, len - off);
				System.arraycopy(ring, pos, out, destOff + off, chunk);
				pos = (pos + chunk) % ring.length;
				off += chunk;
			}
			return out;
		}
	}

	private void write(byte[] src, int n) {
		int off = 0;
		while (off < n) {
			int chunk = Math.min(ring.length - writePos, n - off);
			System.arraycopy(src, off, ring, writePos, chunk);
			writePos = (writePos + chunk) % ring.length;
			off += chunk;
		}
		totalWritten += n;
	}

	private void writeSilence(long count) {
		long fill = Math.min(count, ring.length); // a longer hole simply empties the whole ring
		while (fill > 0) {
			int chunk = (int) Math.min(ring.length - writePos, fill);
			Arrays.fill(ring, writePos, writePos + chunk, (byte) 0);
			writePos = (writePos + chunk) % ring.length;
			fill -= chunk;
		}
		totalWritten += count; // the timeline advances by the whole hole, ring capacity aside
	}

	/** Bytes of PCM that {@code nanos} of wall clock is worth, rounded to a whole stereo frame. */
	static long bytesFor(long nanos) {
		long bytes = Math.round(nanos * 1e-9 * AudioCapture.BYTES_PER_SEC);
		return bytes - Math.floorMod(bytes, (long) AudioCapture.FRAME_BYTES);
	}

	static long nanosFor(long bytes) {
		return Math.round(bytes / (double) AudioCapture.BYTES_PER_SEC * 1e9);
	}
}
