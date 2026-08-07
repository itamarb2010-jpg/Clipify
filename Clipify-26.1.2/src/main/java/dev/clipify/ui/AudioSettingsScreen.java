package dev.clipify.ui;

import dev.clipify.Clipify;
import dev.clipify.ReplayBufferService;
import dev.clipify.config.ClipifyConfig;
import dev.clipify.encode.MicMeter;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The "Audio" settings category. It wears {@link ClipifyConfigScreen}'s exact chrome — the same
 * "Clipify Settings" title, the same General/Quality/Audio/Hotkeys tab bar (Audio selected), and the
 * same "Save &amp; Apply" button — so it reads as just another category, and shares that screen's
 * working {@link ClipifyConfig}. The audio-specific controls (source mix + device list + mic picker)
 * live in a scrollable body between the tab bar and the button.
 *
 * <p>UI only: nothing here is wired to a capture backend yet — the settings just persist.
 */
public final class AudioSettingsScreen extends Screen {

	// chrome geometry — identical to ClipifyConfigScreen
	private static final int WIDGET_WIDTH = 320;
	private static final int TAB_BAR_Y = 30;
	private static final int CONTENT_TOP = 62;
	private static final int ACCENT_COLOR = 0xFFE24D4D; // tab underline only (matches config screen)

	// neutral palette (no accent on any control)
	private static final int TEXT = 0xFFE7E8EC;
	private static final int MUTED = 0xFF9AA0AA;
	private static final int FAINT = 0xFF5A5F6A;
	private static final int DIVIDER = 0xFF3A3D46;
	private static final int BOX_BG = 0xFF202329;
	private static final int BOX_HOV = 0xFF2A2E36;
	private static final int BORDER = 0xFF4A4E58;
	private static final int SLIDER_ON = 0xFFECEDF0;
	private static final int SLIDER_OFF = 0xFF6A6F79;
	private static final int TRACK = 0xFF3A3D46;
	private static final int ICON_TEX = 48;

	// body layout
	private static final int HDR_H = 18;
	private static final int ROW = 26;
	private static final int DEV_ROW = 16;
	private static final int WARN_ROW = 22;             // height of the macOS "no PC audio" warning
	private static final int MAC_WARN = 0xFFFF5A5A;     // red warning text
	private static final int GAP_SECTION = 12;
	private static final int PADY = 6;

	private enum SliderId { NONE, MIC_VOL, PC_VOL }

	private final ClipifyConfigScreen parent;
	private final ClipifyConfig cfg;

	private List<String> outputDevices = List.of();

	private int left, viewTop, viewBottom;
	private int scrollY, maxScroll, contentH;
	private float renderDelta;

	private final List<Clickable> clickables = new ArrayList<>();
	private final List<SliderRegion> sliders = new ArrayList<>();
	private SliderId dragging = SliderId.NONE;
	private int dragX, dragW;
	private IntConsumer dragSet;

	private boolean micTesting;
	private MicMeter micMeter;                    // live level meter for the mic test
	private final float[] levels = new float[96];  // scrolling waveform history, newest at the end
	private CycleButton<String> micDeviceCycle; // scroll-managed vanilla widget
	private Button startTestButton;             // scroll-managed vanilla widget

	private record Clickable(int x, int y, int w, int h, Runnable action) {
		boolean hit(double mx, double my) {
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}
	}

	private record SliderRegion(int x, int y, int w, SliderId id, IntConsumer set) {
		boolean hit(double mx, double my) {
			return mx >= x - 4 && mx <= x + w + 4 && my >= y - 8 && my <= y + 8;
		}
	}

	public AudioSettingsScreen(ClipifyConfigScreen parent, ClipifyConfig working) {
		super(Component.translatable("clipify.config.title"));
		this.parent = parent;
		this.cfg = working;
	}

	@Override
	protected void init() {
		this.left = this.width / 2 - WIDGET_WIDTH / 2;
		this.viewTop = CONTENT_TOP;
		this.viewBottom = this.height - 40;
		this.outputDevices = enumerate(false); // render endpoints — WASAPI loopback can capture any

		// Tab bar — same geometry as ClipifyConfigScreen; the other tabs re-open that screen.
		String[] labels = { "General", "Quality", "Audio", "Hotkeys" };
		int gap = 3;
		int tabW = (WIDGET_WIDTH - (labels.length - 1) * gap) / labels.length;
		for (int i = 0; i < labels.length; i++) {
			int x = left + i * (tabW + gap);
			int idx = i;
			Button b = Button.builder(Component.literal(labels[i]), btn -> {
				if (idx != 2) {
					parent.openTab(idx);
				}
			}).bounds(x, TAB_BAR_Y, tabW, 20).build();
			b.active = i != 2; // grey out Audio so it reads as the selected tab
			addRenderableWidget(b);
		}

		// Save & Apply — same place/size as the config screen; edits the shared working copy.
		addRenderableWidget(Button.builder(Component.translatable("clipify.config.save"), b -> parent.applyAndClose())
				.bounds(left, this.height - 32, WIDGET_WIDTH, 20).build());

		// Vanilla mic-picker + mic-test buttons. They scroll with the body, so they're registered for
		// clicks only and drawn manually (clipped) each frame in drawMic().
		List<String> micOptions = new ArrayList<>();
		micOptions.add("Auto");
		micOptions.addAll(enumerate(true));
		if (!micOptions.contains(cfg.micDevice)) {
			cfg.micDevice = "Auto";
		}
		this.micDeviceCycle = CycleButton.<String>builder(Component::literal, cfg.micDevice)
				.withValues(micOptions)
				.create(left, 0, WIDGET_WIDTH, 20, Component.translatable("clipify.audio.microphone"),
						(btn, v) -> cfg.micDevice = v);
		this.micDeviceCycle.visible = false;
		addWidget(this.micDeviceCycle);

		this.startTestButton = Button.builder(Component.translatable("clipify.audio.start_test"),
				b -> toggleMicTest()).bounds(left, 0, WIDGET_WIDTH, 20).build();
		this.startTestButton.visible = false;
		addWidget(this.startTestButton);
	}

	private static List<String> enumerate(boolean capture) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		try {
			Class<?> lineType = capture ? TargetDataLine.class : SourceDataLine.class;
			for (Mixer.Info info : AudioSystem.getMixerInfo()) {
				Mixer mixer = AudioSystem.getMixer(info);
				if (mixer.isLineSupported(new Line.Info(lineType))) {
					String n = info.getName().trim();
					if (!n.isEmpty() && !n.toLowerCase().startsWith("port ")) {
						names.add(n); // keep the full name so it matches for capture; the UI truncates on draw
					}
				}
			}
		} catch (Throwable ignored) {
			// Java Sound can throw on locked-down systems; fall through to defaults.
		}
		if (names.isEmpty()) {
			return capture ? List.of("Default Input Device")
					: List.of("Default Output Device", "Speakers", "Headphones");
		}
		return new ArrayList<>(names);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta); // vanilla bg + tab buttons + Save button
		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

		// Red underline beneath the Audio tab, exactly like the config screen's active-tab marker.
		int gap = 3;
		int tabW = (WIDGET_WIDTH - 3 * gap) / 4;
		int tx = left + 2 * (tabW + gap);
		graphics.fill(tx, TAB_BAR_Y + 20, tx + tabW, TAB_BAR_Y + 22, ACCENT_COLOR);

		clickables.clear();
		sliders.clear();
		this.renderDelta = delta;
		this.micDeviceCycle.visible = false;
		this.startTestButton.visible = false;

		if (micTesting) {
			float lvl;
			if (micMeter != null) {
				lvl = micMeter.isRunning() ? micMeter.level() : -1f;
			} else {
				ReplayBufferService svc = Clipify.service(); // reusing the running capture's mic level
				lvl = svc != null ? svc.liveMicLevel() : -1f;
			}
			if (lvl < 0f) {
				micTesting = false; // the meter or the capture went away
			} else {
				System.arraycopy(levels, 1, levels, 0, levels.length - 1);
				levels[levels.length - 1] = lvl;
			}
		}

		boolean micOn = cfg.captureMicrophone;
		if (!micOn && micTesting) {
			stopMicTest(); // mic was turned off while testing
		}
		int recH = measureRecording();
		int micH = micOn ? measureMic() : 0;
		contentH = recH + (micOn ? GAP_SECTION + micH : 0);
		int viewH = viewBottom - viewTop;
		maxScroll = Math.max(0, contentH - viewH);
		scrollY = Math.max(0, Math.min(scrollY, maxScroll));

		graphics.enableScissor(0, viewTop, this.width, viewBottom);
		int y = viewTop - scrollY;
		drawRecording(graphics, left, y, mouseX, mouseY);
		if (micOn) {
			drawMic(graphics, left, y + recH + GAP_SECTION, mouseX, mouseY);
		}
		graphics.disableScissor();
		drawScrollbar(graphics);
	}

	private static boolean isMac() {
		String os = System.getProperty("os.name", "").toLowerCase();
		return os.contains("mac") || os.contains("darwin");
	}

	private int measureRecording() {
		if (isMac()) {
			return PADY + HDR_H + ROW + WARN_ROW + PADY; // mic row + macOS warning, no PC audio
		}
		return PADY + HDR_H + ROW + ROW + outputDevices.size() * DEV_ROW + PADY;
	}

	private int measureMic() {
		return PADY + HDR_H + 24 + 6 + 24 + 6 + 30 + PADY;
	}

	private void drawRecording(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
		int iw = WIDGET_WIDTH;
		int cy = y + PADY;
		header(graphics, x, cy, iw, "clipify.audio.recording");
		cy += HDR_H;

		sourceRow(graphics, x, iw, cy, "mic", "clipify.audio.microphone", cfg.captureMicrophone,
				() -> cfg.captureMicrophone = !cfg.captureMicrophone, cfg.micVolume, SliderId.MIC_VOL,
				v -> cfg.micVolume = v, mouseX, mouseY);
		cy += ROW;

		if (isMac()) {
			// PC audio uses WASAPI loopback, which is Windows-only — hide the section, warn instead.
			graphics.text(this.font, Component.translatable("clipify.audio.pc_mac_warning"), x, cy + 6, MAC_WARN, true);
			return;
		}

		sourceRow(graphics, x, iw, cy, "headphones", "clipify.audio.pc_audio", cfg.captureGameAudio,
				() -> cfg.captureGameAudio = !cfg.captureGameAudio, cfg.pcAudioVolume, SliderId.PC_VOL,
				v -> cfg.pcAudioVolume = v, mouseX, mouseY);
		cy += ROW;

		int devX = x + 12;
		for (String dev : outputDevices) {
			boolean on = cfg.pcAudioDevices.contains(dev);
			boolean hov = mouseX >= devX && mouseX <= x + iw && mouseY >= cy && mouseY <= cy + DEV_ROW;
			checkbox(graphics, devX, cy + 2, 11, on, hov);
			graphics.text(this.font, Component.literal(trimTo(dev, iw - 44)), devX + 17, cy + 4,
					on ? TEXT : MUTED, true);
			final String d = dev;
			clickables.add(new Clickable(devX, cy, iw - (devX - x), DEV_ROW, () -> toggleDevice(d)));
			cy += DEV_ROW;
		}
	}

	private void sourceRow(GuiGraphicsExtractor graphics, int x, int iw, int cy, String icon, String labelKey,
	                       boolean enabled, Runnable toggle, int vol, SliderId id, IntConsumer setVol,
	                       int mouseX, int mouseY) {
		int mid = cy + ROW / 2;
		boolean hov = mouseX >= x && mouseX <= x + iw && mouseY >= cy && mouseY <= cy + ROW;
		checkbox(graphics, x, mid - 6, 13, enabled, hov);
		clickables.add(new Clickable(x, cy, 20, ROW, toggle));
		icon(graphics, icon, x + 20, mid - 6, 13, enabled ? TEXT : MUTED);
		graphics.text(this.font, Component.translatable(labelKey), x + 40, mid - 4, enabled ? TEXT : MUTED, true);

		int valW = 26;
		int sliderW = 110;
		int sx = x + iw - valW - sliderW;
		slider(graphics, sx, mid, sliderW, vol, enabled, id, setVol, mouseX, mouseY);
		String pct = vol + "%";
		graphics.text(this.font, Component.literal(pct), x + iw - this.font.width(pct), mid - 4,
				enabled ? TEXT : MUTED, true);
	}

	private void drawMic(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
		int iw = WIDGET_WIDTH;
		int cy = y + PADY;
		header(graphics, x, cy, iw, "clipify.audio.mic_settings");
		cy += HDR_H;

		micDeviceCycle.setY(cy);
		micDeviceCycle.visible = cy >= viewTop && cy + 20 <= viewBottom;
		if (micDeviceCycle.visible) {
			micDeviceCycle.extractRenderState(graphics, mouseX, mouseY, renderDelta);
		}
		cy += 24 + 6;

		startTestButton.setMessage(Component.translatable(micTesting ? "clipify.audio.stop_test" : "clipify.audio.start_test"));
		startTestButton.setY(cy);
		startTestButton.visible = cy >= viewTop && cy + 20 <= viewBottom;
		if (startTestButton.visible) {
			startTestButton.extractRenderState(graphics, mouseX, mouseY, renderDelta);
		}
		cy += 24 + 6;

		waveform(graphics, x, cy, iw, 28);
	}

	// --------------------------------------------------------------- primitives

	private void header(GuiGraphicsExtractor graphics, int x, int cy, int iw, String key) {
		graphics.text(this.font, Component.translatable(key), x, cy, TEXT, true);
		graphics.fill(x, cy + 13, x + iw, cy + 14, DIVIDER);
	}

	/** Neutral checkbox: bordered box, white check when ticked (no colour accent). */
	private void checkbox(GuiGraphicsExtractor graphics, int x, int y, int size, boolean checked, boolean hover) {
		roundRect(graphics, x, y, size, size, 2, hover ? BOX_HOV : BOX_BG);
		roundRectOutline(graphics, x, y, size, size, 2, hover ? 0xFF6A6F79 : BORDER);
		if (checked) {
			icon(graphics, "check", x + 1, y + 1, size - 2, TEXT);
		}
	}

	private void slider(GuiGraphicsExtractor graphics, int x, int cy, int w, int value, boolean enabled,
	                    SliderId id, IntConsumer set, int mouseX, int mouseY) {
		int trackY = cy - 1;
		roundRect(graphics, x, trackY, w, 3, 1, TRACK);
		int knobX = x + Math.round(value / 200f * w); // volume slider is 0–200 % (100 = unity)
		if (knobX > x) {
			roundRect(graphics, x, trackY, knobX - x, 3, 1, enabled ? SLIDER_ON : SLIDER_OFF);
		}
		boolean hot = mouseX >= x - 4 && mouseX <= x + w + 4 && mouseY >= cy - 8 && mouseY <= cy + 8;
		int r = hot || dragging == id ? 6 : 5;
		roundRect(graphics, knobX - r, cy - r, 2 * r, 2 * r, r, enabled ? 0xFFFFFFFF : 0xFFB6BAC2);
		sliders.add(new SliderRegion(x, cy, w, id, set));
	}

	private void waveform(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
		roundRect(graphics, x, y, w, h, 4, 0xFF101216);
		int step = 4;
		int mid = y + h / 2;
		int bars = (w - 6) / step;
		int color = micTesting ? 0xFFCFD3DA : FAINT;
		for (int i = 0, bx = x + 3; i < bars; i++, bx += step) {
			double amp;
			if (micTesting) {
				int idx = levels.length - bars + i; // newest levels on the right
				amp = idx >= 0 ? levels[idx] : 0.0;
			} else {
				amp = 0.14; // idle: a flat faint line
			}
			int hh = (int) Math.max(1, amp * (h / 2 - 2));
			graphics.fill(bx, mid - hh, bx + 2, mid + hh, color);
		}
	}

	private void toggleMicTest() {
		if (micTesting) {
			stopMicTest();
			return;
		}
		java.util.Arrays.fill(levels, 0f);
		// If the running buffer is already capturing the mic, reuse its live level — opening a second
		// line on the same device throws LineUnavailableException. Otherwise open a standalone meter.
		ReplayBufferService svc = Clipify.service();
		if (svc != null && svc.liveMicLevel() >= 0f) {
			micTesting = true;
			return;
		}
		micMeter = new MicMeter();
		micTesting = micMeter.start(cfg.micDevice);
		if (!micTesting) {
			micMeter = null; // couldn't open the device; button falls back to "Start Test"
		}
	}

	private void stopMicTest() {
		micTesting = false;
		if (micMeter != null) {
			micMeter.stop();
			micMeter = null;
		}
	}

	private void drawScrollbar(GuiGraphicsExtractor graphics) {
		if (maxScroll <= 0) {
			return;
		}
		int viewH = viewBottom - viewTop;
		int barX = left + WIDGET_WIDTH + 6;
		int thumbH = Math.max(24, (int) ((long) viewH * viewH / contentH));
		int thumbY = viewTop + (int) ((long) (viewH - thumbH) * scrollY / maxScroll);
		roundRect(graphics, barX, viewTop, 3, viewH, 1, 0xFF1E2127);
		roundRect(graphics, barX, thumbY, 3, thumbH, 1, 0xFF3C414A);
	}

	private String trimTo(String s, int maxW) {
		if (this.font.width(s) <= maxW) {
			return s;
		}
		while (s.length() > 1 && this.font.width(s + "…") > maxW) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
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

	private void roundRectOutline(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int r, int color) {
		r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
		graphics.fill(x + r, y, x + w - r, y + 1, color);
		graphics.fill(x + r, y + h - 1, x + w - r, y + h, color);
		graphics.fill(x, y + r, x + 1, y + h - r, color);
		graphics.fill(x + w - 1, y + r, x + w, y + h - r, color);
		for (int i = 0; i < r; i++) {
			int offset = r - i;
			int inset = r - (int) Math.round(Math.sqrt(Math.max(0, (double) r * r - (double) offset * offset)));
			graphics.fill(x + inset, y + i, x + inset + 1, y + i + 1, color);
			graphics.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, color);
			graphics.fill(x + inset, y + h - 1 - i, x + inset + 1, y + h - i, color);
			graphics.fill(x + w - inset - 1, y + h - 1 - i, x + w - inset, y + h - i, color);
		}
	}

	// ------------------------------------------------------------------- input

	private void toggleDevice(String dev) {
		if (!cfg.pcAudioDevices.remove(dev)) {
			cfg.pcAudioDevices.add(dev);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		double mx = event.x();
		double my = event.y();
		if (my >= viewTop && my <= viewBottom) {
			for (SliderRegion s : sliders) {
				if (s.hit(mx, my)) {
					dragging = s.id();
					dragX = s.x();
					dragW = s.w();
					dragSet = s.set();
					applyDrag(mx);
					return true;
				}
			}
			for (Clickable c : clickables) {
				if (c.hit(mx, my)) {
					c.action().run();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick); // tabs, Save, mic picker, Start Test
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragXo, double dragYo) {
		if (event.button() == 0 && dragging != SliderId.NONE) {
			applyDrag(event.x());
			return true;
		}
		return super.mouseDragged(event, dragXo, dragYo);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == 0 && dragging != SliderId.NONE) {
			dragging = SliderId.NONE;
			dragSet = null;
			return true;
		}
		return super.mouseReleased(event);
	}

	private void applyDrag(double mx) {
		if (dragSet == null || dragW <= 0) {
			return;
		}
		double f = Math.max(0, Math.min(1, (mx - dragX) / dragW));
		dragSet.accept((int) Math.round(f * 200)); // volume slider is 0–200 %
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (maxScroll > 0) {
			scrollY = Math.max(0, Math.min(maxScroll, scrollY - (int) Math.round(verticalAmount * 24)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void removed() {
		stopMicTest(); // release the mic when navigating away (close, tab switch or save)
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
