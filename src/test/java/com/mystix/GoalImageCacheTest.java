package com.mystix;

import java.awt.image.BufferedImage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

	@Test
	public void onlyWikiImagesAreFetched() {
		assertTrue(GoalImageCache.isAllowed("https://oldschool.runescape.wiki/images/Zulrah.png"));
		assertTrue(GoalImageCache.isAllowed("https://oldschool.runescape.wiki/images/Attack_icon.png?7d0d4"));

		assertFalse(GoalImageCache.isAllowed(null));
		assertFalse(GoalImageCache.isAllowed(""));
		assertFalse(GoalImageCache.isAllowed("http://oldschool.runescape.wiki/images/Zulrah.png"));
		assertFalse(GoalImageCache.isAllowed("https://example.com/images/Zulrah.png"));
		assertFalse(GoalImageCache.isAllowed("https://oldschool.runescape.wiki.example.com/images/Zulrah.png"));
		assertFalse(GoalImageCache.isAllowed("https://oldschool.runescape.wiki@example.com/images/Zulrah.png"));
		assertFalse(GoalImageCache.isAllowed("https://oldschool.runescape.wiki:8443/images/Zulrah.png"));
		assertFalse(GoalImageCache.isAllowed("https://oldschool.runescape.wiki/w/Zulrah"));
	}
}
