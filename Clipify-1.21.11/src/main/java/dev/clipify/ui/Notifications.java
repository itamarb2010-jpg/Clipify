package dev.clipify.ui;

import dev.clipify.encode.ClipAssembler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Small toasts in the top-right corner, mirroring how Minecraft reports screenshots.
 *
 * <p>Every method is safe to call from any thread: the work is bounced onto the client thread with
 * {@link MinecraftClient#execute}. Reusing one {@link SystemToast.Type} per category means repeated
 * saves replace the existing toast instead of stacking up a column of them.
 */
public final class Notifications {

	private static final SystemToast.Type CLIP_TOAST = new SystemToast.Type(4000L);
	private static final SystemToast.Type ERROR_TOAST = new SystemToast.Type(8000L);

	private Notifications() {
	}

	public static void saving(MinecraftClient client, int seconds) {
		show(client, CLIP_TOAST,
				Text.translatable("clipify.toast.saving"),
				Text.translatable("clipify.toast.saving.detail", seconds));
	}

	public static void saved(MinecraftClient client, ClipAssembler.Result result) {
		int seconds = (int) Math.round(result.seconds());
		show(client, CLIP_TOAST,
				Text.translatable("clipify.toast.saved", seconds),
				Text.literal(result.file().getFileName().toString()));
	}

	public static void uploading(MinecraftClient client) {
		show(client, CLIP_TOAST,
				Text.translatable("clipify.toast.uploading"),
				Text.translatable("clipify.toast.uploading.detail"));
	}

	/** Copies the share link to the clipboard and shows it, so the player can paste it into Discord. */
	public static void shared(MinecraftClient client, String url) {
		if (client == null) {
			return;
		}
		client.execute(() -> {
			client.keyboard.setClipboard(url);
			SystemToast.show(client.getToastManager(), CLIP_TOAST,
					Text.translatable("clipify.toast.shared"), Text.literal(url));
		});
	}

	public static void error(MinecraftClient client, String message) {
		show(client, ERROR_TOAST,
				Text.translatable("clipify.toast.failed").formatted(Formatting.RED),
				Text.literal(message == null ? "Unknown error" : truncate(message)));
	}

	public static void info(MinecraftClient client, Text title, Text detail) {
		show(client, CLIP_TOAST, title, detail);
	}

	private static void show(MinecraftClient client, SystemToast.Type type, Text title, Text detail) {
		if (client == null) {
			return;
		}
		client.execute(() -> SystemToast.show(client.getToastManager(), type, title, detail));
	}

	/** Toasts wrap to a couple of lines; a stack trace in there helps nobody. */
	private static String truncate(String message) {
		String single = message.replace('\n', ' ').strip();
		return single.length() <= 120 ? single : single.substring(0, 117) + "...";
	}
}
