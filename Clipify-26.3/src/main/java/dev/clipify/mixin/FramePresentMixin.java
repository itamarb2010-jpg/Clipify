package dev.clipify.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.clipify.Clipify;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The capture hook for Minecraft 26.3.
 *
 * <p>26.3's renderer ("renderpearl") dropped {@code RenderSystem.flipFrame}. {@code Minecraft.renderFrame}
 * now draws everything — world, HUD, any open GUI — into the main render target, blits that to the
 * window's swapchain, then submits the frame's GPU work and presents. Injecting where the profiler
 * section switches to {@code "submit"} puts Clipify's own blit + readback into the same submission,
 * right after the finished frame exists (the same spot vanilla's Tracy frame capture uses).
 */
@Mixin(Minecraft.class)
public class FramePresentMixin {

	@Inject(method = "renderFrame",
			at = @At(value = "INVOKE_STRING",
					target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
					args = "ldc=submit"))
	private void clipify$captureFrame(boolean advanceGameTime, CallbackInfo ci) {
		RenderTarget main = ((Minecraft) (Object) this).gameRenderer.mainRenderTarget();
		if (main != null && main.getColorTexture() != null) {
			Clipify.onFramePresented(main.width, main.height);
		}
	}
}
