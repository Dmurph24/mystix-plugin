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
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the in-game Collection Log interface, captures page data when the player
 * browses it, caches to ConfigManager, and syncs to the Mystix API on login/logout.
 *
 * <p>Uses a GameTick poll to detect when the player navigates to a new page
 * (by checking if the title widget text changed). This is more robust than relying
 * on specific script IDs which can change between game updates.
 */
@Slf4j
@Singleton
public class CollectionLogMonitor
{
	private static final int PROFILE_SYNC_DELAY_SECONDS = 5;

	/** Collection log interface group ID. */
	private static final int COLLECTION_LOG_GROUP_ID = 621;

	/**
	 * Widget child indices within the collection log interface (group 621).
	 * Child 20: page header — dynamic children contain the current page name as text.
	 * Child 37: items container — dynamic children are the item widgets with itemId/quantity/opacity.
	 * Child 38: kill count area — dynamic children contain KC text lines.
	 * Child 4-8: tab buttons — name attribute contains the tab name (Bosses, Raids, Clues, etc).
	 */
	private static final int CHILD_PAGE_HEADER = 20;
	private static final int CHILD_ITEMS_CONTAINER = 37;
	private static final int CHILD_KC_CONTAINER = 38;
	private static final int CHILD_TAB_BUTTONS_START = 4;
	private static final int CHILD_TAB_BUTTONS_END = 8;

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

	/** The last captured page title, used to detect page changes. */
	private String lastCapturedTitle = null;

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
		lastCapturedTitle = null;
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
			lastCapturedTitle = null;
		}

		previousGameState = newState;
	}

	/**
	 * Detects when the collection log interface is opened so we can scan widget structure.
	 */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != COLLECTION_LOG_GROUP_ID)
		{
			return;
		}
		if (!config.syncCollectionLog())
		{
			return;
		}

		// Scan all children of group 621 after a short delay to let widgets populate.
		executorService.schedule(() -> clientThread.invokeLater(() ->
		{
			log.info("Collection log widget loaded — scanning children of group {}", COLLECTION_LOG_GROUP_ID);
			for (int i = 0; i < 50; i++)
			{
				Widget child = client.getWidget(COLLECTION_LOG_GROUP_ID, i);
				if (child == null)
				{
					continue;
				}
				String text = child.getText();
				String name = child.getName();
				Widget[] dynamicChildren = child.getDynamicChildren();
				Widget[] staticChildren = child.getStaticChildren();
				int dynamicCount = dynamicChildren != null ? dynamicChildren.length : 0;
				int staticCount = staticChildren != null ? staticChildren.length : 0;

				boolean hasText = text != null && !text.isEmpty();
				boolean hasName = name != null && !name.isEmpty();
				boolean hasDynamic = dynamicCount > 0;
				boolean hasStatic = staticCount > 0;
				int itemId = child.getItemId();

				if (hasText || hasName || hasDynamic || hasStatic || itemId > 0)
				{
					StringBuilder sb = new StringBuilder();
					sb.append("  Child ").append(i).append(":");
					if (hasText)
					{
						String truncated = text.length() > 100 ? text.substring(0, 100) + "..." : text;
						sb.append(" text=\"").append(truncated).append("\"");
					}
					if (hasName)
					{
						sb.append(" name=\"").append(name).append("\"");
					}
					if (itemId > 0)
					{
						sb.append(" itemId=").append(itemId);
					}
					if (hasDynamic)
					{
						Widget firstDyn = dynamicChildren[0];
						sb.append(" dynamic=").append(dynamicCount);
						sb.append(" (firstItemId=").append(firstDyn.getItemId());
						sb.append(", firstName=").append(firstDyn.getName() != null ? firstDyn.getName() : "null");
						sb.append(", firstText=").append(firstDyn.getText() != null ? firstDyn.getText() : "null");
						sb.append(")");
					}
					if (hasStatic)
					{
						Widget firstStat = staticChildren[0];
						sb.append(" static=").append(staticCount);
						sb.append(" (firstText=").append(firstStat.getText() != null ? firstStat.getText() : "null");
						sb.append(", firstName=").append(firstStat.getName() != null ? firstStat.getName() : "null");
						sb.append(")");
					}
					log.info(sb.toString());
				}
			}
		}), 2, TimeUnit.SECONDS);
	}

	/**
	 * Every game tick, check if the collection log is open and the page changed.
	 * This replaces script-based detection which is fragile across game updates.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (!config.syncCollectionLog())
		{
			return;
		}

		// Read current page name from the page header widget (child 20).
		String currentPageName = readPageName();
		if (currentPageName == null)
		{
			// Collection log is not open — reset so we re-capture if it reopens
			if (lastCapturedTitle != null)
			{
				log.debug("Collection log closed");
				lastCapturedTitle = null;
			}
			return;
		}

		// Only capture when the page changes
		if (currentPageName.equals(lastCapturedTitle))
		{
			return;
		}

		lastCapturedTitle = currentPageName;
		captureCurrentPage();
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
		String groupName = readPageName();
		String tab = readTabName();

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
	 * Resolves an item name to its item ID from the currently open collection log widget.
	 * Returns -1 if the log is not open or the item is not found.
	 */
	private int resolveItemIdFromWidget(String itemName)
	{
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
	 * Reads the current page name from the page header widget (child 20).
	 * The first dynamic child with non-empty text is the page name (e.g. "Abyssal Sire").
	 * Returns null if the collection log is not open.
	 */
	private String readPageName()
	{
		Widget headerWidget = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_PAGE_HEADER);
		if (headerWidget == null)
		{
			return null;
		}
		Widget[] children = headerWidget.getDynamicChildren();
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
	 * Reads the currently displayed collection log page from widgets and caches it.
	 */
	private void captureCurrentPage()
	{
		try
		{
			String pageName = readPageName();
			if (pageName == null)
			{
				log.warn("Could not read page name from collection log title widget");
				return;
			}

			String tabName = readTabName();
			Integer killCount = readKillCount();

			Widget itemsContainer = client.getWidget(COLLECTION_LOG_GROUP_ID, CHILD_ITEMS_CONTAINER);
			List<CachedItem> items = new ArrayList<>();
			int totalObtained = 0;
			int totalItems = 0;

			if (itemsContainer == null)
			{
				log.warn("Collection log items container widget (child {}) not found", CHILD_ITEMS_CONTAINER);
			}
			else if (itemsContainer.getDynamicChildren() == null)
			{
				log.warn("Collection log items container has no dynamic children");
			}
			else
			{
				for (Widget itemWidget : itemsContainer.getDynamicChildren())
				{
					int itemId = itemWidget.getItemId();
					if (itemId <= 0)
					{
						continue;
					}

					int quantity = itemWidget.getItemQuantity();
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

			log.info("Captured collection log page: {} ({}/{} items, tab={}, kc={})",
				pageName, totalObtained, totalItems, tabName, killCount);

			saveCacheToConfig();
		}
		catch (Exception e)
		{
			log.warn("Failed to capture collection log page", e);
		}
	}

	/**
	 * Reads the active tab name from the tab buttons (children 4-8).
	 * Each tab button's name attribute contains the tab name wrapped in colour tags.
	 * We strip the tags and return the name. Since we can't easily detect which tab
	 * is active from the widget alone, we determine the tab by checking which tab's
	 * page list contains the current page name.
	 * Falls back to returning the first tab name if detection fails.
	 */
	private String readTabName()
	{
		// The page list sidebar (child 11) contains page names as firstName on dynamic children.
		// But the simplest approach: tab buttons are children 4-8 in order: Bosses, Raids, Clues, Minigames, Other.
		// We can read the name attribute which contains the tab name.
		for (int i = CHILD_TAB_BUTTONS_START; i <= CHILD_TAB_BUTTONS_END; i++)
		{
			Widget tabWidget = client.getWidget(COLLECTION_LOG_GROUP_ID, i);
			if (tabWidget == null)
			{
				continue;
			}
			String name = tabWidget.getName();
			if (name != null && !name.isEmpty())
			{
				// Check if this tab button appears "active" by checking text color or sprite
				// The tab widgets have 4 dynamic children (sprites). We check opacity or similar.
				// For now, use the page header's parent relationship or fall back to checking
				// all tabs. Since child 3 static child 1 has firstName=current tab, try that.
			}
		}

		// Try child 3's static children — earlier scan showed static=5, firstName=<col=ff9040>Bosses</col>
		Widget tabHeaderParent = client.getWidget(COLLECTION_LOG_GROUP_ID, 3);
		if (tabHeaderParent != null)
		{
			Widget[] staticChildren = tabHeaderParent.getStaticChildren();
			if (staticChildren != null)
			{
				for (Widget child : staticChildren)
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
			}
		}

		return null;
	}

	/**
	 * Strips OSRS colour tags like &lt;col=ff9040&gt;...&lt;/col&gt; from a string.
	 */
	private static String stripColourTags(String input)
	{
		return input.replaceAll("<[^>]*>", "").trim();
	}

	/**
	 * Reads the kill count from the KC container (child 38).
	 * Dynamic children contain text like "Kills: 500" or "Completions: 150".
	 * Returns the first numeric value found.
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
				// continue to next child
			}
		}

		return null;
	}

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
			log.info("No collection log data to sync — open your collection log in-game and browse pages to capture data");
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
