package dev.clipify.capture;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Fixed-size pool of direct byte buffers, one per in-flight frame.
 *
 * <p>The pool is what makes the whole pipeline bounded: the render thread can only ever hand
 * {@code capacity} frames to the encoder thread before {@link #acquire()} starts returning
 * {@code null}, at which point frames are dropped instead of queueing up. Memory therefore stays
 * flat at {@code capacity * frameBytes} no matter how far behind FFmpeg falls.
 */
public final class FramePool {

	private final ArrayDeque<ByteBuffer> free;
	private final int frameBytes;
	private final int capacity;
	private int created;

	public FramePool(int frameBytes, int capacity) {
		this.frameBytes = frameBytes;
		this.capacity = capacity;
		this.free = new ArrayDeque<>(capacity);
	}

	/** @return a cleared buffer of {@code frameBytes}, or null when every buffer is in flight */
	public synchronized ByteBuffer acquire() {
		ByteBuffer buf = free.poll();
		if (buf == null) {
			if (created >= capacity) {
				return null;
			}
			// Direct (off-heap) so OpenGL and the pipe writer can both touch it without copying
			// through the Java heap. Allocation is lazy so a short session never pays for buffers
			// it does not use.
			buf = ByteBuffer.allocateDirect(frameBytes);
			created++;
		}
		buf.clear();
		return buf;
	}

	public synchronized void release(ByteBuffer buf) {
		if (buf == null || buf.capacity() != frameBytes) {
			// A buffer from a previous resolution: let the GC take it rather than corrupting the pool.
			return;
		}
		if (free.size() < capacity) {
			free.add(buf);
		}
	}

	public int frameBytes() {
		return frameBytes;
	}

	/** Drops every pooled buffer so the GC can reclaim them after a resolution change. */
	public synchronized void clear() {
		free.clear();
		created = 0;
	}
}
