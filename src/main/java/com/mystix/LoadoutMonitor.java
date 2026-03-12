package com.mystix;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mystix.api.MystixApiClient;
import com.mystix.model.LoadoutSyncPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

@Slf4j
@Singleton
public class LoadoutMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	private static final int EQUIPMENT_CHANGE_DEBOUNCE_SECONDS = 60;
	private static final int EMPTY_SLOT_ID = -1;
	private static final int MAX_INVENTORY_SLOTS = 28;

	private static final String INVENTORY_SETUPS_CONFIG_GROUP = "inventorysetups";
	private static final String SETUPS_V3_PREFIX = "setupsV3_";

	private static final Map<Integer, String> EQUIPMENT_SLOT_MAP = new HashMap<>();

	static {
		EQUIPMENT_SLOT_MAP.put(0, "head");
		EQUIPMENT_SLOT_MAP.put(1, "cape");
		EQUIPMENT_SLOT_MAP.put(2, "neck");
		EQUIPMENT_SLOT_MAP.put(3, "weapon");
		EQUIPMENT_SLOT_MAP.put(4, "body");
		EQUIPMENT_SLOT_MAP.put(5, "shield");
		EQUIPMENT_SLOT_MAP.put(7, "legs");
		EQUIPMENT_SLOT_MAP.put(9, "hands");
		EQUIPMENT_SLOT_MAP.put(10, "feet");
		EQUIPMENT_SLOT_MAP.put(12, "ring");
		EQUIPMENT_SLOT_MAP.put(13, "ammo");
	}

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final ScheduledExecutorService executorService;
	private final ConfigManager configManager;
	private final ItemManager itemManager;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	private ScheduledFuture<?> debounceFuture = null;
	private String lastSyncJson = null;

	@Inject
	public LoadoutMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			EventBus eventBus,
			ScheduledExecutorService executorService,
			ConfigManager configManager,
			ItemManager itemManager,
			Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.executorService = executorService;
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	public void start() {
		eventBus.register(this);
		log.info("LoadoutMonitor started");
	}

	public void stop() {
		eventBus.unregister(this);
		if (debounceFuture != null) {
			debounceFuture.cancel(false);
			debounceFuture = null;
		}
		previousGameState = GameState.UNKNOWN;
		lastSyncJson = null;
		log.debug("LoadoutMonitor stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (!config.syncLoadouts()) {
			return;
		}

		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			log.debug("Player logged in, scheduling loadout sync in {}s", LOGIN_SYNC_DELAY_SECONDS);
			executorService.schedule(() -> clientThread.invokeLater(this::syncLoadouts),
					LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			log.debug("Player logged out, syncing loadouts immediately");
			syncLoadouts();
		}

		previousGameState = newState;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		int containerId = event.getContainerId();
		if (containerId != InventoryID.WORN && containerId != InventoryID.INV) {
			return;
		}

		if (!config.syncLoadouts()) {
			return;
		}

		if (debounceFuture != null) {
			debounceFuture.cancel(false);
		}
		log.debug("Equipment/inventory changed, scheduling loadout sync in {}s", EQUIPMENT_CHANGE_DEBOUNCE_SECONDS);
		debounceFuture = executorService.schedule(() -> clientThread.invokeLater(this::syncLoadouts),
				EQUIPMENT_CHANGE_DEBOUNCE_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * Builds the full loadout payload (active gear + Inventory Setups) and sends
	 * it to the Mystix API if the contents changed since the last sync.
	 */
	private void syncLoadouts() {
		if (!SyncGuard.hasAppKey(config)) {
			log.debug("Loadout sync skipped: no App Key configured");
			return;
		}
		if (!config.syncLoadouts()) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Loadout sync skipped: special game mode detected");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("Loadout sync skipped: could not get player username");
			return;
		}

		List<LoadoutSyncPayload.LoadoutSet> loadoutSets = new ArrayList<>();

		LoadoutSyncPayload.LoadoutSet activeGear = buildActiveGearLoadout();
		if (activeGear != null) {
			loadoutSets.add(activeGear);
		}

		loadoutSets.addAll(buildInventorySetups());

		LoadoutSyncPayload payload = new LoadoutSyncPayload(playerUsername, loadoutSets);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Loadout contents unchanged, skipping sync");
			return;
		}

		lastSyncJson = json;
		log.info("Syncing {} loadout sets for player: {}", loadoutSets.size(), playerUsername);
		apiClient.sendLoadoutSync(payload);
	}

	/**
	 * Reads the player's currently worn equipment and inventory, returning them
	 * as an "active_gear" loadout set. Returns null if both are empty.
	 */
	private LoadoutSyncPayload.LoadoutSet buildActiveGearLoadout() {
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null) {
			return null;
		}

		List<LoadoutSyncPayload.EquipmentItem> equipmentItems = parseEquipmentSlots(equipment.getItems());

		List<LoadoutSyncPayload.InventoryItem> inventoryItems = new ArrayList<>();
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null) {
			Item[] invItems = inventory.getItems();
			for (int i = 0; i < invItems.length && i < MAX_INVENTORY_SLOTS; i++) {
				Item item = invItems[i];
				if (item.getId() == EMPTY_SLOT_ID || item.getQuantity() <= 0) {
					continue;
				}
				int canonicalId = itemManager.canonicalize(item.getId());
				inventoryItems.add(new LoadoutSyncPayload.InventoryItem(canonicalId, i));
			}
		}

		if (equipmentItems.isEmpty() && inventoryItems.isEmpty()) {
			return null;
		}

		return new LoadoutSyncPayload.LoadoutSet(
				"Current Gear", "active_gear", null,
				equipmentItems, inventoryItems);
	}

	private List<LoadoutSyncPayload.EquipmentItem> parseEquipmentSlots(Item[] items) {
		List<LoadoutSyncPayload.EquipmentItem> equipmentItems = new ArrayList<>();
		for (int i = 0; i < items.length; i++) {
			String slot = EQUIPMENT_SLOT_MAP.get(i);
			if (slot == null) {
				continue;
			}
			Item item = items[i];
			if (item.getId() == EMPTY_SLOT_ID || item.getQuantity() <= 0) {
				continue;
			}
			int canonicalId = itemManager.canonicalize(item.getId());
			equipmentItems.add(new LoadoutSyncPayload.EquipmentItem(canonicalId, slot));
		}
		return equipmentItems;
	}

	/**
	 * Reads saved Inventory Setups from ConfigManager. Tries V3 format first,
	 * falling back to legacy V2/V1 formats if V3 is unavailable.
	 */
	private List<LoadoutSyncPayload.LoadoutSet> buildInventorySetups() {
		List<LoadoutSyncPayload.LoadoutSet> setups = new ArrayList<>();

		if (tryLoadV3Setups(setups)) {
			return setups;
		}

		loadLegacySetups(setups);
		return setups;
	}

	/**
	 * Attempts to load Inventory Setups using the V3 per-setup key format.
	 * Returns true if V3 keys were found (even if parsing individual setups fails).
	 */
	private boolean tryLoadV3Setups(List<LoadoutSyncPayload.LoadoutSet> setups) {
		try {
			String wholePrefix = ConfigManager.getWholeKey(INVENTORY_SETUPS_CONFIG_GROUP, null, SETUPS_V3_PREFIX);
			List<String> v3Keys = configManager.getConfigurationKeys(wholePrefix);
			if (v3Keys == null || v3Keys.isEmpty()) {
				return false;
			}

			log.debug("Found {} Inventory Setups (V3 format)", v3Keys.size());
			Map<String, String> sectionMap = loadSectionMap();

			for (String fullKey : v3Keys) {
				String configKey = fullKey.substring(INVENTORY_SETUPS_CONFIG_GROUP.length() + 1);
				String setupJson = configManager.getConfiguration(INVENTORY_SETUPS_CONFIG_GROUP, configKey);
				if (setupJson == null || setupJson.isBlank()) {
					continue;
				}
				try {
					JsonObject setup = gson.fromJson(setupJson, JsonObject.class);
					LoadoutSyncPayload.LoadoutSet loadoutSet = parseInventorySetupV3(setup, sectionMap);
					if (loadoutSet != null) {
						setups.add(loadoutSet);
					}
				} catch (Exception e) {
					log.debug("Failed to parse V3 setup key {}: {}", configKey, e.getMessage());
				}
			}
			return true;
		} catch (Exception e) {
			log.debug("V3 Inventory Setups read failed: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Loads Inventory Setups from legacy V2 or V1 single-array config format.
	 */
	private void loadLegacySetups(List<LoadoutSyncPayload.LoadoutSet> setups) {
		String setupsJson = configManager.getConfiguration(INVENTORY_SETUPS_CONFIG_GROUP, "setupsV2");
		if (setupsJson == null || setupsJson.isBlank()) {
			setupsJson = configManager.getConfiguration(INVENTORY_SETUPS_CONFIG_GROUP, "setups");
		}
		if (setupsJson == null || setupsJson.isBlank()) {
			return;
		}

		try {
			JsonArray setupsArray = gson.fromJson(setupsJson, JsonArray.class);
			log.debug("Found {} Inventory Setups (legacy format)", setupsArray.size());
			for (JsonElement element : setupsArray) {
				JsonObject setup = element.getAsJsonObject();
				LoadoutSyncPayload.LoadoutSet loadoutSet = parseLegacySetup(setup);
				if (loadoutSet != null) {
					setups.add(loadoutSet);
				}
			}
		} catch (Exception e) {
			log.warn("Failed to parse Inventory Setups data: {}", e.getMessage());
		}
	}

	/**
	 * Reads the Inventory Setups sections config and builds a map of
	 * setup name to section/category name for folder grouping.
	 */
	private Map<String, String> loadSectionMap() {
		Map<String, String> sectionMap = new HashMap<>();
		try {
			String sectionsJson = configManager.getConfiguration(INVENTORY_SETUPS_CONFIG_GROUP, "sections");
			if (sectionsJson != null && !sectionsJson.isBlank()) {
				JsonArray sections = gson.fromJson(sectionsJson, JsonArray.class);
				for (JsonElement sectionEl : sections) {
					JsonObject section = sectionEl.getAsJsonObject();
					String sectionName = section.has("name") ? section.get("name").getAsString() : null;
					if (sectionName != null && section.has("setups")) {
						JsonArray setupNames = section.getAsJsonArray("setups");
						for (JsonElement nameEl : setupNames) {
							sectionMap.put(nameEl.getAsString(), sectionName);
						}
					}
				}
			}
		} catch (Exception e) {
			log.debug("Failed to load Inventory Setups sections: {}", e.getMessage());
		}
		return sectionMap;
	}

	private LoadoutSyncPayload.LoadoutSet parseInventorySetupV3(JsonObject setup, Map<String, String> sectionMap) {
		String name = getJsonString(setup, "name", "Unnamed Setup");
		String category = sectionMap.getOrDefault(name, null);

		List<LoadoutSyncPayload.EquipmentItem> equipmentItems = parseJsonEquipmentArray(setup, "eq");
		List<LoadoutSyncPayload.InventoryItem> inventoryItems = parseJsonInventoryArray(setup, "inv");

		if (equipmentItems.isEmpty() && inventoryItems.isEmpty()) {
			return null;
		}

		return new LoadoutSyncPayload.LoadoutSet(name, "inventory_setup", category, equipmentItems, inventoryItems);
	}

	private LoadoutSyncPayload.LoadoutSet parseLegacySetup(JsonObject setup) {
		String name = getJsonString(setup, "name", "Unnamed Setup");

		String category = null;
		if (setup.has("stackName") && !setup.get("stackName").isJsonNull()) {
			category = setup.get("stackName").getAsString();
		}

		List<LoadoutSyncPayload.EquipmentItem> equipmentItems = parseJsonEquipmentArray(setup, "equipment");
		List<LoadoutSyncPayload.InventoryItem> inventoryItems = parseJsonInventoryArray(setup, "inventory");

		if (equipmentItems.isEmpty() && inventoryItems.isEmpty()) {
			return null;
		}

		return new LoadoutSyncPayload.LoadoutSet(name, "inventory_setup", category, equipmentItems, inventoryItems);
	}

	/**
	 * Parses a JSON array of equipment items from the given field, mapping each
	 * slot index to a named equipment slot via EQUIPMENT_SLOT_MAP.
	 */
	private List<LoadoutSyncPayload.EquipmentItem> parseJsonEquipmentArray(JsonObject setup, String fieldName) {
		List<LoadoutSyncPayload.EquipmentItem> items = new ArrayList<>();
		if (!setup.has(fieldName) || setup.get(fieldName).isJsonNull()) {
			return items;
		}

		JsonArray array = setup.getAsJsonArray(fieldName);
		for (int i = 0; i < array.size(); i++) {
			if (array.get(i).isJsonNull()) {
				continue;
			}
			JsonObject eq = array.get(i).getAsJsonObject();
			int itemId = eq.has("id") ? eq.get("id").getAsInt() : EMPTY_SLOT_ID;
			if (itemId <= 0) {
				continue;
			}

			String slot = EQUIPMENT_SLOT_MAP.get(i);
			if (slot == null) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(itemId);
			items.add(new LoadoutSyncPayload.EquipmentItem(canonicalId, slot));
		}
		return items;
	}

	/**
	 * Parses a JSON array of inventory items from the given field, preserving
	 * each item's slot index position.
	 */
	private List<LoadoutSyncPayload.InventoryItem> parseJsonInventoryArray(JsonObject setup, String fieldName) {
		List<LoadoutSyncPayload.InventoryItem> items = new ArrayList<>();
		if (!setup.has(fieldName) || setup.get(fieldName).isJsonNull()) {
			return items;
		}

		JsonArray array = setup.getAsJsonArray(fieldName);
		for (int i = 0; i < array.size(); i++) {
			if (array.get(i).isJsonNull()) {
				continue;
			}
			JsonObject inv = array.get(i).getAsJsonObject();
			int itemId = inv.has("id") ? inv.get("id").getAsInt() : EMPTY_SLOT_ID;
			if (itemId <= 0) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(itemId);
			items.add(new LoadoutSyncPayload.InventoryItem(canonicalId, i));
		}
		return items;
	}

	private String getJsonString(JsonObject obj, String key, String defaultValue) {
		return obj.has(key) ? obj.get(key).getAsString() : defaultValue;
	}
}
