package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.BankSyncPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors potion storage contents and syncs them to the Mystix API.
 * Potion storage is not a regular container — doses are tracked in VarPlayers
 * and must be read via client scripts.
 */
@Slf4j
@Singleton
public class PotionStorageMonitor {
	private static final String SOURCE = "potion_storage";
	private static final int MAX_DOSES = 4;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final EventBus eventBus;
	private final Gson gson;

	private Set<Integer> potionStoreVarps;
	private boolean needsRebuild;
	private String lastSyncJson;

	@Inject
	public PotionStorageMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			EventBus eventBus,
			Gson gson) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.eventBus = eventBus;
		this.gson = gson;
	}

	public void start() {
		eventBus.register(this);
		log.info("PotionStorageMonitor started");
	}

	public void stop() {
		eventBus.unregister(this);
		potionStoreVarps = null;
		needsRebuild = false;
		lastSyncJson = null;
		log.debug("PotionStorageMonitor stopped");
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN) {
			needsRebuild = true;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (potionStoreVarps != null && potionStoreVarps.contains(event.getVarpId())) {
			needsRebuild = true;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event) {
		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload()) {
			potionStoreVarps = null;
		}
	}

	/**
	 * On client tick, if a potion store varp changed, rebuild potion data and sync.
	 * We process on ClientTick (like RuneLite's PotionStorage) so the script runs
	 * on the client thread before the CS2 VM updates widgets.
	 */
	@Subscribe
	public void onClientTick(ClientTick event) {
		if (!needsRebuild) {
			return;
		}
		needsRebuild = false;

		if (!config.syncBankMemory() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}

		// Cache the varp triggers from the widget on first rebuild
		if (potionStoreVarps == null) {
			Widget w = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_ITEMS);
			if (w == null) {
				return;
			}
			int[] triggers = w.getVarTransmitTrigger();
			if (triggers == null) {
				return;
			}
			potionStoreVarps = new HashSet<>();
			Arrays.stream(triggers).forEach(potionStoreVarps::add);
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		List<BankSyncPayload.BankItem> items = collectPotionItems();

		Map<String, List<BankSyncPayload.BankItem>> itemsBySource = new LinkedHashMap<>();
		itemsBySource.put(SOURCE, items);

		BankSyncPayload payload = new BankSyncPayload(playerUsername, itemsBySource);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Potion storage unchanged, skipping sync");
			return;
		}

		lastSyncJson = json;
		log.info("Syncing {} potion storage items for player: {}", items.size(), playerUsername);
		apiClient.sendBankSync(payload);
	}

	/**
	 * Reads all potions from potion storage using game enums and client scripts.
	 * Each potion is stored as its 4-dose item ID (or highest available dose for mixes).
	 */
	private List<BankSyncPayload.BankItem> collectPotionItems() {
		List<BankSyncPayload.BankItem> items = new ArrayList<>();

		EnumComposition potions = client.getEnum(EnumID.POTIONSTORE_POTIONS);
		EnumComposition unfinished = client.getEnum(EnumID.POTIONSTORE_UNFINISHED_POTIONS);

		for (EnumComposition enumComp : new EnumComposition[]{potions, unfinished}) {
			for (int potionEnumId : enumComp.getIntVals()) {
				EnumComposition potionEnum = client.getEnum(potionEnumId);

				client.runScript(ScriptID.POTIONSTORE_DOSES, potionEnumId);
				int totalDoses = client.getIntStack()[0];

				if (totalDoses <= 0) {
					continue;
				}

				// Find the highest dose variant available for this potion
				int maxDose = findMaxDose(potionEnum);
				int itemId = potionEnum.getIntValue(maxDose);
				int quantity = totalDoses / maxDose;
				int remainderDoses = totalDoses % maxDose;

				if (quantity > 0) {
					items.add(new BankSyncPayload.BankItem(itemId, quantity));
				}

				// Add remainder as lower-dose item if applicable
				if (remainderDoses > 0) {
					int remainderItemId = potionEnum.getIntValue(remainderDoses);
					items.add(new BankSyncPayload.BankItem(remainderItemId, 1));
				}
			}
		}

		int vials = client.getVarpValue(VarPlayerID.POTIONSTORE_VIALS);
		if (vials > 0) {
			items.add(new BankSyncPayload.BankItem(ItemID.VIAL_EMPTY, vials));
		}

		return items;
	}

	/** Finds the highest dose key (4, 3, 2, or 1) that has a valid item ID in the enum. */
	private int findMaxDose(EnumComposition potionEnum) {
		for (int dose = MAX_DOSES; dose >= 1; dose--) {
			int itemId = potionEnum.getIntValue(dose);
			if (itemId > 0) {
				return dose;
			}
		}
		return 1;
	}
}
