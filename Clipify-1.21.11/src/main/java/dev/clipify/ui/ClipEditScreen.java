package dev.clipify.ui;

import dev.clipify.Clipify;
import dev.clipify.ReplayBufferService;
import dev.clipify.encode.ClipLibrary;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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

	// layout
	private int cardLeft, cardW;
	private int previewLeft, previewTop, previewW, previewH;
	private int transY;
	private int rulerY;
	private int timelineLeft, timelineTop, timelineW, timelineH;
	private int toolTop;
	private int playX, playY, playSize;
	private int skipStartX, skipEndX, transIconY, skipSize, expandX;

	private final List<IconBtn> iconButtons = new ArrayList<>();

	private record IconBtn(ButtonWidget widget, String icon) {
	}

	public ClipEditScreen(ClipsScreen parent, Path file) {
		super(Text.literal(file.getFileName().toString()));
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

	// ----------------------------------------------------------------------- init

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
				Text.translatable("clipify.clips.save_copy"), canEdit, () -> trim(false));
		addIconButton(cardLeft + halfW + gap, y, cardW - halfW - gap, 20, "save",
				Text.translatable("clipify.clips.overwrite"), canEdit, () -> trim(true));

		y += 24;
		int qW = (cardW - 3 * gap) / 4;
		addIconButton(cardLeft, y, qW, 20, "link",
				Text.translatable(hasLink ? "clipify.clips.copy_link" : "clipify.clips.get_link"), true, this::onShareLink);
		addIconButton(cardLeft + (qW + gap), y, qW, 20, "folder",
				Text.literal("Files"), true, () -> Util.getOperatingSystem().open(file.getParent().toFile()));
		addIconButton(cardLeft + 2 * (qW + gap), y, qW, 20, "trash",
				Text.literal(confirmingDelete ? "Delete?" : "Delete").formatted(confirmingDelete ? Formatting.RED : Formatting.WHITE),
				!busy, this::onDelete);
		addIconButton(cardLeft + 3 * (qW + gap), y, cardW - 3 * (qW + gap), 20, "back",
				Text.literal("Back"), true, this::close);
	}

	private void addIconButton(int x, int y, int w, int h, String icon, Text label, boolean enabled, Runnable action) {
		ButtonWidget b = ButtonWidget.builder(label, btn -> action.run()).dimensions(x, y, w, h).build();
		b.active = enabled;
		addDrawableChild(b);
		iconButtons.add(new IconBtn(b, icon));
	}

	private void ensurePlayer() {
		if (duration > 0 && player == null && ffmpeg() != null) {
			player = new VideoPlayer(this.client, ffmpeg(), file, duration);
			strip = new Filmstrip(this.client, ffmpeg(), file, duration, 20);
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
			this.client.execute(() -> {
				this.duration = d > 0 ? d : 0;
				if (this.end <= 0) {
					this.end = this.duration;
				}
				if (this.client.currentScreen == this) {
					ensurePlayer();
					clearAndInit();
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
		return (int) Math.ceil(guiUnits * (double) this.client.getWindow().getScaleFactor());
	}

	// ------------------------------------------------------------------- actions

	private void onShareLink() {
		ReplayBufferService svc = Clipify.service();
		if (svc != null) {
			svc.shareOrCopy(file, m -> {
				status = m;
				if (this.client.currentScreen == this) {
					clearAndInit();
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
		clearAndInit();
		double s = start;
		double e = end;
		Thread t = new Thread(() -> {
			try {
				Path dir = file.getParent();
				if (overwrite) {
					Path tmp = dir.resolve(file.getFileName().toString() + ".trim.tmp.mp4");
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
				this.client.execute(() -> {
					busy = false;
					status = "Trim failed: " + ex.getMessage();
					if (this.client.currentScreen == this) {
						clearAndInit();
					}
				});
			}
		}, "Clipify-trim");
		t.setDaemon(true);
		t.start();
	}

	private void finishTrim(String message) {
		this.client.execute(() -> {
			busy = false;
			status = message;
			disposeMedia();
			this.duration = -1;
			this.start = 0;
			this.end = 0;
			probeDurationAsync();
			if (this.client.currentScreen == this) {
				clearAndInit();
			}
		});
	}

	private void onDelete() {
		if (busy) {
			return;
		}
		if (!confirmingDelete) {
			confirmingDelete = true;
			clearAndInit();
			return;
		}
		busy = true;
		status = "Deleting…";
		disposeMedia();
		clearAndInit();
		Path target = file;
		Thread t = new Thread(() -> {
			boolean ok = ClipLibrary.deleteWithRetry(target);
			this.client.execute(() -> {
				if (ok) {
					close();
				} else {
					busy = false;
					confirmingDelete = false;
					status = "Couldn't delete — the file is still in use.";
					if (this.client.currentScreen == this) {
						clearAndInit();
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

	// Fullscreen control geometry.
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
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() != 0) {
			return super.mouseClicked(click, doubled);
		}
		double mx = click.x();
		double my = click.y();

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
				player.togglePlay(); // click video toggles play
			}
			return true; // consume everything in fullscreen
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
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (click.button() == 0) {
			if (fsSeeking) {
				fsSeekTo(click.x());
				return true;
			}
			if (grab != Grab.NONE) {
				applyGrab(click.x());
				return true;
			}
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (click.button() == 0 && (grab != Grab.NONE || fsSeeking)) {
			grab = Grab.NONE;
			fsSeeking = false;
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (fullscreen && input.key() == GLFW.GLFW_KEY_ESCAPE) {
			fullscreen = false;
			return true;
		}
		return super.keyPressed(input);
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
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		computeLayout();
		if (player != null) {
			player.tick();
		}
		for (IconBtn b : iconButtons) {
			b.widget().visible = !fullscreen;
		}
		super.render(context, mouseX, mouseY, delta);

		if (fullscreen) {
			renderFullscreen(context, mouseX, mouseY);
			return;
		}

		context.drawTextWithShadow(this.textRenderer, this.title, cardLeft, 12, TEXT);

		renderPreview(context);
		renderTransport(context, mouseX, mouseY);
		if (duration > 0) {
			renderRuler(context);
			renderTimeline(context, mouseX, mouseY);
		}
		// icons on top of the standard buttons, just left of their centred label
		for (IconBtn b : iconButtons) {
			ButtonWidget w = b.widget();
			int lw = this.textRenderer.getWidth(w.getMessage());
			int tx = w.getX() + (w.getWidth() - lw) / 2;
			int iy = w.getY() + (w.getHeight() - 11) / 2;
			icon(context, b.icon(), tx - 14, iy, 11, w.active ? TEXT : FAINT);
		}
		if (!status.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
					this.width / 2, this.height - 12, busy ? 0xFFFFD070 : MUTED);
		}
	}

	private void renderFullscreen(DrawContext context, int mouseX, int mouseY) {
		context.fill(0, 0, this.width, this.height, 0xFF000000);
		if (player != null && player.hasFrame()) {
			int tw = player.texWidth();
			int th = player.texHeight();
			float sc = Math.min((float) this.width / tw, (float) (this.height - 20) / th);
			int dw = Math.max(1, Math.round(tw * sc));
			int dh = Math.max(1, Math.round(th * sc));
			int dx = this.width / 2 - dw / 2;
			int dy = (this.height - 20) / 2 - dh / 2;
			player.setDisplayWidth(realPixels(dw));
			context.drawTexture(RenderPipelines.GUI_TEXTURED, player.textureId(), dx, dy, 0f, 0f, dw, dh, tw, th, tw, th);
		} else {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Loading…"), this.width / 2, this.height / 2, FAINT);
		}
		// bottom control strip
		context.fill(0, this.height - 52, this.width, this.height, 0x99000000);
		// seek bar
		if (duration > 0) {
			int sy = fsBarY();
			int sl = fsSeekLeft();
			int sw = fsSeekW();
			context.fill(sl, sy, sl + sw, sy + 3, 0xFF3A3D46);
			double t = player != null ? player.time() : 0;
			int px = sl + (int) Math.round(clamp(t / duration, 0, 1) * sw);
			context.fill(sl, sy, px, sy + 3, 0xFFD0D4DC);
			context.fill(px - 2, sy - 3, px + 3, sy + 6, 0xFFFFFFFF);
		}
		boolean playing = player != null && player.isPlaying();
		boolean ph = in(mouseX, mouseY, 24, this.height - 33, 20, 20);
		icon(context, playing ? "pause" : "play", 24, this.height - 33, 20, ph ? 0xFFFFFFFF : MUTED);
		double t = player != null ? player.time() : 0;
		String time = fmtClockMs(t) + " / " + fmtClock(Math.max(0, duration));
		context.drawTextWithShadow(this.textRenderer, Text.literal(time), 50, this.height - 27, MUTED);
		int cX = this.width - 24 - 18;
		boolean ch = in(mouseX, mouseY, cX, this.height - 32, 18, 18);
		icon(context, "expand", cX, this.height - 32, 18, ch ? 0xFFFFFFFF : MUTED);
	}

	private void renderPreview(DrawContext context) {
		panel(context, previewLeft - 4, previewTop - 4, previewW + 8, previewH + 8, 8, PANEL, BORDER);
		context.fill(previewLeft, previewTop, previewLeft + previewW, previewTop + previewH, 0xFF000000);

		// While dragging, fall back to the filmstrip only until the real frame for this spot arrives —
		// a moment later it takes over, so scrubbing stays instant without staying soft.
		boolean shown = false;
		if (grab != Grab.NONE && strip != null
				&& (player == null || !player.hasFrameNear(player.time(), 0.15))) {
			shown = strip.drawFrameAt(context, player != null ? player.time() : 0,
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
			context.drawTexture(RenderPipelines.GUI_TEXTURED, player.textureId(), dx, dy, 0f, 0f, dw, dh, tw, th, tw, th);
		} else {
			String msg = duration < 0 ? "Loading…" : (ffmpeg() == null ? "FFmpeg not ready" : "Decoding…");
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(msg), this.width / 2,
					previewTop + previewH / 2 - 4, FAINT);
		}
	}

	private void renderTransport(DrawContext context, int mouseX, int mouseY) {
		boolean playing = player != null && player.isPlaying();
		icon(context, "skip_start", skipStartX, transIconY, skipSize,
				in(mouseX, mouseY, skipStartX, transIconY, skipSize, skipSize) ? TEXT : MUTED);
		icon(context, "skip_end", skipEndX, transIconY, skipSize,
				in(mouseX, mouseY, skipEndX, transIconY, skipSize, skipSize) ? TEXT : MUTED);
		boolean ph = overPlay(mouseX, mouseY);
		int ps = ph ? playSize + 2 : playSize; // subtle grow on hover
		int po = (playSize - ps) / 2;
		icon(context, playing ? "pause_btn" : "play_btn", playX + po, playY + po, ps, 0xFFFFFFFF);

		double t = player != null ? player.time() : 0;
		String time = duration < 0 ? "--:-- / --:--" : fmtClockMs(t) + " / " + fmtClock(Math.max(0, duration));
		context.drawTextWithShadow(this.textRenderer, Text.literal(time), previewLeft, transY + (playSize - 8) / 2, MUTED);
		boolean eh = in(mouseX, mouseY, expandX, transIconY, skipSize, skipSize);
		icon(context, "expand", expandX, transIconY, skipSize, eh ? TEXT : MUTED);
	}

	private void renderRuler(DrawContext context) {
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
			context.fill(x, tickTop, x + 1, tickTop + h, col);
			if (maj) {
				String lbl = fmtClock(t);
				int lw = this.textRenderer.getWidth(lbl);
				int lx = Math.min(Math.max(x - lw / 2, timelineLeft), timelineLeft + timelineW - lw);
				context.drawTextWithShadow(this.textRenderer, Text.literal(lbl), lx, rulerY, 0xFFC2C6CE);
			}
		}
	}

	private void renderTimeline(DrawContext context, int mouseX, int mouseY) {
		int tLeft = timelineLeft;
		int tRight = timelineLeft + timelineW;
		int tTop = timelineTop;
		int tBot = timelineTop + timelineH;

		panel(context, tLeft - 2, tTop - 2, timelineW + 4, timelineH + 4, 6, PANEL, BORDER);
		if (strip != null) {
			strip.draw(context, tLeft, tTop, timelineW, timelineH);
		} else {
			context.fill(tLeft, tTop, tRight, tBot, 0xFF101216);
		}

		int sx = timeToX(start);
		int ex = timeToX(end);
		// Dim the trimmed-out ends only (no coloured selection).
		if (sx > tLeft) {
			context.fill(tLeft, tTop, sx, tBot, 0xB4000000);
		}
		if (ex < tRight) {
			context.fill(ex, tTop, tRight, tBot, 0xB4000000);
		}

		boolean overTl = mouseX >= tLeft - 8 && mouseX <= tRight + 8 && mouseY >= tTop - 12 && mouseY <= tBot + 6;
		if (grab == Grab.NONE && overTl) {
			int hx = Math.max(tLeft, Math.min(tRight, mouseX));
			context.fill(hx, tTop, hx + 1, tBot, 0x55FFFFFF);
			drawTimeBubble(context, hx, tTop - 4, fmtClockMs(xToTime(hx)));
		}

		boolean hovS = overTl && Math.abs(mouseX - sx) <= 8 && Math.abs(mouseX - sx) <= Math.abs(mouseX - ex);
		boolean hovE = overTl && Math.abs(mouseX - ex) <= 8 && !hovS;
		drawHandle(context, sx, tTop, tBot, grab == Grab.START || hovS);
		drawHandle(context, ex, tTop, tBot, grab == Grab.END || hovE);

		double t = player != null ? player.time() : 0;
		int px = timeToX(t);
		drawPlayhead(context, px, rulerY + 12, tBot + 3);

		if (grab == Grab.START) {
			drawTimeBubble(context, sx, tTop - 4, fmtClockMs(start));
		} else if (grab == Grab.END) {
			drawTimeBubble(context, ex, tTop - 4, fmtClockMs(end));
		} else if (grab == Grab.PLAYHEAD) {
			drawTimeBubble(context, px, tTop - 4, fmtClockMs(t));
		}
	}

	private void drawHandle(DrawContext context, int x, int top, int bottom, boolean active) {
		int hw = active ? 6 : 5;
		roundRect(context, x - hw, top - 3, 2 * hw, (bottom + 3) - (top - 3), 4, active ? 0xFFFFFFFF : HANDLE);
		int cy = (top + bottom) / 2;
		context.fill(x - 1, cy - 7, x + 1, cy + 7, 0xFF88909A); // grip
	}

	private void drawPlayhead(DrawContext context, int px, int top, int bottom) {
		context.fill(px - 2, top, px + 3, bottom, 0x66000000); // halo
		context.fill(px - 1, top, px + 1, bottom, PLAYHEAD);   // 2px line
		roundRect(context, px - 4, top - 3, 8, 8, 2, PLAYHEAD); // top knob
	}

	private void drawTimeBubble(DrawContext context, int cx, int bottomY, String text) {
		int tw = this.textRenderer.getWidth(text);
		int w = tw + 8;
		int x0 = Math.max(2, Math.min(this.width - w - 2, cx - w / 2));
		int y0 = bottomY - 13;
		roundRect(context, x0, y0, w, 13, 3, 0xF0000000);
		context.drawTextWithShadow(this.textRenderer, Text.literal(text), x0 + 4, y0 + 3, TEXT);
	}

	private void icon(DrawContext context, String name, int x, int y, int size, int color) {
		Identifier id = Identifier.of("clipify", "textures/gui/icons/" + name + ".png");
		context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX, color);
	}

	private void roundRect(DrawContext context, int x, int y, int w, int h, int r, int color) {
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		if (r <= 0) {
			context.fill(x, y, x + w, y + h, color);
			return;
		}
		context.fill(x, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			int offset = r - i;
			int inset = r - (int) Math.round(Math.sqrt(Math.max(0, (double) r * r - (double) offset * offset)));
			context.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
			context.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}

	private void panel(DrawContext context, int x, int y, int w, int h, int r, int fill, int border) {
		roundRect(context, x, y, w, h, r, border);
		roundRect(context, x + 1, y + 1, w - 2, h - 2, r - 1, fill);
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
	public void close() {
		this.client.setScreen(parent);
	}
}
