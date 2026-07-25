package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.mystix.model.SlayerRewardsPayload;
import org.junit.Test;

/**
 * Tests for SlayerRewardsMonitor row parsing (the widget-scrape half is
 * exercised in-client; parsing is the part with real failure modes).
 */
public class SlayerRewardsMonitorTest {
	@Test
	public void testParsesNameRangeAndPercent() {
		SlayerRewardsPayload.Row row =
				SlayerRewardsMonitor.parseRow("Aberrant Spectres (130-200)", 2848, "4.8%");
		assertNotNull(row);
		assertEquals("Aberrant Spectres", row.getName());
		assertEquals(4.8, row.getPercent(), 0.0001);
	}

	@Test
	public void testParsesZeroPercentBlockedRow() {
		SlayerRewardsPayload.Row row =
				SlayerRewardsMonitor.parseRow("Abyssal Demons (130-200)", 6412, "0.0%");
		assertNotNull(row);
		assertEquals(0.0, row.getPercent(), 0.0001);
	}

	@Test
	public void testParsesNameWithoutRange() {
		SlayerRewardsPayload.Row row = SlayerRewardsMonitor.parseRow("Bosses", 2848, "2.1%");
		assertNotNull(row);
		assertEquals("Bosses", row.getName());
	}

	@Test
	public void testStripsColorTags() {
		SlayerRewardsPayload.Row row = SlayerRewardsMonitor.parseRow(
				"<col=ff9040>Ankou (50-80)</col>", 6413, "<col=ff9040>3.4%</col>");
		assertNotNull(row);
		assertEquals("Ankou", row.getName());
		assertEquals(3.4, row.getPercent(), 0.0001);
	}

	@Test
	public void testRejectsNonPercentText() {
		assertNull(SlayerRewardsMonitor.parseRow("Ankou (50-80)", 2848, "Confirm"));
	}

	@Test
	public void testRejectsNulls() {
		assertNull(SlayerRewardsMonitor.parseRow(null, 2848, "3.4%"));
		assertNull(SlayerRewardsMonitor.parseRow("Ankou", 2848, null));
	}
}
