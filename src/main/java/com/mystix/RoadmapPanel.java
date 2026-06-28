package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import com.mystix.model.RoadmapList;
import com.mystix.model.RoadmapSummary;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side-panel tab listing the player's roadmaps and their goals.
 *
 * <p>Lets the user pick a roadmap (players may have several), shows each goal's
 * name + progress, and offers a "Sync &amp; refresh" button that re-pushes the
 * plugin's login syncs, recomputes the selected roadmap on the backend, and
 * re-renders. All network callbacks are marshalled back to the EDT.
 */
@Slf4j
public class RoadmapPanel extends PluginPanel {
	/**
	 * Delay between firing the data syncs and asking the backend to recompute, so
	 * the freshly synced data has a chance to be ingested and committed first.
	 * Matches the ~10s wait the mobile app's refresh uses.
	 */
	private static final int RECOMPUTE_DELAY_SECONDS = 10;

	private static final Color COMPLETE_COLOR = new Color(0x4C, 0xAF, 0x50);

	/** Pixel width the goal-name HTML wraps at, so long names never truncate. */
	private static final int NAME_WRAP_WIDTH = 165;

	private final RoadmapManager roadmapManager;
	private final ScheduledExecutorService executor;
	private final Runnable forceSyncAll;

	private final JComboBox<RoadmapSummary> roadmapSelector = new JComboBox<>();
	private final JButton syncButton = new JButton("Sync & refresh");
	private final JButton reloadButton = new JButton("Reload roadmaps");
	private final JLabel statusLabel = new JLabel();
	private final JPanel goalsPanel = new JPanel();

	private boolean suppressSelectorEvents = false;

	public RoadmapPanel(
			RoadmapManager roadmapManager,
			ScheduledExecutorService executor,
			Runnable forceSyncAll) {
		super(false);
		this.roadmapManager = roadmapManager;
		this.executor = executor;
		this.forceSyncAll = forceSyncAll;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		add(buildHeader(), BorderLayout.NORTH);

		goalsPanel.setLayout(new BoxLayout(goalsPanel, BoxLayout.Y_AXIS));
		goalsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(goalsPanel, BorderLayout.CENTER);

		add(buildFooterNote(), BorderLayout.SOUTH);
	}

	private JPanel buildHeader() {
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JLabel title = new JLabel("Roadmaps");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(title);
		header.add(Box.createVerticalStrut(8));

		roadmapSelector.setAlignmentX(Component.LEFT_ALIGNMENT);
		roadmapSelector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		roadmapSelector.addActionListener(e -> {
			if (suppressSelectorEvents) {
				return;
			}
			RoadmapSummary selected = (RoadmapSummary) roadmapSelector.getSelectedItem();
			if (selected != null) {
				roadmapManager.setSelectedRoadmapId(selected.getCollectionId());
				loadSelectedRoadmap();
			}
		});
		header.add(roadmapSelector);
		header.add(Box.createVerticalStrut(8));

		syncButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		syncButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		syncButton.setToolTipText(
				"Re-send your latest data and recompute the selected roadmap.");
		syncButton.addActionListener(e -> onSyncAndRefresh());
		header.add(syncButton);
		header.add(Box.createVerticalStrut(4));

		reloadButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		reloadButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		reloadButton.addActionListener(e -> loadRoadmaps());
		header.add(reloadButton);
		header.add(Box.createVerticalStrut(8));

		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(statusLabel);

		return header;
	}

	private JPanel buildFooterNote() {
		JPanel notePanel = new JPanel(new BorderLayout());
		notePanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
		JLabel note = new JLabel(
				"<html><body style='width:170px'><i>Tip: logging out and back in helps "
				+ "push your latest progress. A completed goal can take a few minutes "
				+ "to update.</i></body></html>");
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		note.setFont(FontManager.getRunescapeSmallFont());
		notePanel.add(note, BorderLayout.CENTER);
		return notePanel;
	}

	@Override
	public void onActivate() {
		// Refresh whenever the panel is opened so it always shows current data.
		loadRoadmaps();
	}

	/** Loads the roadmap list and (re)populates the selector. Called on panel open. */
	public void loadRoadmaps() {
		if (!roadmapManager.hasAppKey()) {
			setStatus("Add your Mystix App Key in the plugin settings.");
			clearGoals();
			return;
		}
		setStatus("Loading roadmaps...");
		roadmapManager.fetchRoadmaps(new MystixApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				SwingUtilities.invokeLater(() -> populateSelector(result.getRoadmaps()));
			}

			@Override
			public void onError(String message) {
				SwingUtilities.invokeLater(() -> {
					setStatus(message);
					clearGoals();
				});
			}
		});
	}

	private void populateSelector(List<RoadmapSummary> roadmaps) {
		suppressSelectorEvents = true;
		DefaultComboBoxModel<RoadmapSummary> model = new DefaultComboBoxModel<>();
		for (RoadmapSummary summary : roadmaps) {
			model.addElement(summary);
		}
		roadmapSelector.setModel(model);
		suppressSelectorEvents = false;

		if (roadmaps.isEmpty()) {
			setStatus("No roadmaps yet. Create one in the Mystix app.");
			clearGoals();
			return;
		}

		// Restore the previously selected roadmap if it still exists.
		Integer selectedId = roadmapManager.getSelectedRoadmapId();
		int indexToSelect = 0;
		if (selectedId != null) {
			for (int i = 0; i < roadmaps.size(); i++) {
				if (roadmaps.get(i).getCollectionId() == selectedId) {
					indexToSelect = i;
					break;
				}
			}
		}
		suppressSelectorEvents = true;
		roadmapSelector.setSelectedIndex(indexToSelect);
		suppressSelectorEvents = false;

		RoadmapSummary selected = roadmaps.get(indexToSelect);
		roadmapManager.setSelectedRoadmapId(selected.getCollectionId());
		loadSelectedRoadmap();
	}

	private void loadSelectedRoadmap() {
		RoadmapSummary selected = (RoadmapSummary) roadmapSelector.getSelectedItem();
		if (selected == null) {
			return;
		}
		setStatus("Loading goals...");
		roadmapManager.fetchRoadmap(selected.getCollectionId(),
				new MystixApiClient.RoadmapCallback<Roadmap>() {
					@Override
					public void onSuccess(Roadmap result) {
						SwingUtilities.invokeLater(() -> {
							setStatus("");
							renderGoals(result);
						});
					}

					@Override
					public void onError(String message) {
						SwingUtilities.invokeLater(() -> {
							setStatus(message);
							clearGoals();
						});
					}
				});
	}

	private void onSyncAndRefresh() {
		RoadmapSummary selected = (RoadmapSummary) roadmapSelector.getSelectedItem();
		if (selected == null) {
			setStatus("Select a roadmap first.");
			return;
		}
		if (roadmapManager.getPlayerUsername() == null) {
			setStatus("Log in to OSRS to sync.");
			return;
		}

		syncButton.setEnabled(false);
		setStatus("Syncing your data...");

		// 1. Re-push all the login syncs (timers, skills, bank, loadout, loot).
		forceSyncAll.run();

		// 2. After the syncs have had a chance to land, recompute and re-render.
		executor.schedule(() -> {
			SwingUtilities.invokeLater(() -> setStatus("Recomputing roadmap..."));
			roadmapManager.recomputeRoadmap(selected.getCollectionId(),
					new MystixApiClient.RoadmapCallback<Roadmap>() {
						@Override
						public void onSuccess(Roadmap result) {
							SwingUtilities.invokeLater(() -> {
								setStatus("Up to date.");
								renderGoals(result);
								syncButton.setEnabled(true);
							});
						}

						@Override
						public void onError(String message) {
							SwingUtilities.invokeLater(() -> {
								setStatus(message);
								syncButton.setEnabled(true);
							});
						}
					});
		}, RECOMPUTE_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	private void renderGoals(Roadmap roadmap) {
		goalsPanel.removeAll();
		List<RoadmapGoal> goals = roadmap.getGoalsSorted();
		if (goals.isEmpty()) {
			JLabel empty = new JLabel("This roadmap has no goals yet.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			goalsPanel.add(empty);
		} else {
			for (RoadmapGoal goal : goals) {
				goalsPanel.add(buildGoalRow(goal));
				goalsPanel.add(Box.createVerticalStrut(4));
			}
		}
		goalsPanel.revalidate();
		goalsPanel.repaint();
	}

	private JPanel buildGoalRow(RoadmapGoal goal) {
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Width-constrained HTML so the full goal name wraps instead of truncating.
		JLabel name = new JLabel(wrapGoalName(goalName(goal)));
		name.setForeground(goal.isComplete() ? COMPLETE_COLOR : Color.WHITE);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(name);

		if (goal.isComplete()) {
			JLabel done = new JLabel("Completed");
			done.setForeground(COMPLETE_COLOR);
			done.setFont(FontManager.getRunescapeSmallFont());
			done.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(Box.createVerticalStrut(4));
			row.add(done);
		} else {
			JButton markComplete = new JButton("Mark complete");
			markComplete.setFont(FontManager.getRunescapeSmallFont());
			markComplete.setMargin(new Insets(2, 6, 2, 6));
			markComplete.setAlignmentX(Component.LEFT_ALIGNMENT);
			markComplete.addActionListener(e -> confirmMarkComplete(goal));
			row.add(Box.createVerticalStrut(6));
			row.add(markComplete);
		}

		// Lock the height only AFTER the children are added, otherwise BoxLayout
		// clamps the row to the empty-panel height and the content gets clipped.
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	/** Confirms, then asks the backend to mark a goal complete and re-renders. */
	private void confirmMarkComplete(RoadmapGoal goal) {
		RoadmapSummary selected = (RoadmapSummary) roadmapSelector.getSelectedItem();
		if (selected == null) {
			return;
		}
		int choice = JOptionPane.showConfirmDialog(
				this,
				"Mark \"" + goalName(goal) + "\" as complete?",
				"Mark goal complete",
				JOptionPane.YES_NO_OPTION);
		if (choice != JOptionPane.YES_OPTION) {
			return;
		}
		setStatus("Marking complete...");
		roadmapManager.completeGoal(selected.getCollectionId(), goal.getId(),
				new MystixApiClient.RoadmapCallback<Roadmap>() {
					@Override
					public void onSuccess(Roadmap result) {
						SwingUtilities.invokeLater(() -> {
							setStatus("");
							renderGoals(result);
						});
					}

					@Override
					public void onError(String message) {
						SwingUtilities.invokeLater(() -> setStatus(message));
					}
				});
	}

	private static String goalName(RoadmapGoal goal) {
		return goal.getName() == null ? "Goal" : goal.getName();
	}

	/** Wraps a goal name in width-constrained HTML so long names don't truncate. */
	private static String wrapGoalName(String name) {
		String escaped = name
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
		return "<html><body style='width:" + NAME_WRAP_WIDTH + "px'>" + escaped + "</body></html>";
	}

	private void clearGoals() {
		goalsPanel.removeAll();
		goalsPanel.revalidate();
		goalsPanel.repaint();
	}

	private void setStatus(String text) {
		statusLabel.setText(text == null ? "" : text);
	}
}
