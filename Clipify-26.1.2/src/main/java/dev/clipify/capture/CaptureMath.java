package dev.clipify.capture;

/**
 * Pure helpers for capture sizing and constant-frame-rate timing.
 *
 * <p>Deliberately free of any Minecraft, LWJGL or Fabric reference so the tricky arithmetic that
 * governs frame pacing and letterboxing can be unit-tested on a plain JVM.
 */
public final class CaptureMath {

	private CaptureMath() {
	}

	/**
	 * Scales a source size down to fit within {@code maxWidth x maxHeight}, preserving aspect ratio
	 * and rounding to even dimensions (required by H.264 yuv420p). {@code max <= 0} disables that
	 * axis' limit, so {@code (w, h, 0, 0)} means "keep native size, just make it even".
	 */
	public static int[] fitCaptureSize(int srcWidth, int srcHeight, int maxWidth, int maxHeight) {
		int w = srcWidth;
		int h = srcHeight;
		if (maxWidth > 0 && maxHeight > 0) {
			double scale = Math.min((double) maxWidth / srcWidth, (double) maxHeight / srcHeight);
			if (scale < 1.0) {
				w = (int) Math.round(srcWidth * scale);
				h = (int) Math.round(srcHeight * scale);
			}
		}
		w = Math.max(160, w) & ~1;
		h = Math.max(120, h) & ~1;
		return new int[] { w, h };
	}

	/**
	 * Centres a source rectangle inside a destination surface, preserving aspect ratio (the classic
	 * letterbox/pillarbox fit). Returned as {@code [x, y, width, height]} with even width/height.
	 */
	public static int[] fitLetterbox(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
		double scale = Math.min((double) dstWidth / srcWidth, (double) dstHeight / srcHeight);
		int w = Math.min(dstWidth, Math.max(2, (int) Math.round(srcWidth * scale)) & ~1);
		int h = Math.min(dstHeight, Math.max(2, (int) Math.round(srcHeight * scale)) & ~1);
		int x = (dstWidth - w) / 2;
		int y = (dstHeight - h) / 2;
		return new int[] { x, y, w, h };
	}

	/**
	 * One step of the constant-frame-rate remapper.
	 *
	 * <p>Wall-clock capture times are irregular (Minecraft's frame rate wobbles), but the encoder
	 * needs a monotonic frame index at a fixed rate. This maps a capture timestamp to the next
	 * output index, reporting how many duplicate frames must be emitted to fill a gap — and, when a
	 * stall is too long to paper over, rebasing the timeline instead of emitting a flood of
	 * duplicates that would overrun the ring buffer.
	 *
	 * @param originNanos   timeline origin; use {@link Long#MIN_VALUE} for the very first frame
	 * @param lastIndex     last emitted output index; {@code -1} before any frame
	 * @param captureNanos  this frame's capture time
	 * @param fps           output frame rate
	 * @param maxDuplicates largest gap (in frames) to fill before rebasing
	 * @return the resulting {@link Step}
	 */
	public static Step nextFrame(long originNanos, long lastIndex, long captureNanos, int fps, int maxDuplicates) {
		if (originNanos == Long.MIN_VALUE) {
			return new Step(captureNanos, 0L, 0L);
		}
		long target = Math.round((captureNanos - originNanos) / 1_000_000_000.0 * fps);
		if (target <= lastIndex) {
			target = lastIndex + 1;
		}
		long gap = target - lastIndex - 1;
		if (gap > maxDuplicates) {
			// Rebase so this frame becomes lastIndex+1 with no duplicates.
			long newOrigin = captureNanos - (lastIndex + 1) * 1_000_000_000L / fps;
			return new Step(newOrigin, lastIndex + 1, 0L);
		}
		return new Step(originNanos, target, gap);
	}

	/**
	 * @param originNanos  the (possibly rebased) timeline origin to carry forward
	 * @param targetIndex  the output index this frame is written at
	 * @param duplicates   number of duplicate frames to emit before it
	 */
	public record Step(long originNanos, long targetIndex, long duplicates) {}
}
