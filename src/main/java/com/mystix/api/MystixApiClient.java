package com.mystix.api;

import com.google.gson.Gson;
import com.mystix.MystixConfig;
import com.mystix.SyncGuard;
import com.mystix.model.BankSyncPayload;
import com.mystix.model.LoadoutSyncPayload;
import com.mystix.model.PlayerSkillsSyncPayload;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class MystixApiClient
{
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final String API_BASE_URL = "https://api.mystix.app";
	private static final String TIMERS_ENDPOINT = "/api/runelite/timers/";
	private static final String SKILLS_ENDPOINT = "/api/runelite/skills/";
	private static final String BANK_ENDPOINT = "/api/runelite/bank/";
	private static final String LOADOUT_ENDPOINT = "/api/runelite/loadouts/";

	private static final int HTTP_OK_MIN = 200;
	private static final int HTTP_OK_MAX = 300;

	private final MystixConfig config;
	private final Gson gson;
	private final HttpClient httpClient;

	@Inject
	public MystixApiClient(MystixConfig config, Gson gson)
	{
		this.config = config;
		this.gson = gson;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(REQUEST_TIMEOUT)
			.build();
	}

	public void sendTimersSync(List<TimerSyncItem> timers)
	{
		String json = TimersSyncPayload.toJson(timers);
		postAsync(TIMERS_ENDPOINT, json, "timers",
			response -> log.info("Mystix timers sync successful: {} timers", timers.size()));
	}

	public void sendPlayerSkillsSync(PlayerSkillsSyncPayload payload)
	{
		postAsync(SKILLS_ENDPOINT, payload.toJson(gson), "skills",
			response -> log.info("Mystix player skills sync successful for player: {}", payload.getPlayer()));
	}

	public void sendLoadoutSync(LoadoutSyncPayload payload)
	{
		postAsync(LOADOUT_ENDPOINT, payload.toJson(gson), "loadout",
			response -> log.info("Mystix loadout sync successful for player: {}", payload.getPlayerUsername()));
	}

	public void sendBankSync(BankSyncPayload payload)
	{
		postAsync(BANK_ENDPOINT, payload.toJson(gson), "bank",
			response -> log.info("Mystix bank sync successful: {} items for player: {}",
				payload.getItems().size(), payload.getPlayerUsername()));
	}

	/**
	 * Posts a JSON payload to the given API endpoint asynchronously. Validates the app key,
	 * builds the request with auth headers, and delegates success/error handling to callbacks.
	 */
	private void postAsync(String endpoint, String json, String syncType,
		Consumer<HttpResponse<String>> onSuccess)
	{
		String appKey = config.mystixAppKey();
		if (!SyncGuard.hasAppKey(config))
		{
			log.debug("Skipping {} sync: no Mystix App Key configured", syncType);
			return;
		}

		String url = API_BASE_URL + endpoint;

		try
		{
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.header("X-RuneLite-Key", appKey.trim())
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build();

			httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response ->
				{
					if (response.statusCode() >= HTTP_OK_MIN && response.statusCode() < HTTP_OK_MAX)
					{
						onSuccess.accept(response);
					}
					else
					{
						log.warn("Mystix API returned {} for {} sync", response.statusCode(), syncType);
					}
				})
				.exceptionally(ex ->
				{
					log.warn("Failed to send {} sync to Mystix API: {}", syncType, ex.getMessage());
					return null;
				});
		}
		catch (Exception e)
		{
			log.warn("Failed to send {} sync: {}", syncType, e.getMessage());
		}
	}
}
