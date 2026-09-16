package com.mystix;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class StorageItemLedgerTest {
	private static final Map<Integer, String> NAMES = new HashMap<>();

	static {
		NAMES.put(ItemID.RAW_ANGLERFISH, "Raw anglerfish");
		NAMES.put(ItemID.RAW_SHARK, "Raw shark");
		NAMES.put(ItemID.RAW_SHRIMP, "Raw shrimps");
		NAMES.put(ItemID.MORT_SLIMEY_EEL, "Raw slimy eel");
		NAMES.put(ItemID.TBWT_RAW_KARAMBWAN, "Raw karambwan");
		NAMES.put(ItemID.OAK_LOGS, "Oak logs");
		NAMES.put(ItemID.YEW_LOGS, "Yew logs");
		NAMES.put(ItemID.UNCUT_SAPPHIRE, "Uncut sapphire");
		NAMES.put(ItemID.UNCUT_RUBY, "Uncut ruby");
		NAMES.put(ItemID.UNCUT_RED_TOPAZ, "Uncut red topaz");
		NAMES.put(ItemID.COAL, "Coal");
		NAMES.put(ItemID.UNIDENTIFIED_RANARR, "Grimy ranarr weed");
		NAMES.put(ItemID.UNIDENTIFIED_GUAM, "Grimy guam leaf");
	}

	private static StorageItemLedger ledger(StorageItemSpec spec) {
		return new StorageItemLedger(spec, id -> NAMES.getOrDefault(id, "Item " + id));
	}

	private static Map<Integer, Integer> qty(int id, int q) {
		Map<Integer, Integer> m = new HashMap<>();
		m.put(id, q);
		return m;
	}

	@Test
	public void catchesWithOpenBarrelAreCreditedPerFish() {
		StorageItemLedger l = ledger(StorageItemSpec.fishBarrel());
		l.onGatherMessage("You catch an anglerfish!");
		assertTrue(l.settle(true));
		l.onGatherMessage("You catch a shark.");
		l.onGatherMessage("Rada's blessing enabled you to catch an extra fish.");
		l.settle(true);
		l.onGatherMessage("You catch some shrimps.");
		l.onGatherMessage("You catch a slimy swamp eel.");
		l.onGatherMessage("You catch a Karambwan.");
		l.settle(true);
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.RAW_ANGLERFISH));
		assertEquals(Integer.valueOf(2), l.contents().get(ItemID.RAW_SHARK));
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.RAW_SHRIMP));
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.MORT_SLIMEY_EEL));
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.TBWT_RAW_KARAMBWAN));
	}

	@Test
	public void closedBarrelOrFishLandingInInventoryIsNotCredited() {
		StorageItemLedger l = ledger(StorageItemSpec.fishBarrel());
		l.onGatherMessage("You catch an anglerfish!");
		assertFalse(l.settle(false));
		assertTrue(l.contents().isEmpty());

		// Open, but the fish showed up in the inventory: the barrel is full.
		l.onGatherMessage("You catch an anglerfish!");
		l.onInventoryDiff(qty(ItemID.RAW_ANGLERFISH, 1), Map.of(), false, false);
		assertFalse(l.settle(true));
		assertTrue(l.isFull());
		l.onGatherMessage("You catch an anglerfish!");
		l.settle(true);
		assertTrue(l.contents().isEmpty());
	}

	@Test
	public void infernalHarpoonCookedFishIsNotStored() {
		StorageItemLedger l = ledger(StorageItemSpec.fishBarrel());
		l.onGatherMessage("You catch an anglerfish!");
		l.onXp(Skill.COOKING, 230);
		l.settle(true);
		assertTrue(l.contents().isEmpty());
	}

	@Test
	public void fillMovesInventoryFishInAndCapacityCaps() {
		StorageItemLedger l = ledger(StorageItemSpec.fishBarrel());
		l.onInventoryDiff(Map.of(), qty(ItemID.RAW_SHARK, 20), true, false);
		l.onInventoryDiff(Map.of(), qty(ItemID.RAW_ANGLERFISH, 20), true, false);
		l.settle(false);
		assertEquals(Integer.valueOf(20), l.contents().get(ItemID.RAW_SHARK));
		assertEquals(Integer.valueOf(8), l.contents().get(ItemID.RAW_ANGLERFISH));
		assertEquals(28, l.total());
		assertTrue(l.isFull());
		// Without a recent Fill click, fish leaving the inventory is a drop / use.
		l.onInventoryDiff(Map.of(), qty(ItemID.RAW_SHARK, 5), false, false);
		l.settle(false);
		assertEquals(28, l.total());
	}

	@Test
	public void emptyingIntoTheBankClearsAndCheckResyncs() {
		StorageItemLedger l = ledger(StorageItemSpec.fishBarrel());
		l.onGatherMessage("You catch an anglerfish!");
		l.settle(true);
		assertTrue(l.onGameMessage("You empty all of your containers into the bank."));
		assertTrue(l.settle(true));
		assertTrue(l.contents().isEmpty());

		assertTrue(l.onCheckText("The barrel contains: 5 x Raw anglerfish,<br>3 x Raw shark"));
		assertTrue(l.settle(false));
		assertEquals(Integer.valueOf(5), l.contents().get(ItemID.RAW_ANGLERFISH));
		assertEquals(Integer.valueOf(3), l.contents().get(ItemID.RAW_SHARK));
		assertTrue(l.onCheckText("The barrel is empty."));
		l.settle(false);
		assertTrue(l.contents().isEmpty());
		// Unrelated text is ignored.
		assertFalse(l.onCheckText("You have 3 x Raw shark in your bank."));
		assertFalse(l.onGameMessage("Your Ring of Recoil has shattered."));
	}

	@Test
	public void fullMessageStopsCreditingUntilEmptied() {
		StorageItemLedger l = ledger(StorageItemSpec.fishBarrel());
		l.onGatherMessage("You catch an anglerfish!");
		l.settle(true);
		l.onGameMessage("The barrel is full. It may be emptied at a bank.");
		l.onGatherMessage("You catch an anglerfish!");
		l.settle(true);
		assertEquals(1, l.total());
		l.onGameMessage("Your containers are already empty.");
		l.settle(true);
		assertTrue(l.contents().isEmpty());
		assertFalse(l.isFull());
	}

	@Test
	public void logBasketCountsChoppedLogsAndPartialEmpties() {
		StorageItemLedger l = ledger(StorageItemSpec.logBasket());
		l.onGatherMessage("You get some oak logs.");
		l.onGatherMessage("Your Kandarin headgear provides you with an additional log.");
		l.settle(true);
		l.onGatherMessage("You get some yew logs.");
		l.onXp(Skill.FIREMAKING, 200); // infernal axe burnt it
		l.settle(true);
		assertEquals(Integer.valueOf(2), l.contents().get(ItemID.OAK_LOGS));
		assertNull(l.contents().get(ItemID.YEW_LOGS));
		// "Empty" then one log appears in the inventory.
		l.onInventoryDiff(qty(ItemID.OAK_LOGS, 1), Map.of(), false, true);
		l.settle(true);
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.OAK_LOGS));
		assertTrue(l.onGameMessage("You empty your basket into the bank."));
		l.settle(true);
		assertTrue(l.contents().isEmpty());
	}

	@Test
	public void gemBagParsesMinedGemsAndTheCheckMessage() {
		StorageItemLedger l = ledger(StorageItemSpec.gemBag());
		l.onGatherMessage("You just mined a sapphire!");
		l.onGatherMessage("You just found a ruby!");
		l.settle(true);
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.UNCUT_SAPPHIRE));
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.UNCUT_RUBY));
		assertTrue(l.onGameMessage("Sapphires: 12 / Rubies: 3 / Red topazes: 2"));
		l.settle(false);
		assertEquals(Integer.valueOf(12), l.contents().get(ItemID.UNCUT_SAPPHIRE));
		assertEquals(Integer.valueOf(3), l.contents().get(ItemID.UNCUT_RUBY));
		assertEquals(Integer.valueOf(2), l.contents().get(ItemID.UNCUT_RED_TOPAZ));
		assertTrue(l.onGameMessage("You empty your gem bag into the bank."));
		l.settle(false);
		assertTrue(l.contents().isEmpty());
	}

	@Test
	public void coalBagCountsMinedCoalAndParsesItsCheckMessage() {
		StorageItemLedger l = ledger(StorageItemSpec.coalBag());
		l.onGatherMessage("You manage to mine some coal.");
		l.onGatherMessage("Your Celestial ring allows you to mine an additional ore.");
		l.settle(true);
		assertEquals(Integer.valueOf(2), l.contents().get(ItemID.COAL));
		assertTrue(l.onGameMessage("The coal bag contains 27 pieces of coal."));
		l.settle(false);
		assertEquals(Integer.valueOf(27), l.contents().get(ItemID.COAL));
		assertTrue(l.onGameMessage("The coal bag contains one piece of coal."));
		l.settle(false);
		assertEquals(Integer.valueOf(1), l.contents().get(ItemID.COAL));
		assertTrue(l.onGameMessage("The coal bag is now empty."));
		l.settle(false);
		assertTrue(l.contents().isEmpty());
	}

	@Test
	public void herbSackCreditsHarvestsByFarmingXpOnly() {
		StorageItemLedger l = ledger(StorageItemSpec.herbSack());
		// Ranarr harvest: 30.5 XP shows as 30 or 31 with nothing entering the inventory.
		l.onXp(Skill.FARMING, 31);
		l.settle(true);
		l.onXp(Skill.FARMING, 30);
		l.settle(true);
		assertEquals(Integer.valueOf(2), l.contents().get(ItemID.UNIDENTIFIED_RANARR));
		// An allotment harvest changes the inventory: no herb credit even when the XP happens to match.
		l.onXp(Skill.FARMING, 12);
		l.onInventoryDiff(Map.of(), Map.of(), false, false);
		l.settle(true);
		assertNull(l.contents().get(ItemID.UNIDENTIFIED_GUAM));
		// Closed sack: the herb lands in the inventory instead.
		l.onXp(Skill.FARMING, 30);
		l.settle(false);
		assertEquals(Integer.valueOf(2), l.contents().get(ItemID.UNIDENTIFIED_RANARR));
		// Check lists one herb per line until the tick settles.
		assertTrue(l.onGameMessage("You look in your herb sack and see:"));
		assertTrue(l.onGameMessage("7 x Grimy ranarr weed"));
		assertTrue(l.onGameMessage("2 x Grimy guam leaf"));
		l.settle(true);
		assertEquals(Integer.valueOf(7), l.contents().get(ItemID.UNIDENTIFIED_RANARR));
		assertEquals(Integer.valueOf(2), l.contents().get(ItemID.UNIDENTIFIED_GUAM));
	}

	@Test
	public void singularisesCheckNames() {
		assertEquals("sapphire", StorageItemLedger.singular("Sapphires"));
		assertEquals("ruby", StorageItemLedger.singular("Rubies"));
		assertEquals("red topaz", StorageItemLedger.singular("Red topazes"));
		assertEquals("opal", StorageItemLedger.singular("Opal"));
	}
}
