package dev.clipify.ui;

import dev.clipify.encode.ClipLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Tiny dialog to rename a clip or screenshot file (its extension is preserved). Opened by
 * right-clicking a row on {@link ClipsScreen}; on success it returns to the Clips list, which
 * re-reads the folder and shows the new name.
 */
public final class RenameScreen extends Screen {

	private static final int W = 240;

	private final ClipsScreen parent;
	private final Path file;
	private EditBox nameField;
	private String error = "";

	public RenameScreen(ClipsScreen parent, ClipLibrary.Clip item) {
		super(Component.translatable("clipify.clips.rename_title"));
		this.parent = parent;
		this.file = item.file();
	}

	@Override
	protected void init() {
		int x = this.width / 2 - W / 2;
		int y = this.height / 2 - 20;
		nameField = new EditBox(this.font, x, y, W, 20, Component.translatable("clipify.clips.rename_title"));
		nameField.setMaxLength(120);
		nameField.setValue(baseName());
		addRenderableWidget(nameField);

		int half = W / 2 - 2;
		addRenderableWidget(Button.builder(Component.translatable("clipify.clips.rename_save"), b -> save())
				.bounds(x, y + 28, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
				.bounds(x + W - half, y + 28, half, 20).build());

		setInitialFocus(nameField);
	}

	private String baseName() {
		String n = file.getFileName().toString();
		int dot = n.lastIndexOf('.');
		return dot > 0 ? n.substring(0, dot) : n;
	}

	private void save() {
		try {
			ClipLibrary.rename(file, nameField.getValue());
			onClose();
		} catch (IOException e) {
			error = e.getMessage();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 46, 0xFFFFFFFF);
		if (!error.isEmpty()) {
			graphics.centeredText(this.font,
					Component.literal(error).withStyle(ChatFormatting.RED), this.width / 2, this.height / 2 + 34, 0xFFFF5555);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
