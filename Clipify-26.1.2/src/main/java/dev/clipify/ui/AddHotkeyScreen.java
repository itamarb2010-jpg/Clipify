package dev.clipify.ui;

import dev.clipify.config.ClipifyConfig;
import dev.clipify.config.ClipifyConfig.Hotkey;
import dev.clipify.config.ClipifyConfig.HotkeyAction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Small picker opened by the Hotkeys tab's "Add Hotkey" button: choose which action the new hotkey
 * triggers. The chosen action is appended to the shared working config as an unbound hotkey (the
 * user then binds a key for it back on the Hotkeys tab).
 */
public final class AddHotkeyScreen extends Screen {

	private static final int W = 220;

	private final ClipifyConfigScreen parent;
	private final ClipifyConfig working;

	public AddHotkeyScreen(ClipifyConfigScreen parent, ClipifyConfig working) {
		super(Component.translatable("clipify.config.add_hotkey"));
		this.parent = parent;
		this.working = working;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - W / 2;
		int y = this.height / 2 - 72;
		addAction(x, y, HotkeyAction.CLIP, "clipify.hotkey.clip");
		addAction(x, y + 24, HotkeyAction.OPEN_LAST_CLIP, "clipify.hotkey.open_last");
		addAction(x, y + 48, HotkeyAction.SCREENSHOT, "clipify.hotkey.screenshot");
		addAction(x, y + 72, HotkeyAction.OPEN_FOLDER, "clipify.hotkey.open_folder");
		addAction(x, y + 96, HotkeyAction.OPEN_SETTINGS, "clipify.hotkey.open_settings");
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
				.bounds(x, y + 128, W, 20).build());
	}

	private void addAction(int x, int y, HotkeyAction action, String labelKey) {
		addRenderableWidget(Button.builder(Component.translatable(labelKey), b -> {
			working.hotkeys.add(new Hotkey(action, -1, false, false, false, 30));
			onClose();
		}).bounds(x, y, W, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
