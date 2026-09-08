package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for QuestMonitor config toggle behavior.
 */
public class QuestMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncQuestsDefaultIsTrue() {
		assertTrue(config.syncQuests());
	}

	@Test
	public void testConfigSyncQuestsCanBeDisabled() {
		config.setSyncQuests(false);
		assertFalse(config.syncQuests());
	}

	@Test
	public void testConfigSyncQuestsCanBeReEnabled() {
		config.setSyncQuests(false);
		assertFalse(config.syncQuests());

		config.setSyncQuests(true);
		assertTrue(config.syncQuests());
	}

	@Test
	public void scrollNamesAQuestTheEnumDoesNotKnow() {
		assertEquals("A Ruff Situation",
				QuestMonitor.parseQuestCompletedScroll("You have completed A Ruff Situation!"));
		assertEquals("The Corsair Curse",
				QuestMonitor.parseQuestCompletedScroll("You have completed The Corsair Curse!"));
		assertEquals("One Small Favour",
				QuestMonitor.parseQuestCompletedScroll("'One Small Favour' completed!"));
	}

	@Test
	public void scrollRestoresQuestWordAndRfdPrefix() {
		assertEquals("Doric's Quest",
				QuestMonitor.parseQuestCompletedScroll("You have completed Doric's Quest!"));
		assertEquals("Recipe for Disaster - Mountain Dwarf",
				QuestMonitor.parseQuestCompletedScroll("You have freed the Mountain Dwarf!"));
	}

	@Test
	public void scrollIgnoresNonCompletions() {
		assertNull(QuestMonitor.parseQuestCompletedScroll(null));
		assertNull(QuestMonitor.parseQuestCompletedScroll("You have kind of completed the Cook's Assistant!"));
	}

	@Test
	public void questTableStatusMirrorsRuneLitesEnumRead() {
		// QUEST_STATUS_GET returns 2 finished, 1 not started, anything else in progress.
		assertEquals(2, QuestMonitor.statusFromScript(2));
		assertEquals(0, QuestMonitor.statusFromScript(1));
		assertEquals(1, QuestMonitor.statusFromScript(0));
		assertEquals(1, QuestMonitor.statusFromScript(3));
	}
}
