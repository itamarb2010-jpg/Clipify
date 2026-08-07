package dev.clipify.ui;

import dev.clipify.encode.ClipLibrary;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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
	private TextFieldWidget nameField;
	private String error = "";

	public RenameScreen(ClipsScreen parent, ClipLibrary.Clip item) {
		super(Text.translatable("clipify.clips.rename_title"));
		this.parent = parent;
		this.file = item.file();
	}

	@Override
	protected void init() {
		int x = this.width / 2 - W / 2;
		int y = this.height / 2 - 20;
		nameField = new TextFieldWidget(this.textRenderer, x, y, W, 20,
				Text.translatable("clipify.clips.rename_title"));
		nameField.setMaxLength(120);
		nameField.setText(baseName());
		addDrawableChild(nameField);

		int half = W / 2 - 2;
		addDrawableChild(ButtonWidget.builder(Text.translatable("clipify.clips.rename_save"), b -> save())
				.dimensions(x, y + 28, half, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> close())
				.dimensions(x + W - half, y + 28, half, 20).build());

		setInitialFocus(nameField);
	}

	private String baseName() {
		String n = file.getFileName().toString();
		int dot = n.lastIndexOf('.');
		return dot > 0 ? n.substring(0, dot) : n;
	}

	private void save() {
		try {
			ClipLibrary.rename(file, nameField.getText());
			close();
		} catch (IOException e) {
			error = e.getMessage();
		}
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
			save();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title,
				this.width / 2, this.height / 2 - 46, 0xFFFFFFFF);
		if (!error.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal(error).formatted(Formatting.RED), this.width / 2, this.height / 2 + 34, 0xFFFF5555);
		}
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
