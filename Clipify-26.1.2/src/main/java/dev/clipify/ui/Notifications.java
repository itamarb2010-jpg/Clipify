package dev.clipify.ui;

import dev.clipify.encode.ClipAssembler;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Small toasts in the top-right corner, mirroring how Minecraft reports screenshots.
 *
 * <p>Every method is safe to call from any thread: the work is bounced onto the client thread with
 * {@link Minecraft#execute}. Reusing one {@link SystemToast.SystemToastId} per category means
 * repeated saves replace the existing toast instead of stacking up a column of them.
 */
public final class Notifications {

	private static final SystemToast.SystemToastId CLIP_TOAST = new SystemToast.SystemToastId();
	private static final SystemToast.SystemToastId ERROR_TOAST = new SystemToast.SystemToastId();

	private Notifications() {
	}

	public static void saving(Minecraft client, int seconds) {
		show(client, CLIP_TOAST,
				Component.translatable("clipify.toast.saving"),
				Component.translatable("clipify.toast.saving.detail", seconds));
	}

	public static void saved(Minecraft client, ClipAssembler.Result result) {
		int seconds = (int) Math.round(result.seconds());
		show(client, CLIP_TOAST,
				Component.translatable("clipify.toast.saved", seconds),
				Component.literal(result.file().getFileName().toString()));
	}

	public static void uploading(Minecraft client) {
		show(client, CLIP_TOAST,
				Component.translatable("clipify.toast.uploading"),
				Component.translatable("clipify.toast.uploading.detail"));
	}

	/** Copies the share link to the clipboard and shows it, so the player can paste it into Discord. */
	public static void shared(Minecraft client, String url) {
		if (client == null) {
			return;
		}
		client.execute(() -> {
			client.keyboardHandler.setClipboard(url);
			SystemToast.addOrUpdate(client.getToastManager(), CLIP_TOAST,
					Component.translatable("clipify.toast.shared"), Component.literal(url));
		});
	}

	public static void error(Minecraft client, String message) {
		show(client, ERROR_TOAST,
				Component.translatable("clipify.toast.failed").withStyle(ChatFormatting.RED),
				Component.literal(message == null ? "Unknown error" : truncate(message)));
	}

	public static void info(Minecraft client, Component title, Component detail) {
		show(client, CLIP_TOAST, title, detail);
	}

	private static void show(Minecraft client, SystemToast.SystemToastId id, Component title, Component detail) {
		if (client == null) {
			return;
		}
		client.execute(() -> SystemToast.addOrUpdate(client.getToastManager(), id, title, detail));
	}

	/** Toasts wrap to a couple of lines; a stack trace in there helps nobody. */
	private static String truncate(String message) {
		String single = message.replace('\n', ' ').strip();
		return single.length() <= 120 ? single : single.substring(0, 117) + "...";
	}
}
