package dev.clipify.ui;

import dev.clipify.Clipify;
import dev.clipify.ReplayBufferService;
import dev.clipify.config.ClipifyConfig;
import dev.clipify.encode.ClipLibrary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The "Clips" screen: a searchable, paginated list of saved recordings and screenshots, filterable by
 * All / Clips / Screenshots. Left-click opens a clip in {@link ClipEditScreen} (or a screenshot in
 * {@link ScreenshotViewScreen}); right-click a row to rename it via {@link RenameScreen}.
 *
 * <p>The search is live: the six row buttons are created once and only their labels/visibility are
 * refreshed as you type, so the search box never loses focus (rebuilding the screen each keystroke
 * would).
 */
public final class ClipsScreen extends Screen {

	private static final int PER_PAGE = 6;
	private static final int ROW_H = 22;
	private static final int WIDTH = 300;
	private static final int SEARCH_Y = 30;
	private static final int FILTER_Y = 50;
	private static final int ROWS_TOP = 74;

	private enum Filter {
		ALL, CLIPS, SCREENSHOTS
	}

	private final Screen parent;
	private Filter filter = Filter.ALL;
	private String query = "";
	private int page;

	private List<ClipLibrary.Clip> loaded = List.of();  // everything on disk for the current filter
	private List<ClipLibrary.Clip> visible = List.of(); // loaded, narrowed by the search query

	private EditBox searchField;
	private final List<Button> filterButtons = new ArrayList<>();
	private final Button[] rowButtons = new Button[PER_PAGE];
	private Button prevButton;
	private Button nextButton;

	public ClipsScreen(Screen parent) {
		super(Component.translatable("clipify.clips.title"));
		this.parent = parent;
	}

	private ClipifyConfig currentConfig() {
		ReplayBufferService svc = Clipify.service();
		return svc != null ? svc.config() : ClipifyConfig.load();
	}

	private List<ClipLibrary.Clip> diskList() {
		ClipifyConfig cfg = currentConfig();
		return switch (filter) {
			case ALL -> ClipLibrary.listAll(cfg.resolveOutputFolder(), cfg.resolveScreenshotFolder());
			case CLIPS -> ClipLibrary.list(cfg.resolveOutputFolder());
			case SCREENSHOTS -> ClipLibrary.listScreenshots(cfg.resolveScreenshotFolder());
		};
	}

	@Override
	protected void init() {
		int left = this.width / 2 - WIDTH / 2;
		loaded = diskList();

		searchField = new EditBox(this.font, left, SEARCH_Y, WIDTH, 18,
				Component.translatable("clipify.clips.search"));
		searchField.setMaxLength(80);
		searchField.setHint(Component.translatable("clipify.clips.search"));
		searchField.setValue(query);
		searchField.setResponder(q -> {
			if (!q.equals(query)) {
				query = q;
				page = 0;
				refreshRows(); // in place — no rebuild, so focus stays in the box
			}
		});
		addRenderableWidget(searchField);

		filterButtons.clear();
		int fGap = 4;
		int fW = (WIDTH - fGap * 2) / 3;
		addFilterButton(left, fW, Filter.ALL, "clipify.clips.filter_all");
		addFilterButton(left + fW + fGap, fW, Filter.CLIPS, "clipify.clips.filter_clips");
		addFilterButton(left + (fW + fGap) * 2, fW, Filter.SCREENSHOTS, "clipify.clips.filter_shots");

		for (int i = 0; i < PER_PAGE; i++) {
			final int slot = i;
			Button b = Button.builder(Component.empty(), btn -> onRowClicked(slot))
					.bounds(left, ROWS_TOP + i * ROW_H, WIDTH, 20).build();
			rowButtons[i] = b;
			addRenderableWidget(b);
		}

		int footerY = this.height - 52;
		int half = WIDTH / 2 - 2;
		prevButton = Button.builder(Component.literal("< Prev"), b -> {
			page--;
			refreshRows();
		}).bounds(left, footerY, half, 20).build();
		addRenderableWidget(prevButton);
		nextButton = Button.builder(Component.literal("Next >"), b -> {
			page++;
			refreshRows();
		}).bounds(left + WIDTH - half, footerY, half, 20).build();
		addRenderableWidget(nextButton);

		addRenderableWidget(Button.builder(Component.translatable("clipify.clips.open_folder"),
						b -> Clipify.openClipsFolder(this.minecraft, currentConfig()))
				.bounds(left, footerY + 24, half, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(left + WIDTH - half, footerY + 24, half, 20).build());

		setInitialFocus(searchField);
		refreshRows();
	}

	private void addFilterButton(int x, int w, Filter f, String key) {
		Button b = Button.builder(Component.translatable(key), btn -> {
			if (filter != f) {
				filter = f;
				page = 0;
				loaded = diskList();
				refreshRows();
			}
		}).bounds(x, FILTER_Y, w, 18).build();
		filterButtons.add(b);
		addRenderableWidget(b);
	}

	/** Re-applies filter/search/page to the fixed widgets in place, keeping the search box focused. */
	private void refreshRows() {
		visible = applyQuery();
		int maxPage = Math.max(0, (visible.size() - 1) / PER_PAGE);
		page = Math.max(0, Math.min(page, maxPage));

		int from = page * PER_PAGE;
		for (int i = 0; i < PER_PAGE; i++) {
			Button b = rowButtons[i];
			int idx = from + i;
			if (idx < visible.size()) {
				b.visible = true;
				b.active = true;
				b.setMessage(rowLabel(visible.get(idx)));
			} else {
				b.visible = false;
			}
		}
		int to = Math.min(visible.size(), from + PER_PAGE);
		prevButton.active = page > 0;
		nextButton.active = to < visible.size();
		for (int i = 0; i < filterButtons.size(); i++) {
			filterButtons.get(i).active = filter != Filter.values()[i]; // grey out the active filter
		}
	}

	private List<ClipLibrary.Clip> applyQuery() {
		if (query.isBlank()) {
			return loaded;
		}
		String q = query.toLowerCase(Locale.ROOT);
		List<ClipLibrary.Clip> out = new ArrayList<>();
		for (ClipLibrary.Clip c : loaded) {
			if (c.name().toLowerCase(Locale.ROOT).contains(q)) {
				out.add(c);
			}
		}
		return out;
	}

	private void onRowClicked(int slot) {
		int idx = page * PER_PAGE + slot;
		if (idx < visible.size()) {
			open(visible.get(idx));
		}
	}

	private void open(ClipLibrary.Clip item) {
		if (item.screenshot()) {
			this.minecraft.setScreen(new ScreenshotViewScreen(this, item.file()));
		} else {
			this.minecraft.setScreen(new ClipEditScreen(this, item.file()));
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 1) { // right-click a row to rename it
			for (int i = 0; i < PER_PAGE; i++) {
				Button b = rowButtons[i];
				if (b != null && b.visible && b.isMouseOver(event.x(), event.y())) {
					int idx = page * PER_PAGE + i;
					if (idx < visible.size()) {
						this.minecraft.setScreen(new RenameScreen(this, visible.get(idx)));
						return true;
					}
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private static Component rowLabel(ClipLibrary.Clip item) {
		String size = String.format("%.1f MB", item.sizeBytes() / 1_000_000.0);
		String tag = item.screenshot() ? "IMG  " : "CLIP  ";
		ChatFormatting tagColor = item.screenshot() ? ChatFormatting.AQUA : ChatFormatting.GREEN;
		return Component.literal(tag).withStyle(tagColor)
				.append(Component.literal(item.name()).withStyle(ChatFormatting.WHITE))
				.append(Component.literal("   " + size).withStyle(ChatFormatting.GRAY));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		if (visible.isEmpty()) {
			String key = query.isBlank() ? "clipify.clips.empty" : "clipify.clips.no_matches";
			graphics.centeredText(this.font,
					Component.translatable(key).withStyle(ChatFormatting.GRAY),
					this.width / 2, ROWS_TOP + 24, 0xFFA0A0A0);
		} else {
			int maxPage = (visible.size() - 1) / PER_PAGE;
			graphics.centeredText(this.font,
					Component.literal((page + 1) + " / " + (maxPage + 1) + "   (" + visible.size() + " items)")
							.withStyle(ChatFormatting.GRAY),
					this.width / 2, this.height - 78, 0xFFA0A0A0);
		}
		graphics.centeredText(this.font,
				Component.translatable("clipify.clips.rename_hint").withStyle(ChatFormatting.DARK_GRAY),
				this.width / 2, this.height - 66, 0xFF808080);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
