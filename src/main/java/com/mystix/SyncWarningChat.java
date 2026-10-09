package com.mystix;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.eventbus.Subscribe;

/**
 * Posts the sync warning to the chatbox when {@link MystixConfig#syncWarning()}
 * is {@link SyncWarningStyle#CHAT}: once when syncing breaks, again on each
 * login while it stays broken, and a short note once it works again.
 *
 * <p>A flaky connection can drop out and come back every few minutes, so an
 * outage is announced at most once per {@link #OUTAGE_COOLDOWN}. An outage
 * held back by the cooldown is not announced at all (so its end says nothing
 * either), unless it is still going when the cooldown runs out.
 */
@Singleton
public class SyncWarningChat {
	static final String KEY_REJECTED_MESSAGE =
			"Mystix: App Key not accepted, nothing is syncing. Paste a new key from the app.";
	static final String UNREACHABLE_MESSAGE = "Mystix: can't connect, your data isn't syncing right now.";
	static final String RECOVERED_MESSAGE = "Mystix: syncing again.";
	static final Duration OUTAGE_COOLDOWN = Duration.ofMinutes(10);

	private final MystixConfig config;
	private final SyncHealth syncHealth;
	private final ChatMessageManager chatMessageManager;

	private final Clock clock;

	private SyncHealth.Status announced = SyncHealth.Status.OK;
	private Instant lastOutageWarningAt;

	@Inject
	public SyncWarningChat(MystixConfig config, SyncHealth syncHealth, ChatMessageManager chatMessageManager) {
		this(config, syncHealth, chatMessageManager, Clock.systemUTC());
	}

	SyncWarningChat(MystixConfig config, SyncHealth syncHealth, ChatMessageManager chatMessageManager,
			Clock clock) {
		this.config = config;
		this.syncHealth = syncHealth;
		this.chatMessageManager = chatMessageManager;
		this.clock = clock;
	}

	/**
	 * The chat line to post now that the status is {@code current}, or null
	 * when there is nothing new to say. Records what was announced.
	 */
	String nextMessage(SyncHealth.Status current) {
		if (current == announced) {
			return null;
		}
		Instant now = clock.instant();
		if (current == SyncHealth.Status.UNREACHABLE
				&& lastOutageWarningAt != null
				&& now.isBefore(lastOutageWarningAt.plus(OUTAGE_COOLDOWN))) {
			return null;
		}
		announced = current;
		switch (current) {
			case KEY_REJECTED:
				return KEY_REJECTED_MESSAGE;
			case UNREACHABLE:
				lastOutageWarningAt = now;
				return UNREACHABLE_MESSAGE;
			default:
				return RECOVERED_MESSAGE;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		if (config.syncWarning() != SyncWarningStyle.CHAT || !SyncGuard.hasAppKey(config)) {
			announced = SyncHealth.Status.OK;
			return;
		}
		SyncHealth.Status current = syncHealth.status();
		String message = nextMessage(current);
		if (message == null) {
			return;
		}
		ChatMessageBuilder builder = new ChatMessageBuilder();
		builder.append(current == SyncHealth.Status.OK ? ChatColorType.NORMAL : ChatColorType.HIGHLIGHT)
				.append(message);
		chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(builder.build())
				.build());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGIN_SCREEN) {
			// Remind again after the next login if it is still broken (an
			// outage still waits out its cooldown).
			announced = SyncHealth.Status.OK;
		}
	}

	public void reset() {
		announced = SyncHealth.Status.OK;
	}
}
