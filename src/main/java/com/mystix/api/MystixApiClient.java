package com.mystix.api;

import com.google.gson.Gson;
import com.mystix.MystixConfig;
import com.mystix.SyncGuard;
import com.mystix.model.BankSyncPayload;
import com.mystix.model.LoadoutSyncPayload;
import com.mystix.model.LootDropPayload;
import com.mystix.model.LootSyncPayload;
import com.mystix.model.PlayerSkillsSyncPayload;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Singleton
public class MystixApiClient
{
	private static final long REQUEST_TIMEOUT_SECONDS = 10;
	private static final long LARGE_REQUEST_TIMEOUT_SECONDS = 60;
	private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
	private static final String API_BASE_URL = "https://api.mystix.app";
	private static final String TIMERS_ENDPOINT = "/api/runelite/timers/";
	private static final String SKILLS_ENDPOINT = "/api/runelite/skills/";
	private static final String BANK_ENDPOINT = "/api/runelite/bank/";
	private static final String LOADOUT_ENDPOINT = "/api/runelite/loadouts/";
	private static final String LOOT_ENDPOINT = "/api/runelite/loot/";
	private static final String LOOT_DROP_ENDPOINT = "/api/runelite/loot/drop/";

	private static final int HTTP_OK_MIN = 200;
	private static final int HTTP_OK_MAX = 300;

	private final MystixConfig config;
	private final Gson gson;
	private final OkHttpClient okHttpClient;
	private final OkHttpClient largeRequestClient;

	@Inject
	public MystixApiClient(MystixConfig config, Gson gson, OkHttpClient okHttpClient)
	{
		this.config = config;
		this.gson = gson;
		this.okHttpClient = okHttpClient.newBuilder()
			.callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build();
		this.largeRequestClient = okHttpClient.newBuilder()
			.callTimeout(LARGE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.readTimeout(LARGE_REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			.build();
	}

	public void sendTimersSync(List<TimerSyncItem> timers)
	{
		String json = TimersSyncPayload.toJson(timers);
		postAsync(TIMERS_ENDPOINT, json, "timers", false,
			() -> log.info("Mystix timers sync successful: {} timers", timers.size()));
	}

	public void sendPlayerSkillsSync(PlayerSkillsSyncPayload payload)
	{
		postAsync(SKILLS_ENDPOINT, payload.toJson(gson), "skills", false,
			() -> log.info("Mystix player skills sync successful for player: {}", payload.getPlayer()));
	}

	public void sendLoadoutSync(LoadoutSyncPayload payload)
	{
		postAsync(LOADOUT_ENDPOINT, payload.toJson(gson), "loadout", false,
			() -> log.info("Mystix loadout sync successful for player: {}", payload.getPlayerUsername()));
	}

	public void sendBankSync(BankSyncPayload payload)
	{
		postAsync(BANK_ENDPOINT, payload.toJson(gson), "bank", false,
			() -> log.info("Mystix bank sync successful: {} items for player: {}",
				payload.getTotalItemCount(), payload.getPlayerUsername()));
	}

	public void sendLootSync(LootSyncPayload payload)
	{
		postAsync(LOOT_ENDPOINT, payload.toJson(gson), "loot", true,
			() -> log.info("Mystix loot sync successful: {} records for player: {}",
				payload.getLootRecords().size(), payload.getPlayerUsername()));
	}

	public void sendLootDrops(List<LootDropPayload> drops)
	{
		if (drops.isEmpty())
		{
			return;
		}

		// Build batch payload: { player_username, source_client, drops: [{npc_id, npc_name, kill_count, items}, ...] }
		String playerUsername = drops.get(0).getPlayerUsername();
		String sourceClient = drops.get(0).getSourceClient();
		List<Map<String, Object>> dropList = new ArrayList<>();
		for (LootDropPayload drop : drops)
		{
			Map<String, Object> dropMap = new LinkedHashMap<>();
			dropMap.put("npc_id", drop.getNpcId());
			dropMap.put("npc_name", drop.getNpcName());
			dropMap.put("kill_count", drop.getKillCount());
			dropMap.put("dropped_at", drop.getDroppedAt());
			dropMap.put("items", drop.getItems());
			dropList.add(dropMap);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("player_username", playerUsername);
		payload.put("source_client", sourceClient);
		payload.put("drops", dropList);

		String json = gson.toJson(payload);
		postAsync(LOOT_DROP_ENDPOINT, json, "loot-drops", true,
			() -> log.info("Mystix loot drops batch recorded: {} drops for player: {}",
				drops.size(), playerUsername));
	}

	private void postAsync(String endpoint, String json, String syncType, boolean largeRequest,
		Runnable onSuccess)
	{
		if (!SyncGuard.hasAppKey(config))
		{
			log.debug("Skipping {} sync: no Mystix App Key configured", syncType);
			return;
		}

		String appKey = config.mystixAppKey();
		String url = API_BASE_URL + endpoint;

		Request request = new Request.Builder()
			.url(url)
			.header("Content-Type", "application/json")
			.header("X-RuneLite-Key", appKey.trim())
			.post(RequestBody.create(JSON_MEDIA_TYPE, json))
			.build();

		OkHttpClient client = largeRequest ? largeRequestClient : okHttpClient;
		client.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to send {} sync to Mystix API: {}", syncType, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.code() >= HTTP_OK_MIN && response.code() < HTTP_OK_MAX)
					{
						onSuccess.run();
					}
					else
					{
						log.warn("Mystix API returned {} for {} sync", response.code(), syncType);
					}
				}
				finally
				{
					response.close();
				}
			}
		});
	}
}
