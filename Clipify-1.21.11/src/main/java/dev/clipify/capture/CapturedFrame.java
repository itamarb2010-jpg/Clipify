package dev.clipify.capture;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One BGRA frame handed from the render thread to the encoder thread.
 *
 * <p>The frame carries its own {@link #release() releaser} because its storage can come from two
 * places: a persistently mapped pixel-pack buffer owned by {@link FrameGrabber} (the fast path — the
 * encoder reads the readback memory directly, so the render thread never copies a pixel), or a
 * {@link FramePool} buffer on GPUs too old for persistent mapping. Whoever finishes with the frame
 * calls {@link #release()} and does not need to know which.
 */
public final class CapturedFrame {

	private final ByteBuffer pixels;
	private final long captureNanos;
	private final Runnable releaser;
	private final AtomicBoolean released = new AtomicBoolean();

	/**
	 * @param pixels       frame storage, positioned at 0 and limited to the frame size
	 * @param captureNanos {@link System#nanoTime()} at the moment the frame was presented, used by the
	 *                     encoder thread to keep the output stream constant-frame-rate
	 * @param releaser     returns the storage to its owner; run at most once
	 */
	public CapturedFrame(ByteBuffer pixels, long captureNanos, Runnable releaser) {
		this.pixels = pixels;
		this.captureNanos = captureNanos;
		this.releaser = releaser;
	}

	public ByteBuffer pixels() {
		return pixels;
	}

	public long captureNanos() {
		return captureNanos;
	}

	/**
	 * Gives the frame's storage back to whoever owns it. Idempotent — a double release would hand the
	 * same readback slot out twice and let the GPU overwrite a frame the encoder is still reading.
	 */
	public void release() {
		if (released.compareAndSet(false, true)) {
			releaser.run();
		}
	}
}
