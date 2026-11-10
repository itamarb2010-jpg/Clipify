package dev.clipify.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.clipify.ClipifyLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;

/**
 * A single dynamic GUI texture fed from PNG bytes. {@link #set} swaps the image, {@link #dispose}
 * frees it. All calls must be on the render thread.
 */
public final class PreviewTexture {

	private final Minecraft client;
	private final Identifier id;
	private DynamicTexture texture;
	private int width;
	private int height;

	public PreviewTexture(Minecraft client) {
		this.client = client;
		this.id = Identifier.fromNamespaceAndPath("clipify", "preview_" + Long.toHexString(System.nanoTime()));
	}

	/** Replaces the current image. {@code png} is a PNG-encoded image. Render thread only. */
	public void set(byte[] png) {
		if (png == null || png.length == 0) {
			return;
		}
		NativeImage image;
		try {
			image = NativeImage.read(png);
		} catch (IOException e) {
			ClipifyLog.LOGGER.debug("Preview image decode failed", e);
			return;
		}
		dispose();
		texture = new DynamicTexture(() -> "clipify-preview", image);
		client.getTextureManager().register(id, texture);
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
			client.getTextureManager().release(id);
			texture.close();
			texture = null;
		}
	}
}
