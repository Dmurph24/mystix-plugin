package com.mystix;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Reads the storage state the community "Charges Improved" plugin
 * (config group {@code tictac7x-charges}) keeps for the same storage items
 * we infer. When a player runs it, its state is exact and battle-tested,
 * so it overrides our own inference: read once at login and again on every
 * change it writes. Read-only: the plugin hub forbids writing to another
 * plugin's config group.
 */
final class ChargesPluginBridge {
	static final String CONFIG_GROUP = "tictac7x-charges";
	static final String STORAGE_SUFFIX = "_storage";

	/** Their config key (without the suffix) to our bank-memory source. Several keys fold into one source. */
	static final Map<String, String> SOURCE_BY_KEY;

	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("fish_barrel", "fish_barrel");
		m.put("herb_sack", "herb_sack");
		m.put("silklined_herb_sack", "herb_sack");
		m.put("log_basket", "log_basket");
		m.put("forestry_basket", "log_basket");
		m.put("gem_bag", "gem_bag");
		m.put("gem_pouch", "gem_bag");
		m.put("gem_satchel", "gem_bag");
		m.put("gem_tote", "gem_bag");
		m.put("gem_sack", "gem_bag");
		m.put("coal_bag", "coal_bag");
		SOURCE_BY_KEY = Collections.unmodifiableMap(m);
	}

	private ChargesPluginBridge() {
	}

	/** Our source for one of their config keys ({@code fish_barrel_storage}), or null when not one we mirror. */
	static String sourceForConfigKey(String key) {
		if (key == null || !key.endsWith(STORAGE_SUFFIX)) {
			return null;
		}
		return SOURCE_BY_KEY.get(key.substring(0, key.length() - STORAGE_SUFFIX.length()));
	}

	/** Their JSON ({@code [{"itemId":13439,"quantity":5}]}) to item id to quantity; null when absent or malformed. */
	static Map<Integer, Integer> parse(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			JsonElement root = new JsonParser().parse(json);
			if (!root.isJsonArray()) {
				return null;
			}
			Map<Integer, Integer> contents = new LinkedHashMap<>();
			for (JsonElement e : (JsonArray) root) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject o = e.getAsJsonObject();
				if (!o.has("itemId") || !o.has("quantity")) {
					continue;
				}
				int qty = o.get("quantity").getAsInt();
				if (qty > 0) {
					contents.merge(o.get("itemId").getAsInt(), qty, Integer::sum);
				}
			}
			return contents;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	/**
	 * Every mirrored source's contents as their plugin has them, summing the
	 * keys that fold into one source. Sources with no stored value at all are
	 * left out (their plugin is not installed, or never tracked that item).
	 */
	static Map<String, Map<Integer, Integer>> readAll(BiFunction<String, String, String> config) {
		Map<String, Map<Integer, Integer>> bySource = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : SOURCE_BY_KEY.entrySet()) {
			Map<Integer, Integer> contents = parse(config.apply(CONFIG_GROUP, e.getKey() + STORAGE_SUFFIX));
			if (contents == null) {
				continue;
			}
			Map<Integer, Integer> merged = bySource.computeIfAbsent(e.getValue(), s -> new LinkedHashMap<>());
			contents.forEach((id, qty) -> merged.merge(id, qty, Integer::sum));
		}
		return bySource;
	}

	/** The config keys that fold into a source. */
	static List<String> keysFor(String source) {
		List<String> keys = new java.util.ArrayList<>();
		for (Map.Entry<String, String> e : SOURCE_BY_KEY.entrySet()) {
			if (e.getValue().equals(source)) {
				keys.add(e.getKey() + STORAGE_SUFFIX);
			}
		}
		return keys;
	}
}
