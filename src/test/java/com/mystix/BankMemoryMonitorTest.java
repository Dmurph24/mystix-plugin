package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for BankMemoryMonitor config toggle behavior.
 */
public class BankMemoryMonitorTest {
	private TestMystixConfig config;

	@Before
	public void setUp() {
		config = new TestMystixConfig();
	}

	@Test
	public void testConfigSyncBankMemoryDefaultIsTrue() {
		assertEquals(true, config.syncBankMemory());
	}

	@Test
	public void testConfigSyncBankMemoryCanBeDisabled() {
		config.setSyncBankMemory(false);
		assertEquals(false, config.syncBankMemory());
	}

	@Test
	public void testConfigSyncBankMemoryCanBeReEnabled() {
		config.setSyncBankMemory(false);
		assertFalse(config.syncBankMemory());

		config.setSyncBankMemory(true);
		assertTrue(config.syncBankMemory());
	}
}
