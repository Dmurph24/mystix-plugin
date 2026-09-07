package com.mystix;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * Side-panel progress bar: the same 6px rounded pill the Mystix app draws under
 * each goal. Stretches to the card width; height is fixed.
 */
final class GoalProgressBar extends JComponent {
	static final int HEIGHT = 6;
	private static final Color TRACK = new Color(0x17, 0x17, 0x22);
	private static final Color FILL = new Color(0x25, 0x63, 0xEB);

	private final double fraction;

	GoalProgressBar(double fraction) {
		this.fraction = Math.max(0, Math.min(1, fraction));
		setOpaque(false);
		setPreferredSize(new Dimension(0, HEIGHT));
		setMinimumSize(new Dimension(0, HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
		setAlignmentX(Component.LEFT_ALIGNMENT);
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int w = getWidth();
			g2.setColor(TRACK);
			g2.fillRoundRect(0, 0, w, HEIGHT, HEIGHT, HEIGHT);
			int fill = (int) Math.round(w * fraction);
			if (fill > 0) {
				g2.setColor(FILL);
				g2.fillRoundRect(0, 0, Math.min(w, Math.max(fill, HEIGHT)), HEIGHT, HEIGHT, HEIGHT);
			}
		} finally {
			g2.dispose();
		}
	}
}
