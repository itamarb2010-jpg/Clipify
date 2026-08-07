package dev.clipify.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureMathTest {

	@Test
	void nativeSizeIsKeptButForcedEven() {
		int[] size = CaptureMath.fitCaptureSize(1921, 1081, 0, 0);
		assertEquals(1920, size[0]);
		assertEquals(1080, size[1]);
	}

	@Test
	void largeWindowIsScaledIntoTheCap() {
		int[] size = CaptureMath.fitCaptureSize(3840, 2160, 1920, 1080);
		assertEquals(1920, size[0]);
		assertEquals(1080, size[1]);
	}

	@Test
	void scalingPreservesAspectRatioAgainstTheTighterAxis() {
		// 21:9 source into a 16:9 cap must be limited by width, not height.
		int[] size = CaptureMath.fitCaptureSize(3440, 1440, 1920, 1080);
		assertEquals(1920, size[0]);
		assertEquals(804, size[1]); // 1440 * (1920/3440) = 803.72 -> round 804 (already even)
		double srcAspect = 3440.0 / 1440.0;
		double outAspect = (double) size[0] / size[1];
		assertTrue(Math.abs(srcAspect - outAspect) < 0.02, "aspect drifted: " + outAspect);
	}

	@Test
	void smallWindowIsClampedToMinimum() {
		int[] size = CaptureMath.fitCaptureSize(10, 10, 0, 0);
		assertEquals(160, size[0]);
		assertEquals(120, size[1]);
	}

	@Test
	void allDimensionsAreEven() {
		int[] size = CaptureMath.fitCaptureSize(1365, 767, 1280, 720);
		assertEquals(0, size[0] % 2);
		assertEquals(0, size[1] % 2);
	}

	@Test
	void letterboxCentresAndPreservesAspect() {
		// 16:9 source into a 1:1 surface -> pillarboxed vertically centred.
		int[] box = CaptureMath.fitLetterbox(1920, 1080, 1000, 1000);
		int x = box[0], y = box[1], w = box[2], h = box[3];
		assertEquals(1000, w);          // full width
		assertEquals(562 & ~1, h);      // 1000 * 9/16 = 562.5 -> 562
		assertEquals(0, x);
		assertTrue(y > 0, "should be vertically centred");
		assertEquals((1000 - h) / 2, y);
	}

	@Test
	void firstFrameStartsTheTimeline() {
		CaptureMath.Step step = CaptureMath.nextFrame(Long.MIN_VALUE, -1L, 5_000L, 60, 120);
		assertEquals(5_000L, step.originNanos());
		assertEquals(0L, step.targetIndex());
		assertEquals(0L, step.duplicates());
	}

	@Test
	void steadyFrameRateProducesNoDuplicates() {
		int fps = 60;
		long period = 1_000_000_000L / fps;
		long origin = 0L;
		long lastIndex = 0L;
		for (int i = 1; i <= 100; i++) {
			long capture = i * period;
			CaptureMath.Step step = CaptureMath.nextFrame(origin, lastIndex, capture, fps, fps * 2);
			assertEquals(0L, step.duplicates(), "unexpected dup at frame " + i);
			assertEquals(i, step.targetIndex());
			origin = step.originNanos();
			lastIndex = step.targetIndex();
		}
	}

	@Test
	void aRenderStallInsertsDuplicateFrames() {
		int fps = 60;
		long period = 1_000_000_000L / fps;
		// Skipped ~5 frames' worth of time in a single render.
		CaptureMath.Step step = CaptureMath.nextFrame(0L, 0L, 6 * period, fps, fps * 2);
		assertEquals(6L, step.targetIndex());
		assertEquals(5L, step.duplicates());
		assertEquals(0L, step.originNanos());
	}

	@Test
	void aLongStallRebasesInsteadOfFloodingDuplicates() {
		int fps = 60;
		long period = 1_000_000_000L / fps;
		int maxDup = fps * 2;
		// A 10-second freeze would otherwise be 600 duplicate frames.
		long capture = 10L * 1_000_000_000L;
		CaptureMath.Step step = CaptureMath.nextFrame(0L, 0L, capture, fps, maxDup);
		assertEquals(0L, step.duplicates(), "long stall must not emit duplicates");
		assertEquals(1L, step.targetIndex());
		// Origin rebased so this frame is exactly index 1 on the timeline.
		assertEquals(capture - period, step.originNanos());
	}

	@Test
	void neverGoesBackwardsWhenTwoFramesShareATimestamp() {
		int fps = 30;
		CaptureMath.Step a = CaptureMath.nextFrame(0L, 5L, 100L, fps, 60);
		// Same capture time again: must still advance by one.
		assertEquals(6L, a.targetIndex());
		assertEquals(0L, a.duplicates());
	}
}
