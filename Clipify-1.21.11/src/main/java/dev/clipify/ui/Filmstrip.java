package dev.clipify.ui;

import dev.clipify.encode.ClipLibrary;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A row of evenly-spaced thumbnails drawn under the timeline, like a video editor's filmstrip.
 * Thumbnails are extracted once on a background thread; each is uploaded to its own small texture on
 * the render thread. Draws whatever is ready and a placeholder for the rest.
 */
public final class Filmstrip {

	// Also used full-size as the instant stand-in while the playhead is being dragged, so it is well
	// past what the little timeline cells need. 20 of these cost ~18 MB of texture memory.
	private static final int THUMB_WIDTH = 640;

	private final MinecraftClient client;
	private final int count;
	private final double duration;
	private final Identifier[] ids;
	private final NativeImageBackedTexture[] textures;
	private final int[] tw;
	private final int[] th;
	private volatile boolean closed;

	public Filmstrip(MinecraftClient client, Path ffmpeg, Path file, double duration, int count) {
		this.client = client;
		this.count = Math.max(1, count);
		this.duration = Math.max(0.01, duration);
		this.ids = new Identifier[this.count];
		this.textures = new NativeImageBackedTexture[this.count];
		this.tw = new int[this.count];
		this.th = new int[this.count];
		for (int i = 0; i < this.count; i++) {
			ids[i] = Identifier.of("clipify", "strip_" + Long.toHexString(System.nanoTime()) + "_" + i);
		}
		Thread t = new Thread(() -> extract(ffmpeg, file, Math.max(0.01, duration)), "Clipify-filmstrip");
		t.setDaemon(true);
		t.start();
	}

	private void extract(Path ffmpeg, Path file, double duration) {
		for (int i = 0; i < count && !closed; i++) {
			// Sample at the middle of each cell so the strip reads left→right through the clip.
			double at = duration * (i + 0.5) / count;
			byte[] png = ClipLibrary.thumbnailPng(ffmpeg, file, at, THUMB_WIDTH);
			if (png == null) {
				continue;
			}
			NativeImage image;
			try {
				image = NativeImage.read(png);
			} catch (IOException e) {
				continue;
			}
			final int slot = i;
			client.execute(() -> {
				if (closed) {
					image.close();
					return;
				}
				textures[slot] = new LinearImageTexture(() -> "clipify-strip", image);
				client.getTextureManager().registerTexture(ids[slot], textures[slot]);
				tw[slot] = image.getWidth();
				th[slot] = image.getHeight();
			});
		}
	}

	/** Tiles the thumbnails to fill [x, x+w] at the given height. Render thread only. */
	public void draw(DrawContext context, int x, int y, int w, int h) {
		for (int i = 0; i < count; i++) {
			int cellX = x + (int) Math.round((double) w * i / count);
			int cellEnd = x + (int) Math.round((double) w * (i + 1) / count);
			int cellW = Math.max(1, cellEnd - cellX);
			if (textures[i] != null) {
				context.drawTexture(RenderPipelines.GUI_TEXTURED, ids[i], cellX, y, 0f, 0f, cellW, h, tw[i], th[i], tw[i], th[i]);
			} else {
				context.fill(cellX, y, cellX + cellW, y + h, (i & 1) == 0 ? 0xFF141414 : 0xFF1B1B1B);
			}
			// thin divider
			context.fill(cellEnd - 1, y, cellEnd, y + h, 0xFF000000);
		}
	}

	/**
	 * Draws the nearest already-loaded thumbnail for time {@code t} aspect-fit into the box — an
	 * instant, if soft, stand-in for the live frame while a crisp decode is still loading (keeps
	 * scrubbing feeling responsive). Returns false if no thumbnail is ready yet.
	 */
	public boolean drawFrameAt(DrawContext context, double t, int x, int y, int w, int h) {
		int idx = (int) Math.floor(t / duration * count);
		idx = Math.max(0, Math.min(count - 1, idx));
		int found = -1;
		for (int d = 0; d < count && found < 0; d++) {
			if (idx - d >= 0 && textures[idx - d] != null) {
				found = idx - d;
			} else if (idx + d < count && textures[idx + d] != null) {
				found = idx + d;
			}
		}
		if (found < 0) {
			return false;
		}
		int fw = tw[found];
		int fh = th[found];
		float sc = Math.min((float) w / fw, (float) h / fh);
		int dw = Math.max(1, Math.round(fw * sc));
		int dh = Math.max(1, Math.round(fh * sc));
		int dx = x + (w - dw) / 2;
		int dy = y + (h - dh) / 2;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, ids[found], dx, dy, 0f, 0f, dw, dh, fw, fh, fw, fh);
		return true;
	}

	public void close() {
		closed = true;
		for (int i = 0; i < count; i++) {
			if (textures[i] != null) {
				client.getTextureManager().destroyTexture(ids[i]);
				textures[i].close();
				textures[i] = null;
			}
		}
	}
}
