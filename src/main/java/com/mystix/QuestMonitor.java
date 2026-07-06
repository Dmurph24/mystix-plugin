package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.QuestsSyncPayload;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the player's quest progress and syncs it to the Mystix API.
 *
 * <p>Reads every quest's state via RuneLite's {@link Quest} enum + {@link QuestState}
 * (the same source WikiSync uses) and pushes a {questName: status} map where status is
 * 0 = not started, 1 = in progress, 2 = completed. Using {@link Quest#getName()} for the
 * keys reproduces WikiSync's exact names (including the Recipe for Disaster subquests),
 * so the backend consumes the payload unchanged.
 *
 * <p>Triggers: a sync on login (after a short delay so quest varps have loaded) and on
 * logout, plus a near-real-time sync when a quest completes. Quest state changes flip a
 * varp, so we mark a re-check on {@link VarbitChanged} and read+dedupe on the next
 * {@link GameTick} (throttled), which collapses varp churn to at most one read per few
 * ticks. A JSON equality check means an unchanged quest set is never resent.
 */
@Slf4j
@Singleton
public class QuestMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	// Minimum ticks between varp-driven reads, so continuous varp churn during play
	// doesn't re-read every quest every tick. A quest completion still syncs within
	// a few ticks, and login/logout syncs are the safety net.
	private static final int RESYNC_THROTTLE_TICKS = 3;

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean questCheckPending;
	private int lastReadTick = -1;
	private String lastSyncJson;

	@Inject
	public QuestMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ScheduledExecutorService executorService,
			Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
		this.gson = gson;
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		questCheckPending = false;
		lastReadTick = -1;
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the current quest states on the client thread. Used by the
	 * roadmap panel's "Sync &amp; refresh"; clears the dedup cache so an unchanged set
	 * still sends.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			syncQuests();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// Quest varps load shortly after LOGGED_IN, so wait before reading.
			executorService.schedule(() -> clientThread.invokeLater(this::syncQuests),
					LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			// Logging out: flush final state (this handler runs on the client thread).
			syncQuests();
		}
		previousGameState = newState;
	}

	/** A quest state change flips a varp; mark a re-check for the next game tick. */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event) {
		questCheckPending = true;
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (!questCheckPending) {
			return;
		}
		if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
			return;
		}
		questCheckPending = false;
		lastReadTick = client.getTickCount();
		syncQuests();
	}

	/** Reads all quest states, builds the payload, and syncs (deduped). Client thread only. */
	private void syncQuests() {
		if (!config.syncQuests() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		// Sorted keys give a stable JSON ordering so the dedup check is reliable.
		Map<String, Integer> questStates = new TreeMap<>();
		for (Quest quest : Quest.values()) {
			questStates.put(quest.getName(), toStatus(quest.getState(client)));
		}

		QuestsSyncPayload payload = new QuestsSyncPayload(playerUsername, questStates);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Quests unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing {} quests for player: {}", questStates.size(), playerUsername);
		apiClient.sendQuestsSync(payload);
	}

	/** Maps a RuneLite {@link QuestState} to the WikiSync status code (0/1/2). */
	private static int toStatus(QuestState state) {
		if (state == QuestState.FINISHED) {
			return 2;
		}
		if (state == QuestState.IN_PROGRESS) {
			return 1;
		}
		return 0;
	}
}
