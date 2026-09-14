package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.KingdomSyncPayload;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Syncs Managing Miscellania state (approval, coffer, subject allocation and the two
 * kingdom quests) so Mystix can warn when the coffer runs low or approval decays.
 *
 * <p>The kingdom varbits are part of the varp block every logged-in client receives, so
 * they are readable anywhere, not only on the islands. Read timing reuses
 * {@link DiarySyncTrigger}: the first read waits for the post-login replay to settle (a
 * mid-replay read could report a prior account's values), later changes resync a few
 * ticks after their {@link VarbitChanged}, and the final state is flushed on logout.
 * Walking onto the islands also triggers a resync so the backend can record the visit
 * (collection and wages pause after 30 days without one). Nothing is sent before Throne
 * of Miscellania is finished, and an approval read of 0 (impossible after the quest, the
 * floor is 25%) is treated as not loaded yet.
 */
@Slf4j
@Singleton
public class KingdomMonitor {
	/** Miscellania and Etceteria. */
	static final Set<Integer> KINGDOM_REGIONS = Collections.unmodifiableSet(
			new HashSet<>(java.util.Arrays.asList(10044, 10300)));

	/**
	 * Accumulated resource pool (not yet in the gameval constants of the RuneLite version
	 * we build against). Forwarded raw under {@code extra} until its meaning is confirmed.
	 */
	static final int RESTOTAL_LOWBITS_VARBIT = 11564;
	static final String EXTRA_RESTOTAL_LOWBITS = "restotal_lowbits";

	private static final Set<Integer> KINGDOM_VARBITS = Collections.unmodifiableSet(
			new HashSet<>(java.util.Arrays.asList(
					VarbitID.MISC_APPROVAL,
					VarbitID.MISC_COFFERS,
					VarbitID.MISC_POINTS_WOOD,
					VarbitID.MISC_POINTS_HERB,
					VarbitID.MISC_POINTS_FISH,
					VarbitID.MISC_POINTS_MINE,
					VarbitID.MISC_POINTS_RAREWOOD,
					VarbitID.MISC_POINTS_FARM,
					VarbitID.MISC_COOKED,
					VarbitID.MISC_RAREWOOD_TYPE,
					VarbitID.MISC_HERBS_OR_FLAX,
					RESTOTAL_LOWBITS_VARBIT)));

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final Gson gson;
	private final DiarySyncTrigger trigger = new DiarySyncTrigger();

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean wasInKingdom;
	private String lastSyncJson;

	@Inject
	public KingdomMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.gson = gson;
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		wasInKingdom = false;
		trigger.reset();
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the kingdom state on the client thread (the panel's
	 * "Sync &amp; refresh"); clears the dedup so an unchanged value still sends. No-op until
	 * the session has settled, so it can never sample the post-login replay window.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			if (client.getGameState() != GameState.LOGGED_IN || !trigger.isBaselineSynced()) {
				return;
			}
			lastSyncJson = null;
			syncKingdom();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		boolean wasLoggedIn = previousGameState == GameState.LOGGED_IN;
		boolean nowLoggedIn = newState == GameState.LOGGED_IN;
		if (wasLoggedIn && !nowLoggedIn) {
			if (trigger.leaveAndShouldFlush()) {
				syncKingdom();
			}
			// Every login re-sends once so the server's capture time stays fresh even
			// when nothing changed.
			lastSyncJson = null;
			wasInKingdom = false;
		}
		previousGameState = newState;
	}

	/** Only the kingdom varbits matter here; every other change is ignored. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		if (KINGDOM_VARBITS.contains(event.getVarbitId())) {
			trigger.varbitChanged();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		boolean inKingdom = loggedIn && isInKingdom();
		if (inKingdom && !wasInKingdom) {
			trigger.varbitChanged();
		}
		wasInKingdom = inKingdom;
		if (trigger.tick(client.getTickCount(), loggedIn) == DiarySyncTrigger.Decision.SYNC) {
			syncKingdom();
		}
	}

	static boolean isKingdomRegion(int regionId) {
		return KINGDOM_REGIONS.contains(regionId);
	}

	/** Whether a read is trustworthy enough to send. */
	static boolean shouldSend(boolean throneCompleted, int approvalPoints) {
		return throneCompleted && approvalPoints > 0;
	}

	private boolean isInKingdom() {
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || localPlayer.getWorldLocation() == null) {
			return false;
		}
		return isKingdomRegion(localPlayer.getWorldLocation().getRegionID());
	}

	/** Reads the kingdom varbits and syncs them (deduped). Client thread only. */
	private void syncKingdom() {
		if (!config.syncKingdom() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		boolean throneCompleted = Quest.THRONE_OF_MISCELLANIA.getState(client) == QuestState.FINISHED;
		int approvalPoints = client.getVarbitValue(VarbitID.MISC_APPROVAL);
		if (!shouldSend(throneCompleted, approvalPoints)) {
			return;
		}
		boolean royalTroubleCompleted = Quest.ROYAL_TROUBLE.getState(client) == QuestState.FINISHED;
		Map<String, Integer> extra = Collections.singletonMap(
				EXTRA_RESTOTAL_LOWBITS, client.getVarbitValue(RESTOTAL_LOWBITS_VARBIT));

		KingdomSyncPayload payload = new KingdomSyncPayload(
				playerUsername,
				wasInKingdom,
				throneCompleted,
				royalTroubleCompleted,
				approvalPoints,
				client.getVarbitValue(VarbitID.MISC_COFFERS),
				client.getVarbitValue(VarbitID.MISC_POINTS_WOOD),
				client.getVarbitValue(VarbitID.MISC_POINTS_HERB),
				client.getVarbitValue(VarbitID.MISC_POINTS_FISH),
				client.getVarbitValue(VarbitID.MISC_POINTS_MINE),
				client.getVarbitValue(VarbitID.MISC_POINTS_RAREWOOD),
				client.getVarbitValue(VarbitID.MISC_POINTS_FARM),
				client.getVarbitValue(VarbitID.MISC_COOKED),
				client.getVarbitValue(VarbitID.MISC_RAREWOOD_TYPE),
				client.getVarbitValue(VarbitID.MISC_HERBS_OR_FLAX),
				extra);
		String json = payload.toJson(gson);
		// Dedup on the whole payload (username included), so switching to another
		// account with identical values still syncs.
		if (json.equals(lastSyncJson)) {
			log.debug("Kingdom state unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing kingdom state (approval {}, coffer {}) for player: {}",
				approvalPoints, payload.getCoffer(), playerUsername);
		apiClient.sendKingdomSync(payload);
	}
}
