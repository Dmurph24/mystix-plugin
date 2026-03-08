package com.mystix.wom;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Text;

/**
 * Sends player update requests to the Wise Old Man API.
 * Triggered on login and logout so WOM stays up to date.
 */
@Slf4j
@Singleton
public class WomSyncService
{
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final String WOM_API_BASE = "https://api.wiseoldman.net/v2";

	private final HttpClient httpClient;

	@Inject
	public WomSyncService()
	{
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(REQUEST_TIMEOUT)
			.build();
	}

	/**
	 * Sends a POST to {@code /v2/players/{username}} so WOM fetches the
	 * latest hiscores for the player. Runs asynchronously.
	 */
	public void updatePlayer(String username)
	{
		if (username == null || username.isBlank())
		{
			return;
		}

		String cleanName = Text.toJagexName(username);
		String url = WOM_API_BASE + "/players/" + cleanName;

		try
		{
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.timeout(REQUEST_TIMEOUT)
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build();

			httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenAccept(response ->
				{
					if (response.statusCode() >= 200 && response.statusCode() < 300)
					{
						log.info("Wise Old Man update successful for {}", cleanName);
					}
					else
					{
						log.warn("Wise Old Man API returned {} for {}", response.statusCode(), cleanName);
					}
				})
				.exceptionally(ex ->
				{
					log.warn("Failed to send Wise Old Man update for {}: {}", cleanName, ex.getMessage());
					return null;
				});
		}
		catch (Exception e)
		{
			log.warn("Failed to send Wise Old Man update for {}: {}", cleanName, e.getMessage());
		}
	}
}
