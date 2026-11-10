package dev.clipify.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.clipify.ClipifyLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Pulls finished frames off the GPU for Minecraft 26.3, whose renderer ("renderpearl") can run on
 * OpenGL <i>or</i> Vulkan. Everything here goes through the renderer's own device API, so the same
 * code works on both backends — the 1.21.11 / 26.1.2 grabbers talk to OpenGL directly and would not.
 *
 * <p>Every call to {@link #capture}:
 * <ol>
 *   <li>Blits the game's final frame (main render target: world, HUD and any open GUI) into a private
 *       texture at the capture size, with linear filtering — the GPU does the scaling.</li>
 *   <li>Queues an asynchronous copy of that texture into one of a ring of readback buffers. The
 *       renderer calls back on the render thread once the GPU has finished, a frame or two later.</li>
 * </ol>
 * The callback maps the buffer, copies it into a pooled frame and hands that to the encoder. There is
 * no persistent mapping in this API, so this is the "copying readback" path of the other grabbers.
 *
 * <p>Rows come back bottom-up and as RGBA; the 26.3 recorder declares bottom-up rows in its AVI
 * header and swaps red/blue inside FFmpeg, so the render thread never touches individual pixels.
 *
 * <p>The renderer has no adjustable viewport, so a window whose shape no longer matches the
 * recording is letterboxed at copy time: the blit target is the fitted picture's size, and its rows
 * are copied into the middle of a black frame.
 */
public final class FrameGrabber {

	/** Readback buffers in flight. The GPU needs a frame or two; the rest is slack for slow drivers. */
	private static final int SLOTS = 4;
	private static final int TEXTURE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC;
	private static final int BUFFER_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;

	private int width;
	private int height;
	private int frameBytes;

	private boolean initialized;
	private boolean broken;

	private FramePool pool;
	private Consumer<CapturedFrame> sink;

	/** The current blit target + readback ring; replaced when the window changes shape. */
	private Surface surface;
	/** Surfaces replaced while copies were still in flight; closed once those have called back. */
	private final List<Surface> retired = new ArrayList<>();

	private int lastSrcW = -1;
	private int lastSrcH = -1;

	private long framesEmitted;
	private long framesDroppedNoBuffer;
	private long framesSkippedGpuBusy;

	// -------------------------------------------------------------- lifecycle

	/**
	 * Prepares capture at {@code captureWidth}x{@code captureHeight}. Must run on the render thread.
	 * GPU objects are created lazily on the first frame, once the source size is known.
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
		this.initialized = true;
		ClipifyLog.LOGGER.info("Capture surface ready: {}x{} (renderer readback, {} slots)", width, height, SLOTS);
		return true;
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

	/** Frames are always copied out of the readback buffer on this renderer. */
	public boolean isZeroCopy() {
		return false;
	}

	// ---------------------------------------------------------------- capture

	/**
	 * Grabs the frame the game has just finished drawing.
	 *
	 * @param srcWidth  width of the frame being presented (main render target)
	 * @param srcHeight height of the frame being presented
	 * @param nowNanos  presentation timestamp for this frame
	 */
	public void capture(int srcWidth, int srcHeight, long nowNanos) {
		RenderSystem.assertOnRenderThread();
		if (!isReady() || srcWidth <= 0 || srcHeight <= 0) {
			return;
		}
		sweepRetired();

		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView source = main.getColorTextureView();
		if (source == null) {
			return;
		}

		try {
			if (surface == null || srcWidth != lastSrcW || srcHeight != lastSrcH) {
				rebuildSurface(srcWidth, srcHeight);
			}
			Surface s = surface;
			int slot = s.claim();
			if (slot < 0) {
				framesSkippedGpuBusy++;
				return;
			}

			GpuDevice device = RenderSystem.getDevice();
			try (RenderPass pass = device.createCommandEncoder().createRenderPass(
					() -> "Clipify capture", s.view, Optional.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
				pass.setUniform("InSampler", source, RenderSystem.getSamplerCache().getClampToEdge(
						s.w == srcWidth && s.h == srcHeight ? FilterMode.NEAREST : FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}

			long stamp = nowNanos;
			device.createCommandEncoder().copyTextureToBuffer(s.texture, s.buffers[slot], 0L,
					() -> onReadbackDone(s, slot, stamp), 0);
		} catch (RuntimeException e) {
			ClipifyLog.LOGGER.error("Clipify frame capture failed; disabling capture", e);
			broken = true;
		}
	}

	/** Render thread, called by the renderer once the GPU has filled {@code slot}. */
	private void onReadbackDone(Surface s, int slot, long stamp) {
		try {
			if (s.closed || broken || sink == null || pool == null) {
				return;
			}
			ByteBuffer dst = pool.acquire();
			if (dst == null) {
				framesDroppedNoBuffer++; // the encoder is behind; keep capturing
				return;
			}
			try (GpuBufferSlice.MappedView view = s.buffers[slot].map(true, false)) {
				copyInto(s, view.data(), dst);
			} catch (RuntimeException e) {
				pool.release(dst);
				throw e;
			}
			FramePool owningPool = pool;
			framesEmitted++;
			sink.accept(new CapturedFrame(dst, stamp, () -> owningPool.release(dst)));
		} catch (RuntimeException e) {
			ClipifyLog.LOGGER.error("Clipify readback failed; a frame was dropped", e);
		} finally {
			s.release(slot);
		}
	}

	/** Bulk copy when the picture fills the frame, otherwise rows into the middle of a black frame. */
	private void copyInto(Surface s, ByteBuffer src, ByteBuffer dst) {
		dst.clear();
		int pictureBytes = s.w * s.h * 4;
		if (s.w == width && s.h == height) {
			ByteBuffer from = src.duplicate();
			from.position(0).limit(pictureBytes);
			dst.put(from);
			dst.flip();
			return;
		}
		// Letterbox: black everywhere, then each picture row at its offset. Rows are bottom-up on
		// both sides, so row r of the picture lands on row dstY + r of the frame.
		while (dst.hasRemaining()) {
			dst.put((byte) 0);
		}
		int rowBytes = s.w * 4;
		ByteBuffer from = src.duplicate();
		for (int r = 0; r < s.h; r++) {
			int srcPos = r * rowBytes;
			from.limit(srcPos + rowBytes).position(srcPos);
			dst.position(((s.y + r) * width + s.x) * 4);
			dst.put(from);
		}
		dst.position(0).limit(frameBytes);
	}

	private void rebuildSurface(int srcWidth, int srcHeight) {
		int[] box = CaptureMath.fitLetterbox(srcWidth, srcHeight, width, height);
		int w = Math.max(2, box[2]);
		int h = Math.max(2, box[3]);
		if (surface != null) {
			retired.add(surface);
			surface.closed = true;
		}
		surface = new Surface(box[0], box[1], w, h);
		lastSrcW = srcWidth;
		lastSrcH = srcHeight;
	}

	/** Destroys replaced surfaces once every copy queued on them has called back. */
	private void sweepRetired() {
		retired.removeIf(s -> {
			if (s.inFlight > 0) {
				return false;
			}
			s.destroy();
			return true;
		});
	}

	// --------------------------------------------------------------- teardown

	/** Releases every GPU object. Must run on the render thread. */
	public void close() {
		if (!initialized && surface == null && retired.isEmpty()) {
			return;
		}
		RenderSystem.assertOnRenderThread();
		if (initialized) {
			ClipifyLog.LOGGER.info("Capture surface closed ({} frames captured, {} skipped with every readback slot busy, {} dropped with no buffer free)",
					framesEmitted, framesSkippedGpuBusy, framesDroppedNoBuffer);
			framesEmitted = 0;
			framesSkippedGpuBusy = 0;
			framesDroppedNoBuffer = 0;
		}
		if (surface != null) {
			surface.closed = true;
			retired.add(surface);
			surface = null;
		}
		sweepRetired(); // anything still in flight is destroyed by a later sweep or left to the GC
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

	// ---------------------------------------------------------------- surface

	/** A blit target at the fitted picture size plus its ring of readback buffers. Render thread only. */
	private static final class Surface {
		final int x;
		final int y;
		final int w;
		final int h;
		final GpuTexture texture;
		final GpuTextureView view;
		final GpuBuffer[] buffers = new GpuBuffer[SLOTS];
		final boolean[] busy = new boolean[SLOTS];
		int inFlight;
		boolean closed;

		Surface(int x, int y, int w, int h) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			GpuDevice device = RenderSystem.getDevice();
			this.texture = device.createTexture("Clipify capture", TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
			this.view = device.createTextureView(texture);
			for (int i = 0; i < SLOTS; i++) {
				buffers[i] = device.createBuffer(() -> "Clipify readback", BUFFER_USAGE, (long) w * h * 4L);
			}
			// Clear once so the first frames are black, not undefined, if a copy beats the first blit.
			try (RenderPass ignored = device.createCommandEncoder().createRenderPass(
					() -> "Clipify capture clear", view, Optional.of(new Vector4f(0f, 0f, 0f, 1f)))) {
				// clearing is done by opening the pass
			}
		}

		int claim() {
			for (int i = 0; i < SLOTS; i++) {
				if (!busy[i]) {
					busy[i] = true;
					inFlight++;
					return i;
				}
			}
			return -1;
		}

		void release(int slot) {
			if (busy[slot]) {
				busy[slot] = false;
				inFlight--;
			}
		}

		void destroy() {
			for (GpuBuffer b : buffers) {
				b.close();
			}
			view.close();
			texture.close();
		}
	}
}
