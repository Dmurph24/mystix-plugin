package com.mystix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.runelite.api.GameState;
import org.junit.Test;

public class GoalProgressTrackerTest {
	@Test
	public void onlyRealLogoutEndsSession() {
		assertTrue(GoalProgressTracker.endsSession(GameState.LOGIN_SCREEN));
		assertTrue(GoalProgressTracker.endsSession(GameState.LOGIN_SCREEN_AUTHENTICATOR));
	}

	@Test
	public void regionLoadsHopsAndDropsKeepTheSession() {
		// A teleport or region boundary goes LOGGED_IN -> LOADING -> LOGGED_IN;
		// a hop goes through HOPPING; a blip goes through CONNECTION_LOST. Progress
		// made since the last server read must survive all of them.
		assertFalse(GoalProgressTracker.endsSession(GameState.LOADING));
		assertFalse(GoalProgressTracker.endsSession(GameState.HOPPING));
		assertFalse(GoalProgressTracker.endsSession(GameState.CONNECTION_LOST));
		assertFalse(GoalProgressTracker.endsSession(GameState.LOGGED_IN));
		assertFalse(GoalProgressTracker.endsSession(GameState.LOGGING_IN));
	}
}
