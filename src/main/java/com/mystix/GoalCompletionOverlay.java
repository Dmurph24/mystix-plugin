package com.mystix;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the whole roadmap's progress bar inside the goal completion popup while
 * it is on screen. The game's notification widget only carries text, so the
 * popup body reserves blank lines at the bottom and this overlay paints the bar
 * ("done / total goals" plus percent) into that space, anchored to the widget.
 */
public class GoalCompletionOverlay extends Overlay {
	/** Inset from the popup's frame on each side. */
	private static final int SIDE_PAD = 14;
	/**
	 * Inset from the bottom of the popup's box. The body reserves two blank
	 * lines and the box adds its own margin under the text; the bar block
	 * (about two lines tall) sits at the bottom of that space so roughly a line
	 * of air separates it from the text above and a similar margin remains below.
	 */
	private static final int BOTTOM_PAD = 8;
	/** The box grows in during its open animation; wait until it has room. */
	private static final int MIN_BOX_HEIGHT = 48;

	private final Client client;
	private final GoalCompletionNotifier notifier;

	@Inject
	public GoalCompletionOverlay(Client client, GoalCompletionNotifier notifier) {
		this.client = client;
		this.notifier = notifier;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		GoalCompletionNotifier.Popup popup = notifier.getActive();
		if (popup == null || popup.getGoalsTotal() <= 0) {
			return null;
		}
		Widget container = client.getWidget(InterfaceID.NotificationDisplay.CONTAINER);
		if (container == null || container.isHidden()) {
			return null;
		}
		Rectangle bounds = container.getBounds();
		if (bounds == null || bounds.width <= 2 * SIDE_PAD || bounds.height < MIN_BOX_HEIGHT) {
			return null;
		}

		int done = popup.getGoalsDone();
		int total = popup.getGoalsTotal();
		int percent = (int) Math.min(100, Math.max(0, (long) done * 100L / total));
		GoalProgressBarComponent bar = new GoalProgressBarComponent(
				percent / 100d, done + " / " + total + " goals", percent + "%");

		// Bar block height: top gap + bar + gap + one text line.
		int height = GoalProgressBarComponent.TOP_GAP + GoalProgressBarComponent.BAR_HEIGHT
				+ GoalProgressBarComponent.BAR_TEXT_GAP + graphics.getFontMetrics().getHeight();
		int width = bounds.width - 2 * SIDE_PAD;
		bar.setPreferredLocation(new Point(bounds.x + SIDE_PAD, bounds.y + bounds.height - BOTTOM_PAD - height));
		bar.setPreferredSize(new Dimension(width, 0));
		return bar.render(graphics);
	}
}
