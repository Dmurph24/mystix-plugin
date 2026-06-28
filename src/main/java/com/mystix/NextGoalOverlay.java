package com.mystix;

import com.mystix.model.RoadmapGoal;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Game-window overlay showing the next uncompleted goal of the selected roadmap.
 *
 * <p>Gated by {@link MystixConfig#showNextGoal()}. Reads the cached roadmap from
 * {@link RoadmapManager} (no network on the render thread) and renders the goal
 * name + progress. Renders nothing when disabled, no app key, or no incomplete
 * goal is available.
 */
public class NextGoalOverlay extends OverlayPanel {
	private static final Color TITLE_COLOR = new Color(0xF2, 0x8C, 0x28); // Mystix orange
	private static final int PREFERRED_WIDTH = 160;

	private final MystixConfig config;
	private final RoadmapManager roadmapManager;

	@Inject
	public NextGoalOverlay(MystixConfig config, RoadmapManager roadmapManager) {
		this.config = config;
		this.roadmapManager = roadmapManager;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.showNextGoal() || !roadmapManager.hasAppKey()) {
			return null;
		}

		RoadmapGoal goal = roadmapManager.getNextGoal();
		if (goal == null || goal.getName() == null) {
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(PREFERRED_WIDTH, 0));

		panelComponent.getChildren().add(TitleComponent.builder()
				.text("Next Goal")
				.color(TITLE_COLOR)
				.build());

		panelComponent.getChildren().add(LineComponent.builder()
				.left(goal.getName())
				.build());

		String progress = goal.progressLabel();
		if (!progress.isEmpty()) {
			panelComponent.getChildren().add(LineComponent.builder()
					.left("Progress:")
					.right(progress)
					.rightColor(Color.GREEN)
					.build());
		}

		return super.render(graphics);
	}
}
