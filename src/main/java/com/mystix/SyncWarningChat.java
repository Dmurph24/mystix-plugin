package com.mystix;

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
 */
@Singleton
public class SyncWarningChat {
	static final String KEY_REJECTED_MESSAGE =
			"Mystix: App Key not accepted, nothing is syncing. Paste your key from the Mystix app.";
	static final String UNREACHABLE_MESSAGE = "Mystix: can't connect, your data isn't syncing right now.";
	static final String RECOVERED_MESSAGE = "Mystix: syncing again.";

	private final MystixConfig config;
	private final SyncHealth syncHealth;
	private final ChatMessageManager chatMessageManager;

	private SyncHealth.Status announced = SyncHealth.Status.OK;

	@Inject
	public SyncWarningChat(MystixConfig config, SyncHealth syncHealth, ChatMessageManager chatMessageManager) {
		this.config = config;
		this.syncHealth = syncHealth;
		this.chatMessageManager = chatMessageManager;
	}

	/**
	 * The chat line for a change from the last announced status to
	 * {@code current}, or null when there is nothing new to say.
	 */
	static String messageFor(SyncHealth.Status announced, SyncHealth.Status current) {
		if (current == announced) {
			return null;
		}
		switch (current) {
			case KEY_REJECTED:
				return KEY_REJECTED_MESSAGE;
			case UNREACHABLE:
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
		String message = messageFor(announced, current);
		announced = current;
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
			// Remind again after the next login if it is still broken.
			announced = SyncHealth.Status.OK;
		}
	}

	public void reset() {
		announced = SyncHealth.Status.OK;
	}
}
