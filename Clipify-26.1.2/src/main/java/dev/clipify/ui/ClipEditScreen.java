package dev.clipify.ui;

import dev.clipify.Clipify;
import dev.clipify.ClipifyLog;
import dev.clipify.ReplayBufferService;
import dev.clipify.encode.ClipLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A real video-editor UI: a media-player transport bar and a timeline (clip block + draggable trim
 * handles + playhead) drawn from a small design system with baked anti-aliased icons, plus standard
 * Minecraft buttons (with icons) for the file actions and a fullscreen preview. Playback/scrubbing
 * are handled by {@link VideoPlayer}; the timeline background is a {@link Filmstrip}.
 */
public final class ClipEditScreen extends Screen {

	private enum Grab { NONE, START, END, PLAYHEAD }

	private static final int PANEL = 0xFF16171B;
	private static final int BORDER = 0xFF33373F;
	private static final int TEXT = 0xFFE7E8EC;
	private static final int MUTED = 0xFF9AA0AA;
	private static final int FAINT = 0xFF5A5F6A;
	private static final int PLAYHEAD = 0xFFC8CCD4; // playhead (neutral gray, not green)
	private static final int HANDLE = 0xFFF0F0F2;   // white trim handles
	private static final int ICON_TEX = 48;

	private final ClipsScreen parent;
	private final Path file;

	private volatile double duration = -1;
	private double start;
	private double end;

	private VideoPlayer player;
	private Filmstrip strip;
	private Grab grab = Grab.NONE;

	private boolean fullscreen;
	private boolean fsSeeking;

	private volatile String status = "";
	private volatile boolean busy;
	private boolean confirmingDelete;

	private int cardLeft, cardW;
	private int previewLeft, previewTop, previewW, previewH;
	private int transY;
	private int rulerY;
	private int timelineLeft, timelineTop, timelineW, timelineH;
	private int toolTop;
	private int playX, playY, playSize;
	private int skipStartX, skipEndX, transIconY, skipSize, expandX;

	private final List<IconBtn> iconButtons = new ArrayList<>();

	private record IconBtn(Button widget, String icon) {
	}

	public ClipEditScreen(ClipsScreen parent, Path file) {
		super(Component.literal(file.getFileName().toString()));
		this.parent = parent;
		this.file = file;
	}

	private Path ffmpeg() {
		ReplayBufferService svc = Clipify.service();
		return svc != null && svc.ffmpegProvider().executable() != null
				? svc.ffmpegProvider().executable() : null;
	}

	// --------------------------------------------------------------------- layout

	private void computeLayout() {
		int cx = this.width / 2;
		cardW = Math.min(this.width - 40, 600);
		cardLeft = cx - cardW / 2;

		previewTop = 28;
		int reserveBelow = 224;
		int maxPreviewH = Math.max(120, this.height - previewTop - reserveBelow);
		previewW = Math.min(cardW, (int) (maxPreviewH * 16.0 / 9.0));
		previewH = (int) (previewW * 9.0 / 16.0);
		previewLeft = cx - previewW / 2;

		transY = previewTop + previewH + 10;
		playSize = 26;
		skipSize = 16;
		playX = cx - playSize / 2;
		playY = transY;
		transIconY = transY + (playSize - skipSize) / 2;
		int gap = 16;
		skipStartX = playX - gap - skipSize;
		skipEndX = playX + playSize + gap;
		expandX = previewLeft + previewW - skipSize;

		rulerY = transY + playSize + 12;
		timelineLeft = previewLeft;
		timelineW = previewW;
		timelineTop = rulerY + 26;
		timelineH = 30;

		toolTop = timelineTop + timelineH + 16;
	}

	@Override
	protected void init() {
		computeLayout();
		if (duration < 0) {
			probeDurationAsync();
		}
		ensurePlayer();

		iconButtons.clear();
		boolean canEdit = ffmpeg() != null && duration > 0 && !busy;
		ReplayBufferService svc = Clipify.service();
		boolean hasLink = svc != null && svc.hasLink(file);

		int gap = 6;
		int y = toolTop;
		int halfW = (cardW - gap) / 2;
		addIconButton(cardLeft, y, halfW, 20, "copy",
				Component.translatable("clipify.clips.save_copy"), canEdit, () -> trim(false));
		addIconButton(cardLeft + halfW + gap, y, cardW - halfW - gap, 20, "save",
				Component.translatable("clipify.clips.overwrite"), canEdit, () -> trim(true));

		y += 24;
		int qW = (cardW - 3 * gap) / 4;
		addIconButton(cardLeft, y, qW, 20, "link",
				Component.translatable(hasLink ? "clipify.clips.copy_link" : "clipify.clips.get_link"), true, this::onShareLink);
		addIconButton(cardLeft + (qW + gap), y, qW, 20, "folder",
				Component.literal("Files"), true, () -> Util.getPlatform().openPath(file.getParent()));
		addIconButton(cardLeft + 2 * (qW + gap), y, qW, 20, "trash",
				Component.literal(confirmingDelete ? "Delete?" : "Delete").withStyle(confirmingDelete ? ChatFormatting.RED : ChatFormatting.WHITE),
				!busy, this::onDelete);
		addIconButton(cardLeft + 3 * (qW + gap), y, cardW - 3 * (qW + gap), 20, "back",
				Component.literal("Back"), true, this::onClose);
	}

	private void addIconButton(int x, int y, int w, int h, String icon, Component label, boolean enabled, Runnable action) {
		Button b = Button.builder(label, btn -> action.run()).bounds(x, y, w, h).build();
		b.active = enabled;
		addRenderableWidget(b);
		iconButtons.add(new IconBtn(b, icon));
	}

	private void ensurePlayer() {
		if (duration > 0 && player == null && ffmpeg() != null) {
			player = new VideoPlayer(this.minecraft, ffmpeg(), file, duration);
			strip = new Filmstrip(this.minecraft, ffmpeg(), file, duration, 20);
		}
	}

	private void probeDurationAsync() {
		Path ff = ffmpeg();
		if (ff == null) {
			status = "FFmpeg isn't ready yet.";
			return;
		}
		Thread t = new Thread(() -> {
			double d = ClipLibrary.durationSeconds(ff, file);
			this.minecraft.execute(() -> {
				this.duration = d > 0 ? d : 0;
				if (this.end <= 0) {
					this.end = this.duration;
				}
				if (this.minecraft.screen == this) {
					ensurePlayer();
					rebuildWidgets();
				}
			});
		}, "Clipify-probe");
		t.setDaemon(true);
		t.start();
	}

	private int timeToX(double t) {
		return timelineLeft + (int) Math.round(clamp(t, 0, duration) / duration * timelineW);
	}

	private double xToTime(double x) {
		return clamp((x - timelineLeft) / (double) timelineW, 0, 1) * duration;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** GUI units → the real pixels they cover, which is the detail the preview can actually show. */
	private int realPixels(int guiUnits) {
		return (int) Math.ceil(guiUnits * (double) this.minecraft.getWindow().getGuiScale());
	}

	// ------------------------------------------------------------------- actions

	private void onShareLink() {
		ReplayBufferService svc = Clipify.service();
		if (svc != null) {
			svc.shareOrCopy(file, m -> {
				status = m;
				if (this.minecraft.screen == this) {
					rebuildWidgets();
				}
			});
		}
	}

	private void trim(boolean overwrite) {
		Path ff = ffmpeg();
		if (ff == null || duration <= 0 || busy) {
			return;
		}
		busy = true;
		status = "Trimming…";
		if (overwrite) {
			// The preview decoder reads this very file and FFmpeg keeps its input open without
			// FILE_SHARE_DELETE, so on Windows the finished trim could not be moved over the
			// original while the player was alive — the save failed for no reason the user could
			// see. Close the media first, exactly as deleting does; finishTrim() re-probes the new
			// file and brings the preview back.
			disposeMedia();
		}
		rebuildWidgets();
		double s = start;
		double e = end;
		Thread t = new Thread(() -> {
			Path dir = file.getParent();
			Path tmp = overwrite ? dir.resolve(file.getFileName().toString() + ".trim.tmp.mp4") : null;
			try {
				if (overwrite) {
					ClipLibrary.trim(ff, file, s, e, tmp);
					ClipLibrary.replace(tmp, file);
					ReplayBufferService svc = Clipify.service();
					if (svc != null) {
						svc.forgetLink(file);
					}
					finishTrim("Saved (overwritten).");
				} else {
					Path dest = ClipLibrary.newClipPath(dir, "-trim");
					ClipLibrary.trim(ff, file, s, e, dest);
					finishTrim("Saved a trimmed copy.");
				}
			} catch (Exception ex) {
				ClipifyLog.LOGGER.error("Clipify: saving the trimmed clip failed", ex);
				if (tmp != null) {
					// Otherwise a failed save leaves a full-size half-clip in the clips folder.
					ClipLibrary.deleteQuietly(tmp);
				}
				this.minecraft.execute(() -> {
					busy = false;
					status = "Trim failed: " + ex.getMessage();
					ensurePlayer(); // closed above for the overwrite; put the preview back
					if (this.minecraft.screen == this) {
						rebuildWidgets();
					}
				});
			}
		}, "Clipify-trim");
		t.setDaemon(true);
		t.start();
	}

	private void finishTrim(String message) {
		this.minecraft.execute(() -> {
			busy = false;
			status = message;
			disposeMedia();
			this.duration = -1;
			this.start = 0;
			this.end = 0;
			probeDurationAsync();
			if (this.minecraft.screen == this) {
				rebuildWidgets();
			}
		});
	}

	private void onDelete() {
		if (busy) {
			return;
		}
		if (!confirmingDelete) {
			confirmingDelete = true;
			rebuildWidgets();
			return;
		}
		busy = true;
		status = "Deleting…";
		disposeMedia();
		rebuildWidgets();
		Path target = file;
		Thread t = new Thread(() -> {
			boolean ok = ClipLibrary.deleteWithRetry(target);
			this.minecraft.execute(() -> {
				if (ok) {
					onClose();
				} else {
					busy = false;
					confirmingDelete = false;
					status = "Couldn't delete — the file is still in use.";
					if (this.minecraft.screen == this) {
						rebuildWidgets();
					}
				}
			});
		}, "Clipify-delete");
		t.setDaemon(true);
		t.start();
	}

	// ---------------------------------------------------------------------- mouse

	private static boolean in(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx <= x + w && my >= y && my <= y + h;
	}

	private boolean overPlay(double mx, double my) {
		return in(mx, my, playX, playY, playSize, playSize);
	}

	private int fsBarY() {
		return this.height - 46;
	}

	private int fsSeekLeft() {
		return 24;
	}

	private int fsSeekW() {
		return this.width - 48;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		double mx = event.x();
		double my = event.y();

		if (fullscreen) {
			int cX = this.width - 24 - 18;
			if (in(mx, my, cX, this.height - 32, 18, 18)) {
				fullscreen = false;
				return true;
			}
			if (in(mx, my, 24, this.height - 33, 20, 20)) {
				if (player != null) {
					player.togglePlay();
				}
				return true;
			}
			if (duration > 0 && my >= fsBarY() - 6 && my <= fsBarY() + 8
					&& mx >= fsSeekLeft() - 6 && mx <= fsSeekLeft() + fsSeekW() + 6) {
				fsSeeking = true;
				fsSeekTo(mx);
				return true;
			}
			if (player != null && my < fsBarY() - 10) {
				player.togglePlay();
			}
			return true;
		}

		computeLayout();
		if (player != null && overPlay(mx, my)) {
			player.togglePlay();
			return true;
		}
		if (in(mx, my, expandX, transIconY, skipSize, skipSize)) {
			fullscreen = true;
			return true;
		}
		if (player != null && in(mx, my, skipStartX, transIconY, skipSize, skipSize)) {
			player.seek(start);
			return true;
		}
		if (player != null && in(mx, my, skipEndX, transIconY, skipSize, skipSize)) {
			player.seek(end);
			return true;
		}
		if (duration > 0 && my >= timelineTop - 10 && my <= timelineTop + timelineH + 10
				&& mx >= timelineLeft - 8 && mx <= timelineLeft + timelineW + 8) {
			int sx = timeToX(start);
			int ex = timeToX(end);
			double dS = Math.abs(mx - sx);
			double dE = Math.abs(mx - ex);
			grab = (dS <= 8 && dS <= dE) ? Grab.START : (dE <= 8 ? Grab.END : Grab.PLAYHEAD);
			applyGrab(mx);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (event.button() == 0) {
			if (fsSeeking) {
				fsSeekTo(event.x());
				return true;
			}
			if (grab != Grab.NONE) {
				applyGrab(event.x());
				return true;
			}
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == 0 && (grab != Grab.NONE || fsSeeking)) {
			grab = Grab.NONE;
			fsSeeking = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (fullscreen && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			fullscreen = false;
			return true;
		}
		return super.keyPressed(event);
	}

	private void fsSeekTo(double mx) {
		if (player != null && duration > 0) {
			player.seek(clamp((mx - fsSeekLeft()) / fsSeekW(), 0, 1) * duration);
		}
	}

	private void applyGrab(double mx) {
		double t = xToTime(mx);
		switch (grab) {
			case START -> {
				start = clamp(t, 0, Math.max(0, end - 0.1));
				if (player != null) {
					player.seek(start);
				}
			}
			case END -> {
				end = clamp(t, Math.min(duration, start + 0.1), duration);
				if (player != null) {
					player.seek(end);
				}
			}
			case PLAYHEAD -> {
				if (player != null) {
					player.seek(t);
				}
			}
			default -> {
			}
		}
	}

	// --------------------------------------------------------------------- render

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		computeLayout();
		if (player != null) {
			player.tick();
		}
		for (IconBtn b : iconButtons) {
			b.widget().visible = !fullscreen;
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		if (fullscreen) {
			renderFullscreen(graphics, mouseX, mouseY);
			return;
		}

		graphics.text(this.font, this.title, cardLeft, 12, TEXT, true);

		renderPreview(graphics);
		renderTransport(graphics, mouseX, mouseY);
		if (duration > 0) {
			renderRuler(graphics);
			renderTimeline(graphics, mouseX, mouseY);
		}
		for (IconBtn b : iconButtons) {
			Button w = b.widget();
			int lw = this.font.width(w.getMessage());
			int tx = w.getX() + (w.getWidth() - lw) / 2;
			int iy = w.getY() + (w.getHeight() - 11) / 2;
			icon(graphics, b.icon(), tx - 14, iy, 11, w.active ? TEXT : FAINT);
		}
		if (!status.isEmpty()) {
			graphics.centeredText(this.font, Component.literal(status),
					this.width / 2, this.height - 12, busy ? 0xFFFFD070 : MUTED);
		}
	}

	private void renderFullscreen(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.fill(0, 0, this.width, this.height, 0xFF000000);
		if (player != null && player.hasFrame()) {
			int tw = player.texWidth();
			int th = player.texHeight();
			float sc = Math.min((float) this.width / tw, (float) (this.height - 20) / th);
			int dw = Math.max(1, Math.round(tw * sc));
			int dh = Math.max(1, Math.round(th * sc));
			int dx = this.width / 2 - dw / 2;
			int dy = (this.height - 20) / 2 - dh / 2;
			player.setDisplayWidth(realPixels(dw));
			graphics.blit(RenderPipelines.GUI_TEXTURED, player.textureId(), dx, dy, 0f, 0f, dw, dh, tw, th, tw, th);
		} else {
			graphics.centeredText(this.font, Component.literal("Loading…"), this.width / 2, this.height / 2, FAINT);
		}
		graphics.fill(0, this.height - 52, this.width, this.height, 0x99000000);
		if (duration > 0) {
			int sy = fsBarY();
			int sl = fsSeekLeft();
			int sw = fsSeekW();
			graphics.fill(sl, sy, sl + sw, sy + 3, 0xFF3A3D46);
			double t = player != null ? player.time() : 0;
			int px = sl + (int) Math.round(clamp(t / duration, 0, 1) * sw);
			graphics.fill(sl, sy, px, sy + 3, 0xFFD0D4DC);
			graphics.fill(px - 2, sy - 3, px + 3, sy + 6, 0xFFFFFFFF);
		}
		boolean playing = player != null && player.isPlaying();
		boolean ph = in(mouseX, mouseY, 24, this.height - 33, 20, 20);
		icon(graphics, playing ? "pause" : "play", 24, this.height - 33, 20, ph ? 0xFFFFFFFF : MUTED);
		double t = player != null ? player.time() : 0;
		String time = fmtClockMs(t) + " / " + fmtClock(Math.max(0, duration));
		graphics.text(this.font, Component.literal(time), 50, this.height - 27, MUTED, true);
		int cX = this.width - 24 - 18;
		boolean ch = in(mouseX, mouseY, cX, this.height - 32, 18, 18);
		icon(graphics, "expand", cX, this.height - 32, 18, ch ? 0xFFFFFFFF : MUTED);
	}

	private void renderPreview(GuiGraphicsExtractor graphics) {
		panel(graphics, previewLeft - 4, previewTop - 4, previewW + 8, previewH + 8, 8, PANEL, BORDER);
		graphics.fill(previewLeft, previewTop, previewLeft + previewW, previewTop + previewH, 0xFF000000);

		// While dragging, fall back to the filmstrip only until the real frame for this spot arrives —
		// a moment later it takes over, so scrubbing stays instant without staying soft.
		boolean shown = false;
		if (grab != Grab.NONE && strip != null
				&& (player == null || !player.hasFrameNear(player.time(), 0.15))) {
			shown = strip.drawFrameAt(graphics, player != null ? player.time() : 0,
					previewLeft, previewTop, previewW, previewH);
		}
		if (shown) {
			return;
		}
		if (player != null && player.hasFrame()) {
			int tw = player.texWidth();
			int th = player.texHeight();
			float sc = Math.min((float) previewW / tw, (float) previewH / th);
			int dw = Math.max(1, Math.round(tw * sc));
			int dh = Math.max(1, Math.round(th * sc));
			int dx = this.width / 2 - dw / 2;
			int dy = previewTop + (previewH - dh) / 2;
			player.setDisplayWidth(realPixels(dw));
			graphics.blit(RenderPipelines.GUI_TEXTURED, player.textureId(), dx, dy, 0f, 0f, dw, dh, tw, th, tw, th);
		} else {
			String msg = duration < 0 ? "Loading…" : (ffmpeg() == null ? "FFmpeg not ready" : "Decoding…");
			graphics.centeredText(this.font, Component.literal(msg), this.width / 2, previewTop + previewH / 2 - 4, FAINT);
		}
	}

	private void renderTransport(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		boolean playing = player != null && player.isPlaying();
		icon(graphics, "skip_start", skipStartX, transIconY, skipSize,
				in(mouseX, mouseY, skipStartX, transIconY, skipSize, skipSize) ? TEXT : MUTED);
		icon(graphics, "skip_end", skipEndX, transIconY, skipSize,
				in(mouseX, mouseY, skipEndX, transIconY, skipSize, skipSize) ? TEXT : MUTED);
		boolean ph = overPlay(mouseX, mouseY);
		int ps = ph ? playSize + 2 : playSize; // subtle grow on hover
		int po = (playSize - ps) / 2;
		icon(graphics, playing ? "pause_btn" : "play_btn", playX + po, playY + po, ps, 0xFFFFFFFF);

		double t = player != null ? player.time() : 0;
		String time = duration < 0 ? "--:-- / --:--" : fmtClockMs(t) + " / " + fmtClock(Math.max(0, duration));
		graphics.text(this.font, Component.literal(time), previewLeft, transY + (playSize - 8) / 2, MUTED, true);
		boolean eh = in(mouseX, mouseY, expandX, transIconY, skipSize, skipSize);
		icon(graphics, "expand", expandX, transIconY, skipSize, eh ? TEXT : MUTED);
	}

	private void renderRuler(GuiGraphicsExtractor graphics) {
		double major = niceStep(duration / 12.0);
		double fine = major / 10.0;
		int tickTop = rulerY + 12;
		int n = (int) Math.floor(duration / fine + 1e-6);
		for (int i = 0; i <= n; i++) {
			double t = i * fine;
			int x = timeToX(t);
			boolean maj = i % 10 == 0;
			boolean mid = i % 5 == 0;
			int h = maj ? 9 : (mid ? 5 : 3);
			int col = maj ? 0xFFAEB2BA : (mid ? 0xFF70747E : 0xFF50545E);
			graphics.fill(x, tickTop, x + 1, tickTop + h, col);
			if (maj) {
				String lbl = fmtClock(t);
				int lw = this.font.width(lbl);
				int lx = Math.min(Math.max(x - lw / 2, timelineLeft), timelineLeft + timelineW - lw);
				graphics.text(this.font, Component.literal(lbl), lx, rulerY, 0xFFC2C6CE, true);
			}
		}
	}

	private void renderTimeline(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int tLeft = timelineLeft;
		int tRight = timelineLeft + timelineW;
		int tTop = timelineTop;
		int tBot = timelineTop + timelineH;

		panel(graphics, tLeft - 2, tTop - 2, timelineW + 4, timelineH + 4, 6, PANEL, BORDER);
		if (strip != null) {
			strip.draw(graphics, tLeft, tTop, timelineW, timelineH);
		} else {
			graphics.fill(tLeft, tTop, tRight, tBot, 0xFF101216);
		}

		int sx = timeToX(start);
		int ex = timeToX(end);
		if (sx > tLeft) {
			graphics.fill(tLeft, tTop, sx, tBot, 0xB4000000);
		}
		if (ex < tRight) {
			graphics.fill(ex, tTop, tRight, tBot, 0xB4000000);
		}

		boolean overTl = mouseX >= tLeft - 8 && mouseX <= tRight + 8 && mouseY >= tTop - 12 && mouseY <= tBot + 6;
		if (grab == Grab.NONE && overTl) {
			int hx = Math.max(tLeft, Math.min(tRight, mouseX));
			graphics.fill(hx, tTop, hx + 1, tBot, 0x55FFFFFF);
			drawTimeBubble(graphics, hx, tTop - 4, fmtClockMs(xToTime(hx)));
		}

		boolean hovS = overTl && Math.abs(mouseX - sx) <= 8 && Math.abs(mouseX - sx) <= Math.abs(mouseX - ex);
		boolean hovE = overTl && Math.abs(mouseX - ex) <= 8 && !hovS;
		drawHandle(graphics, sx, tTop, tBot, grab == Grab.START || hovS);
		drawHandle(graphics, ex, tTop, tBot, grab == Grab.END || hovE);

		double t = player != null ? player.time() : 0;
		int px = timeToX(t);
		drawPlayhead(graphics, px, rulerY + 12, tBot + 3);

		if (grab == Grab.START) {
			drawTimeBubble(graphics, sx, tTop - 4, fmtClockMs(start));
		} else if (grab == Grab.END) {
			drawTimeBubble(graphics, ex, tTop - 4, fmtClockMs(end));
		} else if (grab == Grab.PLAYHEAD) {
			drawTimeBubble(graphics, px, tTop - 4, fmtClockMs(t));
		}
	}

	private void drawHandle(GuiGraphicsExtractor graphics, int x, int top, int bottom, boolean active) {
		int hw = active ? 6 : 5;
		roundRect(graphics, x - hw, top - 3, 2 * hw, (bottom + 3) - (top - 3), 4, active ? 0xFFFFFFFF : HANDLE);
		int cy = (top + bottom) / 2;
		graphics.fill(x - 1, cy - 7, x + 1, cy + 7, 0xFF88909A);
	}

	private void drawPlayhead(GuiGraphicsExtractor graphics, int px, int top, int bottom) {
		graphics.fill(px - 2, top, px + 3, bottom, 0x66000000);
		graphics.fill(px - 1, top, px + 1, bottom, PLAYHEAD);
		roundRect(graphics, px - 4, top - 3, 8, 8, 2, PLAYHEAD);
	}

	private void drawTimeBubble(GuiGraphicsExtractor graphics, int cx, int bottomY, String text) {
		int tw = this.font.width(text);
		int w = tw + 8;
		int x0 = Math.max(2, Math.min(this.width - w - 2, cx - w / 2));
		int y0 = bottomY - 13;
		roundRect(graphics, x0, y0, w, 13, 3, 0xF0000000);
		graphics.text(this.font, Component.literal(text), x0 + 4, y0 + 3, TEXT, true);
	}

	private void icon(GuiGraphicsExtractor graphics, String name, int x, int y, int size, int color) {
		Identifier id = Identifier.fromNamespaceAndPath("clipify", "textures/gui/icons/" + name + ".png");
		graphics.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX, color);
	}

	private void roundRect(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int r, int color) {
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r <= 0) {
			graphics.fill(x, y, x + w, y + h, color);
			return;
		}
		graphics.fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			int offset = r - i;
			int inset = r - (int) Math.round(Math.sqrt(Math.max(0, (double) r * r - (double) offset * offset)));
			graphics.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			graphics.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}

	private void panel(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int r, int fill, int border) {
		roundRect(graphics, x, y, w, h, r, border);
		roundRect(graphics, x + 1, y + 1, w - 2, h - 2, r - 1, fill);
	}

	private static String fmtClock(double seconds) {
		int s = (int) Math.floor(Math.max(0, seconds));
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	private static String fmtClockMs(double seconds) {
		double s = Math.max(0, seconds);
		int m = (int) (s / 60);
		return String.format(Locale.ROOT, "%d:%04.1f", m, s - m * 60);
	}

	private static double niceStep(double target) {
		double[] steps = {1, 2, 5, 10, 15, 20, 30, 60, 120, 300};
		for (double s : steps) {
			if (s >= target) {
				return s;
			}
		}
		return 600;
	}

	private void disposeMedia() {
		if (player != null) {
			player.close();
			player = null;
		}
		if (strip != null) {
			strip.close();
			strip = null;
		}
	}

	@Override
	public void removed() {
		disposeMedia();
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
