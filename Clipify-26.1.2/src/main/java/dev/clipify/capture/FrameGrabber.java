package dev.clipify.capture;

import com.mojang.blaze3d.opengl.GlConst;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.clipify.ClipifyLog;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Pulls finished frames off the GPU without ever stalling the render thread.
 *
 * <p>Every call to {@link #capture} does three things:
 * <ol>
 *   <li>GPU-blits the window's back buffer into a private renderbuffer, scaling to the capture
 *       resolution and flipping vertically (OpenGL's origin is bottom-left, video's is top-left) —
 *       both for free, inside the blit.</li>
 *   <li>Issues an <b>asynchronous</b> {@code glReadPixels} into a pixel-pack buffer object and
 *       drops a fence behind it. This returns immediately; the GPU fills the PBO in the background.</li>
 *   <li>Checks the fence of the PBO from {@code SLOTS} frames ago and, only if the GPU has already
 *       signalled it, maps that buffer and copies it out for the encoder thread.</li>
 * </ol>
 *
 * <p>Because step 3 never waits, a busy GPU costs dropped frames rather than frame-time. All GL
 * work is confined to the render thread; {@link RenderSystem#assertOnRenderThread()} enforces it.
 */
public final class FrameGrabber {

	/** Depth of the PBO ring. Three gives the GPU two full frames to finish a readback. */
	private static final int SLOTS = 3;
	/** Consecutive readback GL errors tolerated before capture is disabled. */
	private static final int MAX_ERROR_STREAK = 120;

	private final int[] pbos = new int[SLOTS];
	private final long[] fences = new long[SLOTS];
	private final boolean[] pending = new boolean[SLOTS];
	private final long[] slotNanos = new long[SLOTS];

	private int fbo;
	private int rbo;
	private int width;
	private int height;
	private int frameBytes;
	private int nextSlot;

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
		this.nextSlot = 0;
		this.lastSrcW = -1;
		this.lastSrcH = -1;

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

			for (int i = 0; i < SLOTS; i++) {
				pbos[i] = GlStateManager._glGenBuffers();
				GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, pbos[i]);
				GlStateManager._glBufferData(GlConst.GL_PIXEL_PACK_BUFFER, frameBytes, GL15.GL_STREAM_READ);
				pending[i] = false;
				fences[i] = 0L;
			}
			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);

			int err = GlStateManager._getError();
			if (err != GL11.GL_NO_ERROR) {
				ClipifyLog.LOGGER.error("Clipify capture setup raised GL error 0x{}", Integer.toHexString(err));
				closeInternal();
				broken = true;
				return false;
			}

			initialized = true;
			ClipifyLog.LOGGER.info("Capture surface ready: {}x{} ({} MiB of PBOs)", width, height,
					(long) frameBytes * SLOTS / (1024 * 1024));
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

	public boolean isReady() {
		return initialized && !broken;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
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

		int slot = nextSlot;

		// Never overwrite a PBO the GPU is still writing into: if its fence has not been signalled
		// we skip this frame entirely rather than blocking.
		if (pending[slot] && !drainSlot(slot)) {
			framesSkippedGpuBusy++;
			return;
		}

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

			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, pbos[slot]);

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

			// Clear the whole pending error queue so the check below is attributable to us alone.
			GlStateManager.clearGlErrors();

			// Offset form: the result lands in the bound PBO instead of client memory, so this
			// call returns without waiting for the GPU.
			GlStateManager._readPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);

			int err = GlStateManager._getError();
			// Restore the GL default pack alignment so MC is never surprised by our state.
			GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);

			if (err != GL11.GL_NO_ERROR) {
				handleReadbackError(err);
				return;
			}
			captureErrorStreak = 0;

			fences[slot] = GlStateManager._glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
			pending[slot] = true;
			slotNanos[slot] = nowNanos;
			nextSlot = (slot + 1) % SLOTS;
		} catch (RuntimeException e) {
			ClipifyLog.LOGGER.error("Clipify frame capture failed; disabling capture", e);
			broken = true;
		} finally {
			GlStateManager._glBindFramebuffer(GlConst.GL_READ_FRAMEBUFFER, prevRead);
			GlStateManager._glBindFramebuffer(GlConst.GL_DRAW_FRAMEBUFFER, prevDraw);
		}
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
	 * Copies a finished readback out of its PBO.
	 *
	 * @return true if the slot is now free (either drained or discarded), false if the GPU has not
	 *         finished with it yet
	 */
	private boolean drainSlot(int slot) {
		long fence = fences[slot];
		if (fence != 0L) {
			int status = GlStateManager._glClientWaitSync(fence, 0, 0L);
			if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
				return false;
			}
			GlStateManager._glDeleteSync(fence);
			fences[slot] = 0L;
		}

		ByteBuffer dst = pool.acquire();
		try {
			if (dst == null) {
				// Encoder is behind. Recycle the slot anyway so capture keeps flowing.
				framesDroppedNoBuffer++;
				return true;
			}

			GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, pbos[slot]);
			ByteBuffer mapped = GlStateManager._glMapBufferRange(
					GlConst.GL_PIXEL_PACK_BUFFER, 0L, frameBytes, GL30.GL_MAP_READ_BIT);
			if (mapped == null) {
				pool.release(dst);
				framesDroppedNoBuffer++;
				return true;
			}
			try {
				dst.clear();
				dst.put(mapped);
				dst.flip();
			} finally {
				GlStateManager._glUnmapBuffer(GlConst.GL_PIXEL_PACK_BUFFER);
				GlStateManager._glBindBuffer(GlConst.GL_PIXEL_PACK_BUFFER, 0);
			}

			framesEmitted++;
			sink.accept(new CapturedFrame(dst, slotNanos[slot]));
			return true;
		} finally {
			pending[slot] = false;
		}
	}

	// --------------------------------------------------------------- teardown

	/** Releases every GL object. Must run on the render thread. */
	public void close() {
		if (!initialized && fbo == 0 && rbo == 0) {
			return;
		}
		RenderSystem.assertOnRenderThread();
		closeInternal();
	}

	private void closeInternal() {
		for (int i = 0; i < SLOTS; i++) {
			if (fences[i] != 0L) {
				GlStateManager._glDeleteSync(fences[i]);
				fences[i] = 0L;
			}
			if (pbos[i] != 0) {
				GlStateManager._glDeleteBuffers(pbos[i]);
				pbos[i] = 0;
			}
			pending[i] = false;
		}
		if (fbo != 0) {
			GlStateManager._glDeleteFramebuffers(fbo);
			fbo = 0;
		}
		if (rbo != 0) {
			GL30.glDeleteRenderbuffers(rbo);
			rbo = 0;
		}
		initialized = false;
		pool = null;
		sink = null;
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
}
