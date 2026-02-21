package com.mystix.api;

import com.mystix.MystixConfig;
import com.mystix.model.TimerUpdatePayload;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends timer update payloads to the Mystix API with X-RuneLite-Key header authorization.
 */
@Slf4j
@Singleton
public class MystixApiClient
{
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final String API_BASE_URL = "https://api.mystix.app";
	private static final String ENDPOINT_PATH = "/api/v1/timers";

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
	 * Sends a timer update to the Mystix API. Runs asynchronously to avoid blocking.
	 * Logs errors without affecting RuneLite stability.
	 */
	public void sendTimerUpdate(TimerUpdatePayload payload)
	{
		String appKey = config.mystixAppKey();
		if (appKey == null || appKey.isBlank())
		{
			log.debug("Skipping API send: no Mystix App Key configured");
			return;
		}

		String url = API_BASE_URL.replaceAll("/$", "") + ENDPOINT_PATH;
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
						log.debug("Timer update sent successfully: {}", payload.getEntity());
					}
					else
					{
						log.warn("Mystix API returned {} for {}: {}", response.statusCode(), payload.getEntity(), response.body());
					}
				})
				.exceptionally(ex ->
				{
					log.warn("Failed to send timer update to Mystix API: {}", ex.getMessage());
					return null;
				});
		}
		catch (Exception e)
		{
			log.warn("Failed to send timer update: {}", e.getMessage());
		}
	}
}
