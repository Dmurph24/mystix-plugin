package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapList;
import com.mystix.model.RoadmapSummary;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;

/**
 * Shared roadmap state for the side panel and the overlay.
 *
 * <p>Holds the currently selected roadmap id (persisted per RuneLite profile via
 * {@link ConfigManager}) and the last fetched {@link Roadmap}, so the panel and
 * the overlay render the same data without each issuing their own requests. All
 * mutation happens through here; the panel drives fetches, the overlay reads the
 * cached roadmap.
 *
 * <p>Also owns the background reconciliation: a periodic re-read of every one
 * of the player's roadmaps plus debounced re-reads requested shortly after data
 * uploads, so the server's view of goal progress reaches the plugin without the
 * panel open. Every fetched roadmap is handed to the roadmap listener (the goal
 * tracker); only the selected one becomes the overlay's current roadmap.
 */
@Slf4j
@Singleton
public class RoadmapManager {
	static final String CONFIG_GROUP = "mystix";
	static final String SELECTED_ROADMAP_KEY = "selectedRoadmapId";
	static final String COMPLETED_EXPANDED_KEY = "completedGoalsExpanded";

	private final Client client;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ConfigManager configManager;
	private final ReconcileScheduler scheduler;

	private volatile Roadmap currentRoadmap;
	/** Hears every fetched roadmap, selected or not; set by the plugin. */
	private volatile Consumer<Roadmap> roadmapListener;
	/** Hears the player's current set of roadmap ids after each list fetch. */
	private volatile Consumer<Set<Integer>> roadmapSetListener;
	/** Hears roadmaps that arrived from a background refresh; set by the open panel. */
	private volatile Consumer<Roadmap> panelListener;
	private volatile boolean panelActive;

	@Inject
	public RoadmapManager(
			Client client,
			MystixConfig config,
			MystixApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.scheduler = new ReconcileScheduler(executorService, this::refreshAllQuietly);
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
		setCurrentRoadmap(null);
	}

	public void setRoadmapListener(Consumer<Roadmap> listener) {
		this.roadmapListener = listener;
	}

	public void setRoadmapSetListener(Consumer<Set<Integer>> listener) {
		this.roadmapSetListener = listener;
	}

	/** Every fetched roadmap goes to the tracker; the selected one also becomes current. */
	private void onRoadmapFetched(Roadmap roadmap) {
		Consumer<Roadmap> listener = roadmapListener;
		if (listener != null) {
			listener.accept(roadmap);
		}
		Integer selected = getSelectedRoadmapId();
		if (selected != null && selected == roadmap.getCollectionId()) {
			setCurrentRoadmap(roadmap);
		}
	}

	/**
	 * Makes a roadmap the active one (a goal in it just completed): persists the
	 * selection, swaps the overlay's roadmap and lets an open panel follow.
	 */
	public void selectRoadmap(Roadmap roadmap) {
		if (roadmap == null) {
			return;
		}
		Integer selected = getSelectedRoadmapId();
		if (selected == null || selected != roadmap.getCollectionId()) {
			log.debug("Switching active roadmap to {}", roadmap.getTitle());
			setSelectedRoadmapId(roadmap.getCollectionId());
		}
		setCurrentRoadmap(roadmap);
	}

	public void setPanelListener(Consumer<Roadmap> listener) {
		this.panelListener = listener;
	}

	/** Asks an open panel to re-render the cached roadmap (local progress changed). */
	public void notifyPanel() {
		Roadmap roadmap = currentRoadmap;
		Consumer<Roadmap> listener = panelListener;
		if (roadmap != null && listener != null) {
			listener.accept(roadmap);
		}
	}

	/** Whether the side panel is open; background refreshes run while it is. */
	public void setPanelActive(boolean active) {
		this.panelActive = active;
	}

	public Integer getSelectedRoadmapId() {
		return configManager.getConfiguration(
				CONFIG_GROUP, SELECTED_ROADMAP_KEY, Integer.class);
	}

	public void setSelectedRoadmapId(int collectionId) {
		configManager.setConfiguration(CONFIG_GROUP, SELECTED_ROADMAP_KEY, collectionId);
	}

	/** Whether the panel's "Completed (N)" list is expanded; collapsed by default. */
	public boolean isCompletedGoalsExpanded() {
		Boolean expanded = configManager.getConfiguration(
				CONFIG_GROUP, COMPLETED_EXPANDED_KEY, Boolean.class);
		return expanded != null && expanded;
	}

	public void setCompletedGoalsExpanded(boolean expanded) {
		configManager.setConfiguration(CONFIG_GROUP, COMPLETED_EXPANDED_KEY, expanded);
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
		apiClient.getRoadmaps(player, new MystixApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				// Keep the tracker aware of every roadmap, not just the one the
				// panel is about to open.
				onRoadmapListFetched(result, player, null);
				callback.onSuccess(result);
			}

			@Override
			public void onError(String message) {
				callback.onError(message);
			}
		});
	}

	/**
	 * Tells the tracker which roadmaps exist and fetches each one that the caller
	 * is not already fetching itself, so goals in every roadmap are tracked.
	 *
	 * @param skipId roadmap the caller fetches on its own (may be null)
	 * @param onDone runs once every fetch has finished (may be null)
	 */
	private void onRoadmapListFetched(RoadmapList list, String player, Runnable onDone) {
		List<RoadmapSummary> summaries = list.getRoadmaps();
		Set<Integer> ids = new HashSet<>();
		for (RoadmapSummary summary : summaries) {
			ids.add(summary.getCollectionId());
		}
		Consumer<Set<Integer>> setListener = roadmapSetListener;
		if (setListener != null) {
			setListener.accept(ids);
		}
		if (getSelectedRoadmapId() == null && !summaries.isEmpty()) {
			setSelectedRoadmapId(summaries.get(0).getCollectionId());
		}
		if (summaries.isEmpty()) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		AtomicInteger remaining = new AtomicInteger(summaries.size());
		for (RoadmapSummary summary : summaries) {
			apiClient.getRoadmap(summary.getCollectionId(), player, new MystixApiClient.RoadmapCallback<Roadmap>() {
				@Override
				public void onSuccess(Roadmap result) {
					onRoadmapFetched(result);
					finish();
				}

				@Override
				public void onError(String message) {
					log.debug("Roadmap {} refresh failed: {}", summary.getCollectionId(), message);
					finish();
				}

				private void finish() {
					if (remaining.decrementAndGet() == 0 && onDone != null) {
						onDone.run();
					}
				}
			});
		}
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

	// ------------------------------------------------------- reconciliation

	/** Starts the periodic background re-read of the selected roadmap. */
	public void startPeriodicRefresh() {
		scheduler.startPeriodic();
	}

	public void stopPeriodicRefresh() {
		scheduler.stop();
	}

	/** Re-reads every roadmap after a short lag (debounced). */
	public void requestReconcile(int delaySeconds) {
		scheduler.request(delaySeconds);
		notifyPanel(); // so server-driven goals can show their syncing state
	}

	/** True while a server re-read is scheduled or running (drives "Syncing" hints). */
	public boolean isSyncing() {
		return scheduler.isBusy();
	}

	/**
	 * Refreshes every roadmap silently (no UI callback): the list, then each
	 * roadmap's rendered goals. Runs on login, on the periodic schedule and after
	 * uploads, so goal progress in any roadmap reaches the plugin even when the
	 * panel is closed. A plain GET re-evaluates goals server-side, so no
	 * recompute call is needed.
	 */
	public void refreshAllQuietly() {
		if (!hasAppKey() || (!config.showNextGoal() && !panelActive)) {
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN || GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String player = getPlayerUsername();
		if (player == null) {
			return;
		}
		if (!scheduler.markInFlight()) {
			log.debug("Quiet roadmap refresh skipped: one already in flight");
			return;
		}
		notifyPanel();
		apiClient.getRoadmaps(player, new MystixApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				onRoadmapListFetched(result, player, () -> {
					scheduler.clearInFlight();
					notifyPanel();
				});
			}

			@Override
			public void onError(String message) {
				scheduler.clearInFlight();
				log.debug("Quiet roadmap refresh failed: {}", message);
				notifyPanel();
			}
		});
	}

	private MystixApiClient.RoadmapCallback<Roadmap> cacheThen(
			int collectionId, MystixApiClient.RoadmapCallback<Roadmap> callback) {
		return new MystixApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
				// Feeds the tracker; caches for the overlay only if selected.
				onRoadmapFetched(result);
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
