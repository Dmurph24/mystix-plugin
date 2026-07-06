package com.mystix;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link SyncGuard#isCompleteAppKeyEntry}, which decides whether a
 * ConfigChanged event should trigger an auto full-sync.
 */
public class SyncGuardTest
{
	private static final String FULL_KEY = "ABCDEFGHJKLMNPQR"; // 16 chars

	@Test
	public void testCompleteKeyInMystixGroupTriggers()
	{
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, MystixConfig.APP_KEY, FULL_KEY));
	}

	@Test
	public void testKeyWithSurroundingWhitespaceIsTrimmed()
	{
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, MystixConfig.APP_KEY, "  " + FULL_KEY + "  "));
	}

	@Test
	public void testShortPartialKeyDoesNotTrigger()
	{
		// 7 chars, below the minimum length.
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, MystixConfig.APP_KEY, "ABCDEFG"));
	}

	@Test
	public void testMinLengthKeyTriggers()
	{
		// Exactly at the minimum length (boundary).
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, MystixConfig.APP_KEY, "ABCDEFGH"));
	}

	@Test
	public void testLongerThanCurrentKeyStillTriggers()
	{
		// A future, longer key must still fire (the check is a floor, not equality).
		assertTrue(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, MystixConfig.APP_KEY, FULL_KEY + "EXTRA"));
	}

	@Test
	public void testClearedKeyDoesNotTrigger()
	{
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, MystixConfig.APP_KEY, ""));
	}

	@Test
	public void testNullValueDoesNotTrigger()
	{
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, MystixConfig.APP_KEY, null));
	}

	@Test
	public void testOtherConfigItemDoesNotTrigger()
	{
		// A full-length value on a different key (e.g. a sync toggle) must not fire.
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			MystixConfig.CONFIG_GROUP, "syncTimeTracking", FULL_KEY));
	}

	@Test
	public void testOtherConfigGroupDoesNotTrigger()
	{
		assertFalse(SyncGuard.isCompleteAppKeyEntry(
			"timetracking", MystixConfig.APP_KEY, FULL_KEY));
	}
}
