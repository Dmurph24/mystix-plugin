package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapList;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;

/**
 * Shared roadmap state for the side panel and the overlay.
 *
 * <p>Holds the currently selected roadmap id (persisted per RuneLite profile via
 * {@link ConfigManager}) and the last fetched {@link Roadmap}, so the panel and
 * the overlay render the same data without each issuing their own requests. All
 * mutation happens through here; the panel drives fetches, the overlay reads the
 * cached roadmap.
 */
@Slf4j
@Singleton
public class RoadmapManager {
	static final String CONFIG_GROUP = "mystix";
	static final String SELECTED_ROADMAP_KEY = "selectedRoadmapId";

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ConfigManager configManager;

	private volatile Roadmap currentRoadmap;

	@Inject
	public RoadmapManager(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ConfigManager configManager) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
	}

	/** The logged-in RS username, or null if not logged in. */
	public String getPlayerUsername() {
		return SyncGuard.getPlayerUsername(client);
	}

	public boolean hasAppKey() {
		return SyncGuard.hasAppKey(config);
	}

	/** The last fetched roadmap (may be null), used by the overlay. */
	public Roadmap getCurrentRoadmap() {
		return currentRoadmap;
	}

	void setCurrentRoadmap(Roadmap roadmap) {
		this.currentRoadmap = roadmap;
	}

	void clear() {
		this.currentRoadmap = null;
	}

	public Integer getSelectedRoadmapId() {
		return configManager.getConfiguration(
				CONFIG_GROUP, SELECTED_ROADMAP_KEY, Integer.class);
	}

	public void setSelectedRoadmapId(int collectionId) {
		configManager.setConfiguration(CONFIG_GROUP, SELECTED_ROADMAP_KEY, collectionId);
	}

	/**
	 * Fetches the player's roadmap list. The callback runs on the OkHttp thread;
	 * Swing callers must marshal back to the EDT themselves.
	 */
	public void fetchRoadmaps(MystixApiClient.RoadmapCallback<RoadmapList> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to load your roadmaps");
			return;
		}
		apiClient.getRoadmaps(player, callback);
	}

	/**
	 * Fetches one roadmap fully rendered and caches it as the current roadmap on
	 * success (so the overlay picks it up). The callback also fires.
	 */
	public void fetchRoadmap(int collectionId, MystixApiClient.RoadmapCallback<Roadmap> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to load your roadmap");
			return;
		}
		apiClient.getRoadmap(collectionId, player, cacheThen(collectionId, callback));
	}

	/**
	 * Recomputes one roadmap on the backend and caches the re-rendered result.
	 */
	public void recomputeRoadmap(int collectionId, MystixApiClient.RoadmapCallback<Roadmap> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to refresh your roadmap");
			return;
		}
		apiClient.recomputeRoadmap(collectionId, player, cacheThen(collectionId, callback));
	}

	/**
	 * Manually marks a goal complete on the backend and caches the re-rendered
	 * roadmap (so the overlay's next goal updates too). The callback runs on the
	 * OkHttp thread; Swing callers marshal back to the EDT themselves.
	 */
	public void completeGoal(int collectionId, int goalId,
			MystixApiClient.RoadmapCallback<Roadmap> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to update your roadmap");
			return;
		}
		apiClient.completeRoadmapGoal(collectionId, goalId, player, cacheThen(collectionId, callback));
	}

	/**
	 * Deletes a goal from the backend and caches the re-rendered roadmap (so the
	 * overlay's next goal updates too). Callback runs on the OkHttp thread.
	 */
	public void deleteGoal(int collectionId, int goalId,
			MystixApiClient.RoadmapCallback<Roadmap> callback) {
		String player = getPlayerUsername();
		if (player == null) {
			callback.onError("Log in to update your roadmap");
			return;
		}
		apiClient.deleteRoadmapGoal(collectionId, goalId, player, cacheThen(collectionId, callback));
	}

	/**
	 * Refreshes the cached selected roadmap silently (no UI callback). Used by the
	 * overlay path on login / periodically so it has fresh data even when the
	 * panel is closed.
	 */
	public void refreshSelectedQuietly() {
		if (!config.showNextGoal() || !hasAppKey()) {
			return;
		}
		Integer selected = getSelectedRoadmapId();
		if (selected == null || getPlayerUsername() == null) {
			return;
		}
		fetchRoadmap(selected, new MystixApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
				// cacheThen already stored it.
			}

			@Override
			public void onError(String message) {
				log.debug("Quiet roadmap refresh failed: {}", message);
			}
		});
	}

	private MystixApiClient.RoadmapCallback<Roadmap> cacheThen(
			int collectionId, MystixApiClient.RoadmapCallback<Roadmap> callback) {
		return new MystixApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
				Integer selected = getSelectedRoadmapId();
				// Only cache for the overlay if this is the selected roadmap.
				if (selected != null && selected == collectionId) {
					setCurrentRoadmap(result);
				}
				callback.onSuccess(result);
			}

			@Override
			public void onError(String message) {
				callback.onError(message);
			}
		};
	}

	/** Convenience for callers that only need the cached roadmap, no fetch. */
	public void withCurrentRoadmap(Consumer<Roadmap> consumer) {
		consumer.accept(currentRoadmap);
	}
}
