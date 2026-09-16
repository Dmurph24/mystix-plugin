package com.mystix;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Describes one storage item whose contents the game never exposes as a
 * container or varbit (fish barrel, log basket, gem bag, coal bag, herb
 * sack): which items it takes, its capacity, and the chat text the game
 * emits when something goes in, when it is checked, and when it is emptied.
 * The bookkeeping lives in {@link StorageItemLedger}; RuneLite wiring in
 * {@link StorageItemMonitor}.
 */
final class StorageItemSpec {
	/** Chat lines (GAMEMESSAGE) every "empty your containers" bank action emits. */
	static final List<String> CONTAINERS_EMPTIED_MESSAGES = List.of(
			"You empty all of your containers into the bank.",
			"Your containers are already empty.");

	final String source;
	final Set<Integer> closedIds;
	final Set<Integer> openIds;
	/** Items the container accepts (canonical ids). */
	final Set<Integer> acceptedIds;
	/** Combined capacity, or 0 for no combined cap. */
	final int capacity;
	/** Per-item capacity, or 0 for none. */
	final int perItemCapacity;
	/** SPAM chat line emitted when an accepted item is gathered; group 1 is the item name. Null when the item has none. */
	final Pattern gatherPattern;
	/** SPAM lines meaning "one more of the item just gathered". */
	final Set<String> extraGatherMessages;
	/** Exact GAMEMESSAGE lines meaning the container is now empty. */
	final Set<String> emptiedMessages;
	/** GAMEMESSAGE prefixes meaning the container was emptied into the bank. */
	final Set<String> emptiedPrefixes;
	/** Exact GAMEMESSAGE lines meaning the container is full (gathering now lands in the inventory). */
	final Set<String> fullMessages;
	/** Chat / widget text prefix of the "Check" listing, e.g. "The barrel contains:". */
	final String checkPrefix;
	/** Chat / widget text meaning "Check" found it empty. */
	final Set<String> checkEmptyMessages;
	/** Skill whose XP drop on a gather tick means one gathered item was consumed instead of stored (infernal tools). */
	final Skill consumeSkill;
	/** Skill whose XP drop, with no inventory change, is itself the store signal (herb sack: Farming). */
	final Skill xpCreditSkill;
	/** For {@link #xpCreditSkill}: XP per stored item, keyed by item id. */
	final Map<Integer, Double> xpPerItem;
	/** Leading word to strip from item names before matching chat text ("raw ", "uncut "). */
	final String namePrefixToStrip;
	/** Chat names that differ from the item name (after the prefix strip). */
	final Map<String, String> nameAliases;

	private StorageItemSpec(Builder b) {
		this.source = b.source;
		this.closedIds = Set.copyOf(b.closedIds);
		this.openIds = Set.copyOf(b.openIds);
		this.acceptedIds = Set.copyOf(b.acceptedIds);
		this.capacity = b.capacity;
		this.perItemCapacity = b.perItemCapacity;
		this.gatherPattern = b.gatherPattern;
		this.extraGatherMessages = Set.copyOf(b.extraGatherMessages);
		this.emptiedMessages = Set.copyOf(b.emptiedMessages);
		this.emptiedPrefixes = Set.copyOf(b.emptiedPrefixes);
		this.fullMessages = Set.copyOf(b.fullMessages);
		this.checkPrefix = b.checkPrefix;
		this.checkEmptyMessages = Set.copyOf(b.checkEmptyMessages);
		this.consumeSkill = b.consumeSkill;
		this.xpCreditSkill = b.xpCreditSkill;
		this.xpPerItem = Collections.unmodifiableMap(new LinkedHashMap<>(b.xpPerItem));
		this.namePrefixToStrip = b.namePrefixToStrip;
		this.nameAliases = Collections.unmodifiableMap(new HashMap<>(b.nameAliases));
	}

	boolean isContainerItem(int itemId) {
		return closedIds.contains(itemId) || openIds.contains(itemId);
	}

	/** The chat form of an item name: lower case, container prefix stripped. */
	String chatName(String itemName) {
		String name = itemName == null ? "" : itemName.trim().toLowerCase();
		if (namePrefixToStrip != null && name.startsWith(namePrefixToStrip)) {
			name = name.substring(namePrefixToStrip.length());
		}
		return name;
	}

	// ------------------------------------------------------------- specs

	static StorageItemSpec fishBarrel() {
		return new Builder("fish_barrel")
				.closed(ItemID.FISH_BARREL_CLOSED, ItemID.FISH_SACK_BARREL_CLOSED)
				.open(ItemID.FISH_BARREL_OPEN, ItemID.FISH_SACK_BARREL_OPEN)
				.accepts(ItemID.RAW_SHRIMP, ItemID.RAW_SARDINE, ItemID.RAW_HERRING, ItemID.RAW_ANCHOVIES,
						ItemID.RAW_MACKEREL, ItemID.RAW_TROUT, ItemID.RAW_COD, ItemID.RAW_PIKE, ItemID.MORT_SLIMEY_EEL,
						ItemID.RAW_SALMON, ItemID.RAW_TUNA, ItemID.HUNTING_RAW_FISH_SPECIAL, ItemID.RAW_CAVE_EEL,
						ItemID.RAW_LOBSTER, ItemID.RAW_BASS, ItemID.BRUT_SPAWNING_TROUT, ItemID.RAW_SWORDFISH,
						ItemID.RAW_LAVA_EEL, ItemID.BRUT_SPAWNING_SALMON, ItemID.RAW_MONKFISH, ItemID.TBWT_RAW_KARAMBWAN,
						ItemID.BRUT_STURGEON, ItemID.RAW_SHARK, ItemID.INFERNAL_EEL, ItemID.RAW_ANGLERFISH,
						ItemID.RAW_DARK_CRAB, ItemID.SNAKEBOSS_EEL, ItemID.RAW_SWORDTIP_SQUID, ItemID.RAW_JUMBO_SQUID,
						ItemID.AERIAL_FISHING_BLUEGILL, ItemID.AERIAL_FISHING_COMMON_TENCH,
						ItemID.AERIAL_FISHING_MOTTLED_EEL, ItemID.AERIAL_FISHING_GREATER_SIREN,
						ItemID.RAW_SEATURTLE, ItemID.RAW_MANTARAY)
				.capacity(28)
				.gather("^You catch (?:an?|some)(?: raw)? ([a-zA-Z ]+?)[.!]?(?: It hardens as you handle it with your ice gloves\\.)?$")
				.extraGather("Rada's blessing enabled you to catch an extra fish.",
						"The spirit flakes enabled you to catch an extra fish.")
				.emptied("The barrel is empty.")
				.full("The barrel is full. It may be emptied at a bank.")
				.check("The barrel contains:", "The barrel is empty.")
				.consumeSkill(Skill.COOKING)
				.stripPrefix("raw ")
				.alias("slimy swamp eel", "slimy eel")
				.alias("shrimp", "shrimps")
				.build();
	}

	static StorageItemSpec logBasket() {
		return new Builder("log_basket")
				.closed(ItemID.LOG_BASKET_CLOSED, ItemID.FORESTRY_BASKET_CLOSED)
				.open(ItemID.LOG_BASKET_OPEN, ItemID.FORESTRY_BASKET_OPEN)
				.accepts(ItemID.LOGS, ItemID.ACHEY_TREE_LOGS, ItemID.OAK_LOGS, ItemID.WILLOW_LOGS, ItemID.TEAK_LOGS,
						ItemID.MAPLE_LOGS, ItemID.MAHOGANY_LOGS, ItemID.ARCTIC_PINE_LOG, ItemID.YEW_LOGS,
						ItemID.MAGIC_LOGS, ItemID.REDWOOD_LOGS, ItemID.ROSEWOOD_LOGS, ItemID.IRONWOOD_LOGS,
						ItemID.CAMPHOR_LOGS, ItemID.BLISTERWOOD_LOGS, ItemID.JUNIPER_LOGS)
				.capacity(28)
				.gather("^You get some ([a-zA-Z ]+?)\\.?$")
				.extraGather("Your Kandarin headgear provides you with an additional log.",
						"The nature offerings enabled you to chop an extra log.")
				.emptied("You empty your basket into the bank.", "You empty your basket.",
						"Your basket is empty.", "The basket is empty.")
				.full("The basket is full.")
				.check("The basket contains:", "The basket is empty.")
				.consumeSkill(Skill.FIREMAKING)
				.build();
	}

	static StorageItemSpec gemBag() {
		return new Builder("gem_bag")
				.closed(ItemID.GEM_BAG, ItemID.GEM_POUCH, ItemID.GEM_SATCHEL, ItemID.GEM_TOTE, ItemID.GEM_SACK)
				.open(ItemID.GEM_BAG_OPEN, ItemID.GEM_POUCH_OPEN, ItemID.GEM_SATCHEL_OPEN, ItemID.GEM_TOTE_OPEN,
						ItemID.GEM_SACK_OPEN)
				.accepts(ItemID.UNCUT_SAPPHIRE, ItemID.UNCUT_EMERALD, ItemID.UNCUT_RUBY, ItemID.UNCUT_DIAMOND,
						ItemID.UNCUT_DRAGONSTONE, ItemID.UNCUT_OPAL, ItemID.UNCUT_JADE, ItemID.UNCUT_RED_TOPAZ)
				.perItemCapacity(60)
				.gather("^You just (?:mined|found) an? ([a-zA-Z ]+?)[.!]?$")
				.emptiedPrefix("You empty your gem")
				.emptied("The gem bag is now empty.", "The gem bag is empty.")
				.check("", "The gem bag is empty.")
				.stripPrefix("uncut ")
				.build();
	}

	static StorageItemSpec coalBag() {
		return new Builder("coal_bag")
				.closed(ItemID.COAL_BAG)
				.open(ItemID.COAL_BAG_OPEN)
				.accepts(ItemID.COAL)
				.capacity(36) // 27, or 36 with a Smithing cape; the full message stops crediting either way
				.gather("^You manage to mine some (coal)\\.$")
				.extraGather("Your Celestial ring allows you to mine an additional ore.",
						"The Varrock platebody enabled you to mine an additional ore.")
				.emptied("The coal bag is now empty.", "The coal bag is empty.")
				.full("The coal bag is full.")
				.check("The coal bag", "The coal bag is empty.")
				.build();
	}

	static StorageItemSpec herbSack() {
		Builder b = new Builder("herb_sack")
				.closed(ItemID.SLAYER_HERB_SACK, ItemID.SLAYER_HERB_SACK_SILK)
				.open(ItemID.SLAYER_HERB_SACK_OPEN, ItemID.SLAYER_HERB_SACK_SILK_OPEN)
				// 30 per herb, or 100 for the silk-lined sack. The larger bound is
				// safe: once a sack is full the herb lands in the inventory, which
				// blocks the credit.
				.perItemCapacity(100)
				.emptiedPrefix("You empty your herb sack")
				.emptied("The herb sack is empty.")
				.check("You look in your herb sack and see:", "The herb sack is empty.")
				.xpCreditSkill(Skill.FARMING)
				.stripPrefix("grimy ");
		// Farming XP per herb harvested: the only per-herb signal the game gives
		// when an open sack swallows the harvest.
		b.xp(ItemID.UNIDENTIFIED_GUAM, 12.5).xp(ItemID.UNIDENTIFIED_MARENTILL, 15).xp(ItemID.UNIDENTIFIED_TARROMIN, 18)
				.xp(ItemID.UNIDENTIFIED_HARRALANDER, 24).xp(ItemID.UNIDENTIFIED_RANARR, 30.5)
				.xp(ItemID.UNIDENTIFIED_TOADFLAX, 38.5).xp(ItemID.UNIDENTIFIED_IRIT, 48.5)
				.xp(ItemID.UNIDENTIFIED_AVANTOE, 61.5).xp(ItemID.UNIDENTIFIED_KWUARM, 78)
				.xp(ItemID.UNIDENTIFIED_SNAPDRAGON, 98.5).xp(ItemID.UNIDENTIFIED_HUASCA, 110)
				.xp(ItemID.UNIDENTIFIED_CADANTINE, 120).xp(ItemID.UNIDENTIFIED_LANTADYME, 151.5)
				.xp(ItemID.UNIDENTIFIED_DWARF_WEED, 192).xp(ItemID.UNIDENTIFIED_TORSTOL, 224.5);
		b.accepts(b.xpPerItem.keySet().stream().mapToInt(Integer::intValue).toArray());
		return b.build();
	}

	static List<StorageItemSpec> all() {
		return List.of(fishBarrel(), logBasket(), gemBag(), coalBag(), herbSack());
	}

	// ---------------------------------------------------------- builder

	private static final class Builder {
		final String source;
		final Set<Integer> closedIds = new java.util.HashSet<>();
		final Set<Integer> openIds = new java.util.HashSet<>();
		final Set<Integer> acceptedIds = new java.util.LinkedHashSet<>();
		int capacity;
		int perItemCapacity;
		Pattern gatherPattern;
		final Set<String> extraGatherMessages = new java.util.HashSet<>();
		final Set<String> emptiedMessages = new java.util.HashSet<>(CONTAINERS_EMPTIED_MESSAGES);
		final Set<String> emptiedPrefixes = new java.util.HashSet<>();
		final Set<String> fullMessages = new java.util.HashSet<>();
		String checkPrefix = "";
		final Set<String> checkEmptyMessages = new java.util.HashSet<>();
		Skill consumeSkill;
		Skill xpCreditSkill;
		final Map<Integer, Double> xpPerItem = new LinkedHashMap<>();
		String namePrefixToStrip;
		final Map<String, String> nameAliases = new HashMap<>();

		Builder(String source) {
			this.source = source;
		}

		Builder closed(int... ids) { for (int id : ids) closedIds.add(id); return this; }
		Builder open(int... ids) { for (int id : ids) openIds.add(id); return this; }
		Builder accepts(int... ids) { for (int id : ids) acceptedIds.add(id); return this; }
		Builder capacity(int c) { capacity = c; return this; }
		Builder perItemCapacity(int c) { perItemCapacity = c; return this; }
		Builder gather(String regex) { gatherPattern = Pattern.compile(regex); return this; }
		Builder extraGather(String... m) { Collections.addAll(extraGatherMessages, m); return this; }
		Builder emptied(String... m) { Collections.addAll(emptiedMessages, m); return this; }
		Builder emptiedPrefix(String... m) { Collections.addAll(emptiedPrefixes, m); return this; }
		Builder full(String... m) { Collections.addAll(fullMessages, m); return this; }
		Builder check(String prefix, String... empty) { checkPrefix = prefix; Collections.addAll(checkEmptyMessages, empty); return this; }
		Builder consumeSkill(Skill s) { consumeSkill = s; return this; }
		Builder xpCreditSkill(Skill s) { xpCreditSkill = s; return this; }
		Builder xp(int itemId, double xp) { xpPerItem.put(itemId, xp); return this; }
		Builder stripPrefix(String p) { namePrefixToStrip = p; return this; }
		Builder alias(String chat, String item) { nameAliases.put(chat, item); return this; }

		StorageItemSpec build() {
			return new StorageItemSpec(this);
		}
	}
}
