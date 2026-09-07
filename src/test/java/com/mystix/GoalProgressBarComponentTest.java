package com.mystix;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class GoalProgressBarComponentTest {
	@Test
	public void rendersBarAndReportsSize() {
		BufferedImage image = new BufferedImage(200, 40, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		FontMetrics fm = g.getFontMetrics();

		GoalProgressBarComponent bar = new GoalProgressBarComponent(0.5, "123 / 500", "25%");
		bar.setPreferredLocation(new Point(0, 0));
		bar.setPreferredSize(new Dimension(200, 0));
		Dimension size = bar.render(g);

		int expectedHeight = GoalProgressBarComponent.TOP_GAP + GoalProgressBarComponent.BAR_HEIGHT
				+ GoalProgressBarComponent.BAR_TEXT_GAP + fm.getHeight();
		assertEquals(new Dimension(200, expectedHeight), size);
		assertEquals(size, bar.getBounds().getSize());

		int y = GoalProgressBarComponent.TOP_GAP + GoalProgressBarComponent.BAR_HEIGHT / 2;
		int filled = image.getRGB(20, y);
		int empty = image.getRGB(180, y);
		assertEquals(GoalProgressBarComponent.FILL.getRGB(), filled);
		assertNotEquals(GoalProgressBarComponent.FILL.getRGB(), empty);
		g.dispose();
	}

	@Test
	public void minWidthGrowsWithPercent() {
		BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
		FontMetrics fm = image.createGraphics().getFontMetrics();
		int without = new GoalProgressBarComponent(0.1, "123 / 500", null).minWidth(fm);
		int with = new GoalProgressBarComponent(0.1, "123 / 500", "25%").minWidth(fm);
		assertTrue(with > without);
	}
}
