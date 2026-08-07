package dev.clipify.ui;

import dev.clipify.ClipifyLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.IOException;

/**
 * A single dynamic GUI texture fed from decoded video frames (PNG bytes). One instance is reused
 * for the whole editor session — {@link #set} swaps the image, {@link #dispose} frees it. All calls
 * must be on the render thread.
 */
public final class PreviewTexture {

	private final MinecraftClient client;
	private final Identifier id;
	private NativeImageBackedTexture texture;
	private int width;
	private int height;

	public PreviewTexture(MinecraftClient client) {
		this.client = client;
		this.id = Identifier.of("clipify", "preview_" + Long.toHexString(System.nanoTime()));
	}

	/** Replaces the current frame. {@code png} is a PNG-encoded image (from FFmpeg). Render thread only. */
	public void set(byte[] png) {
		if (png == null || png.length == 0) {
			return;
		}
		NativeImage image;
		try {
			image = NativeImage.read(png);
		} catch (IOException e) {
			ClipifyLog.LOGGER.debug("Preview frame decode failed", e);
			return;
		}
		dispose();
		texture = new NativeImageBackedTexture(() -> "clipify-preview", image);
		client.getTextureManager().registerTexture(id, texture);
		width = image.getWidth();
		height = image.getHeight();
	}

	public boolean ready() {
		return texture != null;
	}

	public Identifier id() {
		return id;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public void dispose() {
		if (texture != null) {
			client.getTextureManager().destroyTexture(id);
			texture.close();
			texture = null;
		}
	}
}
