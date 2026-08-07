package dev.clipify.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

import java.util.function.Supplier;

/**
 * A {@link NativeImageBackedTexture} that samples with LINEAR filtering. The stock class hardcodes
 * NEAREST, which makes a scaled-up video frame look blocky; LINEAR smooths it so the preview reads as
 * video rather than pixels.
 */
public final class LinearImageTexture extends NativeImageBackedTexture {

	public LinearImageTexture(Supplier<String> nameSupplier, NativeImage image) {
		super(nameSupplier, image);
		this.sampler = RenderSystem.getSamplerCache().getRepeated(FilterMode.LINEAR);
	}
}
