package com.mystix;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Red translucent banner at the top of the game window while Mystix cannot
 * sync: the App Key was rejected, or the server has been unreachable for a
 * while (see {@link SyncHealth}). Gated by {@link MystixConfig#showSyncWarning()}.
 */
public class SyncWarningOverlay extends OverlayPanel {
	static final Color BACKGROUND = new Color(180, 24, 24, 145);
	private static final int WIDTH = 260;

	static final String KEY_REJECTED_TITLE = "Mystix App Key not accepted";
	static final String KEY_REJECTED_TEXT =
			"Nothing is syncing to Mystix. Copy your App Key from Profile in the Mystix app"
					+ " and paste it into the Mystix plugin settings.";
	static final String UNREACHABLE_TITLE = "Can't connect to Mystix";
	static final String UNREACHABLE_TEXT =
			"Your game data is not reaching Mystix right now. Check your internet connection."
					+ " This clears once a sync gets through.";

	private final MystixConfig config;
	private final SyncHealth syncHealth;

	@Inject
	public SyncWarningOverlay(MystixConfig config, SyncHealth syncHealth) {
		this.config = config;
		this.syncHealth = syncHealth;
		setPosition(OverlayPosition.TOP_CENTER);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		if (!config.showSyncWarning() || !SyncGuard.hasAppKey(config)) {
			return null;
		}
		String title;
		String text;
		switch (syncHealth.status()) {
			case KEY_REJECTED:
				title = KEY_REJECTED_TITLE;
				text = KEY_REJECTED_TEXT;
				break;
			case UNREACHABLE:
				title = UNREACHABLE_TITLE;
				text = UNREACHABLE_TEXT;
				break;
			default:
				return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setBackgroundColor(BACKGROUND);
		panelComponent.setPreferredSize(new Dimension(WIDTH, 0));
		panelComponent.getChildren().add(TitleComponent.builder().text(title).color(Color.WHITE).build());
		panelComponent.getChildren().add(LineComponent.builder().left(text).leftColor(Color.WHITE).build());
		return super.render(graphics);
	}
}
