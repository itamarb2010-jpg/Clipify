package dev.clipify.encode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The audio ring's wall-clock arithmetic — the half of clip A/V sync that lives on the sound side.
 * Every sample is tagged with the tenth of a second it was "captured" in, so a window can be read
 * back and checked against the instants it was asked for.
 */
class PcmRingTest {

	private static final long MS = 1_000_000L;
	private static final long T0 = 5_000_000_000L; // an arbitrary nanoTime origin

	/** 100 ms of PCM whose every sample is {@code value}. */
	private static byte[] tenth(int value) {
		byte[] b = new byte[AudioCapture.BYTES_PER_SEC / 10];
		for (int i = 0; i + 1 < b.length; i += 2) {
			b[i] = (byte) (value & 0xFF);
			b[i + 1] = (byte) ((value >> 8) & 0xFF);
		}
		return b;
	}

	/** The sample {@code seconds} into a window. */
	private static int sampleAt(byte[] window, double seconds) {
		int i = (int) (seconds * AudioCapture.BYTES_PER_SEC);
		i -= i % 4;
		return (short) ((window[i + 1] << 8) | (window[i] & 0xFF));
	}

	private static int bytes(double seconds) {
		int n = (int) (seconds * AudioCapture.BYTES_PER_SEC);
		return n - n % 4;
	}

	/** Feeds {@code count} back-to-back tenths of a second, the i-th tagged {@code i}. */
	private static PcmRing filled(PcmRing ring, int count) {
		for (int i = 0; i < count; i++) {
			ring.append(tenth(i), AudioCapture.BYTES_PER_SEC / 10, T0 + (i + 1) * 100 * MS);
		}
		return ring;
	}

	@Test
	void windowStartsOnTheInstantItWasAskedFor() {
		PcmRing ring = filled(new PcmRing(5), 10); // t0 … t0+1s

		// 200 ms starting a quarter of a second in: the tail of tenth #2, #3, then the head of #4.
		byte[] window = ring.window(T0 + 250 * MS, bytes(0.2));

		assertNotNull(window);
		assertEquals(bytes(0.2), window.length);
		assertEquals(2, sampleAt(window, 0.01)); // 260 ms
		assertEquals(2, sampleAt(window, 0.04)); // 290 ms
		assertEquals(3, sampleAt(window, 0.06)); // 310 ms
		assertEquals(4, sampleAt(window, 0.16)); // 410 ms
	}

	@Test
	void aStalledSourceKeepsItsPlaceOnTheClock() {
		// A source that goes quiet for two seconds — exactly what WASAPI does while the endpoint is
		// idle. The audio that follows must stay where the clock says it belongs; sliding it forward
		// is what used to make a clip's sound run ahead of its picture.
		PcmRing ring = new PcmRing(10);
		ring.append(tenth(1), AudioCapture.BYTES_PER_SEC / 10, T0 + 100 * MS);
		ring.append(tenth(7), AudioCapture.BYTES_PER_SEC / 10, T0 + 2200 * MS);

		byte[] window = ring.window(T0, bytes(2.3));

		assertNotNull(window);
		assertEquals(1, sampleAt(window, 0.05), "the first chunk sits where it was captured");
		assertEquals(0, sampleAt(window, 0.5), "the hole is silence, not the next chunk pulled forward");
		assertEquals(0, sampleAt(window, 2.0), "…all the way to the end of the hole");
		assertEquals(7, sampleAt(window, 2.15), "and the late chunk lands at its own instant");
	}

	@Test
	void padsTimeTheSourceHasNoDataFor() {
		PcmRing ring = filled(new PcmRing(5), 5); // t0 … t0+0.5s

		byte[] window = ring.window(T0 - 500 * MS, bytes(1.5));

		assertNotNull(window);
		assertEquals(0, sampleAt(window, 0.25), "silence before capture began");
		assertEquals(0, sampleAt(window, 0.55), "captured audio starts half a second in");
		assertEquals(4, sampleAt(window, 0.95), "…and runs to where capture has got to");
		assertEquals(0, sampleAt(window, 1.2), "silence again past the newest sample");
	}

	@Test
	void keepsTheNewestAudioOnceTheRingWraps() {
		PcmRing ring = filled(new PcmRing(1), 15); // 1.5 s through a 1 s ring

		byte[] window = ring.window(T0 + 1000 * MS, bytes(0.5));

		assertNotNull(window);
		assertEquals(10, sampleAt(window, 0.05));
		assertEquals(14, sampleAt(window, 0.45));
	}

	@Test
	void reportsNothingForAWindowItCannotCover() {
		PcmRing ring = filled(new PcmRing(1), 10);

		assertNull(ring.window(T0 - 60_000 * MS, bytes(1.0)), "long before anything was captured");
		assertNull(ring.window(T0 + 60_000 * MS, bytes(1.0)), "long after the newest sample");
		assertNull(new PcmRing(1).window(T0, bytes(1.0)), "nothing captured at all");
	}
}
