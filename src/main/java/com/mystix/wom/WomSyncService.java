package com.mystix.wom;

import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends player update requests to the Wise Old Man API.
 * Triggered on login and logout so WOM stays up to date.
 */
@Slf4j
@Singleton
public class WomSyncService
{
	private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
	private static final String WOM_API_BASE = "https://api.wiseoldman.net/v2";

	private final OkHttpClient okHttpClient;

	@Inject
	public WomSyncService(OkHttpClient okHttpClient)
	{
		this.okHttpClient = okHttpClient;
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

		Request request = new Request.Builder()
			.url(url)
			.header("Content-Type", "application/json")
			.post(RequestBody.create(JSON_MEDIA_TYPE, "{}"))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to send Wise Old Man update for {}: {}", cleanName, e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (response.code() >= 200 && response.code() < 300)
					{
						log.info("Wise Old Man update successful for {}", cleanName);
					}
					else
					{
						log.warn("Wise Old Man API returned {} for {}", response.code(), cleanName);
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
