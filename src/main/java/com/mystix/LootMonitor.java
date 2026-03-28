package com.mystix;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mystix.api.MystixApiClient;
import com.mystix.model.LootDropPayload;
import com.mystix.model.LootSyncPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

@Slf4j
@Singleton
public class LootMonitor
{
	private static final int PROFILE_SYNC_DELAY_SECONDS = 5;
	private static final String LOOT_TRACKER_CONFIG_GROUP = "loottracker";
	private static final String LOOT_TRACKER_DROPS_PREFIX = "drops_";

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final ScheduledExecutorService executorService;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final ItemManager itemManager;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	/** Tracks cumulative kill counts per NPC name for the current session. */
	private final Map<String, Integer> sessionKillCounts = new LinkedHashMap<>();

	@Inject
	public LootMonitor(
		Client client,
		MystixConfig config,
		MystixApiClient apiClient,
		EventBus eventBus,
		ScheduledExecutorService executorService,
		ClientThread clientThread,
		ConfigManager configManager,
		ItemManager itemManager,
		Gson gson)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.executorService = executorService;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.itemManager = itemManager;
		this.gson = gson;
	}

	public void start()
	{
		eventBus.register(this);
		log.info("LootMonitor started");
	}

	public void stop()
	{
		eventBus.unregister(this);
		previousGameState = GameState.UNKNOWN;
		sessionKillCounts.clear();
		log.debug("LootMonitor stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN)
		{
			log.debug("Player logged in, waiting for RS profile before loot sync");
			// Use clientThread.invokeLater to poll each tick until the RS profile
			// key is available. Returns false to retry, true when done.
			clientThread.invokeLater(() ->
			{
				if (client.getGameState().getState() < GameState.LOGGED_IN.getState())
				{
					// Logged out before profile loaded — abort
					return true;
				}
				String profileKey = configManager.getRSProfileKey();
				if (profileKey == null)
				{
					return false; // not ready yet, retry next tick
				}
				log.debug("RS profile ready ({}), scheduling loot sync in {}s", profileKey, PROFILE_SYNC_DELAY_SECONDS);
				executorService.schedule(this::syncLoot, PROFILE_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
				return true;
			});
		}
		else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN)
		{
			log.debug("Player logged out, syncing loot");
			syncLoot();
		}

		previousGameState = newState;
	}

	/**
	 * Handles loot drops from the RuneLite loot tracker plugin.
	 * LootReceived fires after the loot tracker processes a kill, providing
	 * the NPC name, kill count (amount), items, and NPC metadata with the ID.
	 */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getType() != LootRecordType.NPC && event.getType() != LootRecordType.EVENT)
		{
			return;
		}
		if (!config.syncLoot())
		{
			return;
		}
		if (!SyncGuard.hasAppKey(config))
		{
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client))
		{
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null)
		{
			return;
		}

		String npcName = event.getName();

		// event.getAmount() is the number of kills in THIS event (always 1),
		// not cumulative KC. We track KC ourselves: seed from the loot tracker
		// config on first kill, then increment locally per kill.
		int killCount = sessionKillCounts.compute(npcName, (name, current) ->
		{
			if (current == null)
			{
				// First kill this session — seed from loot tracker config.
				// Config has the count BEFORE this kill, so add the event amount.
				return readCumulativeKillCount(name) + event.getAmount();
			}
			return current + event.getAmount();
		});

		// The metadata contains an NpcMetadata object with the NPC ID.
		// NpcMetadata is package-private, so we access it via reflection-free approach:
		// resolve from nearby loaded NPCs by name, or from metadata if castable.
		int npcId = resolveNpcIdFromEvent(event);

		List<LootSyncPayload.LootItem> items = new ArrayList<>();
		for (ItemStack itemStack : event.getItems())
		{
			int itemId = canonicalizeItemId(itemStack.getId());
			items.add(new LootSyncPayload.LootItem(itemId, itemStack.getQuantity()));
		}

		if (items.isEmpty())
		{
			return;
		}

		LootDropPayload payload = new LootDropPayload(playerUsername, npcId, npcName, killCount, items);
		log.info("Loot drop from {} (id={}, kc={}): {} items", npcName, npcId, killCount, items.size());
		apiClient.sendLootDrop(payload);
	}

	/**
	 * Reads cumulative loot data from RuneLite's loot tracker config and syncs to the API.
	 */
	private void syncLoot()
	{
		if (!config.syncLoot())
		{
			return;
		}
		if (!SyncGuard.hasAppKey(config))
		{
			log.debug("Loot sync skipped: no App Key configured");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client))
		{
			log.debug("Loot sync skipped: special game mode detected");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null)
		{
			log.warn("Loot sync skipped: could not get player username");
			return;
		}

		List<LootSyncPayload.LootRecord> lootRecords = readLootTrackerData();
		if (lootRecords.isEmpty())
		{
			log.debug("No loot tracker data to sync");
			return;
		}

		LootSyncPayload payload = new LootSyncPayload(playerUsername, lootRecords);
		log.info("Syncing {} loot records for player: {}", lootRecords.size(), playerUsername);
		apiClient.sendLootSync(payload);
	}

	/**
	 * Reads cumulative loot data from RuneLite's loot tracker ConfigManager storage.
	 *
	 * The loot tracker stores ConfigLoot objects as JSON under RSProfile keys
	 * with the format: drops_{LootRecordType}_{NpcName} (e.g. "drops_NPC_Zulrah").
	 */
	private List<LootSyncPayload.LootRecord> readLootTrackerData()
	{
		List<LootSyncPayload.LootRecord> records = new ArrayList<>();

		try
		{
			String profileKey = configManager.getRSProfileKey();
			if (profileKey == null)
			{
				log.debug("No RS profile key available for loot sync");
				return records;
			}

			List<String> keys = configManager.getRSProfileConfigurationKeys(
				LOOT_TRACKER_CONFIG_GROUP, profileKey, LOOT_TRACKER_DROPS_PREFIX
			);

			for (String key : keys)
			{
				try
				{
					String json = configManager.getConfiguration(LOOT_TRACKER_CONFIG_GROUP, profileKey, key);
					if (json == null || json.isEmpty())
					{
						continue;
					}

					LootSyncPayload.LootRecord record = parseConfigLootJson(json);
					if (record != null)
					{
						records.add(record);
					}
				}
				catch (Exception e)
				{
					log.debug("Failed to parse loot tracker key: {}", key, e);
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to read loot tracker data from config", e);
		}

		return records;
	}

	/**
	 * Parses a serialized ConfigLoot JSON object into our LootRecord format.
	 * This runs on the executor thread so it avoids client-thread-only APIs
	 * (no NPC lookups, no ItemManager calls). The loot tracker already stores
	 * canonical item IDs and the NPC name is used by the backend for matching.
	 */
	private LootSyncPayload.LootRecord parseConfigLootJson(String json)
	{
		JsonObject obj = new JsonParser().parse(json).getAsJsonObject();

		String type = obj.has("type") ? obj.get("type").getAsString() : "";
		if (!"NPC".equals(type) && !"EVENT".equals(type))
		{
			return null;
		}

		String npcName = obj.has("name") ? obj.get("name").getAsString() : null;
		if (npcName == null || npcName.isEmpty())
		{
			return null;
		}

		// Use a deterministic hash for the NPC ID since we can't call client
		// APIs from the executor thread. The backend resolves the real NPC via name.
		int npcId = npcName.hashCode() & 0x7FFFFFFF;
		int killCount = obj.has("kills") ? obj.get("kills").getAsInt() : 0;

		Map<Integer, Integer> aggregatedItems = new LinkedHashMap<>();
		if (obj.has("drops"))
		{
			JsonArray drops = obj.getAsJsonArray("drops");
			for (int i = 0; i + 1 < drops.size(); i += 2)
			{
				int itemId = drops.get(i).getAsInt();
				int quantity = drops.get(i + 1).getAsInt();
				aggregatedItems.merge(itemId, quantity, Integer::sum);
			}
		}

		List<LootSyncPayload.LootItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregatedItems.entrySet())
		{
			items.add(new LootSyncPayload.LootItem(entry.getKey(), entry.getValue()));
		}

		log.debug("Parsed loot record: {} (id={}, kills={}, items={})", npcName, npcId, killCount, items.size());
		return new LootSyncPayload.LootRecord(npcId, npcName, killCount, items);
	}

	/**
	 * Reads the cumulative kill count for an NPC from the loot tracker's config.
	 * The loot tracker stores config under key "drops_NPC_{name}" with a "kills" field.
	 * This runs on the client thread (called from onLootReceived) so ConfigManager access is safe.
	 */
	private int readCumulativeKillCount(String npcName)
	{
		try
		{
			String profileKey = configManager.getRSProfileKey();
			if (profileKey == null)
			{
				return 0;
			}
			String configKey = LOOT_TRACKER_DROPS_PREFIX + "NPC_" + npcName;
			String json = configManager.getConfiguration(LOOT_TRACKER_CONFIG_GROUP, profileKey, configKey);
			if (json == null || json.isEmpty())
			{
				return 0;
			}
			JsonObject obj = new JsonParser().parse(json).getAsJsonObject();
			return obj.has("kills") ? obj.get("kills").getAsInt() : 0;
		}
		catch (Exception e)
		{
			log.debug("Could not read cumulative kill count for {}", npcName, e);
			return 0;
		}
	}

	/**
	 * Resolves the NPC ID from a LootReceived event.
	 * Tries to extract from the event metadata, falls back to nearby NPC lookup.
	 */
	private int resolveNpcIdFromEvent(LootReceived event)
	{
		// The metadata object is an NpcMetadata which has getId().
		// Since NpcMetadata is package-private, we use a generic approach.
		Object metadata = event.getMetadata();
		if (metadata != null)
		{
			try
			{
				// NpcMetadata has a public getId() method
				int id = (int) metadata.getClass().getMethod("getId").invoke(metadata);
				if (id > 0)
				{
					return id;
				}
			}
			catch (Exception e)
			{
				log.debug("Could not extract NPC ID from metadata: {}", e.getMessage());
			}
		}

		return resolveNpcId(event.getName());
	}

	/**
	 * Resolves an NPC name to its NPC ID by checking currently loaded NPCs.
	 * Falls back to a deterministic hash if no matching NPC is found nearby.
	 */
	private int resolveNpcId(String npcName)
	{
		for (NPC npc : getWorldNpcs())
		{
			if (npc.getName() != null && npc.getName().equalsIgnoreCase(npcName))
			{
				return npc.getId();
			}
		}
		return npcName.hashCode() & 0x7FFFFFFF;
	}

	private Iterable<? extends NPC> getWorldNpcs()
	{
		WorldView wv = client.getTopLevelWorldView();
		return wv == null ? Collections.emptyList() : wv.npcs();
	}

	/**
	 * Converts noted item IDs to their base item ID using the ItemManager.
	 */
	private int canonicalizeItemId(int itemId)
	{
		ItemComposition comp = itemManager.getItemComposition(itemId);
		if (comp != null && comp.getNote() != -1)
		{
			return comp.getLinkedNoteId();
		}
		return itemId;
	}
}
