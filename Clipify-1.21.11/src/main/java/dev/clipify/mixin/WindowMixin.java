package dev.clipify.mixin;

import dev.clipify.Clipify;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.tracy.TracyFrameCapturer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The capture hook.
 *
 * <p>{@code MinecraftClient.render} finishes with {@code framebuffer.blitToScreen()} — which, in
 * 1.21.11's Blaze3D backend, resolves to a {@code glBlitFramebuffer} into framebuffer 0 — and then
 * calls {@code window.swapBuffers(...)}. Injecting at the head of {@code swapBuffers} is therefore
 * the last moment at which the window's back buffer still holds the completed frame: world, HUD,
 * any open GUI and the F3 overlay, exactly as the player sees it.
 *
 * <p>Hooking here rather than in {@code render} also means we automatically catch the extra present
 * that happens while a fullscreen toggle is in flight.
 */
@Mixin(Window.class)
public class WindowMixin {

	@Inject(method = "swapBuffers", at = @At("HEAD"))
	private void clipify$captureFrame(@Nullable TracyFrameCapturer capturer, CallbackInfo ci) {
		Window self = (Window) (Object) this;
		Clipify.onFramePresented(self.getFramebufferWidth(), self.getFramebufferHeight());
	}
}
