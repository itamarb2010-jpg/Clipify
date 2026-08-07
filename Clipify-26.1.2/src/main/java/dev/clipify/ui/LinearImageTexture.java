package dev.clipify.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.util.function.Supplier;

/**
 * A {@link DynamicTexture} that samples with LINEAR filtering. The stock class hardcodes NEAREST,
 * which makes a scaled-up video frame look blocky; LINEAR smooths it so the preview reads as video
 * rather than pixels.
 */
public final class LinearImageTexture extends DynamicTexture {

	public LinearImageTexture(Supplier<String> label, NativeImage image) {
		super(label, image);
		this.sampler = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
	}
}
