package com.mystix;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mystix.api.MystixApiClient;
import com.mystix.model.LoadoutSyncPayload;
import java.util.ArrayList;
import java.util.EnumSet;
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
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Monitors player equipment and Inventory Setups, syncing loadout data
 * to the Mystix API. Syncs on:
 * - Login (3s delay)
 * - Logout (immediate)
 * - Equipment change (60s debounce)
 */
@Slf4j
@Singleton
public class LoadoutMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	private static final int EQUIPMENT_CHANGE_DEBOUNCE_SECONDS = 60;

	private static final String INVENTORY_SETUPS_CONFIG_GROUP = "inventorysetups";
	private static final String SETUPS_V3_PREFIX = "setupsV3_";

	/**
	 * Maps RuneLite EquipmentInventorySlot indices to backend slot strings.
	 */
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
			ItemManager itemManager) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.executorService = executorService;
		this.configManager = configManager;
		this.itemManager = itemManager;
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
		if (event.getContainerId() != InventoryID.EQUIPMENT.getId()) {
			return;
		}

		if (!config.syncLoadouts()) {
			return;
		}

		// Cancel any pending debounce and reschedule
		if (debounceFuture != null) {
			debounceFuture.cancel(false);
		}
		log.debug("Equipment changed, scheduling loadout sync in {}s", EQUIPMENT_CHANGE_DEBOUNCE_SECONDS);
		debounceFuture = executorService.schedule(() -> clientThread.invokeLater(this::syncLoadouts),
				EQUIPMENT_CHANGE_DEBOUNCE_SECONDS, TimeUnit.SECONDS);
	}

	private void syncLoadouts() {
		if (config.mystixAppKey() == null || config.mystixAppKey().isBlank()) {
			log.debug("Loadout sync skipped: no App Key configured");
			return;
		}
		if (!config.syncLoadouts()) {
			return;
		}
		if (isSpecialGameMode()) {
			log.debug("Loadout sync skipped: special game mode detected");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		String playerUsername = localPlayer != null ? localPlayer.getName() : null;
		if (playerUsername == null || playerUsername.isBlank()) {
			log.warn("Loadout sync skipped: could not get player username");
			return;
		}

		List<LoadoutSyncPayload.LoadoutSet> loadoutSets = new ArrayList<>();

		// Build active gear loadout
		LoadoutSyncPayload.LoadoutSet activeGear = buildActiveGearLoadout();
		if (activeGear != null) {
			loadoutSets.add(activeGear);
		}

		// Build inventory setups from ConfigManager
		List<LoadoutSyncPayload.LoadoutSet> inventorySetups = buildInventorySetups();
		loadoutSets.addAll(inventorySetups);

		LoadoutSyncPayload payload = new LoadoutSyncPayload(playerUsername, loadoutSets);
		String json = payload.toJson();

		// Deduplicate: only send if contents changed
		if (json.equals(lastSyncJson)) {
			log.debug("Loadout contents unchanged, skipping sync");
			return;
		}

		lastSyncJson = json;
		log.info("Syncing {} loadout sets for player: {}", loadoutSets.size(), playerUsername);
		apiClient.sendLoadoutSync(payload);
	}

	/**
	 * Builds the active gear loadout from the equipment container.
	 */
	private LoadoutSyncPayload.LoadoutSet buildActiveGearLoadout() {
		ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		if (equipment == null) {
			return null;
		}

		List<LoadoutSyncPayload.EquipmentItem> equipmentItems = new ArrayList<>();
		Item[] items = equipment.getItems();

		for (int i = 0; i < items.length; i++) {
			String slot = EQUIPMENT_SLOT_MAP.get(i);
			if (slot == null) {
				continue;
			}

			Item item = items[i];
			if (item.getId() == -1 || item.getQuantity() <= 0) {
				continue;
			}

			int canonicalId = itemManager.canonicalize(item.getId());
			equipmentItems.add(new LoadoutSyncPayload.EquipmentItem(canonicalId, slot));
		}

		if (equipmentItems.isEmpty()) {
			return null;
		}

		return new LoadoutSyncPayload.LoadoutSet(
				"Current Gear", "active_gear", null,
				equipmentItems, new ArrayList<>());
	}

	/**
	 * Reads Inventory Setups data from ConfigManager and builds loadout sets.
	 * Supports V3 (current, per-setup keys), V2, and V1 (legacy single-array) formats.
	 */
	private List<LoadoutSyncPayload.LoadoutSet> buildInventorySetups() {
		List<LoadoutSyncPayload.LoadoutSet> setups = new ArrayList<>();
		Gson gson = new Gson();

		// Try V3 first (current format): each setup stored under "setupsV3_<hash>"
		try {
			String wholePrefix = ConfigManager.getWholeKey(INVENTORY_SETUPS_CONFIG_GROUP, null, SETUPS_V3_PREFIX);
			List<String> v3Keys = configManager.getConfigurationKeys(wholePrefix);
			if (v3Keys != null && !v3Keys.isEmpty()) {
				log.debug("Found {} Inventory Setups (V3 format)", v3Keys.size());

				// Load sections to map setup names -> categories
				Map<String, String> sectionMap = loadSectionMap(gson);

				for (String fullKey : v3Keys) {
					// Extract the config key after the group prefix ("inventorysetups.")
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
				return setups;
			}
		} catch (Exception e) {
			log.debug("V3 Inventory Setups read failed: {}", e.getMessage());
		}

		// Fall back to V2, then V1 (legacy single-array formats)
		String setupsJson = configManager.getConfiguration(INVENTORY_SETUPS_CONFIG_GROUP, "setupsV2");
		if (setupsJson == null || setupsJson.isBlank()) {
			setupsJson = configManager.getConfiguration(INVENTORY_SETUPS_CONFIG_GROUP, "setups");
		}
		if (setupsJson == null || setupsJson.isBlank()) {
			return setups;
		}

		try {
			JsonArray setupsArray = gson.fromJson(setupsJson, JsonArray.class);
			log.debug("Found {} Inventory Setups (legacy format)", setupsArray.size());
			for (JsonElement element : setupsArray) {
				JsonObject setup = element.getAsJsonObject();
				LoadoutSyncPayload.LoadoutSet loadoutSet = parseInventorySetup(setup);
				if (loadoutSet != null) {
					setups.add(loadoutSet);
				}
			}
		} catch (Exception e) {
			log.warn("Failed to parse Inventory Setups data: {}", e.getMessage());
		}

		return setups;
	}

	/**
	 * Loads the sections config and builds a map of setup name -> section name.
	 */
	private Map<String, String> loadSectionMap(Gson gson) {
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

	/**
	 * Parses a V3 Inventory Setup JSON object (uses "eq"/"inv" field names, null for empty slots).
	 */
	private LoadoutSyncPayload.LoadoutSet parseInventorySetupV3(JsonObject setup, Map<String, String> sectionMap) {
		String name = setup.has("name") ? setup.get("name").getAsString() : "Unnamed Setup";
		String category = sectionMap.getOrDefault(name, null);

		List<LoadoutSyncPayload.EquipmentItem> equipmentItems = new ArrayList<>();
		List<LoadoutSyncPayload.InventoryItem> inventoryItems = new ArrayList<>();

		// V3 uses "eq" for equipment
		if (setup.has("eq") && !setup.get("eq").isJsonNull()) {
			JsonArray eqArray = setup.getAsJsonArray("eq");
			for (int i = 0; i < eqArray.size(); i++) {
				if (eqArray.get(i).isJsonNull()) {
					continue;
				}
				JsonObject eq = eqArray.get(i).getAsJsonObject();
				int itemId = eq.has("id") ? eq.get("id").getAsInt() : -1;
				if (itemId <= 0) {
					continue;
				}

				String slot = EQUIPMENT_SLOT_MAP.get(i);
				if (slot == null) {
					continue;
				}

				int canonicalId = itemManager.canonicalize(itemId);
				equipmentItems.add(new LoadoutSyncPayload.EquipmentItem(canonicalId, slot));
			}
		}

		// V3 uses "inv" for inventory
		if (setup.has("inv") && !setup.get("inv").isJsonNull()) {
			JsonArray invArray = setup.getAsJsonArray("inv");
			for (int i = 0; i < invArray.size(); i++) {
				if (invArray.get(i).isJsonNull()) {
					continue;
				}
				JsonObject inv = invArray.get(i).getAsJsonObject();
				int itemId = inv.has("id") ? inv.get("id").getAsInt() : -1;
				if (itemId <= 0) {
					continue;
				}

				int canonicalId = itemManager.canonicalize(itemId);
				inventoryItems.add(new LoadoutSyncPayload.InventoryItem(canonicalId, i));
			}
		}

		if (equipmentItems.isEmpty() && inventoryItems.isEmpty()) {
			return null;
		}

		return new LoadoutSyncPayload.LoadoutSet(
				name, "inventory_setup", category,
				equipmentItems, inventoryItems);
	}

	/**
	 * Parses a legacy (V1/V2) Inventory Setup JSON object into a LoadoutSet.
	 */
	private LoadoutSyncPayload.LoadoutSet parseInventorySetup(JsonObject setup) {
		String name = setup.has("name") ? setup.get("name").getAsString() : "Unnamed Setup";

		String category = null;
		if (setup.has("stackName") && !setup.get("stackName").isJsonNull()) {
			category = setup.get("stackName").getAsString();
		}

		List<LoadoutSyncPayload.EquipmentItem> equipmentItems = new ArrayList<>();
		List<LoadoutSyncPayload.InventoryItem> inventoryItems = new ArrayList<>();

		if (setup.has("equipment") && !setup.get("equipment").isJsonNull()) {
			JsonArray equipmentArray = setup.getAsJsonArray("equipment");
			for (int i = 0; i < equipmentArray.size(); i++) {
				JsonObject eq = equipmentArray.get(i).getAsJsonObject();
				int itemId = eq.has("id") ? eq.get("id").getAsInt() : -1;
				if (itemId <= 0) {
					continue;
				}

				String slot = EQUIPMENT_SLOT_MAP.get(i);
				if (slot == null) {
					continue;
				}

				int canonicalId = itemManager.canonicalize(itemId);
				equipmentItems.add(new LoadoutSyncPayload.EquipmentItem(canonicalId, slot));
			}
		}

		if (setup.has("inventory") && !setup.get("inventory").isJsonNull()) {
			JsonArray inventoryArray = setup.getAsJsonArray("inventory");
			for (int i = 0; i < inventoryArray.size(); i++) {
				JsonObject inv = inventoryArray.get(i).getAsJsonObject();
				int itemId = inv.has("id") ? inv.get("id").getAsInt() : -1;
				if (itemId <= 0) {
					continue;
				}

				int canonicalId = itemManager.canonicalize(itemId);
				inventoryItems.add(new LoadoutSyncPayload.InventoryItem(canonicalId, i));
			}
		}

		if (equipmentItems.isEmpty() && inventoryItems.isEmpty()) {
			return null;
		}

		return new LoadoutSyncPayload.LoadoutSet(
				name, "inventory_setup", category,
				equipmentItems, inventoryItems);
	}

	/**
	 * Checks if the player is on a special game mode world.
	 */
	private boolean isSpecialGameMode() {
		EnumSet<WorldType> worldTypes = client.getWorldType();
		return worldTypes.contains(WorldType.SEASONAL)
				|| worldTypes.contains(WorldType.DEADMAN)
				|| worldTypes.contains(WorldType.FRESH_START_WORLD)
				|| worldTypes.contains(WorldType.TOURNAMENT_WORLD)
				|| worldTypes.contains(WorldType.BETA_WORLD)
				|| worldTypes.contains(WorldType.NOSAVE_MODE)
				|| worldTypes.contains(WorldType.QUEST_SPEEDRUNNING);
	}
}
