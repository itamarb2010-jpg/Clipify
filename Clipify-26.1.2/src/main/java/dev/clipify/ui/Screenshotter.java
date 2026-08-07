package dev.clipify.ui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.clipify.ClipifyLog;
import dev.clipify.config.ClipifyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The Screenshot hotkey: grabs the current frame, writes it to the screenshots folder as a PNG, and
 * copies the image to the system clipboard (no upload, no link). The framebuffer grab runs on the
 * render thread; the clipboard copy is bounced onto a worker thread.
 *
 * <p>Minecraft runs the JVM <b>headless</b> ({@code java.awt.headless=true}), so Java's AWT clipboard
 * throws {@code HeadlessException}. The copy therefore shells out to an OS-native helper instead.
 */
public final class Screenshotter {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private Screenshotter() {
	}

	public static void capture(Minecraft client, ClipifyConfig config) {
		if (client == null) {
			return;
		}
		client.execute(() -> {
			try {
				Screenshot.takeScreenshot(client.getMainRenderTarget(), image -> onImage(client, config, image));
			} catch (Throwable t) {
				ClipifyLog.LOGGER.error("Screenshot capture failed", t);
				Notifications.error(client, "Screenshot failed: " + t.getMessage());
			}
		});
	}

	private static void onImage(Minecraft client, ClipifyConfig config, NativeImage image) {
		Path file;
		try {
			Path dir = config.resolveScreenshotFolder();
			Files.createDirectories(dir);
			file = uniquePath(dir);
		} catch (Throwable t) {
			image.close();
			ClipifyLog.LOGGER.error("Could not prepare screenshot", t);
			Notifications.error(client, "Could not save the screenshot.");
			return;
		}
		// Encode the PNG and copy off the render thread — encoding a 1440p+ image on it stutters the
		// game. The NativeImage is ours (takeScreenshot handed it over); the worker closes it when done.
		final Path saved = file;
		Thread worker = new Thread(() -> {
			boolean copied = false;
			try {
				image.writeToFile(saved);
				copied = copyToClipboard(saved);
			} catch (Throwable t) {
				ClipifyLog.LOGGER.error("Could not save screenshot", t);
				Notifications.error(client, "Could not save the screenshot.");
				return;
			} finally {
				image.close();
			}
			Notifications.info(client,
					Component.translatable(copied ? "clipify.toast.screenshot" : "clipify.toast.screenshot.saved"),
					Component.literal(saved.getFileName().toString()));
		}, "Clipify-screenshot");
		worker.setDaemon(true);
		worker.start();
	}

	private static Path uniquePath(Path dir) {
		String base = "screenshot-" + LocalDateTime.now().format(STAMP);
		Path candidate = dir.resolve(base + ".png");
		int i = 2;
		while (Files.exists(candidate) && i < 1000) {
			candidate = dir.resolve(base + "-" + i + ".png");
			i++;
		}
		return candidate;
	}

	/**
	 * Copies the PNG image onto the system clipboard via an OS-native helper (AWT is headless here).
	 * Windows: PowerShell + WinForms {@code Clipboard::SetImage}; macOS: {@code osascript}; Linux:
	 * {@code xclip}. Best-effort — returns false if the helper is missing or fails. Blocking, so it is
	 * called from a worker thread.
	 */
	static boolean copyToClipboard(Path png) {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String path = png.toAbsolutePath().toString();
		List<String> cmd;
		if (os.contains("win")) {
			String script = "Add-Type -AssemblyName System.Windows.Forms,System.Drawing;"
					+ "$i=[System.Drawing.Image]::FromFile('" + path.replace("'", "''") + "');"
					+ "[System.Windows.Forms.Clipboard]::SetImage($i);$i.Dispose()";
			cmd = List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-STA",
					"-WindowStyle", "Hidden", "-Command", script);
		} else if (os.contains("mac")) {
			cmd = List.of("osascript", "-e",
					"set the clipboard to (read (POSIX file \"" + path + "\") as «class PNGf»)");
		} else {
			cmd = List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-i", path);
		}
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return false;
			}
			return p.exitValue() == 0;
		} catch (Exception e) {
			ClipifyLog.LOGGER.warn("Clipboard image copy failed ({})", os, e);
			return false;
		}
	}
}
