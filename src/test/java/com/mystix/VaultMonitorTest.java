package com.mystix;

import net.runelite.api.gameval.InventoryID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class VaultMonitorTest {
	@Test
	public void eachTrackedContainerHasItsOwnSource() {
		assertEquals("seed_vault", VaultMonitor.sourceFor(InventoryID.SEED_VAULT));
		assertEquals("looting_bag", VaultMonitor.sourceFor(InventoryID.LOOTING_BAG));
		assertEquals("boat_cargo_hold_1", VaultMonitor.sourceFor(InventoryID.SAILING_BOAT_1_CARGOHOLD));
		assertEquals("boat_cargo_hold_5", VaultMonitor.sourceFor(InventoryID.SAILING_BOAT_5_CARGOHOLD));
		assertEquals("trawling_net", VaultMonitor.sourceFor(InventoryID.SAILING_TRAWLING_NET));
		assertEquals("seed_box", VaultMonitor.sourceFor(InventoryID.SEED_BOX));
		assertEquals("tackle_box", VaultMonitor.sourceFor(InventoryID.TACKLE_BOX));
		assertEquals("forestry_kit", VaultMonitor.sourceFor(InventoryID.FORESTRY_KIT));
		assertEquals("huntsmans_kit", VaultMonitor.sourceFor(InventoryID.HUNTSMANS_KIT));
	}

	@Test
	public void inventoryAndBankAreNotVaultSources() {
		assertNull(VaultMonitor.sourceFor(InventoryID.INV));
		assertNull(VaultMonitor.sourceFor(InventoryID.WORN));
		assertNull(VaultMonitor.sourceFor(InventoryID.BANK));
	}

	@Test
	public void interfaceOwnedCargoHoldIdsMapToTheirBoat() {
		assertEquals("boat_cargo_hold_3", VaultMonitor.sourceFor(33733));
		assertEquals("boat_cargo_hold_1", VaultMonitor.sourceFor(InventoryID.SAILING_BOAT_1_CARGOHOLD | VaultMonitor.REMOTE_CONTAINER_FLAG));
		assertEquals("trawling_net", VaultMonitor.sourceFor(InventoryID.SAILING_TRAWLING_NET | VaultMonitor.REMOTE_CONTAINER_FLAG));
		assertNull(VaultMonitor.sourceFor(InventoryID.SEED_VAULT | VaultMonitor.REMOTE_CONTAINER_FLAG));
	}
}
