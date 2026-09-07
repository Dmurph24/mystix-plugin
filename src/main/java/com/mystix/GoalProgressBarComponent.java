package com.mystix;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * App-style progress bar for the goal overlay: a thin rounded bar with a
 * "current / target" label beneath it and an optional right-aligned percent.
 *
 * <p>RuneLite's {@code ProgressBarComponent} is fixed at 16px with square corners
 * and the label inside the bar, so it cannot match the app's look; this draws the
 * 6px pill the app uses instead.
 */
final class GoalProgressBarComponent implements LayoutableRenderableEntity {
	static final int TOP_GAP = 4;
	static final int BAR_HEIGHT = 6;
	static final int BAR_TEXT_GAP = 3;
	static final int PERCENT_GAP = 8;

	static final Color TRACK = new Color(0, 0, 0, 110);
	static final Color FILL = new Color(0x25, 0x63, 0xEB);
	static final Color LABEL = new Color(0xC6, 0xC6, 0xC6);

	private final double fraction;
	private final String label;
	private final String percent;

	private Point preferredLocation = new Point();
	private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);
	private final Rectangle bounds = new Rectangle();

	/**
	 * @param fraction bar fill in [0, 1]
	 * @param label    text drawn under the bar, e.g. "12.4K XP / 100K XP"
	 * @param percent  right-aligned text, e.g. "12%", or null to omit
	 */
	GoalProgressBarComponent(double fraction, String label, String percent) {
		this.fraction = Math.max(0, Math.min(1, fraction));
		this.label = label == null ? "" : label;
		this.percent = percent;
	}

	@Override
	public Dimension render(Graphics2D g) {
		final int x = preferredLocation.x;
		final int barY = preferredLocation.y + TOP_GAP;
		final int width = preferredSize.width;
		final FontMetrics fm = g.getFontMetrics();

		Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(TRACK);
		g.fillRoundRect(x, barY, width, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
		int fill = (int) Math.round(width * fraction);
		if (fill > 0) {
			g.setColor(FILL);
			// Never narrower than the corner diameter so a 1% bar is still a pill.
			g.fillRoundRect(x, barY, Math.min(width, Math.max(fill, BAR_HEIGHT)), BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
		}
		if (oldAa != null) {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
		}

		int textBaseline = barY + BAR_HEIGHT + BAR_TEXT_GAP + fm.getAscent();
		TextComponent text = new TextComponent();
		text.setText(label);
		text.setColor(LABEL);
		text.setPosition(new Point(x, textBaseline));
		text.render(g);

		if (percent != null) {
			int pw = fm.stringWidth(percent);
			if (fm.stringWidth(label) + PERCENT_GAP + pw <= width) {
				TextComponent pct = new TextComponent();
				pct.setText(percent);
				pct.setColor(LABEL);
				pct.setPosition(new Point(x + width - pw, textBaseline));
				pct.render(g);
			}
		}

		int height = TOP_GAP + BAR_HEIGHT + BAR_TEXT_GAP + fm.getHeight();
		bounds.setBounds(x, preferredLocation.y, width, height);
		return new Dimension(width, height);
	}

	/** Width needed so the label (and percent) fit on one line. */
	int minWidth(FontMetrics fm) {
		int w = fm.stringWidth(label);
		if (percent != null) {
			w += PERCENT_GAP + fm.stringWidth(percent);
		}
		return w;
	}

	@Override
	public Rectangle getBounds() {
		return bounds;
	}

	@Override
	public void setPreferredLocation(Point position) {
		this.preferredLocation = position;
	}

	@Override
	public void setPreferredSize(Dimension dimension) {
		this.preferredSize = dimension;
	}
}
