package com.mystix;

import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Game-window overlay showing the current goal of the selected roadmap.
 *
 * <p>Gated by {@link MystixConfig#showNextGoal()}. Reads the cached roadmap from
 * {@link RoadmapManager} (no network on the render thread) and renders a
 * "[roadmap name] - Current Goal" header above the goal name, with the goal's
 * item icon beside it when the goal targets an item. Renders nothing when
 * disabled, no app key, or no incomplete goal is available.
 */
public class NextGoalOverlay extends OverlayPanel {
	private static final Color TITLE_COLOR = new Color(0xF2, 0x8C, 0x28); // Mystix orange

	/** Horizontal padding added to the widest line so text never touches the edge. */
	private static final int WIDTH_PADDING = 14;
	/** Floor so a very short goal still renders as a panel, not a sliver. */
	private static final int MIN_WIDTH = 120;
	/** Gap between the item icon and the goal name. */
	private static final int ICON_GAP = 6;

	private final MystixConfig config;
	private final RoadmapManager roadmapManager;
	private final ItemManager itemManager;

	// Cache the last item icon so we don't re-fetch every render frame.
	private int cachedItemId = -1;
	private BufferedImage cachedIcon;

	@Inject
	public NextGoalOverlay(MystixConfig config, RoadmapManager roadmapManager, ItemManager itemManager) {
		this.config = config;
		this.roadmapManager = roadmapManager;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.showNextGoal() || !roadmapManager.hasAppKey()) {
			return null;
		}

		Roadmap roadmap = roadmapManager.getCurrentRoadmap();
		if (roadmap == null) {
			return null;
		}
		RoadmapGoal goal = roadmap.firstIncompleteGoal();
		if (goal == null || goal.getName() == null) {
			return null;
		}

		String title = roadmap.getTitle();
		String header = (title == null || title.isEmpty())
				? "Current Goal"
				: title + " - Current Goal";
		String goalName = goal.getName();
		BufferedImage icon = iconFor(goal);

		panelComponent.getChildren().clear();

		// Size the panel to the widest line. RuneLite's TitleComponent centres its
		// text within the panel's preferred width and doesn't wrap, so a fixed
		// width makes longer roadmap/goal names spill past the background box.
		FontMetrics metrics = graphics.getFontMetrics();
		int nameLineWidth = metrics.stringWidth(goalName)
				+ (icon != null ? icon.getWidth() + ICON_GAP : 0);
		int contentWidth = Math.max(metrics.stringWidth(header), nameLineWidth);
		panelComponent.setPreferredSize(
				new Dimension(Math.max(MIN_WIDTH, contentWidth + WIDTH_PADDING), 0));

		panelComponent.getChildren().add(TitleComponent.builder()
				.text(header)
				.color(TITLE_COLOR)
				.build());

		LineComponent nameLine = LineComponent.builder().left(goalName).build();
		if (icon != null) {
			panelComponent.getChildren().add(SplitComponent.builder()
					.first(new ImageComponent(icon))
					.second(nameLine)
					.orientation(ComponentOrientation.HORIZONTAL)
					.gap(new Point(ICON_GAP, 0))
					.build());
		} else {
			panelComponent.getChildren().add(nameLine);
		}

		return super.render(graphics);
	}

	/** The goal's item sprite, or null when the goal doesn't target an item. */
	private BufferedImage iconFor(RoadmapGoal goal) {
		Integer itemId = goal.getItemId();
		if (itemId == null) {
			return null;
		}
		if (itemId != cachedItemId) {
			cachedItemId = itemId;
			cachedIcon = itemManager.getImage(itemId);
		}
		return cachedIcon;
	}
}
