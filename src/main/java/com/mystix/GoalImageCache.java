package com.mystix;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * In-memory cache of goal artwork the server links to (wiki images for bosses,
 * combat tasks and the like), scaled to overlay icon size. Images are fetched
 * once per URL on the background executor; until one arrives {@link #get}
 * returns null and the overlay simply draws no icon. Failed URLs are not
 * retried for the rest of the session.
 */
@Slf4j
@Singleton
public class GoalImageCache {
	/** Icons are scaled to fit this square, matching RuneLite's item sprites. */
	static final int ICON_SIZE = 32;
	private static final int FETCH_TIMEOUT_SECONDS = 10;

	private final OkHttpClient httpClient;
	private final ScheduledExecutorService executorService;

	private final Map<String, BufferedImage> images = new ConcurrentHashMap<>();
	private final Set<String> pending = ConcurrentHashMap.newKeySet();
	private final Set<String> failed = ConcurrentHashMap.newKeySet();

	@Inject
	public GoalImageCache(OkHttpClient okHttpClient, ScheduledExecutorService executorService) {
		this.httpClient = okHttpClient.newBuilder()
				.callTimeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
				.build();
		this.executorService = executorService;
	}

	/**
	 * The scaled image for a URL if it has been loaded; otherwise starts loading
	 * it (once) and returns null. Safe to call every render frame.
	 */
	public BufferedImage get(String url) {
		if (url == null || !url.startsWith("https://")) {
			return null;
		}
		BufferedImage cached = images.get(url);
		if (cached != null) {
			return cached;
		}
		if (!failed.contains(url) && pending.add(url)) {
			executorService.execute(() -> fetch(url));
		}
		return null;
	}

	public void clear() {
		images.clear();
		pending.clear();
		failed.clear();
	}

	private void fetch(String url) {
		try {
			Request request = new Request.Builder().url(url).get().build();
			try (Response response = httpClient.newCall(request).execute()) {
				if (!response.isSuccessful() || response.body() == null) {
					log.debug("Goal image fetch failed ({}): {}", response.code(), url);
					failed.add(url);
					return;
				}
				try (InputStream in = response.body().byteStream()) {
					BufferedImage image = ImageIO.read(in);
					if (image == null) {
						log.debug("Goal image not decodable: {}", url);
						failed.add(url);
						return;
					}
					images.put(url, fit(image));
				}
			}
		} catch (IOException | RuntimeException e) {
			log.debug("Goal image fetch error for {}: {}", url, e.toString());
			failed.add(url);
		} finally {
			pending.remove(url);
		}
	}

	/** Scales the image down to fit the icon square, keeping its aspect ratio. */
	static BufferedImage fit(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		if (w <= ICON_SIZE && h <= ICON_SIZE) {
			return image;
		}
		double scale = Math.min((double) ICON_SIZE / w, (double) ICON_SIZE / h);
		int nw = Math.max(1, (int) Math.round(w * scale));
		int nh = Math.max(1, (int) Math.round(h * scale));
		return ImageUtil.resizeImage(image, nw, nh);
	}
}
