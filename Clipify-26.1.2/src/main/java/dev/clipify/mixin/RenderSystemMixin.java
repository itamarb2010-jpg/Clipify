package dev.clipify.mixin;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.clipify.Clipify;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The capture hook for Minecraft 26.x.
 *
 * <p>The 1.21 render path ended with {@code Window.swapBuffers}; 26.x replaced that with
 * {@code RenderSystem.flipFrame(...)} → {@code getDevice().presentFrame()}. In
 * {@code Minecraft.render} the main render target is composited with
 * {@code mainRenderTarget.blitToScreen()} (which resolves to a {@code glBlitFramebuffer} into
 * framebuffer 0) immediately before {@code flipFrame} is called. Injecting at the head of
 * {@code flipFrame} is therefore the last moment at which the window's back buffer still holds the
 * finished frame — world, HUD, any open GUI — exactly as the player sees it.
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {

	@Inject(method = "flipFrame", at = @At("HEAD"))
	private static void clipify$captureFrame(@Nullable TracyFrameCapture capture, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		Window window = mc != null ? mc.getWindow() : null;
		if (window != null) {
			Clipify.onFramePresented(window.getWidth(), window.getHeight());
		}
	}
}
