package com.mystix.api;

import com.mystix.MystixConfig;
import com.mystix.model.BankSyncPayload;
import com.mystix.model.PlayerSkillsSyncPayload;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends bulk timer sync to the Mystix API with X-RuneLite-Key header authorization.
 */
@Slf4j
@Singleton
public class MystixApiClient
{
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final String API_BASE_URL = "https://api.mystix.app";
	private static final String TIMERS_ENDPOINT = "/api/runelite/timers/";
	private static final String SKILLS_ENDPOINT = "/api/runelite/skills/";
	private static final String BANK_ENDPOINT = "/api/runelite/bank/";

	private final MystixConfig config;
	private final HttpClient httpClient;

	@Inject
	public MystixApiClient(MystixConfig config)
	{
		this.config = config;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(REQUEST_TIMEOUT)
			.build();
	}

	/**
	 * Sends bulk timer sync to the Mystix API. Runs asynchronously to avoid blocking.
	 * Logs errors without affecting RuneLite stability.
	 */
	public void sendTimersSync(List<TimerSyncItem> timers)
	{
		String appKey = config.mystixAppKey();
		if (appKey == null || appKey.isBlank())
		{
			log.debug("Skipping API send: no Mystix App Key configured");
			return;
		}

		String url = API_BASE_URL.replaceAll("/$", "") + TIMERS_ENDPOINT;
		String json = TimersSyncPayload.toJson(timers);
		log.debug("Timers sync payload: {}", json);

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
					if (response.statusCode() >= 200 && response.statusCode() < 300)
					{
						log.info("Mystix timers sync successful: {} timers", timers.size());
					}
					else
					{
						log.warn("Mystix API returned {}: {}", response.statusCode(), response.body());
					}
				})
				.exceptionally(ex ->
				{
					log.warn("Failed to send timers sync to Mystix API: {}", ex.getMessage());
					return null;
				});
		}
		catch (Exception e)
		{
			log.warn("Failed to send timers sync: {}", e.getMessage());
		}
	}

	/**
	 * Sends player skills sync to the Mystix API. Runs asynchronously to avoid blocking.
	 * Logs errors without affecting RuneLite stability.
	 */
	public void sendPlayerSkillsSync(PlayerSkillsSyncPayload payload)
	{
		String appKey = config.mystixAppKey();
		if (appKey == null || appKey.isBlank())
		{
			log.debug("Skipping player skills sync: no Mystix App Key configured");
			return;
		}

		String url = API_BASE_URL.replaceAll("/$", "") + SKILLS_ENDPOINT;
		String json = payload.toJson();

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
					if (response.statusCode() >= 200 && response.statusCode() < 300)
					{
						log.info("Mystix player skills sync successful for player: {}", payload.getPlayer());
					}
					else
					{
						log.warn("Mystix API returned {} for skills sync: {}", response.statusCode(), response.body());
					}
				})
				.exceptionally(ex ->
				{
					log.warn("Failed to send player skills sync to Mystix API: {}", ex.getMessage());
					return null;
				});
		}
		catch (Exception e)
		{
			log.warn("Failed to send player skills sync: {}", e.getMessage());
		}
	}

	/**
	 * Sends bank sync to the Mystix API. Runs asynchronously to avoid blocking.
	 * Logs errors without affecting RuneLite stability.
	 */
	public void sendBankSync(BankSyncPayload payload)
	{
		String appKey = config.mystixAppKey();
		if (appKey == null || appKey.isBlank())
		{
			log.debug("Skipping bank sync: no Mystix App Key configured");
			return;
		}

		String url = API_BASE_URL.replaceAll("/$", "") + BANK_ENDPOINT;
		String json = payload.toJson();

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
					if (response.statusCode() >= 200 && response.statusCode() < 300)
					{
						log.info("Mystix bank sync successful: {} items for player: {}",
							payload.getItems().size(), payload.getPlayerUsername());
					}
					else
					{
						log.warn("Mystix API returned {} for bank sync: {}", response.statusCode(), response.body());
					}
				})
				.exceptionally(ex ->
				{
					log.warn("Failed to send bank sync to Mystix API: {}", ex.getMessage());
					return null;
				});
		}
		catch (Exception e)
		{
			log.warn("Failed to send bank sync: {}", e.getMessage());
		}
	}
}
