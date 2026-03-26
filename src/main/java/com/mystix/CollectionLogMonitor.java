package com.mystix;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mystix.api.MystixApiClient;
import com.mystix.model.CollectionLogEntryPayload;
import com.mystix.model.CollectionLogSyncPayload;
import java.lang.reflect.Type;
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
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the in-game Collection Log interface (group 621), captures page data
 * when the player browses it, caches pages to ConfigManager, and syncs to the
 * Mystix API on login/logout.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>On each game tick, checks if the collection log is open by reading the
 *       page header widget (child {@value #CHILD_PAGE_HEADER}).</li>
 *   <li>When the player navigates to a new page (title text changes), captures
 *       the page name, tab, kill count, and all items from the widgets.</li>
 *   <li>Captured pages are cached in-memory and persisted to ConfigManager so
 *       they survive client restarts.</li>
 *   <li>On login, the cache is loaded and synced to the API after a short delay.
 *       On logout, whatever is cached is synced immediately.</li>
 *   <li>Real-time "new item obtained" events are detected via the game chat
 *       message and sent individually to the API.</li>
 * </ol>
 *
 * <h3>Widget layout (group 621)</h3>
 * <pre>
 *   Child  3: tab header — static children contain the active tab name
 *   Child  4-8: tab buttons (Bosses, Raids, Clues, Minigames, Other)
 *   Child 20: page header — first dynamic child's text is the page name
 *   Child 37: items container — dynamic children are item widgets
 *   Child 38: kill count area — dynamic children contain "Kills: N" text
 * </pre>
 */
@Slf4j
@Singleton
public class CollectionLogMonitor
{
	private static final int PROFILE_SYNC_DELAY_SECONDS = 5;

	// ---------------------------------------------------------------------------
	// Widget constants for the collection log interface (group 621)
	// ---------------------------------------------------------------------------
	private static final int COLLECTION_LOG_GROUP_ID = 621;
	private static final int CHILD_TAB_HEADER = 3;
	private static final int CHILD_PAGE_HEADER = 20;
	private static final int CHILD_ITEMS_CONTAINER = 37;
	private static final int CHILD_KC_CONTAINER = 38;

	private static final String COLLECTION_LOG_NEW_ITEM_PREFIX =
		"New item added to your collection log: ";

	// ---------------------------------------------------------------------------
	// ConfigManager keys for cache persistence
	// ---------------------------------------------------------------------------
	private static final String CONFIG_GROUP = "mystix";
	private static final String CLOG_CACHE_KEY = "collectionlog_cache";

	// ---------------------------------------------------------------------------
	// Injected dependencies
	// ---------------------------------------------------------------------------
	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final ScheduledExecutorService executorService;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final Gson gson;

	// ---------------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------------
	private GameState previousGameState = GameState.UNKNOWN;

	/** The last captured page title — used to detect when the player navigates to a new page. */
	private String lastCapturedTitle = null;

	/** In-memory cache of captured collection log pages, keyed by page name. */
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
		lastCapturedTitle = null;
		cachedPages.clear();
		log.debug("CollectionLogMonitor stopped");
	}

	// =========================================================================
	// Event handlers
	// =========================================================================

	/**
	 * On login: load cached pages from ConfigManager and schedule a sync.
	 * On logout: sync whatever is cached immediately.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN)
		{
			scheduleLoginSync();
		}
		else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN)
		{
			log.debug("Player logged out — syncing collection log");
			syncCollectionLog();
			lastCapturedTitle = null;
		}

		previousGameState = newState;
	}

	/**
	 * Every game tick, check if the collection log is open and whether the
	 * player navigated to a new page. If so, capture it.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!config.syncCollectionLog())
		{
			return;
		}

		String currentPageName = readPageName();

		if (currentPageName == null)
		{
			// Collection log is closed — reset so we re-capture when it reopens.
			if (lastCapturedTitle != null)
			{
				log.debug("Collection log closed");
				lastCapturedTitle = null;
			}
			return;
		}

		if (!currentPageName.equals(lastCapturedTitle))
		{
			lastCapturedTitle = currentPageName;
			captureCurrentPage();
		}
	}

	/**
	 * Detects when a new collection log item is obtained via the game chat
	 * message ("New item added to your collection log: ...") and sends a
	 * real-time entry event to the API.
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
		if (!message.startsWith(COLLECTION_LOG_NEW_ITEM_PREFIX))
		{
			return;
		}

		if (!SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client))
		{
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null)
		{
			return;
		}

		String itemName = message.substring(COLLECTION_LOG_NEW_ITEM_PREFIX.length()).trim();
		int itemId = resolveItemIdFromWidget(itemName);
		if (itemId <= 0)
		{
			log.debug("Could not resolve item ID for collection log entry: {}", itemName);
			return;
		}

		String groupName = readPageName();
		String tab = readTabName();

		CollectionLogEntryPayload payload = new CollectionLogEntryPayload(
			playerUsername,
			itemId,
			groupName != null ? groupName : "Unknown",
			tab != null ? tab : "Other"
		);
		executorService.submit(() -> apiClient.sendCollectionLogEntry(payload));
		log.info("New collection log item: {} for player {}", itemName, playerUsername);
	}

	// =========================================================================
	// Widget reading helpers
	// =========================================================================

	/**
	 * Reads the current page name from the page header widget (child 20).
	 * Returns null if the collection log is not open.
	 */
	private String readPageName()
	{
		Widget header = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_PAGE_HEADER);
		if (header == null)
		{
			return null;
		}

		Widget[] children = header.getDynamicChildren();
		if (children == null)
		{
			return null;
		}

		for (Widget child : children)
		{
			String text = child.getText();
			if (text != null && !text.isEmpty())
			{
				return text.trim();
			}
		}
		return null;
	}

	/**
	 * Reads the active tab name from the tab header widget (child 3).
	 * The static children contain the active tab name wrapped in colour tags.
	 */
	private String readTabName()
	{
		Widget tabHeader = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_TAB_HEADER);
		if (tabHeader == null)
		{
			return null;
		}

		Widget[] children = tabHeader.getStaticChildren();
		if (children == null)
		{
			return null;
		}

		for (Widget child : children)
		{
			String name = child.getName();
			if (name != null && !name.isEmpty())
			{
				return stripColourTags(name);
			}
			String text = child.getText();
			if (text != null && !text.isEmpty())
			{
				return stripColourTags(text);
			}
		}
		return null;
	}

	/**
	 * Reads the kill count from the KC container (child 38).
	 * Looks for text like "Kills: 500" or "Completions: 150" and
	 * returns the first number found, or null if none.
	 */
	private Integer readKillCount()
	{
		Widget kcContainer = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_KC_CONTAINER);
		if (kcContainer == null)
		{
			return null;
		}

		Widget[] children = kcContainer.getDynamicChildren();
		if (children == null)
		{
			return null;
		}

		for (Widget child : children)
		{
			String text = child.getText();
			if (text == null || text.isEmpty())
			{
				continue;
			}
			Integer value = parseFirstNumber(text);
			if (value != null)
			{
				return value;
			}
		}
		return null;
	}

	/**
	 * Reads all items from the items container (child 37) and returns them
	 * as a list of {@link CachedItem}. An item is "obtained" when its widget
	 * opacity is 0 (fully visible); unobtained items are dimmed (opacity > 0).
	 */
	private List<CachedItem> readItems()
	{
		Widget container = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_ITEMS_CONTAINER);
		if (container == null || container.getDynamicChildren() == null)
		{
			return Collections.emptyList();
		}

		List<CachedItem> items = new ArrayList<>();
		for (Widget widget : container.getDynamicChildren())
		{
			int itemId = widget.getItemId();
			if (itemId <= 0)
			{
				continue;
			}

			boolean obtained = widget.getOpacity() == 0;
			int quantity = obtained ? widget.getItemQuantity() : 0;
			items.add(new CachedItem(itemId, quantity, obtained));
		}
		return items;
	}

	/**
	 * Resolves an item name to its item ID by scanning the currently visible
	 * items container. Returns -1 if the item is not found.
	 */
	private int resolveItemIdFromWidget(String itemName)
	{
		Widget container = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_ITEMS_CONTAINER);
		if (container == null || container.getDynamicChildren() == null)
		{
			return -1;
		}

		for (Widget widget : container.getDynamicChildren())
		{
			if (widget.getItemId() > 0
				&& widget.getName() != null
				&& stripColourTags(widget.getName()).equalsIgnoreCase(itemName))
			{
				return widget.getItemId();
			}
		}
		return -1;
	}

	// =========================================================================
	// Page capture
	// =========================================================================

	/**
	 * Reads the currently displayed collection log page from the widgets,
	 * stores it in the in-memory cache, and persists to ConfigManager.
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

			String tab = readTabName();
			Integer killCount = readKillCount();
			List<CachedItem> items = readItems();

			int totalObtained = 0;
			for (CachedItem item : items)
			{
				if (item.obtained)
				{
					totalObtained++;
				}
			}

			// Try to match the page name to a nearby NPC for the optional FK.
			Integer npcId = null;
			String npcName = null;
			NPC matchedNpc = findNearbyNpcByName(pageName);
			if (matchedNpc != null)
			{
				npcId = matchedNpc.getId();
				npcName = pageName;
			}

			CachedPage page = new CachedPage(
				pageName,
				tab != null ? tab : "Other",
				npcId,
				npcName,
				killCount,
				totalObtained,
				items.size(),
				items
			);
			cachedPages.put(pageName, page);
			saveCacheToConfig();

			log.info("Captured collection log page: {} ({}/{} obtained, tab={}, kc={})",
				pageName, totalObtained, items.size(), tab, killCount);
		}
		catch (Exception e)
		{
			log.warn("Failed to capture collection log page", e);
		}
	}

	// =========================================================================
	// Sync
	// =========================================================================

	/**
	 * Waits for the RS profile key to become available, loads the cache from
	 * ConfigManager, then schedules a sync after a short delay.
	 */
	private void scheduleLoginSync()
	{
		log.debug("Player logged in — waiting for RS profile before collection log sync");
		clientThread.invokeLater(() ->
		{
			if (client.getGameState().getState() < GameState.LOGGED_IN.getState())
			{
				// Player logged out before profile loaded — abort.
				return true;
			}
			if (configManager.getRSProfileKey() == null)
			{
				// Profile not ready yet — retry next tick.
				return false;
			}
			loadCacheFromConfig();
			log.debug("RS profile ready — scheduling collection log sync in {}s", PROFILE_SYNC_DELAY_SECONDS);
			executorService.schedule(this::syncCollectionLog, PROFILE_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
			return true;
		});
	}

	/**
	 * Converts the in-memory cache to a {@link CollectionLogSyncPayload} and
	 * sends it to the Mystix API.
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
			log.info("No collection log data to sync — open your collection log in-game to capture pages");
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

	// =========================================================================
	// Cache persistence
	// =========================================================================

	/** Serializes the in-memory cache to ConfigManager as JSON. */
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

	/** Loads previously cached pages from ConfigManager into the in-memory map. */
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
				log.info("No cached collection log data found — open your collection log in-game to capture pages");
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

	// =========================================================================
	// Utility
	// =========================================================================

	/** Strips OSRS colour tags like {@code <col=ff9040>...</col>} from a string. */
	private static String stripColourTags(String input)
	{
		return input.replaceAll("<[^>]*>", "").trim();
	}

	/** Extracts the first integer from a string like "Kills: 500". Returns null if none found. */
	private static Integer parseFirstNumber(String text)
	{
		for (String part : text.split("[^0-9]+"))
		{
			if (!part.isEmpty())
			{
				try
				{
					return Integer.parseInt(part);
				}
				catch (NumberFormatException e)
				{
					// continue
				}
			}
		}
		return null;
	}

	/** Finds a nearby NPC whose name matches the given string, or null if none found. */
	private NPC findNearbyNpcByName(String name)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		for (NPC npc : wv.npcs())
		{
			if (npc.getName() != null && npc.getName().equalsIgnoreCase(name))
			{
				return npc;
			}
		}
		return null;
	}

	// =========================================================================
	// Cache data classes (package-private fields + no-arg constructor for GSON)
	// =========================================================================

	/** A captured collection log page with its metadata and items. */
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

		@SuppressWarnings("unused") // used by GSON deserialization
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

	/** A single item within a collection log page. */
	private static class CachedItem
	{
		int itemId;
		int quantity;
		boolean obtained;

		@SuppressWarnings("unused") // used by GSON deserialization
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
