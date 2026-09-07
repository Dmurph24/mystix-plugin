package com.mystix;

import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class GoalImageCacheTest {
	@Test
	public void smallImagesAreLeftAlone() {
		BufferedImage small = new BufferedImage(20, 16, BufferedImage.TYPE_INT_ARGB);
		assertSame(small, GoalImageCache.fit(small));
	}

	@Test
	public void largeImagesFitTheIconSquareKeepingAspect() {
		BufferedImage tall = new BufferedImage(200, 400, BufferedImage.TYPE_INT_ARGB);
		BufferedImage fitted = GoalImageCache.fit(tall);
		assertEquals(GoalImageCache.ICON_SIZE, fitted.getHeight());
		assertEquals(16, fitted.getWidth());

		BufferedImage wide = new BufferedImage(640, 64, BufferedImage.TYPE_INT_ARGB);
		BufferedImage fittedWide = GoalImageCache.fit(wide);
		assertEquals(GoalImageCache.ICON_SIZE, fittedWide.getWidth());
		assertTrue(fittedWide.getHeight() <= GoalImageCache.ICON_SIZE);
	}
}
