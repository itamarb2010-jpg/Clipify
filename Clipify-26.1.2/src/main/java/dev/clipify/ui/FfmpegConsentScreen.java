package dev.clipify.ui;

import dev.clipify.ReplayBufferService;
import dev.clipify.encode.FfmpegProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks, once per instance, before Clipify downloads FFmpeg. Shown the first time the title screen
 * opens in an instance with no FFmpeg, and again whenever a clip is requested while FFmpeg is still
 * missing. Nothing is downloaded unless the player clicks Download (Modrinth's consent rule).
 */
public final class FfmpegConsentScreen extends Screen {

	private static final int W = 300;

	private final Screen parent;
	private final ReplayBufferService service;
	private final List<FormattedCharSequence> lines = new ArrayList<>();
	private int textTop;

	public FfmpegConsentScreen(Screen parent, ReplayBufferService service) {
		super(Component.literal("Clipify needs FFmpeg"));
		this.parent = parent;
		this.service = service;
	}

	@Override
	protected void init() {
		int textW = Math.min(W + 40, this.width - 40);
		lines.clear();
		long mb = FfmpegProvider.downloadMegabytes();
		String exe = FfmpegProvider.isWindows() ? "ffmpeg.exe" : "ffmpeg";
		addParagraph(Component.literal("Clipify records your clips with FFmpeg, a free video encoder. "
				+ "It isn't installed in this Minecraft instance yet."), textW);
		addBlank();
		addParagraph(Component.literal("Download: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal((mb > 0 ? "about " + mb + " MB" : "FFmpeg")
						+ " from " + FfmpegProvider.downloadSource()).withStyle(ChatFormatting.WHITE)), textW);
		addParagraph(Component.literal("Saved to: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal("clipify/bin in this instance's folder").withStyle(ChatFormatting.WHITE)), textW);
		addBlank();
		addParagraph(Component.literal("Nothing is downloaded unless you click Download. Already have FFmpeg? "
				+ "Put " + exe + " in that folder instead.").withStyle(ChatFormatting.GRAY), textW);

		int blockH = 20 + lines.size() * 10 + 12 + 20;
		int top = Math.max(10, (this.height - blockH) / 2);
		textTop = top + 20;
		int buttonsY = textTop + lines.size() * 10 + 12;

		int half = W / 2 - 2;
		int x = this.width / 2 - W / 2;
		String dl = mb > 0 ? "Download FFmpeg (" + mb + " MB)" : "Download FFmpeg";
		addRenderableWidget(Button.builder(Component.literal(dl), b -> answer(true))
				.bounds(x, buttonsY, half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Don't download"), b -> answer(false))
				.bounds(x + W - half, buttonsY, half, 20).build());
	}

	private void addParagraph(Component text, int width) {
		lines.addAll(this.font.split(text, width));
	}

	private void addBlank() {
		lines.add(FormattedCharSequence.EMPTY);
	}

	private void answer(boolean allow) {
		service.answerFfmpegConsent(allow);
		if (allow) {
			Notifications.info(this.minecraft, Component.literal("Downloading FFmpeg"),
					Component.literal("Clipping starts as soon as it's done."));
		}
		this.minecraft.setScreen(parent);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false; // an answer is needed; Esc would leave the question hanging
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, textTop - 20, 0xFFFFFFFF);
		int y = textTop;
		for (FormattedCharSequence line : lines) {
			graphics.centeredText(this.font, line, this.width / 2, y, 0xFFFFFFFF);
			y += 10;
		}
	}
}
