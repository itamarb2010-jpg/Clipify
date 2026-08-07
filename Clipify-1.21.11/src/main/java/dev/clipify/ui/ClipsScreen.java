package dev.clipify.ui;

import dev.clipify.Clipify;
import dev.clipify.ReplayBufferService;
import dev.clipify.config.ClipifyConfig;
import dev.clipify.encode.ClipLibrary;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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

	private TextFieldWidget searchField;
	private final List<ButtonWidget> filterButtons = new ArrayList<>();
	private final ButtonWidget[] rowButtons = new ButtonWidget[PER_PAGE];
	private ButtonWidget prevButton;
	private ButtonWidget nextButton;

	public ClipsScreen(Screen parent) {
		super(Text.translatable("clipify.clips.title"));
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

		searchField = new TextFieldWidget(this.textRenderer, left, SEARCH_Y, WIDTH, 18,
				Text.translatable("clipify.clips.search"));
		searchField.setMaxLength(80);
		searchField.setPlaceholder(Text.translatable("clipify.clips.search").formatted(Formatting.DARK_GRAY));
		searchField.setText(query);
		searchField.setChangedListener(q -> {
			if (!q.equals(query)) {
				query = q;
				page = 0;
				refreshRows(); // in place — no rebuild, so focus stays in the box
			}
		});
		addDrawableChild(searchField);

		filterButtons.clear();
		int fGap = 4;
		int fW = (WIDTH - fGap * 2) / 3;
		addFilterButton(left, fW, Filter.ALL, "clipify.clips.filter_all");
		addFilterButton(left + fW + fGap, fW, Filter.CLIPS, "clipify.clips.filter_clips");
		addFilterButton(left + (fW + fGap) * 2, fW, Filter.SCREENSHOTS, "clipify.clips.filter_shots");

		for (int i = 0; i < PER_PAGE; i++) {
			final int slot = i;
			ButtonWidget b = ButtonWidget.builder(Text.empty(), btn -> onRowClicked(slot))
					.dimensions(left, ROWS_TOP + i * ROW_H, WIDTH, 20).build();
			rowButtons[i] = b;
			addDrawableChild(b);
		}

		int footerY = this.height - 52;
		int half = WIDTH / 2 - 2;
		prevButton = ButtonWidget.builder(Text.literal("< Prev"), b -> {
			page--;
			refreshRows();
		}).dimensions(left, footerY, half, 20).build();
		addDrawableChild(prevButton);
		nextButton = ButtonWidget.builder(Text.literal("Next >"), b -> {
			page++;
			refreshRows();
		}).dimensions(left + WIDTH - half, footerY, half, 20).build();
		addDrawableChild(nextButton);

		addDrawableChild(ButtonWidget.builder(Text.translatable("clipify.clips.open_folder"),
						b -> Clipify.openClipsFolder(this.client, currentConfig()))
				.dimensions(left, footerY + 24, half, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), b -> close())
				.dimensions(left + WIDTH - half, footerY + 24, half, 20).build());

		setInitialFocus(searchField);
		refreshRows();
	}

	private void addFilterButton(int x, int w, Filter f, String key) {
		ButtonWidget b = ButtonWidget.builder(Text.translatable(key), btn -> {
			if (filter != f) {
				filter = f;
				page = 0;
				loaded = diskList();
				refreshRows();
			}
		}).dimensions(x, FILTER_Y, w, 18).build();
		filterButtons.add(b);
		addDrawableChild(b);
	}

	/** Re-applies filter/search/page to the fixed widgets in place, keeping the search box focused. */
	private void refreshRows() {
		visible = applyQuery();
		int maxPage = Math.max(0, (visible.size() - 1) / PER_PAGE);
		page = Math.max(0, Math.min(page, maxPage));

		int from = page * PER_PAGE;
		for (int i = 0; i < PER_PAGE; i++) {
			ButtonWidget b = rowButtons[i];
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
			this.client.setScreen(new ScreenshotViewScreen(this, item.file()));
		} else {
			this.client.setScreen(new ClipEditScreen(this, item.file()));
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (click.button() == 1) { // right-click a row to rename it
			for (int i = 0; i < PER_PAGE; i++) {
				ButtonWidget b = rowButtons[i];
				if (b != null && b.visible && b.isMouseOver(click.x(), click.y())) {
					int idx = page * PER_PAGE + i;
					if (idx < visible.size()) {
						this.client.setScreen(new RenameScreen(this, visible.get(idx)));
						return true;
					}
				}
			}
		}
		return super.mouseClicked(click, doubled);
	}

	private static Text rowLabel(ClipLibrary.Clip item) {
		String size = String.format("%.1f MB", item.sizeBytes() / 1_000_000.0);
		String tag = item.screenshot() ? "IMG  " : "CLIP  ";
		Formatting tagColor = item.screenshot() ? Formatting.AQUA : Formatting.GREEN;
		return Text.literal(tag).formatted(tagColor)
				.append(Text.literal(item.name()).formatted(Formatting.WHITE))
				.append(Text.literal("   " + size).formatted(Formatting.GRAY));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFFFF);
		if (visible.isEmpty()) {
			String key = query.isBlank() ? "clipify.clips.empty" : "clipify.clips.no_matches";
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.translatable(key).formatted(Formatting.GRAY),
					this.width / 2, ROWS_TOP + 24, 0xFFA0A0A0);
		} else {
			int maxPage = (visible.size() - 1) / PER_PAGE;
			context.drawCenteredTextWithShadow(this.textRenderer,
					Text.literal((page + 1) + " / " + (maxPage + 1) + "   (" + visible.size() + " items)")
							.formatted(Formatting.GRAY),
					this.width / 2, this.height - 78, 0xFFA0A0A0);
		}
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.translatable("clipify.clips.rename_hint").formatted(Formatting.DARK_GRAY),
				this.width / 2, this.height - 66, 0xFF808080);
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}
}
