package dev.clipify.ui;

import dev.clipify.ClipifyLog;
import dev.clipify.encode.ClipLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A minimal screenshot viewer — just shows the PNG with Copy / Open folder / Delete. Opened from the
 * Clips screen when the selected item is a screenshot (clips open the full editor instead). No player,
 * trim or save/save-as-copy: it's an image, not a video.
 */
public final class ScreenshotViewScreen extends Screen {

	private final Screen parent;
	private final Path file;
	private PreviewTexture texture;
	private String status = "";
	private boolean confirmingDelete;

	public ScreenshotViewScreen(Screen parent, Path file) {
		super(Component.literal(file.getFileName().toString()));
		this.parent = parent;
		this.file = file;
	}

	@Override
	protected void init() {
		if (texture == null) {
			texture = new PreviewTexture(this.minecraft);
			try {
				texture.set(Files.readAllBytes(file));
			} catch (Exception e) {
				ClipifyLog.LOGGER.warn("Could not load screenshot {}", file, e);
			}
		}
		int bw = 110;
		int gap = 6;
		int total = bw * 4 + gap * 3;
		int x = this.width / 2 - total / 2;
		int y = this.height - 28;
		addRenderableWidget(Button.builder(Component.translatable("clipify.screenshot.copy"), b -> copy())
				.bounds(x, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("clipify.clips.open_folder"), b -> openFolder())
				.bounds(x + (bw + gap), y, bw, 20).build());
		addRenderableWidget(Button.builder(deleteLabel(), b -> delete())
				.bounds(x + (bw + gap) * 2, y, bw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(x + (bw + gap) * 3, y, bw, 20).build());
	}

	private Component deleteLabel() {
		return confirmingDelete
				? Component.translatable("clipify.clips.delete_confirm").withStyle(ChatFormatting.RED)
				: Component.translatable("clipify.clips.delete");
	}

	private void copy() {
		status = "";
		Thread t = new Thread(() -> {
			boolean ok = Screenshotter.copyToClipboard(file);
			setStatus(ok ? "Copied to clipboard" : "Copy failed");
		}, "Clipify-screenshot-copy");
		t.setDaemon(true);
		t.start();
	}

	private void openFolder() {
		try {
			Util.getPlatform().openPath(file.getParent());
		} catch (Exception e) {
			ClipifyLog.LOGGER.warn("Could not open screenshots folder", e);
		}
	}

	private void delete() {
		if (!confirmingDelete) {
			confirmingDelete = true;
			rebuildWidgets();
			return;
		}
		Path f = file;
		Thread t = new Thread(() -> {
			ClipLibrary.deleteWithRetry(f);
			this.minecraft.execute(this::onClose);
		}, "Clipify-screenshot-delete");
		t.setDaemon(true);
		t.start();
	}

	private void setStatus(String s) {
		this.minecraft.execute(() -> this.status = s);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		int areaTop = 14;
		int areaBottom = this.height - 34;
		int areaH = Math.max(1, areaBottom - areaTop);
		int areaW = this.width - 16;
		if (texture != null && texture.ready()) {
			int iw = texture.width();
			int ih = texture.height();
			double scale = Math.min((double) areaW / iw, (double) areaH / ih);
			int dw = Math.max(1, (int) (iw * scale));
			int dh = Math.max(1, (int) (ih * scale));
			int dx = this.width / 2 - dw / 2;
			int dy = areaTop + (areaH - dh) / 2;
			graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(), dx, dy, 0f, 0f, dw, dh, iw, ih, iw, ih);
		} else {
			graphics.centeredText(this.font,
					Component.translatable("clipify.screenshot.missing").withStyle(ChatFormatting.GRAY),
					this.width / 2, this.height / 2, 0xFFA0A0A0);
		}
		if (!status.isEmpty()) {
			graphics.centeredText(this.font, Component.literal(status), this.width / 2, this.height - 42, 0xFF80FF80);
		}
	}

	@Override
	public void removed() {
		if (texture != null) {
			texture.dispose();
			texture = null;
		}
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
