package com.mystix;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mystix.api.MystixApiClient;
import com.mystix.model.CollectionLogEntryPayload;
import com.mystix.model.CollectionLogSyncPayload;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.StructComposition;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the in-game Collection Log interface using two complementary strategies:
 *
 * <p><b>Strategy 1 — Game cache + search trick (bulk capture):</b>
 * On login, parses the game cache (enum 2102 → structs → item enums) to build the full
 * collection log structure (all tabs, pages, and item IDs). When the player opens the
 * collection log, automatically triggers the in-game search script (2240) which causes
 * script 4100 to fire once per <em>obtained</em> item. This captures all obtained items
 * across the entire log without the player needing to browse every page.
 *
 * <p><b>Strategy 2 — Widget scraping (enrichment):</b>
 * When the player manually browses a collection log page, captures full details from the
 * widgets: item quantities, kill counts, and NPC associations. This enriches the bulk data
 * with information the search trick cannot provide.
 *
 * <p>Data is cached to ConfigManager and synced to the Mystix API on login/logout.
 */
@Slf4j
@Singleton
public class CollectionLogMonitor
{
	private static final int PROFILE_SYNC_DELAY_SECONDS = 5;

	/** Collection log interface group ID. */
	private static final int COLLECTION_LOG_GROUP_ID = 621;

	/** Script that fires when a collection log page is drawn (once per obtained item during search). */
	private static final int COLLECTION_LOG_DRAW_LIST_SCRIPT = 4100;

	/** Script that triggers the collection log search (iterates all items). */
	private static final int COLLECTION_LOG_SEARCH_SCRIPT = 2240;

	/** Widget child indices within the collection log interface (group 621). */
	private static final int CHILD_TITLE = 1;
	private static final int CHILD_TAB_HEADER = 3;
	private static final int CHILD_ITEMS_CONTAINER = 36;
	private static final int CHILD_KC_TEXT = 37;

	/**
	 * Game cache enum/param IDs for the collection log structure.
	 * Enum 2102 = top-level tabs; param 683 = subtab enum; param 690 = item enum;
	 * param 689 = page/group name (string).
	 */
	private static final int CLOG_TOP_LEVEL_ENUM = 2102;
	private static final int PARAM_SUBTAB_ENUM = 683;
	private static final int PARAM_ITEMS_ENUM = 690;
	private static final int PARAM_PAGE_NAME = 689;

	/** Top-level tab names in enum 2102 order. */
	private static final String[] TAB_NAMES = {"Bosses", "Raids", "Clues", "Minigames", "Other"};

	private static final String COLLECTION_LOG_CHAT_PREFIX = "New item added to your collection log: ";

	private static final String CONFIG_GROUP = "mystix";
	private static final String CLOG_CACHE_KEY = "collectionlog_cache";

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final ScheduledExecutorService executorService;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean collectionLogOpen = false;

	/** True while a search-triggered bulk capture is in progress. */
	private boolean bulkCaptureInProgress = false;

	/** Set of obtained item IDs collected during bulk capture via ScriptPreFired. */
	private final Set<Integer> bulkObtainedItems = new HashSet<>();

	/** Full collection log structure parsed from the game cache: pageName -> CachePageDef. */
	private final Map<String, CachePageDef> cacheStructure = new LinkedHashMap<>();

	/** In-memory cache of captured collection log pages: pageName -> CachedPage. */
	private final Map<String, CachedPage> cachedPages = new LinkedHashMap<>();

	@Inject
	public CollectionLogMonitor(
		Client client,
		MystixConfig config,
		MystixApiClient apiClient,
		EventBus eventBus,
		ScheduledExecutorService executorService,
		ClientThread clientThread,
		ConfigManager configManager,
		Gson gson)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.executorService = executorService;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.gson = gson;
	}

	public void start()
	{
		eventBus.register(this);
		log.info("CollectionLogMonitor started");
	}

	public void stop()
	{
		eventBus.unregister(this);
		previousGameState = GameState.UNKNOWN;
		collectionLogOpen = false;
		bulkCaptureInProgress = false;
		bulkObtainedItems.clear();
		cacheStructure.clear();
		cachedPages.clear();
		log.debug("CollectionLogMonitor stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN)
		{
			log.debug("Player logged in, waiting for RS profile before collection log sync");
			clientThread.invokeLater(() ->
			{
				if (client.getGameState().getState() < GameState.LOGGED_IN.getState())
				{
					return true;
				}
				String profileKey = configManager.getRSProfileKey();
				if (profileKey == null)
				{
					return false;
				}
				parseCacheStructure();
				loadCacheFromConfig();
				log.debug("RS profile ready, scheduling collection log sync in {}s", PROFILE_SYNC_DELAY_SECONDS);
				executorService.schedule(this::syncCollectionLog, PROFILE_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
				return true;
			});
		}
		else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN)
		{
			log.debug("Player logged out, syncing collection log");
			syncCollectionLog();
			collectionLogOpen = false;
			bulkCaptureInProgress = false;
		}

		previousGameState = newState;
	}

	/**
	 * Detects when the collection log interface is opened.
	 * Automatically triggers the search trick to bulk-capture all obtained items.
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != COLLECTION_LOG_GROUP_ID)
		{
			return;
		}

		collectionLogOpen = true;
		log.debug("Collection log interface opened");

		if (!config.syncCollectionLog())
		{
			return;
		}
		if (cacheStructure.isEmpty())
		{
			log.debug("No cache structure available, skipping bulk capture");
			return;
		}

		// Trigger bulk capture: run the search script which fires script 4100
		// once per obtained item across the entire collection log.
		clientThread.invokeLater(() ->
		{
			bulkObtainedItems.clear();
			bulkCaptureInProgress = true;
			log.debug("Starting bulk collection log capture via search trick");
			client.runScript(COLLECTION_LOG_SEARCH_SCRIPT);
		});
	}

	/**
	 * Captures obtained item IDs during bulk search capture.
	 * Script 4100 fires once per obtained item when triggered by the search script.
	 * The item ID is in args[1].
	 */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != COLLECTION_LOG_DRAW_LIST_SCRIPT)
		{
			return;
		}
		if (!bulkCaptureInProgress)
		{
			return;
		}

		try
		{
			Object[] args = event.getScriptEvent().getArguments();
			if (args != null && args.length > 1)
			{
				int itemId = (int) args[1];
				if (itemId > 0)
				{
					bulkObtainedItems.add(itemId);
				}
			}
		}
		catch (Exception e)
		{
			log.debug("Failed to read item ID from script 4100 args", e);
		}
	}

	/**
	 * Captures collection log page data when the draw-list script fires.
	 * During normal browsing (not bulk capture), reads full widget data for the current page.
	 * After bulk capture completes, merges the obtained items into the cache structure.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != COLLECTION_LOG_DRAW_LIST_SCRIPT)
		{
			return;
		}
		if (!collectionLogOpen)
		{
			return;
		}
		if (!config.syncCollectionLog())
		{
			return;
		}

		if (bulkCaptureInProgress)
		{
			// The search has completed (ScriptPostFired means all PreFired callbacks ran).
			// Merge the obtained items into the cache structure.
			finishBulkCapture();
			return;
		}

		// Normal page browsing — capture full widget details (quantities, KC, etc.)
		captureCurrentPage();
	}

	/**
	 * Finishes the bulk capture by merging obtained item IDs with the cache structure.
	 * Creates CachedPage entries for every page in the structure with obtained/not-obtained status.
	 */
	private void finishBulkCapture()
	{
		bulkCaptureInProgress = false;
		int totalObtainedCount = bulkObtainedItems.size();
		log.info("Bulk capture complete: {} obtained items detected", totalObtainedCount);

		if (bulkObtainedItems.isEmpty() && cacheStructure.isEmpty())
		{
			return;
		}

		for (CachePageDef pageDef : cacheStructure.values())
		{
			// If we already have widget-captured data for this page (with quantities/KC),
			// don't overwrite it with the less-detailed bulk data.
			CachedPage existing = cachedPages.get(pageDef.name);
			if (existing != null && existing.killCount != null)
			{
				continue;
			}

			List<CachedItem> items = new ArrayList<>();
			int obtained = 0;

			for (int itemId : pageDef.itemIds)
			{
				boolean isObtained = bulkObtainedItems.contains(itemId);
				items.add(new CachedItem(itemId, isObtained ? 1 : 0, isObtained));
				if (isObtained)
				{
					obtained++;
				}
			}

			CachedPage page = new CachedPage(
				pageDef.name, pageDef.tab, null, null, null,
				obtained, pageDef.itemIds.size(), items
			);
			cachedPages.put(pageDef.name, page);
		}

		bulkObtainedItems.clear();
		log.info("Collection log cache updated: {} pages from bulk capture + cache structure", cachedPages.size());
		saveCacheToConfig();

		// Trigger an immediate sync since we now have full data
		executorService.submit(this::syncCollectionLog);
	}

	/**
	 * Detects when a new collection log item is obtained via the game chat message.
	 * Sends a real-time entry event to the API.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}
		if (!config.syncCollectionLog())
		{
			return;
		}

		String message = event.getMessage();
		if (!message.startsWith(COLLECTION_LOG_CHAT_PREFIX))
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

		String itemName = message.substring(COLLECTION_LOG_CHAT_PREFIX.length()).trim();

		int itemId = resolveItemIdFromWidget(itemName);
		String groupName = resolveCurrentGroupName();
		String tab = resolveCurrentTab();

		if (itemId <= 0)
		{
			log.debug("Could not resolve item ID for collection log entry: {}", itemName);
			return;
		}

		CollectionLogEntryPayload payload = new CollectionLogEntryPayload(
			playerUsername, itemId, groupName != null ? groupName : "Unknown", tab != null ? tab : "Other"
		);
		executorService.submit(() -> apiClient.sendCollectionLogEntry(payload));
		log.info("Collection log entry detected: {} for player {}", itemName, playerUsername);
	}

	/**
	 * Parses the game cache to build the full collection log structure.
	 * Uses enum 2102 (top-level tabs) → param 683 (subtab enums) → param 690 (item enums).
	 * Must be called on the client thread.
	 */
	private void parseCacheStructure()
	{
		try
		{
			cacheStructure.clear();
			EnumComposition topLevel = client.getEnum(CLOG_TOP_LEVEL_ENUM);
			if (topLevel == null)
			{
				log.warn("Could not load collection log top-level enum (2102)");
				return;
			}

			int[] tabStructIds = topLevel.getIntVals();
			for (int tabIdx = 0; tabIdx < tabStructIds.length; tabIdx++)
			{
				String tabName = tabIdx < TAB_NAMES.length ? TAB_NAMES[tabIdx] : "Other";
				StructComposition tabStruct = client.getStructComposition(tabStructIds[tabIdx]);
				if (tabStruct == null)
				{
					continue;
				}

				int subtabEnumId = tabStruct.getIntValue(PARAM_SUBTAB_ENUM);
				if (subtabEnumId <= 0)
				{
					continue;
				}

				EnumComposition subtabEnum = client.getEnum(subtabEnumId);
				if (subtabEnum == null)
				{
					continue;
				}

				int[] pageStructIds = subtabEnum.getIntVals();
				for (int pageStructId : pageStructIds)
				{
					StructComposition pageStruct = client.getStructComposition(pageStructId);
					if (pageStruct == null)
					{
						continue;
					}

					String pageName = pageStruct.getStringValue(PARAM_PAGE_NAME);
					if (pageName == null || pageName.isEmpty())
					{
						continue;
					}

					int itemsEnumId = pageStruct.getIntValue(PARAM_ITEMS_ENUM);
					if (itemsEnumId <= 0)
					{
						continue;
					}

					EnumComposition itemsEnum = client.getEnum(itemsEnumId);
					if (itemsEnum == null)
					{
						continue;
					}

					List<Integer> itemIds = new ArrayList<>();
					for (int itemId : itemsEnum.getIntVals())
					{
						if (itemId > 0)
						{
							itemIds.add(itemId);
						}
					}

					cacheStructure.put(pageName, new CachePageDef(pageName, tabName, itemIds));
				}
			}

			log.info("Parsed collection log structure from game cache: {} pages", cacheStructure.size());
		}
		catch (Exception e)
		{
			log.warn("Failed to parse collection log structure from game cache", e);
		}
	}

	/**
	 * Resolves an item name to its item ID from the currently open collection log widget.
	 * Returns -1 if the log is not open or the item is not found.
	 */
	private int resolveItemIdFromWidget(String itemName)
	{
		if (!collectionLogOpen)
		{
			return -1;
		}

		Widget itemsContainer = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_ITEMS_CONTAINER);
		if (itemsContainer == null || itemsContainer.getDynamicChildren() == null)
		{
			return -1;
		}

		for (Widget itemWidget : itemsContainer.getDynamicChildren())
		{
			if (itemWidget.getItemId() > 0 && itemWidget.getName() != null
				&& itemWidget.getName().replaceAll("<[^>]*>", "").equalsIgnoreCase(itemName))
			{
				return itemWidget.getItemId();
			}
		}

		return -1;
	}

	/**
	 * Returns the name of the currently open collection log group, or null.
	 */
	private String resolveCurrentGroupName()
	{
		if (!collectionLogOpen)
		{
			return null;
		}
		return readPageName();
	}

	/**
	 * Returns the name of the currently open collection log tab, or null.
	 */
	private String resolveCurrentTab()
	{
		if (!collectionLogOpen)
		{
			return null;
		}
		return readTabName();
	}

	/**
	 * Reads the page name from the collection log title widget.
	 * Title format: "Collection Log - &lt;PageName&gt;". Returns null if unavailable.
	 */
	private String readPageName()
	{
		Widget titleWidget = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_TITLE);
		if (titleWidget == null || titleWidget.getText() == null)
		{
			return null;
		}
		String fullTitle = titleWidget.getText();
		if (fullTitle.contains(" - "))
		{
			String name = fullTitle.substring(fullTitle.indexOf(" - ") + 3).trim();
			return name.isEmpty() ? null : name;
		}
		return fullTitle;
	}

	/**
	 * Reads the currently displayed collection log page from widgets.
	 * This captures full details (quantities, KC) that the bulk search trick cannot provide.
	 * Widget-captured data takes priority over bulk-captured data.
	 */
	private void captureCurrentPage()
	{
		try
		{
			String pageName = readPageName();
			if (pageName == null)
			{
				return;
			}

			String tabName = readTabName();

			Integer killCount = readKillCount();
			Widget itemsContainer = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_ITEMS_CONTAINER);
			List<CachedItem> items = new ArrayList<>();
			int totalObtained = 0;
			int totalItems = 0;

			if (itemsContainer != null && itemsContainer.getDynamicChildren() != null)
			{
				for (Widget itemWidget : itemsContainer.getDynamicChildren())
				{
					int itemId = itemWidget.getItemId();
					if (itemId <= 0)
					{
						continue;
					}

					int quantity = itemWidget.getItemQuantity();
					// Items that haven't been obtained have an opacity/colour tint.
					// The item widget opacity is 0 for obtained items and >0 for unobtained.
					boolean obtained = itemWidget.getOpacity() == 0;

					items.add(new CachedItem(itemId, obtained ? quantity : 0, obtained));
					totalItems++;
					if (obtained)
					{
						totalObtained++;
					}
				}
			}

			// Try to resolve NPC ID from nearby NPCs matching the page name
			Integer npcId = null;
			String npcName = null;
			int resolvedId = resolveNpcId(pageName);
			if (resolvedId != (pageName.hashCode() & 0x7FFFFFFF))
			{
				npcId = resolvedId;
				npcName = pageName;
			}

			CachedPage page = new CachedPage(
				pageName, tabName != null ? tabName : "Other",
				npcId, npcName, killCount,
				totalObtained, totalItems, items
			);
			cachedPages.put(pageName, page);

			log.debug("Captured collection log page: {} ({}/{} items, tab={})",
				pageName, totalObtained, totalItems, tabName);

			saveCacheToConfig();
		}
		catch (Exception e)
		{
			log.debug("Failed to capture collection log page", e);
		}
	}

	/**
	 * Reads the current tab name from the collection log interface.
	 */
	private String readTabName()
	{
		Widget tabWidget = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_TAB_HEADER);
		if (tabWidget != null && tabWidget.getText() != null && !tabWidget.getText().isEmpty())
		{
			return tabWidget.getText().trim();
		}
		return null;
	}

	/**
	 * Reads the kill count from the KC text widget.
	 * The text format varies: "Kills: 500" or "Completions: 150" or "Opens: 200" etc.
	 */
	private Integer readKillCount()
	{
		Widget kcWidget = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_KC_TEXT);
		if (kcWidget == null || kcWidget.getText() == null || kcWidget.getText().isEmpty())
		{
			return null;
		}

		String text = kcWidget.getText().trim();
		try
		{
			String[] parts = text.split("[^0-9]+");
			for (String part : parts)
			{
				if (!part.isEmpty())
				{
					return Integer.parseInt(part);
				}
			}
		}
		catch (NumberFormatException e)
		{
			log.debug("Could not parse kill count from: {}", text);
		}

		return null;
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
	 * Syncs the cached collection log data to the Mystix API.
	 */
	private void syncCollectionLog()
	{
		if (!config.syncCollectionLog())
		{
			return;
		}
		if (!SyncGuard.hasAppKey(config))
		{
			log.debug("Collection log sync skipped: no App Key configured");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client))
		{
			log.debug("Collection log sync skipped: special game mode detected");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null)
		{
			log.warn("Collection log sync skipped: could not get player username");
			return;
		}

		if (cachedPages.isEmpty())
		{
			log.debug("No collection log data to sync — open your collection log in-game to capture data");
			return;
		}

		List<CollectionLogSyncPayload.Group> groups = new ArrayList<>();
		for (CachedPage page : cachedPages.values())
		{
			List<CollectionLogSyncPayload.Item> items = new ArrayList<>();
			for (CachedItem item : page.items)
			{
				items.add(new CollectionLogSyncPayload.Item(item.itemId, item.quantity, item.obtained));
			}

			groups.add(new CollectionLogSyncPayload.Group(
				page.name, page.tab, page.npcId, page.npcName,
				page.killCount, page.totalObtained, page.totalItems, items
			));
		}

		CollectionLogSyncPayload payload = new CollectionLogSyncPayload(playerUsername, groups);
		log.info("Syncing {} collection log pages for player: {}", groups.size(), playerUsername);
		apiClient.sendCollectionLogSync(payload);
	}

	/**
	 * Saves the cached pages to ConfigManager as JSON so they persist across sessions.
	 */
	private void saveCacheToConfig()
	{
		try
		{
			String json = gson.toJson(cachedPages);
			configManager.setRSProfileConfiguration(CONFIG_GROUP, CLOG_CACHE_KEY, json);
		}
		catch (Exception e)
		{
			log.warn("Failed to save collection log cache to config", e);
		}
	}

	/**
	 * Loads cached pages from ConfigManager into the in-memory map.
	 */
	private void loadCacheFromConfig()
	{
		try
		{
			String profileKey = configManager.getRSProfileKey();
			if (profileKey == null)
			{
				log.warn("Collection log cache load skipped: no RS profile key");
				return;
			}

			String json = configManager.getConfiguration(CONFIG_GROUP, profileKey, CLOG_CACHE_KEY);
			if (json == null || json.isEmpty())
			{
				log.info("No cached collection log data found — open your collection log in-game to capture data");
				return;
			}

			Type mapType = new TypeToken<LinkedHashMap<String, CachedPage>>()
			{
			}.getType();
			Map<String, CachedPage> loaded = gson.fromJson(json, mapType);
			if (loaded != null && !loaded.isEmpty())
			{
				cachedPages.putAll(loaded);
				log.info("Loaded {} cached collection log pages from config", cachedPages.size());
			}
			else
			{
				log.warn("Collection log cache deserialized to empty — raw JSON length: {}", json.length());
			}
		}
		catch (Exception e)
		{
			log.warn("Failed to load collection log cache from config", e);
		}
	}

	/**
	 * Definition of a collection log page from the game cache.
	 * Contains the page name, tab, and all item IDs on that page.
	 */
	private static class CachePageDef
	{
		final String name;
		final String tab;
		final List<Integer> itemIds;

		CachePageDef(String name, String tab, List<Integer> itemIds)
		{
			this.name = name;
			this.tab = tab;
			this.itemIds = itemIds;
		}
	}

	/**
	 * Cached representation of a collection log page.
	 * Fields are non-final with a no-arg constructor for GSON deserialization.
	 */
	private static class CachedPage
	{
		String name;
		String tab;
		Integer npcId;
		String npcName;
		Integer killCount;
		int totalObtained;
		int totalItems;
		List<CachedItem> items;

		@SuppressWarnings("unused")
		CachedPage()
		{
		}

		CachedPage(String name, String tab, Integer npcId, String npcName,
			Integer killCount, int totalObtained, int totalItems, List<CachedItem> items)
		{
			this.name = name;
			this.tab = tab;
			this.npcId = npcId;
			this.npcName = npcName;
			this.killCount = killCount;
			this.totalObtained = totalObtained;
			this.totalItems = totalItems;
			this.items = items;
		}
	}

	/**
	 * Cached representation of a single collection log item.
	 * Fields are non-final with a no-arg constructor for GSON deserialization.
	 */
	private static class CachedItem
	{
		int itemId;
		int quantity;
		boolean obtained;

		@SuppressWarnings("unused")
		CachedItem()
		{
		}

		CachedItem(int itemId, int quantity, boolean obtained)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.obtained = obtained;
		}
	}
}
