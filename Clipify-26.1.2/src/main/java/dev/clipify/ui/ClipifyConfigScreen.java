package dev.clipify.ui;

import dev.clipify.Clipify;
import dev.clipify.ReplayBufferService;
import dev.clipify.config.ClipifyConfig;
import dev.clipify.config.ClipifyConfig.Hotkey;
import dev.clipify.config.ClipifyConfig.HotkeyAction;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Configuration screen shown from Mod Menu, organised into tabbed categories.
 *
 * <p>Edits are made against a {@linkplain ClipifyConfig#copy() detached copy} and written straight
 * into it by each widget's listener, so switching tabs (which rebuilds the widgets via
 * {@link #rebuildWidgets()}) never loses in-progress edits, and Escape discards everything. Only
 * "Save &amp; Apply" pushes the copy into {@link ReplayBufferService#applyConfig}.
 */
public final class ClipifyConfigScreen extends Screen {

	private static final int ROW_HEIGHT = 24;
	private static final int WIDGET_WIDTH = 320;
	private static final int GAP = 4;
	/** Left gutter reserved for a drawn row label (Quality's frame-rate / bitrate fields). */
	private static final int LABEL_WIDTH = 100;
	private static final int LABEL_COLOR = 0xFFB0B0B0;
	private static final int ACCENT_COLOR = 0xFFE24D4D;

	private enum Tab {
		GENERAL("General"),
		QUALITY("Quality"),
		AUDIO("Audio"),
		HOTKEYS("Hotkeys");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private final Screen parent;
	private final ClipifyConfig working;
	private Tab activeTab = Tab.GENERAL;

	private EditBox outputField;
	private EditBox bitrateField;
	private EditBox fpsField;
	private String statusLine = "";
	/** The hotkey whose combo we are currently capturing, or null when not rebinding. */
	private Hotkey rebindingHotkey;

	// Geometry computed in init(), reused by extractRenderState().
	private int left;
	private int contentTop;
	private int tabBarY;

	public ClipifyConfigScreen(Screen parent) {
		super(Component.translatable("clipify.config.title"));
		this.parent = parent;
		ReplayBufferService service = Clipify.service();
		this.working = service != null ? service.config().copy() : ClipifyConfig.load();
	}

	@Override
	protected void init() {
		this.left = this.width / 2 - WIDGET_WIDTH / 2;
		this.tabBarY = 30;
		this.contentTop = 62;
		this.outputField = null;
		this.bitrateField = null;
		this.fpsField = null;

		addTabBar();

		switch (activeTab) {
			case GENERAL -> buildGeneral();
			case QUALITY -> buildQuality();
			case AUDIO -> { /* Audio opens AudioSettingsScreen from the tab bar; never active here. */ }
			case HOTKEYS -> buildHotkeys();
		}

		// Single "Save & Apply" action, pinned near the bottom (same place on every tab).
		// Pressing Escape leaves without saving, which serves as the implicit cancel.
		int bottomY = this.height - 32;
		addRenderableWidget(Button.builder(Component.translatable("clipify.config.save"), b -> saveAndClose())
				.bounds(left, bottomY, WIDGET_WIDTH, 20).build());
	}

	private void addTabBar() {
		Tab[] tabs = Tab.values();
		int gap = 3;
		int tabW = (WIDGET_WIDTH - (tabs.length - 1) * gap) / tabs.length;
		for (int i = 0; i < tabs.length; i++) {
			Tab tab = tabs[i];
			int x = left + i * (tabW + gap);
			Button b = Button.builder(Component.literal(tab.label), btn -> {
				if (tab == Tab.AUDIO) {
					// Audio has its own polished, full-screen settings; it edits the same working copy.
					this.minecraft.setScreen(new AudioSettingsScreen(this, working));
				} else if (activeTab != tab) {
					activeTab = tab;
					rebindingHotkey = null;
					rebuildWidgets();
				}
			}).bounds(x, tabBarY, tabW, 20).build();
			b.active = tab != activeTab; // grey out the current tab so it reads as selected
			addRenderableWidget(b);
		}
	}

	// --------------------------------------------------------------- tab: general

	private void buildGeneral() {
		int half = WIDGET_WIDTH / 2 - GAP / 2;
		int rightX = left + WIDGET_WIDTH - half;
		int y = contentTop;

		addRenderableWidget(CycleButton.onOffBuilder(working.enabled)
				.create(left, y, half, 20, Component.translatable("clipify.config.enabled"),
						(b, v) -> working.enabled = v));
		addRenderableWidget(CycleButton.onOffBuilder(working.showNotifications)
				.create(rightX, y, half, 20, Component.translatable("clipify.config.notifications"),
						(b, v) -> working.showNotifications = v));
		y += ROW_HEIGHT;

		addRenderableWidget(CycleButton.onOffBuilder(working.discordPresence)
				.create(left, y, WIDGET_WIDTH, 20, Component.translatable("clipify.config.discord_presence"),
						(b, v) -> working.discordPresence = v));
		y += ROW_HEIGHT;

		this.outputField = new EditBox(this.font, left, y, WIDGET_WIDTH, 20,
				Component.translatable("clipify.config.output"));
		this.outputField.setMaxLength(256);
		this.outputField.setValue(working.outputFolder);
		this.outputField.setResponder(s -> working.outputFolder = s);
		addRenderableWidget(this.outputField);
		y += ROW_HEIGHT;

		addRenderableWidget(Button.builder(Component.translatable("clipify.config.open_folder"),
						b -> Clipify.openClipsFolder(this.minecraft, working))
				.bounds(left, y, WIDGET_WIDTH, 20).build());
	}

	// ---------------------------------------------------------------- tab: quality

	private void buildQuality() {
		int half = WIDGET_WIDTH / 2 - GAP / 2;
		int rightX = left + WIDGET_WIDTH - half;
		int fieldX = left + LABEL_WIDTH;
		int fieldW = WIDGET_WIDTH - LABEL_WIDTH;
		int y = contentTop;

		// Preset row: one-click Low / Standard / High, plus Custom (the state when nothing matches).
		QualityPreset current = currentPreset();
		QualityPreset[] presets = QualityPreset.VALUES;
		int pGap = 3;
		int pW = (WIDGET_WIDTH - (presets.length - 1) * pGap) / presets.length;
		for (int i = 0; i < presets.length; i++) {
			QualityPreset p = presets[i];
			int px = left + i * (pW + pGap);
			Button b = Button.builder(Component.literal(p.label), btn -> {
				p.applyTo(working);
				rebuildWidgets();
			}).bounds(px, y, pW, 20).build();
			b.active = p != current; // grey out the active preset so it reads as selected
			if (p != QualityPreset.CUSTOM) {
				b.setTooltip(Tooltip.create(Component.literal(p.detail)));
			}
			addRenderableWidget(b);
		}
		y += ROW_HEIGHT;

		// Resolution (full width).
		addRenderableWidget(CycleButton.<Resolution>builder(v -> Component.literal(v.label), currentResolution())
				.withValues(List.of(Resolution.VALUES))
				.create(left, y, WIDGET_WIDTH, 20, Component.translatable("clipify.config.resolution"),
						(b, v) -> { working.maxWidth = v.width; working.maxHeight = v.height; rebuildWidgets(); }));
		y += ROW_HEIGHT;

		// Frame rate — custom typed value (gutter label drawn in extractRenderState()).
		this.fpsField = new EditBox(this.font, fieldX, y, fieldW, 20, Component.translatable("clipify.config.fps"));
		this.fpsField.setValue(Integer.toString(working.fps));
		this.fpsField.setResponder(this::onFpsChanged);
		this.fpsField.setTooltip(Tooltip.create(Component.literal(
				"Custom frame rate in FPS (" + ClipifyConfig.FPS_MIN + "–" + ClipifyConfig.FPS_MAX
						+ ").  e.g. " + hintList(ClipifyConfig.FPS_CHOICES))));
		addRenderableWidget(this.fpsField);
		y += ROW_HEIGHT;

		// Bitrate — custom typed value (gutter label drawn in extractRenderState()).
		this.bitrateField = new EditBox(this.font, fieldX, y, fieldW, 20, Component.translatable("clipify.config.bitrate"));
		this.bitrateField.setValue(Integer.toString(working.videoBitrateKbps));
		this.bitrateField.setResponder(this::onBitrateChanged);
		addRenderableWidget(this.bitrateField);
		y += ROW_HEIGHT;

		// GPU (which hardware encoder to prefer) + Codec, side by side.
		addRenderableWidget(CycleButton.<GpuChoice>builder(v -> Component.literal(v.label), GpuChoice.of(working.encoder))
				.withValues(List.of(GpuChoice.values()))
				.create(left, y, half, 20, Component.translatable("clipify.config.gpu"),
						(b, v) -> working.encoder = gpuToEncoder(v)));
		addRenderableWidget(CycleButton.<ClipifyConfig.VideoCodec>builder(
						v -> Component.literal(v == ClipifyConfig.VideoCodec.H264 ? "H264" : "H265"), working.codec)
				.withValues(List.of(ClipifyConfig.VideoCodec.values()))
				.create(rightX, y, half, 20, Component.translatable("clipify.config.codec"),
						(b, v) -> working.codec = v));
	}

	// -------------------------------------------------------------- tab: hotkeys

	private void buildHotkeys() {
		List<Hotkey> list = working.hotkeys;
		int y = contentTop;
		for (Hotkey hk : list) {
			buildHotkeyRow(hk, y);
			y += ROW_HEIGHT;
		}
		addRenderableWidget(Button.builder(Component.translatable("clipify.config.add_hotkey"),
						b -> this.minecraft.setScreen(new AddHotkeyScreen(this, working)))
				.bounds(left, y, WIDGET_WIDTH, 20).build());
	}

	private void buildHotkeyRow(Hotkey hk, int y) {
		int removeW = 20;
		int removeX = left + WIDGET_WIDTH - removeW;

		Component rebindLabel = (rebindingHotkey == hk)
				? Component.translatable("clipify.config.press_key").withStyle(ChatFormatting.YELLOW)
				: actionText(hk.action).copy().append(": " + Clipify.hotkeyLabel(hk));

		if (hk.action == HotkeyAction.CLIP) {
			int durW = 44;
			int durX = removeX - GAP - durW;
			int rebindW = durX - GAP - left;
			addRenderableWidget(Button.builder(rebindLabel, b -> armRebind(hk))
					.bounds(left, y, rebindW, 20).build());

			EditBox dur = new EditBox(this.font, durX, y, durW, 20, Component.literal("seconds"));
			dur.setValue(Integer.toString(hk.durationSeconds));
			dur.setResponder(s -> {
				try {
					if (!s.isBlank()) {
						hk.durationSeconds = Integer.parseInt(s.trim());
					}
				} catch (NumberFormatException ignored) {
					// Left as-is; validated() clamps on save.
				}
			});
			dur.setTooltip(Tooltip.create(Component.literal("Clip length for THIS hotkey, in seconds ("
					+ ClipifyConfig.DURATION_MIN_SECONDS + "–" + ClipifyConfig.DURATION_MAX_SECONDS + ")")));
			addRenderableWidget(dur);
		} else {
			int rebindW = removeX - GAP - left;
			addRenderableWidget(Button.builder(rebindLabel, b -> armRebind(hk))
					.bounds(left, y, rebindW, 20).build());
		}

		addRenderableWidget(Button.builder(Component.literal("✕"), b -> {
			working.hotkeys.remove(hk);
			if (rebindingHotkey == hk) {
				rebindingHotkey = null;
			}
			rebuildWidgets();
		}).bounds(removeX, y, removeW, 20).build());
	}

	private void armRebind(Hotkey hk) {
		rebindingHotkey = hk;
		rebuildWidgets(); // rebuild so the row shows the "press a key" prompt
	}

	/**
	 * While a hotkey's rebind button is armed, the next key press captures the WHOLE combo — the held
	 * Ctrl/Shift/Alt modifiers AND the key — in one press (so you can just hit "Ctrl+Shift+G" instead
	 * of toggling modifiers). Modifier-only presses are ignored until the real key lands; Escape
	 * cancels the rebind (and is swallowed so it does not also close the screen).
	 */
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (rebindingHotkey != null) {
			int key = event.key();
			if (isModifierKey(key)) {
				return true; // still holding modifiers; wait for the actual key
			}
			if (key != GLFW.GLFW_KEY_ESCAPE) {
				int mods = event.modifiers();
				rebindingHotkey.key = key;
				rebindingHotkey.ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
				rebindingHotkey.shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
				rebindingHotkey.alt = (mods & GLFW.GLFW_MOD_ALT) != 0;
			}
			rebindingHotkey = null;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	private static boolean isModifierKey(int key) {
		return key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL
				|| key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT
				|| key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT;
	}

	// ---------------------------------------------------------------------- logic

	private void onBitrateChanged(String text) {
		try {
			if (!text.isBlank()) {
				working.videoBitrateKbps = Integer.parseInt(text.trim());
			}
		} catch (NumberFormatException ignored) {
			// Left at its previous value; validated() clamps on save.
		}
	}

	private void onFpsChanged(String text) {
		try {
			if (!text.isBlank()) {
				working.fps = Integer.parseInt(text.trim());
			}
		} catch (NumberFormatException ignored) {
			// Left at its previous value; validated() clamps on save.
		}
	}

	private void saveAndClose() {
		working.validated();
		ReplayBufferService service = Clipify.service();
		if (service != null) {
			service.applyConfig(working);
		} else {
			working.save();
		}
		// Apply the Discord toggle immediately (both calls are safe/idempotent) so it doesn't wait
		// for a game restart.
		if (working.discordPresence) {
			dev.clipify.DiscordPresence.start(true);
		} else {
			dev.clipify.DiscordPresence.stop();
		}
		onClose();
	}

	/** Re-open this settings screen on a given tab. Used by {@link AudioSettingsScreen}'s tab bar. */
	public void openTab(int index) {
		this.activeTab = Tab.values()[index];
		this.minecraft.setScreen(this);
	}

	/** Save &amp; Apply from a sibling screen (e.g. the Audio category), which shares this working copy. */
	public void applyAndClose() {
		saveAndClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

		// Subtle underline beneath the active tab.
		Tab[] tabs = Tab.values();
		int gap = 3;
		int tabW = (WIDGET_WIDTH - (tabs.length - 1) * gap) / tabs.length;
		int idx = activeTab.ordinal();
		int tx = left + idx * (tabW + gap);
		graphics.fill(tx, tabBarY + 20, tx + tabW, tabBarY + 22, ACCENT_COLOR);

		// Gutter labels for Quality's custom frame-rate / bitrate fields (drawn left of each field).
		if (activeTab == Tab.QUALITY) {
			if (fpsField != null) {
				graphics.text(this.font, Component.translatable("clipify.config.fps"),
						left, fpsField.getY() + 6, 0xFFFFFFFF, true);
			}
			if (bitrateField != null) {
				graphics.text(this.font, Component.literal("Bitrate (kbps)"),
						left, bitrateField.getY() + 6, 0xFFFFFFFF, true);
			}
		}

		String status = statusLine.isEmpty() ? liveStatus() : statusLine;
		if (!status.isEmpty()) {
			graphics.centeredText(this.font, Component.literal(status), this.width / 2, this.height - 46, LABEL_COLOR);
		}
	}

	private String liveStatus() {
		ReplayBufferService service = Clipify.service();
		if (service == null) {
			return "";
		}
		String s = service.statusMessage();
		return s == null ? "" : "Status: " + s;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}

	// --------------------------------------------------------------- helpers

	private static Component actionText(HotkeyAction a) {
		return switch (a) {
			case CLIP -> Component.translatable("clipify.hotkey.clip");
			case SCREENSHOT -> Component.translatable("clipify.hotkey.screenshot");
			case OPEN_LAST_CLIP -> Component.translatable("clipify.hotkey.open_last");
			case OPEN_FOLDER -> Component.translatable("clipify.hotkey.open_folder");
			case OPEN_SETTINGS -> Component.translatable("clipify.hotkey.open_settings");
		};
	}

	private Resolution currentResolution() {
		for (Resolution r : Resolution.VALUES) {
			if (r.width == working.maxWidth && r.height == working.maxHeight) {
				return r;
			}
		}
		return Resolution.NATIVE;
	}

	private QualityPreset currentPreset() {
		for (QualityPreset p : QualityPreset.VALUES) {
			if (p != QualityPreset.CUSTOM && p.matches(working)) {
				return p;
			}
		}
		return QualityPreset.CUSTOM;
	}

	private static ClipifyConfig.EncoderPreference gpuToEncoder(GpuChoice gpu) {
		return switch (gpu) {
			case AUTO -> ClipifyConfig.EncoderPreference.AUTO;
			case NVIDIA -> ClipifyConfig.EncoderPreference.NVIDIA_NVENC;
			case AMD -> ClipifyConfig.EncoderPreference.AMD_AMF;
			case INTEL -> ClipifyConfig.EncoderPreference.INTEL_QSV;
		};
	}

	/** Renders {@code {30, 60}} as {@code "30, 60"} for a field's "e.g." hint tooltip. */
	private static String hintList(int[] values) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(values[i]);
		}
		return sb.toString();
	}

	/** Quality one-click presets. {@link #CUSTOM} is the state shown when nothing else matches. */
	private enum QualityPreset {
		LOW("Low", "360p 24 FPS", 640, 360, 24, 3000),
		STANDARD("Standard", "720p 60 FPS", 1280, 720, 60, 8000),
		HIGH("High", "1080p 60 FPS", 1920, 1080, 60, 20000),
		CUSTOM("Custom", "", 0, 0, 0, 0);

		static final QualityPreset[] VALUES = values();
		final String label;
		final String detail;
		final int width;
		final int height;
		final int fps;
		final int bitrate;

		QualityPreset(String label, String detail, int width, int height, int fps, int bitrate) {
			this.label = label;
			this.detail = detail;
			this.width = width;
			this.height = height;
			this.fps = fps;
			this.bitrate = bitrate;
		}

		boolean matches(ClipifyConfig c) {
			return c.maxWidth == width && c.maxHeight == height && c.fps == fps && c.videoBitrateKbps == bitrate;
		}

		void applyTo(ClipifyConfig c) {
			if (this == CUSTOM) {
				return;
			}
			c.maxWidth = width;
			c.maxHeight = height;
			c.fps = fps;
			c.videoBitrateKbps = bitrate;
		}
	}

	/** "GPU" dropdown: which hardware encoder to prefer ({@code Auto} falls back to software). */
	private enum GpuChoice {
		AUTO("Auto"),
		NVIDIA("NVIDIA"),
		AMD("AMD"),
		INTEL("Intel");

		final String label;

		GpuChoice(String label) {
			this.label = label;
		}

		static GpuChoice of(ClipifyConfig.EncoderPreference e) {
			return switch (e) {
				case NVIDIA_NVENC -> NVIDIA;
				case AMD_AMF -> AMD;
				case INTEL_QSV -> INTEL;
				default -> AUTO;
			};
		}
	}

	/** Preset capture ceilings offered by the dropdown. {@code 0x0} means "match the window". */
	private enum Resolution {
		NATIVE("Match window", 0, 0),
		P2160("2160p (4K)", 3840, 2160),
		P1440("1440p", 2560, 1440),
		P1080("1080p", 1920, 1080),
		P720("720p", 1280, 720),
		P480("480p", 854, 480);

		static final Resolution[] VALUES = values();
		final String label;
		final int width;
		final int height;

		Resolution(String label, int width, int height) {
			this.label = label;
			this.width = width;
			this.height = height;
		}
	}
}
