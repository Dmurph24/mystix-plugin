package com.mystix;

import com.google.gson.Gson;
import com.mystix.api.MystixApiClient;
import com.mystix.model.SlayerRewardsPayload;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Scrapes the Slayer Rewards task list interface when the player opens it and
 * syncs the rows to the Mystix API.
 *
 * <p>The game renders, per task: the name with its amount range, a status icon
 * (available / cannot assign / blocked / current task), and the assignment
 * percentage the game itself computed for THIS player's blocks, unlocks and
 * levels. That percentage is data no varp exposes, and it is the calibration
 * source for Mystix's task-probability engine, so this is worth capturing
 * every time the interface opens. Opportunistic by nature: it only fires when
 * the player visits the rewards screen.
 *
 * <p>Each row is a run of four consecutive children in the DRAWABLE layer:
 * name text (type 4), spacer (type 3), status sprite (type 5), percent text
 * (type 4). Status is read from the sprite id, never from colours.
 */
@Slf4j
@Singleton
public class SlayerRewardsMonitor {
	/** "Aberrant Spectres (130-200)" -> name, min, max. */
	private static final Pattern NAME_WITH_RANGE =
			Pattern.compile("^(.*?)\\s*\\((\\d+)\\s*-\\s*(\\d+)\\)\\s*$");
	private static final Pattern PERCENT = Pattern.compile("^([\\d.]+)%$");

	private static final int TYPE_TEXT = 4;
	private static final int TYPE_SPACER = 3;
	private static final int TYPE_SPRITE = 5;

	private final Client client;
	private final ClientThread clientThread;
	private final MystixConfig config;
	private final MystixApiClient apiClient;
	private final Gson gson;

	/** Minimum interval between scrape posts even when content changed. */
	private static final long MIN_SCRAPE_INTERVAL_MS = 10_000L;

	/**
	 * Dedupe key of the last sent scrape: rows + master only. The payload's
	 * captured_at is stamped per build, so comparing full payload JSON would
	 * never match and every tab toggle in the rewards UI would post a full
	 * snapshot.
	 */
	private String lastContentKey;
	private long lastScrapeSentMs;

	@Inject
	public SlayerRewardsMonitor(
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
		lastContentKey = null;
		lastScrapeSentMs = 0;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (event.getGroupId() != InterfaceID.SLAYER_REWARDS_TASK_LIST) {
			return;
		}
		// Children populate as the interface finishes loading; read next cycle.
		clientThread.invokeLater(this::scrapeAndSync);
	}

	/** Reads the task list rows and syncs them. Client thread only. */
	private void scrapeAndSync() {
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

		Widget drawable = client.getWidget(InterfaceID.SlayerRewardsTaskList.DRAWABLE);
		if (drawable == null) {
			return;
		}
		Widget[] children = drawable.getDynamicChildren();
		if (children == null || children.length < 4) {
			return;
		}

		List<SlayerRewardsPayload.Row> rows = new ArrayList<>();
		for (int i = 0; i + 3 < children.length; i++) {
			if (!isTaskRow(children, i)) {
				continue;
			}
			SlayerRewardsPayload.Row row = parseRow(
					children[i].getText(),
					children[i + 2].getSpriteId(),
					children[i + 3].getText());
			if (row != null) {
				rows.add(row);
				i += 3;
			}
		}
		if (rows.isEmpty()) {
			log.debug("Slayer rewards scrape found no task rows");
			return;
		}

		int masterId = client.getVarbitValue(VarbitID.SLAYER_MASTER);
		String contentKey = masterId + "|" + gson.toJson(rows);
		if (contentKey.equals(lastContentKey)) {
			log.debug("Slayer rewards scrape unchanged, skipping sync");
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastScrapeSentMs < MIN_SCRAPE_INTERVAL_MS) {
			log.debug("Slayer rewards scrape throttled");
			return;
		}
		SlayerRewardsPayload payload = new SlayerRewardsPayload(
				playerUsername,
				masterId == 0 ? null : masterId,
				rows,
				Instant.now().toString());
		lastContentKey = contentKey;
		lastScrapeSentMs = nowMs;
		log.debug("Syncing slayer rewards scrape: {} rows for player: {}",
				rows.size(), playerUsername);
		apiClient.sendSlayerRewards(payload);
	}

	private static boolean isTaskRow(Widget[] children, int i) {
		return children[i].getType() == TYPE_TEXT && children[i].getText() != null
				&& children[i + 1].getType() == TYPE_SPACER
				&& children[i + 2].getType() == TYPE_SPRITE
				&& children[i + 3].getType() == TYPE_TEXT && children[i + 3].getText() != null;
	}

	/** Parses one widget row; package-private for tests. Returns null on mismatch. */
	static SlayerRewardsPayload.Row parseRow(String nameText, int spriteId, String percentText) {
		if (nameText == null || percentText == null) {
			return null;
		}
		Matcher percentMatcher = PERCENT.matcher(Text.removeTags(percentText).trim());
		if (!percentMatcher.matches()) {
			return null;
		}
		double percent;
		try {
			percent = Double.parseDouble(percentMatcher.group(1));
		} catch (NumberFormatException e) {
			return null;
		}

		String name = Text.removeTags(nameText).trim();
		Integer amountMin = null;
		Integer amountMax = null;
		Matcher nameMatcher = NAME_WITH_RANGE.matcher(name);
		if (nameMatcher.matches()) {
			name = nameMatcher.group(1).trim();
			amountMin = Integer.valueOf(nameMatcher.group(2));
			amountMax = Integer.valueOf(nameMatcher.group(3));
		}
		if (name.isEmpty()) {
			return null;
		}
		return new SlayerRewardsPayload.Row(name, amountMin, amountMax, percent, spriteId);
	}
}
