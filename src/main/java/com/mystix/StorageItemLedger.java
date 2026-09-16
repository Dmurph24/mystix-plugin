package com.mystix;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Inferred contents of one storage item (see {@link StorageItemSpec}),
 * per item id. Pure: fed chat lines, inventory diffs, XP drops and the
 * "Check" text by {@link StorageItemMonitor}, settled once per game tick.
 *
 * <p>Gather messages ("You catch an anglerfish") while the open container is
 * carried credit the item unless it landed in the inventory that tick (the
 * container was full or closed). "Fill" moves the inventory's accepted items
 * in; "Empty" moves them out; the bank's empty-containers action clears it;
 * the Check listing is an exact resync. The ledger is only ever as good as
 * these signals, so the monitor logs inferred-vs-checked totals to judge it.
 */
@Slf4j
final class StorageItemLedger {
	private static final Pattern CHECK_ENTRY = Pattern.compile("(\\d+)\\s*[x×]\\s*([A-Za-z' ]+?)\\s*(?:,|$|/)");
	private static final Pattern GEM_ENTRY = Pattern.compile("([A-Za-z ]+?):\\s*(\\d+)");
	private static final Pattern COAL_ENTRY = Pattern.compile("contains (\\d+|one) pieces? of coal");
	private static final Pattern TAGS = Pattern.compile("<[^>]+>");

	final StorageItemSpec spec;
	/** Resolves an item id to its in-game name; supplied by the monitor (client thread). */
	private final IntFunction<String> itemName;
	/** What the server last knew this container held (goal items only); applied before the first signal of a session. */
	private final Supplier<Map<Integer, Integer>> seed;

	private final Map<Integer, Integer> contents = new LinkedHashMap<>();
	private Map<String, Integer> chatNameIndex;
	private boolean full;
	private boolean changed;
	/** True once a real signal (gather, fill, empty, check) has been seen this session. */
	private boolean known;
	private boolean seeded;

	// Per-tick observations.
	private final Map<Integer, Integer> gatheredThisTick = new HashMap<>();
	private final Map<Integer, Integer> inventoryGainThisTick = new HashMap<>();
	private int consumedThisTick;
	private int lastGatheredItem = -1;
	private boolean inventoryChangedThisTick;
	private int xpGainedThisTick;
	private Map<Integer, Integer> checkListing;
	private boolean checkedEmpty;

	StorageItemLedger(StorageItemSpec spec, IntFunction<String> itemName) {
		this(spec, itemName, Map::of);
	}

	StorageItemLedger(StorageItemSpec spec, IntFunction<String> itemName, Supplier<Map<Integer, Integer>> seed) {
		this.spec = spec;
		this.itemName = itemName;
		this.seed = seed == null ? Map::of : seed;
	}

	/**
	 * Contents are unknown at login. Rather than start from zero (which would
	 * overwrite the server's figure with nothing and lose whatever was already
	 * inside), the first signal of the session builds on what the server last
	 * saw for the goal items. Cleared by {@link #resetSession()}.
	 */
	private void ensureSeeded() {
		if (seeded) {
			return;
		}
		seeded = true;
		Map<Integer, Integer> server = seed.get();
		if (server != null) {
			server.forEach((id, qty) -> {
				if (spec.acceptedIds.contains(id) && qty != null && qty > 0) {
					contents.put(id, qty);
				}
			});
		}
	}

	/** Logout: the next session seeds again. */
	void resetSession() {
		seeded = false;
		known = false;
		full = false;
	}

	/** True once this session has observed the container (before that nothing is pushed or uploaded). */
	boolean isKnown() {
		return known;
	}

	// ----------------------------------------------------------- inputs

	/** A SPAM chat line while the open container is carried. */
	void onGatherMessage(String message) {
		if (message == null) {
			return;
		}
		ensureSeeded();
		if (spec.extraGatherMessages.contains(message)) {
			if (lastGatheredItem > 0 && !gatheredThisTick.isEmpty()) {
				gatheredThisTick.merge(lastGatheredItem, 1, Integer::sum);
			}
			return;
		}
		if (spec.gatherPattern == null) {
			return;
		}
		Matcher m = spec.gatherPattern.matcher(message);
		if (!m.matches()) {
			return;
		}
		int itemId = itemIdForChatName(m.group(1));
		if (itemId > 0) {
			gatheredThisTick.merge(itemId, 1, Integer::sum);
			lastGatheredItem = itemId;
		}
	}

	/** A GAMEMESSAGE line while the container is carried. Returns true when it was about this container. */
	boolean onGameMessage(String message) {
		if (message == null) {
			return false;
		}
		ensureSeeded();
		String text = clean(message);
		if (spec.emptiedMessages.contains(text) || spec.checkEmptyMessages.contains(text)) {
			clear();
			return true;
		}
		for (String prefix : spec.emptiedPrefixes) {
			if (text.startsWith(prefix)) {
				clear();
				return true;
			}
		}
		if (spec.fullMessages.contains(text)) {
			full = true;
			known = true;
			return true;
		}
		return onCheckText(text);
	}

	/**
	 * The "Check" listing (chat lines or the message-box widget). Herb sack
	 * lists one herb per line, so lines accumulate until the tick settles.
	 * Returns true when the text was recognised.
	 */
	boolean onCheckText(String text) {
		if (text == null) {
			return false;
		}
		ensureSeeded();
		text = clean(text);
		if (spec.checkEmptyMessages.contains(text)) {
			checkedEmpty = true;
			known = true;
			return true;
		}
		Map<Integer, Integer> parsed = parseListing(text);
		if (parsed == null) {
			return false;
		}
		if (checkListing == null) {
			checkListing = new LinkedHashMap<>();
		}
		parsed.forEach((id, qty) -> checkListing.merge(id, qty, Integer::sum));
		known = true;
		return true;
	}

	/** The inventory changed: {@code added} / {@code removed} are per-item deltas of accepted items only. */
	void onInventoryDiff(Map<Integer, Integer> added, Map<Integer, Integer> removed, boolean recentFill, boolean recentEmpty) {
		ensureSeeded();
		inventoryChangedThisTick = true;
		added.forEach((id, qty) -> inventoryGainThisTick.merge(id, qty, Integer::sum));
		if (recentFill && !removed.isEmpty()) {
			removed.forEach((id, qty) -> add(id, qty));
			if (!full && spec.capacity > 0 && total() >= spec.capacity) {
				full = true;
			}
		}
		if (recentEmpty && !added.isEmpty()) {
			added.forEach((id, qty) -> add(id, -qty));
			full = false;
		}
	}

	/** An XP drop in a skill this container cares about. */
	void onXp(net.runelite.api.Skill skill, int xpGained) {
		ensureSeeded();
		if (skill == spec.consumeSkill) {
			consumedThisTick++;
		}
		if (skill == spec.xpCreditSkill) {
			xpGainedThisTick += xpGained;
		}
	}

	/**
	 * Once per game tick. {@code open} is whether an open variant was carried.
	 * Returns true when the contents changed.
	 */
	boolean settle(boolean open) {
		if (checkListing != null || checkedEmpty) {
			Map<Integer, Integer> exact = checkListing == null ? Map.of() : checkListing;
			log.debug("{} check: inferred {} vs listed {}", spec.source, contents, exact);
			contents.clear();
			exact.forEach((id, qty) -> add(id, qty));
			full = false;
			changed = true;
		} else if (open) {
			creditGathers();
			creditXp();
		}
		gatheredThisTick.clear();
		inventoryGainThisTick.clear();
		consumedThisTick = 0;
		inventoryChangedThisTick = false;
		xpGainedThisTick = 0;
		checkListing = null;
		checkedEmpty = false;
		boolean result = changed;
		changed = false;
		return result;
	}

	private void creditGathers() {
		if (gatheredThisTick.isEmpty()) {
			return;
		}
		boolean landedInInventory = inventoryGainThisTick.values().stream().anyMatch(q -> q > 0);
		if (landedInInventory) {
			// The container did not take it: it is full (or the item is not accepted).
			full = true;
			return;
		}
		if (full) {
			return;
		}
		int consumed = consumedThisTick;
		for (Map.Entry<Integer, Integer> e : gatheredThisTick.entrySet()) {
			int credit = e.getValue();
			int eaten = Math.min(consumed, credit);
			credit -= eaten;
			consumed -= eaten;
			if (credit > 0) {
				add(e.getKey(), credit);
			}
		}
	}

	private void creditXp() {
		if (spec.xpCreditSkill == null || xpGainedThisTick <= 0 || inventoryChangedThisTick || full) {
			return;
		}
		// Whole-number XP drops of a fractional value alternate around it.
		for (Map.Entry<Integer, Double> e : spec.xpPerItem.entrySet()) {
			if (Math.abs(xpGainedThisTick - e.getValue()) <= 1.0) {
				add(e.getKey(), 1);
				return;
			}
		}
	}

	// ------------------------------------------------------------ state

	Map<Integer, Integer> contents() {
		return new LinkedHashMap<>(contents);
	}

	int total() {
		int t = 0;
		for (int q : contents.values()) {
			t += q;
		}
		return t;
	}

	boolean isFull() {
		return full;
	}

	void clear() {
		if (!contents.isEmpty()) {
			changed = true;
		}
		contents.clear();
		full = false;
		known = true;
	}

	private void add(int itemId, int delta) {
		if (!spec.acceptedIds.contains(itemId) || delta == 0) {
			return;
		}
		int current = contents.getOrDefault(itemId, 0);
		int next = Math.max(0, current + delta);
		if (spec.perItemCapacity > 0) {
			next = Math.min(next, spec.perItemCapacity);
		}
		if (spec.capacity > 0 && delta > 0) {
			int room = spec.capacity - (total() - current);
			next = Math.min(next, Math.max(0, room));
		}
		if (next == current) {
			return;
		}
		if (next == 0) {
			contents.remove(itemId);
		} else {
			contents.put(itemId, next);
		}
		changed = true;
		known = true;
	}

	// ---------------------------------------------------------- parsing

	/** "The barrel contains: 5 x Raw anglerfish, 3 x Raw shark", "Sapphires: 4 / Rubies: 1", "The coal bag contains 27 pieces of coal." */
	Map<Integer, Integer> parseListing(String text) {
		boolean prefixed = spec.checkPrefix == null || spec.checkPrefix.isEmpty() || text.startsWith(spec.checkPrefix);
		if (!prefixed && checkListing == null) {
			return null; // not a listing, and no listing in progress for follow-up lines
		}
		if (!prefixed) {
			return parseEntries(text);
		}
		Map<Integer, Integer> out = new LinkedHashMap<>();
		Matcher coal = COAL_ENTRY.matcher(text);
		if (spec.acceptedIds.size() == 1 && coal.find()) {
			String n = coal.group(1);
			out.put(spec.acceptedIds.iterator().next(), "one".equals(n) ? 1 : Integer.parseInt(n));
			return out;
		}
		String body = spec.checkPrefix == null ? text : text.substring(spec.checkPrefix.length());
		Map<Integer, Integer> entries = parseEntries(body);
		if (entries != null) {
			out.putAll(entries);
		}
		// A listing header with the entries on following lines (herb sack) is
		// still a listing: it opens the resync that those lines fill in.
		boolean header = !spec.checkPrefix.isEmpty() && body.trim().isEmpty();
		return out.isEmpty() && !header ? null : out;
	}

	/** "5 x Raw anglerfish, 3 x Raw shark" or "Sapphires: 4 / Rubies: 1"; null when nothing parses. */
	private Map<Integer, Integer> parseEntries(String body) {
		Map<Integer, Integer> out = new LinkedHashMap<>();
		Matcher entries = CHECK_ENTRY.matcher(body);
		while (entries.find()) {
			int id = itemIdForChatName(entries.group(2));
			if (id > 0) {
				out.merge(id, Integer.parseInt(entries.group(1)), Integer::sum);
			}
		}
		if (out.isEmpty() && spec.namePrefixToStrip != null && spec.namePrefixToStrip.startsWith("uncut")) {
			Matcher gems = GEM_ENTRY.matcher(body);
			while (gems.find()) {
				int id = itemIdForChatName(singular(gems.group(1)));
				if (id > 0) {
					out.merge(id, Integer.parseInt(gems.group(2)), Integer::sum);
				}
			}
		}
		return out.isEmpty() ? null : out;
	}

	/** "Sapphires" to "sapphire", "Rubies" to "ruby", "Red topazes" to "red topaz". */
	static String singular(String plural) {
		String p = plural.trim().toLowerCase(Locale.ROOT);
		if (p.endsWith("ies")) {
			return p.substring(0, p.length() - 3) + "y";
		}
		if (p.endsWith("zes")) {
			return p.substring(0, p.length() - 2);
		}
		if (p.endsWith("s")) {
			return p.substring(0, p.length() - 1);
		}
		return p;
	}

	private int itemIdForChatName(String chatName) {
		if (chatName == null) {
			return -1;
		}
		String name = chatName.trim().toLowerCase(Locale.ROOT);
		name = spec.nameAliases.getOrDefault(name, name);
		if (spec.namePrefixToStrip != null && name.startsWith(spec.namePrefixToStrip)) {
			name = name.substring(spec.namePrefixToStrip.length());
		}
		Map<String, Integer> index = chatNameIndex();
		Integer id = index.get(name);
		return id == null ? -1 : id;
	}

	private Map<String, Integer> chatNameIndex() {
		if (chatNameIndex == null) {
			Map<String, Integer> index = new HashMap<>();
			for (int id : spec.acceptedIds) {
				String n = itemName.apply(id);
				if (n != null && !n.isEmpty() && !"null".equals(n)) {
					index.put(spec.chatName(n), id);
				}
			}
			chatNameIndex = index;
		}
		return chatNameIndex;
	}

	private static String clean(String text) {
		return TAGS.matcher(text.replace("<br>", " ")).replaceAll("").replace(' ', ' ').trim();
	}
}
