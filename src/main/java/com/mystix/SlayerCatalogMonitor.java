package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.SlayerCatalogPayload;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/**
 * Dumps the game-cache slayer DB tables (SlayerTask, SlayerMasterTask,
 * SlayerArea, SlayerTaskSublist, SlayerUnlock) and uploads them to the Mystix
 * API once per cache revision.
 *
 * <p>This is global data, identical for every player on the same revision, so
 * the flow is: dump on the client thread, hash, ask the server whether it
 * already has this hash, and upload only when it does not. In practice one
 * player per game update pays the upload for everyone.
 *
 * <p>Rows are enumerated by probing indexed key columns over bounded ranges
 * (the tables key on small integers); misses are cheap cache lookups.
 */
@Slf4j
@Singleton
public class SlayerCatalogMonitor {
	private static final int LOGIN_DUMP_DELAY_SECONDS = 12;
	private static final int MAX_TASK_ID = 512;
	private static final int MAX_MASTER_ID = 32;
	private static final int MAX_AREA_ID = 256;
	private static final int MAX_SUBLIST_ID = 64;
	private static final int MAX_UNLOCK_BIT = 96;

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final ScheduledExecutorService executorService;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	/** Hash already confirmed present server-side this session; skip re-checks. */
	private String confirmedHash;

	@Inject
	public SlayerCatalogMonitor(
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
		confirmedHash = null;
	}

	public void forceSync() {
		confirmedHash = null;
		clientThread.invokeLater(this::dumpAndUpload);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			// Late enough that the cache DB tables are certainly readable, and
			// well behind the state monitors' own login syncs.
			executorService.schedule(() -> clientThread.invokeLater(this::dumpAndUpload),
					LOGIN_DUMP_DELAY_SECONDS, TimeUnit.SECONDS);
		}
		previousGameState = newState;
	}

	/** Dumps the tables and uploads when the server lacks this hash. Client thread only. */
	private void dumpAndUpload() {
		if (!config.syncSlayer() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}

		Map<String, List<Map<String, Object>>> tables;
		try {
			tables = dumpTables();
		} catch (RuntimeException e) {
			log.warn("Slayer catalog dump failed", e);
			return;
		}
		if (tables.get("slayer_task").isEmpty()) {
			log.debug("Slayer catalog dump produced no tasks; skipping");
			return;
		}

		String tablesJson = gson.toJson(tables);
		String hash = sha256(tablesJson);
		if (hash == null || hash.equals(confirmedHash)) {
			return;
		}
		String revision = String.valueOf(client.getRevision());

		SlayerCatalogPayload payload =
				new SlayerCatalogPayload(playerUsername, revision, hash, tables);
		apiClient.getSlayerCatalogStatus(hash, new MystixApiClient.RoadmapCallback<>() {
			@Override
			public void onSuccess(SlayerCatalogPayload.Status status) {
				confirmedHash = hash;
				if (status.isNeeded()) {
					log.debug("Uploading slayer catalog (revision {}, {} tasks)",
							revision, tables.get("slayer_task").size());
					apiClient.sendSlayerCatalog(payload);
				} else {
					log.debug("Slayer catalog {} already known server-side", hash);
				}
			}

			@Override
			public void onError(String message) {
				log.debug("Slayer catalog status check failed: {}", message);
			}
		});
	}

	private Map<String, List<Map<String, Object>>> dumpTables() {
		Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
		tables.put("slayer_task", dumpSlayerTasks());
		tables.put("slayer_master_task", dumpMasterTasks());
		tables.put("slayer_area", dumpAreas());
		tables.put("slayer_task_sublist", dumpSublist());
		tables.put("slayer_unlock", dumpUnlocks());
		return tables;
	}

	private List<Map<String, Object>> dumpSlayerTasks() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int id = 0; id < MAX_TASK_ID; id++) {
			for (int row : client.getDBRowsByValue(
					DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, id)) {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("id", id);
				out.put("name", firstString(row, DBTableID.SlayerTask.ID,
						DBTableID.SlayerTask.COL_NAME_UPPERCASE));
				out.put("min_combat_level", firstInt(row, DBTableID.SlayerTask.ID,
						DBTableID.SlayerTask.COL_MIN_COMLEVEL));
				rows.add(out);
			}
		}
		return rows;
	}

	private List<Map<String, Object>> dumpMasterTasks() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int masterId = 0; masterId < MAX_MASTER_ID; masterId++) {
			for (int row : client.getDBRowsByValue(
					DBTableID.SlayerMasterTask.ID,
					DBTableID.SlayerMasterTask.COL_MASTER_ID, 0, masterId)) {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("master_id", masterId);
				// COL_TASK is a dbrow reference; dereference to the task's id.
				Integer taskRow = firstInt(row, DBTableID.SlayerMasterTask.ID,
						DBTableID.SlayerMasterTask.COL_TASK);
				out.put("task_id", taskRow == null ? null
						: firstInt(taskRow, DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID));
				out.put("weight", firstInt(row, DBTableID.SlayerMasterTask.ID,
						DBTableID.SlayerMasterTask.COL_WEIGHT));
				out.put("min_amount", firstInt(row, DBTableID.SlayerMasterTask.ID,
						DBTableID.SlayerMasterTask.COL_MIN_AMOUNT));
				out.put("max_amount", firstInt(row, DBTableID.SlayerMasterTask.ID,
						DBTableID.SlayerMasterTask.COL_MAX_AMOUNT));
				Integer unlockRow = firstInt(row, DBTableID.SlayerMasterTask.ID,
						DBTableID.SlayerMasterTask.COL_TASK_UNLOCK);
				out.put("unlock_bit", unlockRow == null ? null
						: firstInt(unlockRow, DBTableID.SlayerUnlock.ID, DBTableID.SlayerUnlock.COL_BIT));
				Integer areaRow = firstInt(row, DBTableID.SlayerMasterTask.ID,
						DBTableID.SlayerMasterTask.COL_AREAS);
				List<Integer> areas = new ArrayList<>();
				if (areaRow != null) {
					Integer areaId = firstInt(areaRow, DBTableID.SlayerArea.ID,
							DBTableID.SlayerArea.COL_AREA_ID);
					if (areaId != null) {
						areas.add(areaId);
					}
				}
				out.put("areas", areas);
				rows.add(out);
			}
		}
		return rows;
	}

	private List<Map<String, Object>> dumpAreas() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int areaId = 1; areaId < MAX_AREA_ID; areaId++) {
			for (int row : client.getDBRowsByValue(
					DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId)) {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("id", areaId);
				out.put("name", firstString(row, DBTableID.SlayerArea.ID,
						DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER));
				rows.add(out);
			}
		}
		return rows;
	}

	private List<Map<String, Object>> dumpSublist() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int sublistId = 1; sublistId < MAX_SUBLIST_ID; sublistId++) {
			for (int row : client.getDBRowsByValue(
					DBTableID.SlayerTaskSublist.ID,
					DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, sublistId)) {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("sublist_id", sublistId);
				Integer taskRow = firstInt(row, DBTableID.SlayerTaskSublist.ID,
						DBTableID.SlayerTaskSublist.COL_TASK);
				out.put("task_id", taskRow == null ? null
						: firstInt(taskRow, DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID));
				out.put("task_name", taskRow == null ? null
						: firstString(taskRow, DBTableID.SlayerTask.ID,
								DBTableID.SlayerTask.COL_NAME_UPPERCASE));
				rows.add(out);
			}
		}
		return rows;
	}

	private List<Map<String, Object>> dumpUnlocks() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int bit = 0; bit < MAX_UNLOCK_BIT; bit++) {
			for (int row : client.getDBRowsByValue(
					DBTableID.SlayerUnlock.ID, DBTableID.SlayerUnlock.COL_BIT, 0, bit)) {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("bit", bit);
				out.put("cost", firstInt(row, DBTableID.SlayerUnlock.ID,
						DBTableID.SlayerUnlock.COL_COST));
				out.put("name", firstString(row, DBTableID.SlayerUnlock.ID,
						DBTableID.SlayerUnlock.COL_NAME));
				out.put("description", firstString(row, DBTableID.SlayerUnlock.ID,
						DBTableID.SlayerUnlock.COL_DESCRIPTION));
				Integer refundable = firstInt(row, DBTableID.SlayerUnlock.ID,
						DBTableID.SlayerUnlock.COL_REFUNDABLE);
				out.put("refundable", refundable != null && refundable != 0);
				Integer taskRow = firstInt(row, DBTableID.SlayerUnlock.ID,
						DBTableID.SlayerUnlock.COL_RELATED_TASK);
				out.put("task_id", taskRow == null ? null
						: firstInt(taskRow, DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID));
				rows.add(out);
			}
		}
		return rows;
	}

	private Integer firstInt(int row, int table, int column) {
		try {
			Object[] values = client.getDBTableField(row, column, 0);
			if (values != null && values.length > 0 && values[0] instanceof Integer) {
				return (Integer) values[0];
			}
		} catch (RuntimeException e) {
			log.trace("DB field read failed: table={} row={} col={}", table, row, column);
		}
		return null;
	}

	private String firstString(int row, int table, int column) {
		try {
			Object[] values = client.getDBTableField(row, column, 0);
			if (values != null && values.length > 0 && values[0] instanceof String) {
				return (String) values[0];
			}
		} catch (RuntimeException e) {
			log.trace("DB field read failed: table={} row={} col={}", table, row, column);
		}
		return null;
	}

	private static String sha256(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}
}
