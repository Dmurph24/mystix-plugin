package com.mystix;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for KingdomMonitor config toggle behaviour and its pure send/region rules.
 */
public class KingdomMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncKingdomDefaultIsTrue() {
		assertTrue(config.syncKingdom());
	}

	@Test
	public void testConfigSyncKingdomCanBeDisabled() {
		config.setSyncKingdom(false);
		assertFalse(config.syncKingdom());
	}

	@Test
	public void testConfigSyncKingdomCanBeReEnabled() {
		config.setSyncKingdom(false);
		assertFalse(config.syncKingdom());

		config.setSyncKingdom(true);
		assertTrue(config.syncKingdom());
	}

	@Test
	public void testKingdomRegionsAreMiscellaniaAndEtceteria() {
		assertTrue(KingdomMonitor.isKingdomRegion(10044));
		assertTrue(KingdomMonitor.isKingdomRegion(10300));
		assertFalse(KingdomMonitor.isKingdomRegion(10043));
		assertFalse(KingdomMonitor.isKingdomRegion(12850));
	}

	@Test
	public void testShouldSendRequiresFinishedThroneAndLoadedApproval() {
		assertFalse(KingdomMonitor.shouldSend(false, 50));
		assertFalse(KingdomMonitor.shouldSend(true, 0));
		assertTrue(KingdomMonitor.shouldSend(true, 32));
	}
}
