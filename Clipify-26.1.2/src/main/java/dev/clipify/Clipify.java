package dev.clipify;

import dev.clipify.config.ClipifyConfig;
import dev.clipify.config.ClipifyConfig.Hotkey;
import dev.clipify.encode.ClipLibrary;
import dev.clipify.ui.Notifications;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client entrypoint. Wires the lifecycle hooks to {@link ReplayBufferService} and dispatches the
 * user-defined {@linkplain ClipifyConfig#hotkeys hotkeys}.
 *
 * <p>Clipify no longer registers vanilla {@code KeyMapping}s (so nothing shows up in Options →
 * Controls): the hotkey list lives entirely in the Clipify settings and is polled here each tick.
 * That is what lets a user bind several keys to the same action — e.g. a different key per clip
 * length.
 */
public final class Clipify implements ClientModInitializer {

	public static final String MOD_ID = "clipify";
	public static final Logger LOGGER = ClipifyLog.LOGGER;

	private static volatile ReplayBufferService service;

	/** Signatures of hotkeys whose combo was already held last tick, so each press fires exactly once. */
	private final Set<String> downLastTick = new HashSet<>();

	@Override
	public void onInitializeClient() {
		ClipifyConfig config = ClipifyConfig.load();
		Minecraft client = Minecraft.getInstance();

		ReplayBufferService svc = new ReplayBufferService(client, config, FabricLoader.getInstance().getGameDir());
		service = svc;

		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
		// IMPORTANT: bootstrap() spawns FFmpeg subprocesses (including an NVENC probe that creates a
		// GPU context). onInitializeClient runs BEFORE Minecraft initialises GLFW/OpenGL, and doing
		// GPU-touching work concurrently with glfwInit intermittently breaks the NVIDIA driver's
		// context creation ("GLFW error before init"). CLIENT_STARTED fires only once the window and
		// GL context are fully up, so all of that heavy work now happens safely after init.
		ClientLifecycleEvents.CLIENT_STARTED.register(c -> {
			svc.bootstrap();
			DiscordPresence.start(config.discordPresence);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
			shutdown();
			DiscordPresence.stop();
		});

		LOGGER.info("Clipify loaded — {} hotkey(s), {}s max buffer (edit them in Clipify settings → Hotkeys)",
				config.hotkeys.size(), config.clipDurationSeconds);
	}

	/** The live service, or null before {@code onInitializeClient} has run. */
	public static ReplayBufferService service() {
		return service;
	}

	/**
	 * Called from {@link dev.clipify.mixin.RenderSystemMixin} on the render thread once per
	 * presented frame. Kept static and null-tolerant because the mixin can fire during early startup.
	 */
	public static void onFramePresented(int framebufferWidth, int framebufferHeight) {
		ReplayBufferService svc = service;
		if (svc != null) {
			svc.onFramePresented(framebufferWidth, framebufferHeight);
		}
	}

	// ------------------------------------------------------------- hotkeys

	private void onClientTick(Minecraft client) {
		ReplayBufferService svc = service;
		if (svc == null) {
			return;
		}
		// Fire in-game AND over most screens (so you can screenshot/clip a menu or chat). The one
		// exception is Clipify's own key-capturing screens, where a press is being *bound*, not fired.
		// Use a modifier combo for menu hotkeys so it doesn't collide with typing in chat.
		net.minecraft.client.gui.screens.Screen screen = client.screen;
		if (screen instanceof dev.clipify.ui.ClipifyConfigScreen
				|| screen instanceof dev.clipify.ui.AddHotkeyScreen
				|| screen instanceof dev.clipify.ui.AudioSettingsScreen) {
			downLastTick.clear();
			return;
		}

		List<Hotkey> hotkeys = svc.config().hotkeys;
		if (hotkeys == null || hotkeys.isEmpty()) {
			downLastTick.clear();
			return;
		}

		Set<String> downNow = new HashSet<>();
		for (Hotkey hk : hotkeys) {
			if (hk == null || !hk.isBound()) {
				continue;
			}
			if (!comboDown(client, hk)) {
				continue;
			}
			String sig = signature(hk);
			downNow.add(sig);
			if (!downLastTick.contains(sig)) {
				fire(client, svc, hk); // rising edge → treat as one "tap"
			}
		}
		downLastTick.clear();
		downLastTick.addAll(downNow);
	}

	private static boolean comboDown(Minecraft client, Hotkey hk) {
		return InputConstants.isKeyDown(client.getWindow(), hk.key)
				&& modifiersMatch(client, hk.ctrl, hk.shift, hk.alt);
	}

	private static void fire(Minecraft client, ReplayBufferService svc, Hotkey hk) {
		switch (hk.action) {
			case CLIP -> svc.saveClip(hk.durationSeconds);
			case SCREENSHOT -> dev.clipify.ui.Screenshotter.capture(client, svc.config());
			case OPEN_LAST_CLIP -> openLastClip(client, svc);
			case OPEN_FOLDER -> openClipsFolder(client, svc.config());
			case OPEN_SETTINGS -> client.setScreen(new dev.clipify.ui.ClipifyConfigScreen(client.screen));
		}
	}

	/** Opens the most recent saved clip straight in the editor, or toasts if there are none yet. */
	private static void openLastClip(Minecraft client, ReplayBufferService svc) {
		List<ClipLibrary.Clip> clips = ClipLibrary.list(svc.config().resolveOutputFolder());
		if (clips.isEmpty()) {
			Notifications.error(client, "No clips yet.");
			return;
		}
		client.setScreen(new dev.clipify.ui.ClipEditScreen(
				new dev.clipify.ui.ClipsScreen(client.screen), clips.get(0).file()));
	}

	/** Stable identity for edge detection: two identical combos share a signature (a harmless dup). */
	private static String signature(Hotkey hk) {
		return hk.action + ":" + hk.key + ":" + (hk.ctrl ? 1 : 0) + (hk.shift ? 1 : 0) + (hk.alt ? 1 : 0);
	}

	/**
	 * Modifiers are checked exactly: requiring Ctrl means a bare key press is ignored, and not
	 * requiring one means the Ctrl+key combo is ignored too, so two hotkeys sharing a key but
	 * differing in modifiers can never both fire.
	 */
	private static boolean modifiersMatch(Minecraft client, boolean ctrl, boolean shift, boolean alt) {
		var window = client.getWindow();
		boolean ctrlDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
				|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
		boolean shiftDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
				|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
		boolean altDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
				|| InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
		return ctrlDown == ctrl && shiftDown == shift && altDown == alt;
	}

	// ------------------------------------------------------------- hotkey labels (used by the UI)

	/** Localised name of a GLFW key code, or {@code "Unbound"} for {@code -1}/unknown. */
	public static String keyLabel(int glfwKey) {
		if (glfwKey == -1 || glfwKey == GLFW.GLFW_KEY_UNKNOWN) {
			return "Unbound";
		}
		return InputConstants.Type.KEYSYM.getOrCreate(glfwKey).getDisplayName().getString();
	}

	/** Human label for a whole combo, e.g. {@code "Ctrl+G"} or {@code "Unbound"}. */
	public static String hotkeyLabel(Hotkey hk) {
		if (hk == null || !hk.isBound()) {
			return "Unbound";
		}
		return modifierPrefix(hk.ctrl, hk.shift, hk.alt) + keyLabel(hk.key);
	}

	public static String modifierPrefix(boolean ctrl, boolean shift, boolean alt) {
		StringBuilder sb = new StringBuilder();
		if (ctrl) {
			sb.append("Ctrl+");
		}
		if (shift) {
			sb.append("Shift+");
		}
		if (alt) {
			sb.append("Alt+");
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- folder

	public static void openClipsFolder(Minecraft client, ClipifyConfig config) {
		Path dir = config.resolveOutputFolder();
		try {
			Files.createDirectories(dir);
			Util.getPlatform().openPath(dir);
		} catch (IOException e) {
			LOGGER.error("Could not open {}", dir, e);
			Notifications.error(client, "Could not open " + dir);
		}
	}

	// -------------------------------------------------------------- shutdown

	private static void shutdown() {
		ReplayBufferService svc = service;
		service = null;
		if (svc != null) {
			svc.shutdown();
		}
	}

	public static Component clipsFolderText(ClipifyConfig config) {
		return Component.literal(config.resolveOutputFolder().toString());
	}
}
