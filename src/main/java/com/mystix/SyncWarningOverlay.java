package com.mystix;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Red translucent banner at the top of the game window while Mystix cannot
 * sync: the App Key was rejected, or the server has been unreachable for a
 * while (see {@link SyncHealth}). Shown when {@link MystixConfig#syncWarning()}
 * is {@link SyncWarningStyle#BANNER}.
 */
public class SyncWarningOverlay extends OverlayPanel {
	static final Color BACKGROUND = new Color(180, 24, 24, 145);
	private static final int WIDTH_PADDING = 14;

	static final String KEY_REJECTED_TITLE = "Mystix App Key not accepted";
	static final String KEY_REJECTED_TEXT = "Paste your key from the Mystix app.";
	static final String UNREACHABLE_TITLE = "Can't connect to Mystix";
	static final String UNREACHABLE_TEXT = "Your data isn't syncing right now.";

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
		if (config.syncWarning() != SyncWarningStyle.BANNER || !SyncGuard.hasAppKey(config)) {
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

		FontMetrics metrics = graphics.getFontMetrics();
		int width = Math.max(metrics.stringWidth(title), metrics.stringWidth(text)) + WIDTH_PADDING;
		panelComponent.getChildren().clear();
		panelComponent.setBackgroundColor(BACKGROUND);
		panelComponent.setPreferredSize(new Dimension(width, 0));
		panelComponent.getChildren().add(TitleComponent.builder().text(title).color(Color.WHITE).build());
		panelComponent.getChildren().add(LineComponent.builder().left(text).leftColor(Color.WHITE).build());
		return super.render(graphics);
	}
}
