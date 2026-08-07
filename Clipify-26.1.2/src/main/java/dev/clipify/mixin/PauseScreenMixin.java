package dev.clipify.mixin;

import dev.clipify.ClipifyLog;
import dev.clipify.ui.ClipsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Injects a "Clips" button into the pause menu ({@link PauseScreen}). Done as a mixin (rather than
 * Fabric's ScreenEvents) because modpacks restyle the pause menu, and adding the widget through the
 * screen's own {@code addRenderableWidget} at the tail of {@code init} is the one mechanism
 * guaranteed to render and receive clicks regardless. Anchored just below the "Mods" button (or
 * Options, or the bottom-most button as a last resort). {@code init} rebuilds the widget list every
 * time, so there is never a duplicate.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void clipify$addClipsButton(CallbackInfo ci) {
		try {
			List<AbstractWidget> buttons = new ArrayList<>();
			for (GuiEventListener e : this.children()) {
				if (e instanceof AbstractWidget w) {
					buttons.add(w);
				}
			}
			if (buttons.isEmpty()) {
				return;
			}

			AbstractWidget anchor = find(buttons, "modmenu.title", "Mods");
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
				for (AbstractWidget b : buttons) {
					if (b != anchor && b.getY() > anchorY) {
						b.setY(b.getY() + shift);
					}
				}
			} else {
				AbstractWidget bottom = buttons.get(0);
				for (AbstractWidget b : buttons) {
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
			this.addRenderableWidget(Button.builder(Component.translatable("clipify.clips.button"),
							b -> Minecraft.getInstance().setScreen(new ClipsScreen(self)))
					.bounds(x, y, w, h).build());
		} catch (Exception e) {
			ClipifyLog.LOGGER.warn("Clipify: failed to add the Clips button to the pause menu", e);
		}
	}

	private static AbstractWidget find(List<AbstractWidget> buttons, String translationKey, String literal) {
		String translated = Component.translatable(translationKey).getString();
		for (AbstractWidget b : buttons) {
			String label = b.getMessage().getString();
			if (label.equals(translated) || label.equalsIgnoreCase(literal)) {
				return b;
			}
		}
		return null;
	}
}
