package com.mystix;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mystix.api.MystixApiClient;
import com.mystix.model.DeathEvent;
import com.mystix.model.DeathSyncPayload;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Records the local player's deaths and syncs them to the Mystix API.
 *
 * <p>A death is {@link ActorDeath} on the local player, plus the Doom of
 * Mokhaiotl death animation, which the game plays instead. The killer is the
 * last NPC or player seen attacking the player shortly before, falling back to
 * whatever the player was attacking; anything older counts as unknown (venom,
 * a stray hit long ago).
 *
 * <p>Deaths are queued in the RuneLite profile config and resent until the
 * backend acknowledges them, so a death survives a failed request or a client
 * restart. On login the queue is sent even when empty, which tells the backend
 * this account reports deaths.
 */
@Slf4j
@Singleton
public class DeathMonitor {
	private static final String PENDING_CONFIG_KEY = "pendingDeathEvents";
	// The RSProfile is set a little after LOGGED_IN, so wait before the first read.
	private static final int LOGIN_SYNC_DELAY_SECONDS = 5;
	// How recent an attacker or target must be to be named as the killer.
	static final int KILLER_WINDOW_TICKS = 10;
	// A death animation and ActorDeath for the same death land a few ticks apart.
	private static final int DUPLICATE_DEATH_TICKS = 10;
	// Deaths the backend never acknowledges stop piling up past this.
	static final int MAX_PENDING = 100;

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;
	private final Gson gson;

	private final List<DeathEvent> pending = new ArrayList<>();
	private GameState previousGameState = GameState.UNKNOWN;
	private KillerCandidate lastAttacker;
	private KillerCandidate lastTarget;
	private int lastDeathTick = -1;

	@Inject
	public DeathMonitor(
			Client client,
			ClientThread clientThread,
			MystixConfig config,
			MystixApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService,
			Gson gson) {
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
		this.gson = gson;
	}

	public void stop() {
		pending.clear();
		previousGameState = GameState.UNKNOWN;
		lastAttacker = null;
		lastTarget = null;
		lastDeathTick = -1;
	}

	/** Resends anything still queued (and the tracking handshake) on the client thread. */
	public void forceSync() {
		clientThread.invokeLater(this::sync);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			executorService.schedule(() -> clientThread.invokeLater(() -> {
				restorePending();
				sync();
			}), LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
			lastAttacker = null;
			lastTarget = null;
			if (!pending.isEmpty()) {
				sync();
			}
		}
		previousGameState = newState;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event) {
		Player local = client.getLocalPlayer();
		if (local == null || event.getActor() != local) {
			return;
		}
		KillerCandidate attacker = findAttacker(local);
		if (attacker != null) {
			lastAttacker = attacker;
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event) {
		Player local = client.getLocalPlayer();
		if (local == null || event.getSource() != local || event.getTarget() == null) {
			return;
		}
		KillerCandidate target = candidate(event.getTarget());
		if (target != null) {
			lastTarget = target;
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event) {
		if (event.getActor() == client.getLocalPlayer()) {
			recordDeath();
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event) {
		Actor actor = event.getActor();
		if (actor == client.getLocalPlayer()
				&& actor.getAnimation() == AnimationID.HUMAN_DOOM_SCORPION_01_PLAYER_DEATH_01) {
			recordDeath();
		}
	}

	private void recordDeath() {
		int tick = client.getTickCount();
		if (lastDeathTick != -1 && tick - lastDeathTick < DUPLICATE_DEATH_TICKS) {
			return;
		}
		lastDeathTick = tick;
		if (!config.syncDeaths() || !SyncGuard.hasAppKey(config) || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		Player local = client.getLocalPlayer();
		if (local == null) {
			return;
		}

		KillerCandidate killer = chooseKiller(lastAttacker, lastTarget, tick);
		WorldPoint point = local.getLocalLocation() == null
				? local.getWorldLocation()
				: WorldPoint.fromLocalInstance(client, local.getLocalLocation());
		WorldView view = client.getTopLevelWorldView();

		DeathEvent death = new DeathEvent(
				UUID.randomUUID().toString(),
				Instant.now().toString(),
				killer == null ? DeathEvent.KILLER_UNKNOWN : killer.type,
				killer == null ? null : killer.npcId,
				killer == null ? null : killer.npcName,
				point == null ? null : point.getRegionID(),
				point == null ? null : point.getX(),
				point == null ? null : point.getY(),
				point == null ? null : point.getPlane(),
				view != null && view.isInstance(),
				client.getWorld(),
				worldTypeNames(client.getWorldType()));
		log.debug("Recorded death: killer={} {}", death.getKillerType(), death.getNpcName());

		pending.add(death);
		trimPending(pending);
		persistPending();
		lastAttacker = null;
		lastTarget = null;
		sync();
	}

	private void sync() {
		if (!config.syncDeaths() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}
		List<DeathEvent> sent = new ArrayList<>(pending);
		apiClient.sendDeathsSync(new DeathSyncPayload(playerUsername, sent),
				() -> clientThread.invokeLater(() -> onAcknowledged(sent)));
	}

	private void onAcknowledged(List<DeathEvent> sent) {
		pending.removeIf(e -> sent.stream().anyMatch(s -> s.getEventUuid().equals(e.getEventUuid())));
		persistPending();
	}

	/** Who is hitting the local player: what it is fighting if that fights back, else anyone targeting it. */
	private KillerCandidate findAttacker(Player local) {
		Actor target = local.getInteracting();
		if (target != null && target.getInteracting() == local) {
			return candidate(target);
		}
		WorldView view = client.getTopLevelWorldView();
		if (view == null) {
			return null;
		}
		for (NPC npc : view.npcs()) {
			if (npc.getInteracting() == local) {
				return candidate(npc);
			}
		}
		for (Player player : view.players()) {
			if (player != local && player.getInteracting() == local) {
				return candidate(player);
			}
		}
		return null;
	}

	private KillerCandidate candidate(Actor actor) {
		int tick = client.getTickCount();
		if (actor instanceof NPC) {
			NPC npc = (NPC) actor;
			return new KillerCandidate(DeathEvent.KILLER_NPC, npc.getId(), npc.getName(), tick);
		}
		if (actor instanceof Player) {
			// Other players are never named: the death only records that one did it.
			return new KillerCandidate(DeathEvent.KILLER_PLAYER, null, null, tick);
		}
		return null;
	}

	/**
	 * The killer for a death on {@code deathTick}: the latest attacker within the
	 * window, else the latest thing the player was attacking, else null.
	 */
	static KillerCandidate chooseKiller(KillerCandidate attacker, KillerCandidate target, int deathTick) {
		if (attacker != null && deathTick - attacker.tick <= KILLER_WINDOW_TICKS) {
			return attacker;
		}
		if (target != null && deathTick - target.tick <= KILLER_WINDOW_TICKS) {
			return target;
		}
		return null;
	}

	static List<String> worldTypeNames(EnumSet<WorldType> worldTypes) {
		List<String> names = new ArrayList<>();
		if (worldTypes != null) {
			for (WorldType type : worldTypes) {
				names.add(type.name());
			}
		}
		return names;
	}

	/** Drops the oldest deaths once the queue is over {@link #MAX_PENDING}. */
	static void trimPending(List<DeathEvent> events) {
		while (events.size() > MAX_PENDING) {
			events.remove(0);
		}
	}

	private void persistPending() {
		if (pending.isEmpty()) {
			configManager.unsetRSProfileConfiguration("mystix", PENDING_CONFIG_KEY);
		} else {
			configManager.setRSProfileConfiguration("mystix", PENDING_CONFIG_KEY, gson.toJson(pending));
		}
	}

	private void restorePending() {
		String stored = configManager.getRSProfileConfiguration("mystix", PENDING_CONFIG_KEY);
		if (stored == null || stored.isEmpty()) {
			return;
		}
		try {
			List<DeathEvent> restored = gson.fromJson(stored, new TypeToken<List<DeathEvent>>() { }.getType());
			if (restored != null) {
				for (DeathEvent e : restored) {
					if (pending.stream().noneMatch(p -> p.getEventUuid().equals(e.getEventUuid()))) {
						pending.add(e);
					}
				}
				trimPending(pending);
			}
		} catch (RuntimeException e) {
			log.warn("Discarding unparseable pending deaths", e);
			configManager.unsetRSProfileConfiguration("mystix", PENDING_CONFIG_KEY);
		}
	}

	/** Something that may have killed the player, and the tick it was last seen. */
	static final class KillerCandidate {
		final String type;
		final Integer npcId;
		final String npcName;
		final int tick;

		KillerCandidate(String type, Integer npcId, String npcName, int tick) {
			this.type = type;
			this.npcId = npcId;
			this.npcName = npcName;
			this.tick = tick;
		}
	}
}
