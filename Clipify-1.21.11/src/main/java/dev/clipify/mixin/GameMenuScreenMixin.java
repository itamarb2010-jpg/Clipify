package dev.clipify.mixin;

import dev.clipify.ClipifyLog;
import dev.clipify.ui.ClipsScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Injects a "Clips" button into the pause menu. Done as a mixin (rather than Fabric's
 * {@code ScreenEvents}) because this modpack restyles the pause menu (Essential et al.), and adding
 * the widget through the screen's own {@code addDrawableChild} at the tail of {@code init} is the
 * one mechanism guaranteed to render and receive clicks regardless of that. Anchored just below the
 * "Mods" button (or Options, or the bottom-most button as a last resort). {@code init} rebuilds the
 * widget list every time, so there is never a duplicate.
 */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

	protected GameMenuScreenMixin(Text title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void clipify$addClipsButton(CallbackInfo ci) {
		try {
			List<ClickableWidget> buttons = new ArrayList<>();
			for (Element e : this.children()) {
				if (e instanceof ClickableWidget cw) {
					buttons.add(cw);
				}
			}
			if (buttons.isEmpty()) {
				return;
			}

			ClickableWidget anchor = find(buttons, "modmenu.title", "Mods");
			if (anchor == null) {
				anchor = find(buttons, "menu.options", "Options...");
			}

			int x;
			int y;
			int w;
			int h;
			if (anchor != null) {
				x = anchor.getX();
				w = anchor.getWidth();
				h = anchor.getHeight();
				int shift = h + 4;
				y = anchor.getY() + shift;
				int anchorY = anchor.getY();
				for (ClickableWidget b : buttons) {
					if (b != anchor && b.getY() > anchorY) {
						b.setY(b.getY() + shift);
					}
				}
			} else {
				// No known anchor: append below the bottom-most button so it can't overlap anything.
				ClickableWidget bottom = buttons.get(0);
				for (ClickableWidget b : buttons) {
					if (b.getY() > bottom.getY()) {
						bottom = b;
					}
				}
				x = bottom.getX();
				w = bottom.getWidth();
				h = bottom.getHeight();
				y = bottom.getY() + h + 4;
			}

			Screen self = (Screen) (Object) this;
			this.addDrawableChild(ButtonWidget.builder(Text.translatable("clipify.clips.button"),
							b -> MinecraftClient.getInstance().setScreen(new ClipsScreen(self)))
					.dimensions(x, y, w, h).build());
		} catch (Exception e) {
			ClipifyLog.LOGGER.warn("Clipify: failed to add the Clips button to the pause menu", e);
		}
	}

	private static ClickableWidget find(List<ClickableWidget> buttons, String translationKey, String literal) {
		String translated = Text.translatable(translationKey).getString();
		for (ClickableWidget b : buttons) {
			String label = b.getMessage().getString();
			if (label.equals(translated) || label.equalsIgnoreCase(literal)) {
				return b;
			}
		}
		return null;
	}
}
