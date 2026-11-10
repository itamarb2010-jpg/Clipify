package dev.clipify.ui;

import dev.clipify.ReplayBufferService;
import dev.clipify.encode.FfmpegProvider;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
	private final List<OrderedText> lines = new ArrayList<>();
	private int textTop;

	public FfmpegConsentScreen(Screen parent, ReplayBufferService service) {
		super(Text.literal("Clipify needs FFmpeg"));
		this.parent = parent;
		this.service = service;
	}

	@Override
	protected void init() {
		int textW = Math.min(W + 40, this.width - 40);
		lines.clear();
		long mb = FfmpegProvider.downloadMegabytes();
		String exe = FfmpegProvider.isWindows() ? "ffmpeg.exe" : "ffmpeg";
		addParagraph(Text.literal("Clipify records your clips with FFmpeg, a free video encoder. "
				+ "It isn't installed in this Minecraft instance yet."), textW);
		addBlank();
		addParagraph(Text.literal("Download: ").formatted(Formatting.GRAY)
				.append(Text.literal((mb > 0 ? "about " + mb + " MB" : "FFmpeg")
						+ " from " + FfmpegProvider.downloadSource()).formatted(Formatting.WHITE)), textW);
		addParagraph(Text.literal("Saved to: ").formatted(Formatting.GRAY)
				.append(Text.literal("clipify/bin in this instance's folder").formatted(Formatting.WHITE)), textW);
		addBlank();
		addParagraph(Text.literal("Nothing is downloaded unless you click Download. Already have FFmpeg? "
				+ "Put " + exe + " in that folder instead.").formatted(Formatting.GRAY), textW);

		int blockH = 20 + lines.size() * 10 + 12 + 20;
		int top = Math.max(10, (this.height - blockH) / 2);
		textTop = top + 20;
		int buttonsY = textTop + lines.size() * 10 + 12;

		int half = W / 2 - 2;
		int x = this.width / 2 - W / 2;
		String dl = mb > 0 ? "Download FFmpeg (" + mb + " MB)" : "Download FFmpeg";
		addDrawableChild(ButtonWidget.builder(Text.literal(dl), b -> answer(true))
				.dimensions(x, buttonsY, half, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Don't download"), b -> answer(false))
				.dimensions(x + W - half, buttonsY, half, 20).build());
	}

	private void addParagraph(Text text, int width) {
		lines.addAll(this.textRenderer.wrapLines(text, width));
	}

	private void addBlank() {
		lines.add(OrderedText.EMPTY);
	}

	private void answer(boolean allow) {
		service.answerFfmpegConsent(allow);
		if (allow) {
			Notifications.info(this.client, Text.literal("Downloading FFmpeg"),
					Text.literal("Clipping starts as soon as it's done."));
		}
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false; // an answer is needed; Esc would leave the question hanging
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, textTop - 20, 0xFFFFFFFF);
		int y = textTop;
		for (OrderedText line : lines) {
			context.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, y, 0xFFFFFFFF);
			y += 10;
		}
	}
}
