package com.mystix;

import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentOrientation;
import net.runelite.client.ui.overlay.components.ImageComponent;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.SplitComponent;

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
	/** Text wraps onto a new line once it would exceed this width. */
	private static final int MAX_TEXT_WIDTH = 200;
	/** Gap between the item icon and the goal name. */
	private static final int ICON_GAP = 6;

	private final MystixConfig config;
	private final RoadmapManager roadmapManager;
	private final ItemManager itemManager;
	private final SkillIconManager skillIconManager;

	// Cache the last icon (keyed by "item:<id>" / "skill:<name>") so we don't
	// re-fetch every render frame.
	private String cachedIconKey;
	private BufferedImage cachedIcon;

	@Inject
	public NextGoalOverlay(MystixConfig config, RoadmapManager roadmapManager,
			ItemManager itemManager, SkillIconManager skillIconManager) {
		this.config = config;
		this.roadmapManager = roadmapManager;
		this.itemManager = itemManager;
		this.skillIconManager = skillIconManager;
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
		int iconWidth = icon != null ? icon.getWidth() : 0;

		// Word-wrap both lines so long content flows onto multiple lines instead
		// of spilling past the background box. The first goal-name line also
		// carries the icon, so it wraps in the narrower space left of it.
		FontMetrics metrics = graphics.getFontMetrics();
		List<String> titleLines = wrapText(header, MAX_TEXT_WIDTH, metrics);
		int contentMax = MAX_TEXT_WIDTH - (icon != null ? iconWidth + ICON_GAP : 0);
		List<String> goalLines = wrapText(goalName, contentMax, metrics);

		// Size the panel to the actual widest rendered line (left-aligned).
		int widest = 0;
		for (String line : titleLines) {
			widest = Math.max(widest, metrics.stringWidth(line));
		}
		for (int i = 0; i < goalLines.size(); i++) {
			int w = metrics.stringWidth(goalLines.get(i));
			if (i == 0 && icon != null) {
				w += iconWidth + ICON_GAP;
			}
			widest = Math.max(widest, w);
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(
				new Dimension(Math.max(MIN_WIDTH, widest + WIDTH_PADDING), 0));

		// Title: left-aligned orange line(s).
		for (String line : titleLines) {
			panelComponent.getChildren().add(LineComponent.builder()
					.left(line)
					.leftColor(TITLE_COLOR)
					.build());
		}
		// Goal name: left-aligned line(s); the icon sits beside the first line.
		for (int i = 0; i < goalLines.size(); i++) {
			LineComponent line = LineComponent.builder().left(goalLines.get(i)).build();
			if (i == 0 && icon != null) {
				panelComponent.getChildren().add(SplitComponent.builder()
						.first(new ImageComponent(icon))
						.second(line)
						.orientation(ComponentOrientation.HORIZONTAL)
						.gap(new Point(ICON_GAP, 0))
						.build());
			} else {
				panelComponent.getChildren().add(line);
			}
		}

		return super.render(graphics);
	}

	/** Greedily word-wraps text into lines no wider than {@code maxWidth}. */
	private static List<String> wrapText(String text, int maxWidth, FontMetrics metrics) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.trim().split("\\s+")) {
			if (line.length() == 0) {
				line.append(word);
			} else if (metrics.stringWidth(line + " " + word) <= maxWidth) {
				line.append(' ').append(word);
			} else {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (line.length() > 0) {
			lines.add(line.toString());
		}
		if (lines.isEmpty()) {
			lines.add(text);
		}
		return lines;
	}

	/** The goal's icon: an item sprite, a skill icon, or null when neither. */
	private BufferedImage iconFor(RoadmapGoal goal) {
		Integer itemId = goal.getItemId();
		if (itemId != null) {
			return cached("item:" + itemId, () -> itemManager.getImage(itemId));
		}
		Skill skill = parseSkill(goal.getSkillName());
		if (skill != null) {
			return cached("skill:" + skill.name(), () -> skillIconManager.getSkillImage(skill));
		}
		return null;
	}

	/** Memoise the last icon so we don't re-fetch it every render frame. */
	private BufferedImage cached(String key, java.util.function.Supplier<BufferedImage> loader) {
		if (!key.equals(cachedIconKey)) {
			cachedIconKey = key;
			cachedIcon = loader.get();
		}
		return cachedIcon;
	}

	/** The RuneLite {@link Skill} for a goal's skill name, or null if unknown
	 * (e.g. Sailing, which RuneLite doesn't have an icon for yet). */
	private static Skill parseSkill(String name) {
		if (name == null) {
			return null;
		}
		try {
			return Skill.valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
