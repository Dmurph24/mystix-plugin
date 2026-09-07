package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.PlayerSkillsSyncPayload;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Uploads skill levels and XP: on login, on logout, and while training (at most
 * every {@link #TRAINING_SYNC_INTERVAL_SECONDS} while XP is actually rising) so
 * the server can keep roadmap skill goals current without waiting for logout.
 */
@Slf4j
@Singleton
public class PlayerSkillsMonitor {
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	/** How often to re-upload while training; nothing is sent when XP is static. */
	static final int TRAINING_SYNC_INTERVAL_SECONDS = 120;

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;

	private GameState previousGameState = GameState.UNKNOWN;
	/** Last XP seen per skill (client thread); the first value after login is the replay. */
	private final Map<String, Integer> lastSeenXp = new HashMap<>();
	private volatile boolean xpDirty;
	private ScheduledFuture<?> trainingTask;
	/** The XP snapshot of the last upload the server acknowledged, keyed by skill name. */
	private volatile Map<String, Integer> lastUploadedXp = Collections.emptyMap();
	private volatile Consumer<Map<String, Integer>> uploadListener;

	@Inject
	public PlayerSkillsMonitor(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ScheduledExecutorService executorService) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.executorService = executorService;
	}

	public void start() {
		if (trainingTask == null) {
			trainingTask = executorService.scheduleAtFixedRate(this::syncIfDirty,
					TRAINING_SYNC_INTERVAL_SECONDS, TRAINING_SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);
		}
	}

	public void stop() {
		if (trainingTask != null) {
			trainingTask.cancel(false);
			trainingTask = null;
		}
		previousGameState = GameState.UNKNOWN;
		lastSeenXp.clear();
		xpDirty = false;
		uploadListener = null;
	}

	/** Receives each acknowledged upload's XP snapshot (set by the plugin). */
	public void setUploadListener(Consumer<Map<String, Integer>> listener) {
		this.uploadListener = listener;
	}

	public Map<String, Integer> getLastUploadedXp() {
		return lastUploadedXp;
	}

	/**
	 * Re-runs the skills sync immediately on the background executor. Used by the
	 * roadmap panel's "Sync &amp; refresh" button to re-push current progress.
	 */
	public void forceSync() {
		executorService.execute(this::syncPlayerSkills);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();

		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			log.debug("Player logged in, scheduling skills sync in {}s", LOGIN_SYNC_DELAY_SECONDS);
			executorService.schedule(this::syncPlayerSkills, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			log.debug("Player logged out, syncing skills");
			lastSeenXp.clear();
			xpDirty = false;
			syncPlayerSkills();
		}

		previousGameState = newState;
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		Integer previous = lastSeenXp.put(event.getSkill().getName(), event.getXp());
		if (xpIncreased(previous, event.getXp())) {
			xpDirty = true;
		}
	}

	/** True only for a real gain: the first observation after login is the server
	 * replay, and boosted-level events carry the same XP. */
	static boolean xpIncreased(Integer previous, int now) {
		return previous != null && now > previous;
	}

	private void syncIfDirty() {
		if (!xpDirty || client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		xpDirty = false;
		syncPlayerSkills();
	}

	/**
	 * Reads all skill levels and XP from the client, builds a payload with
	 * total and combat levels, and sends it to the Mystix API.
	 */
	private void syncPlayerSkills() {
		if (!SyncGuard.hasAppKey(config)) {
			log.debug("Player skills sync skipped: no App Key configured");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("Player skills sync skipped: special game mode detected (Leagues, DMM, etc.)");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("Player skills sync skipped: could not get player username");
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		Map<String, Integer> xpSnapshot = new HashMap<>();
		int totalLevel = 0;

		for (Skill skill : Skill.values()) {
			int level = client.getRealSkillLevel(skill);
			int xp = client.getSkillExperience(skill);
			skills.put(skill.getName(), new PlayerSkillsSyncPayload.SkillData(level, xp));
			xpSnapshot.put(skill.getName(), xp);
			totalLevel += level;
		}

		int combatLevel = localPlayer.getCombatLevel();

		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload(playerUsername, skills, totalLevel, combatLevel);
		log.debug("Syncing {} skills for player: {} (Total Level: {}, Combat Level: {})",
				skills.size(), playerUsername, totalLevel, combatLevel);
		Map<String, Integer> snapshot = Collections.unmodifiableMap(xpSnapshot);
		apiClient.sendPlayerSkillsSync(payload, () -> {
			lastUploadedXp = snapshot;
			Consumer<Map<String, Integer>> listener = uploadListener;
			if (listener != null) {
				listener.accept(snapshot);
			}
		});
	}
}
