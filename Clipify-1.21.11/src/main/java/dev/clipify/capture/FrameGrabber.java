package dev.clipify.capture;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.clipify.ClipifyLog;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Pulls finished frames off the GPU without ever stalling — or memcpy-ing on — the render thread.
 *
 * <p>Every call to {@link #capture} does three things:
 * <ol>
 *   <li>GPU-blits the window's back buffer into a private renderbuffer, scaling to the capture
 *       resolution and flipping vertically (OpenGL's origin is bottom-left, video's is top-left) —
 *       both for free, inside the blit.</li>
 *   <li>Issues an <b>asynchronous</b> {@code glReadPixels} into a pixel-pack buffer object and
 *       drops a fence behind it. This returns immediately; the GPU fills the PBO in the background.</li>
 *   <li>Checks the fences of older PBOs and, for each one the GPU has already signalled, hands that
 *       buffer straight to the encoder thread.</li>
 * </ol>
 *
 * <p><b>Zero copy.</b> Where the driver supports {@code ARB_buffer_storage} (OpenGL 4.4, i.e. every
 * GPU made in the last decade) the pack buffers are mapped <i>once</i>, persistently, and the
 * encoder thread reads its frames straight out of that mapping. The render thread therefore never
 * touches frame bytes at all. The old path — map, {@code memcpy} 8 MB into a pooled buffer, unmap,
 * every single captured frame — was costing a millisecond-plus of render time per frame, which at
 * high frame rates is the difference between "invisible" and "this mod eats my FPS". The copying
 * path is kept as a fallback for pre-4.4 drivers.
 *
 * <p>Because a persistently mapped slot stays checked out until the encoder gives it back, the ring
 * is also the bound on frames in flight: when no slot is free the frame is skipped rather than
 * queued. A busy GPU or a slow encoder costs dropped frames, never frame-time.
 */
public final class FrameGrabber {

	/**
	 * Depth of the PBO ring when frames are handed over by reference. Slots stay checked out until
	 * the encoder thread has written them, so this has to cover the GPU's in-flight readbacks plus
	 * the recorder's queue plus its retained duplicate-fill frame.
	 */
	private static final int SLOTS_ZERO_COPY = 8;
	/** Depth when frames are copied out immediately; two spare frames is all the GPU needs. */
	private static final int SLOTS_COPYING = 3;
	/** Consecutive readback GL errors tolerated before capture is disabled. */
	private static final int MAX_ERROR_STREAK = 120;
	/**
	 * How often a readback is checked for GL errors, in frames. {@code glGetError} is a synchronous
	 * driver round-trip on the render thread, so once the pipeline is known to work it is not worth
	 * paying for on every frame — the first {@link #ERROR_CHECK_WARMUP} captures are checked, then
	 * roughly one every ten seconds.
	 */
	private static final int ERROR_CHECK_INTERVAL = 600;
	private static final int ERROR_CHECK_WARMUP = 5;

	private long[] fences = new long[0];
	private long[] slotNanos = new long[0];
	/** Slots handed to the GPU, oldest first, so frames are emitted in capture order. */
	private final Deque<Integer> pendingOrder = new ArrayDeque<>();

	private PboRing ring;
	/** Rings closed while the encoder still held frames; destroyed once every slot has come back. */
	private final List<PboRing> retiredRings = new ArrayList<>();

	private int fbo;
	private int rbo;
	private int width;
	private int height;
	private int frameBytes;

	private boolean initialized;
	private boolean broken;

	/** Destination rectangle inside the capture surface, preserving the source aspect ratio. */
	private int dstX;
	private int dstY;
	private int dstW;
	private int dstH;
	private boolean clearPending;
	private int lastSrcW = -1;
	private int lastSrcH = -1;

	private FramePool pool;
	private Consumer<CapturedFrame> sink;

	private long framesEmitted;
	private long framesDroppedNoBuffer;
	private long framesSkippedGpuBusy;
	private int captureErrorStreak;
	private int capturesSinceErrorCheck;
	private int capturesSinceInit;

	// -------------------------------------------------------------- lifecycle

	/**
	 * Allocates the capture surface and PBO ring. Must run on the render thread.
	 *
	 * @return false if the GPU rejected the setup, in which case this grabber stays disabled
	 */
	public boolean init(int captureWidth, int captureHeight, FramePool framePool, Consumer<CapturedFrame> frameSink) {
		RenderSystem.assertOnRenderThread();
		close();

		this.width = captureWidth;
		this.height = captureHeight;
		this.frameBytes = captureWidth * captureHeight * 4;
		this.pool = framePool;
		this.sink = frameSink;
		this.broken = false;
		this.lastSrcW = -1;
		this.lastSrcH = -1;
		this.captureErrorStreak = 0;
		this.capturesSinceErrorCheck = 0;
		this.capturesSinceInit = 0;

		GlStateManager.clearGlErrors();

		int prevRead = GlStateManager.getFrameBuffer(GlConst.GL_READ_FRAMEBUFFER);
		int prevDraw = GlStateManager.getFrameBuffer(GlConst.GL_DRAW_FRAMEBUFFER);
		try {
			rbo = GL30.glGenRenderbuffers();
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, rbo);
			GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height);
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);

			fbo = GlStateManager.glGenFramebuffers();
			GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, fbo);
			GL30.glFramebufferRenderbuffer(GlConst.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
					GL30.GL_RENDERBUFFER, rbo);

			int status = GL30.glCheckFramebufferStatus(GlConst.GL_DRAW_FRAMEBUFFER);
			if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
				ClipifyLog.LOGGER.error("Clipify capture framebuffer incomplete (status 0x{})",
						Integer.toHexString(status));
				closeInternal();
				broken = true;
				return false;
			}

			// Paint the surface black once so letterbox bars are black without a per-frame clear.
			GlStateManager._disableScissorTest();
			GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

			ring = createRing();
			fences = new long[ring.size()];
			slotNanos = new long[ring.size()];

			int err = GlStateManager._getError();
			if (err != GL11.GL_NO_ERROR) {
				ClipifyLog.LOGGER.error("Clipify capture setup raised GL error 0x{}", Integer.toHexString(err));
				closeInternal();
				broken = true;
				return false;
			}

			initialized = true;
			ClipifyLog.LOGGER.info("Capture surface ready: {}x{} ({} MiB of PBOs, {})", width, height,
					(long) frameBytes * ring.size() / (1024 * 1024),
					ring.persistent() ? "zero-copy readback" : "copying readback");
			return true;
		} catch (RuntimeException e) {
			ClipifyLog.LOGGER.error("Clipify capture setup failed", e);
			closeInternal();
			broken = true;
			return false;
		} finally {
			GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevRead);
			GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, prevDraw);
		}
	}

	/**
	 * Builds the pack-buffer ring, preferring persistent mapping so the render thread never has to
	 * copy a frame. Falls back to plain {@code glBufferData} buffers if the driver is pre-4.4 or the
	 * mapping is refused.
	 */
	private PboRing createRing() {
		if (supportsPersistentMapping()) {
			PboRing persistent = tryCreatePersistentRing();
			if (persistent != null) {
				return persistent;
			}
			ClipifyLog.LOGGER.warn("Persistent readback mapping was refused; falling back to copied readbacks");
		}

		int[] ids = new int[SLOTS_COPYING];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = GlStateManager._glGenBuffers();
			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, ids[i]);
			GlStateManager._glBufferData(GlConst.GL_PIXEL_PACK_BUFFER, frameBytes, GL15.GL_STREAM_READ);
		}
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);
		return new PboRing(ids, null);
	}

	private static boolean supportsPersistentMapping() {
		try {
			GLCapabilities caps = GL.getCapabilities();
			return caps != null && (caps.OpenGL44 || caps.GL_ARB_buffer_storage);
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** @return the mapped ring, or null if any part of the setup failed (everything is cleaned up) */
	private PboRing tryCreatePersistentRing() {
		// COHERENT lets the encoder thread read the mapping as soon as our fence says the GPU is
		// done, with no further GL call in between — which is the whole point: the render thread
		// hands over a pointer and moves on.
		final int flags = GL30.GL_MAP_READ_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
		int[] ids = new int[SLOTS_ZERO_COPY];
		ByteBuffer[] maps = new ByteBuffer[SLOTS_ZERO_COPY];
		GlStateManager.clearGlErrors();
		try {
			for (int i = 0; i < ids.length; i++) {
				ids[i] = GlStateManager._glGenBuffers();
				GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, ids[i]);
				GL44.glBufferStorage(GlConst.GL_PIXEL_PACK_BUFFER, (long) frameBytes, flags);
				maps[i] = GL30.glMapBufferRange(GlConst.GL_PIXEL_PACK_BUFFER, 0L, frameBytes, flags);
				if (maps[i] == null || GlStateManager._getError() != GL11.GL_NO_ERROR) {
					destroyPartialRing(ids, maps, i + 1);
					return null;
				}
			}
			return new PboRing(ids, maps);
		} catch (RuntimeException | LinkageError e) {
			ClipifyLog.LOGGER.warn("Persistent readback mapping is unavailable on this driver", e);
			destroyPartialRing(ids, maps, ids.length);
			return null;
		} finally {
			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);
		}
	}

	private static void destroyPartialRing(int[] ids, ByteBuffer[] maps, int count) {
		for (int i = 0; i < count; i++) {
			if (ids[i] == 0) {
				continue;
			}
			if (maps[i] != null) {
				GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, ids[i]);
				GlStateManager._glUnmapBuffer(GlConst.GL_PIXEL_PACK_BUFFER);
			}
			GlStateManager._glDeleteBuffers(ids[i]);
			ids[i] = 0;
		}
		GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);
	}

	public boolean isReady() {
		return initialized && !broken;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** True when frames reach the encoder without the render thread copying them. */
	public boolean isZeroCopy() {
		return ring != null && ring.persistent();
	}

	// ---------------------------------------------------------------- capture

	/**
	 * Grabs the frame currently sitting in the window's back buffer.
	 *
	 * @param srcWidth  window framebuffer width
	 * @param srcHeight window framebuffer height
	 * @param nowNanos  presentation timestamp for this frame
	 */
	public void capture(int srcWidth, int srcHeight, long nowNanos) {
		RenderSystem.assertOnRenderThread();
		if (!isReady() || srcWidth <= 0 || srcHeight <= 0) {
			return;
		}

		// Hand over everything the GPU has finished with first: that is what frees up a slot for the
		// frame we are about to grab.
		drainReady();
		if (!retiredRings.isEmpty()) {
			sweepRetiredRings();
		}

		Integer claimed = ring.claim();
		if (claimed == null) {
			framesSkippedGpuBusy++;
			return;
		}
		int slot = claimed;
		boolean issued = false;

		int prevRead = GlStateManager.getFrameBuffer(GlConst.GL_READ_FRAMEBUFFER);
		int prevDraw = GlStateManager.getFrameBuffer(GlConst.GL_DRAW_FRAMEBUFFER);
		try {
			if (srcWidth != lastSrcW || srcHeight != lastSrcH) {
				recomputeDestRect(srcWidth, srcHeight);
			}

			// glBlitFramebuffer honours the scissor box; MinecraftClient has just disabled it in
			// presentTexture, but re-assert it so a future change cannot silently crop our capture.
			GlStateManager._disableScissorTest();

			GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, 0);
			GL11.glReadBuffer(GL11.GL_BACK);
			GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, fbo);

			if (clearPending) {
				GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
				GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
				clearPending = false;
			}

			// Swapping dstY0/dstY1 flips the image so the encoder receives top-down rows.
			GlStateManager._glBlitFrameBuffer(
					0, 0, srcWidth, srcHeight,
					dstX, dstY + dstH, dstX + dstW, dstY,
					GlConst.GL_COLOR_BUFFER_BIT,
					(dstW == srcWidth && dstH == srcHeight) ? GlConst.GL_NEAREST : GlConst.GL_LINEAR);

			GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, fbo);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, ring.id(slot));

			// CRITICAL: Minecraft leaves the pixel-pack state configured for its own texture and
			// screenshot readbacks — in particular GL_PACK_ROW_LENGTH is often non-zero. If we
			// inherit that, glReadPixels computes wider rows than our PBO holds and the driver
			// rejects the call with GL_INVALID_OPERATION ("Invalid PBO operation"), leaving the PBO
			// full of stale pixels from a previous frame. That is what produced the badly stuttering
			// early clips. Force a clean, tightly-packed layout every frame.
			GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
			GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
			GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
			GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);

			boolean checkError = shouldCheckError();
			if (checkError) {
				// Clear the whole pending error queue so the check below is attributable to us alone.
				GlStateManager.clearGlErrors();
			}

			// Offset form: the result lands in the bound PBO instead of client memory, so this
			// call returns without waiting for the GPU.
			GlStateManager._readPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);

			int err = checkError ? GlStateManager._getError() : GL11.GL_NO_ERROR;
			// Restore the GL default pack alignment so MC is never surprised by our state.
			GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);

			if (err != GL11.GL_NO_ERROR) {
				handleReadbackError(err);
				return;
			}
			if (checkError) {
				captureErrorStreak = 0;
			}

			fences[slot] = GlStateManager._glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
			slotNanos[slot] = nowNanos;
			pendingOrder.addLast(slot);
			issued = true;
		} catch (RuntimeException e) {
			ClipifyLog.LOGGER.error("Clipify frame capture failed; disabling capture", e);
			broken = true;
		} finally {
			if (!issued) {
				ring.recycle(slot);
			}
			GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevRead);
			GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, prevDraw);
		}
	}

	/** Errors are checked on the first few captures after a restart, then only occasionally. */
	private boolean shouldCheckError() {
		if (capturesSinceInit < ERROR_CHECK_WARMUP) {
			capturesSinceInit++;
			capturesSinceErrorCheck = 0;
			return true;
		}
		if (captureErrorStreak > 0) {
			// Something is going wrong — go back to checking every frame until it clears.
			capturesSinceErrorCheck = 0;
			return true;
		}
		if (++capturesSinceErrorCheck >= ERROR_CHECK_INTERVAL) {
			capturesSinceErrorCheck = 0;
			return true;
		}
		return false;
	}

	/**
	 * A readback raised a GL error. Log the first occurrence loudly (it means a frame was lost) and,
	 * if errors persist, disable capture rather than keep producing a corrupt clip.
	 */
	private void handleReadbackError(int err) {
		if (captureErrorStreak == 0) {
			ClipifyLog.LOGGER.error("Clipify glReadPixels raised GL error 0x{} — a frame was dropped",
					Integer.toHexString(err));
		}
		if (++captureErrorStreak >= MAX_ERROR_STREAK) {
			ClipifyLog.LOGGER.error("Clipify capture hit {} consecutive GL errors; disabling capture",
					MAX_ERROR_STREAK);
			broken = true;
		}
	}

	/** Fits the source into the capture surface, preserving aspect ratio and centring the result. */
	private void recomputeDestRect(int srcWidth, int srcHeight) {
		int[] box = CaptureMath.fitLetterbox(srcWidth, srcHeight, width, height);
		dstX = box[0];
		dstY = box[1];
		dstW = box[2];
		dstH = box[3];
		lastSrcW = srcWidth;
		lastSrcH = srcHeight;
		// Only repaint the background when bars are actually visible.
		clearPending = (dstW != width || dstH != height);
	}

	/**
	 * Emits every readback the GPU has finished, oldest first. Stops at the first unfinished one so
	 * frames always reach the encoder in capture order, and never waits.
	 */
	private void drainReady() {
		while (!pendingOrder.isEmpty()) {
			int slot = pendingOrder.peekFirst();
			long fence = fences[slot];
			if (fence != 0L) {
				int status = GlStateManager._glClientWaitSync(fence, 0, 0L);
				if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
					return;
				}
				GlStateManager._glDeleteSync(fence);
				fences[slot] = 0L;
			}
			pendingOrder.pollFirst();
			emit(slot);
		}
	}

	/** Hands a finished readback to the encoder thread. */
	private void emit(int slot) {
		PboRing owner = ring;
		ByteBuffer mapped = owner.mapped(slot);
		if (mapped == null) {
			copyOutOfSlot(owner, slot);
			return;
		}
		// The fast path in full: no map, no copy, no unmap. The encoder thread reads the readback
		// memory itself and gives the slot back when it has written the frame.
		mapped.clear();
		framesEmitted++;
		sink.accept(new CapturedFrame(mapped, slotNanos[slot], () -> owner.recycle(slot)));
	}

	/** Pre-4.4 fallback: map the readback, copy it into a pooled buffer, free the slot immediately. */
	private void copyOutOfSlot(PboRing owner, int slot) {
		ByteBuffer dst = pool != null ? pool.acquire() : null;
		try {
			if (dst == null) {
				// Encoder is behind. Recycle the slot anyway so capture keeps flowing.
				framesDroppedNoBuffer++;
				return;
			}

			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, owner.id(slot));
			ByteBuffer src = GlStateManager._glMapBufferRange(
					GlConst.GL_PIXEL_PACK_BUFFER, 0L, frameBytes, GL30.GL_MAP_READ_BIT);
			if (src == null) {
				pool.release(dst);
				framesDroppedNoBuffer++;
				return;
			}
			try {
				dst.clear();
				dst.put(src);
				dst.flip();
			} finally {
				GlStateManager._glUnmapBuffer(GlConst.GL_PIXEL_PACK_BUFFER);
				GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);
			}

			FramePool owningPool = pool;
			framesEmitted++;
			sink.accept(new CapturedFrame(dst, slotNanos[slot], () -> owningPool.release(dst)));
		} finally {
			owner.recycle(slot);
		}
	}

	// --------------------------------------------------------------- teardown

	/** Releases every GL object. Must run on the render thread. */
	public void close() {
		if (!initialized && ring == null && fbo == 0 && rbo == 0 && retiredRings.isEmpty()) {
			return;
		}
		RenderSystem.assertOnRenderThread();
		closeInternal();
	}

	private void closeInternal() {
		for (int i = 0; i < fences.length; i++) {
			if (fences[i] != 0L) {
				GlStateManager._glDeleteSync(fences[i]);
				fences[i] = 0L;
			}
		}
		// Slots the GPU was still filling are ours to take back; slots already handed to the encoder
		// are not, which is exactly why the ring outlives this grabber (see PboRing).
		for (Integer slot : pendingOrder) {
			if (ring != null) {
				ring.recycle(slot);
			}
		}
		pendingOrder.clear();

		if (ring != null) {
			retiredRings.add(ring);
			ring = null;
		}
		sweepRetiredRings();

		if (fbo != 0) {
			GlStateManager._glDeleteFramebuffers(fbo);
			fbo = 0;
		}
		if (rbo != 0) {
			GL30.glDeleteRenderbuffers(rbo);
			rbo = 0;
		}
		fences = new long[0];
		slotNanos = new long[0];
		initialized = false;
		pool = null;
		sink = null;
	}

	/**
	 * Destroys retired rings once the encoder has returned their last frame. Unmapping or deleting a
	 * buffer that a background thread is still reading would take the JVM down with it, so a ring
	 * that still has frames out simply waits for the next sweep.
	 */
	private void sweepRetiredRings() {
		retiredRings.removeIf(r -> {
			if (!r.drained()) {
				return false;
			}
			r.destroy();
			return true;
		});
	}

	// ------------------------------------------------------------------ stats

	public long framesEmitted() {
		return framesEmitted;
	}

	public long framesDroppedNoBuffer() {
		return framesDroppedNoBuffer;
	}

	public long framesSkippedGpuBusy() {
		return framesSkippedGpuBusy;
	}

	// ------------------------------------------------------------------- ring

	/**
	 * The pixel-pack buffers and, on the fast path, their persistent mappings.
	 *
	 * <p>This is a separate object with its own outstanding-slot count so that a ring can be retired
	 * — on a resolution change, a config change or shutdown — while the encoder thread is still
	 * reading frames out of it. The GL objects are only destroyed once every slot has been handed
	 * back, which is what makes the zero-copy handoff safe.
	 */
	private static final class PboRing {

		private final int[] ids;
		/** Persistent mappings, or null when frames are copied out instead. */
		private final ByteBuffer[] maps;
		private final ConcurrentLinkedQueue<Integer> free = new ConcurrentLinkedQueue<>();
		private final AtomicInteger checkedOut = new AtomicInteger();

		PboRing(int[] ids, ByteBuffer[] maps) {
			this.ids = ids;
			this.maps = maps;
			for (int i = 0; i < ids.length; i++) {
				free.add(i);
			}
		}

		/** @return a slot index the GPU may write into, or null when every slot is in use */
		Integer claim() {
			Integer slot = free.poll();
			if (slot != null) {
				checkedOut.incrementAndGet();
			}
			return slot;
		}

		/** Called from whichever thread finished with the slot. */
		void recycle(int slot) {
			free.add(slot);
			checkedOut.decrementAndGet();
		}

		boolean persistent() {
			return maps != null;
		}

		ByteBuffer mapped(int slot) {
			return maps == null ? null : maps[slot];
		}

		int id(int slot) {
			return ids[slot];
		}

		int size() {
			return ids.length;
		}

		boolean drained() {
			return checkedOut.get() == 0;
		}

		/** Render thread only, and only once {@link #drained()} is true. */
		void destroy() {
			destroyPartialRing(ids, maps == null ? new ByteBuffer[ids.length] : maps, ids.length);
		}
	}
}
