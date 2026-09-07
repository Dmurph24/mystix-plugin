package com.mystix;

import com.mystix.api.MystixApiClient;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapGoal;
import com.mystix.model.RoadmapList;
import com.mystix.model.RoadmapSummary;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side-panel tab listing the player's roadmaps and their goals.
 *
 * <p>Lets the user pick a roadmap (players may have several), shows each goal's
 * name + progress, and offers a "Refresh" button that reloads the roadmaps from
 * the app. Goal progress itself updates live from game events and syncs in the
 * background. All network callbacks are marshalled back to the EDT.
 */
@Slf4j
public class RoadmapPanel extends PluginPanel {
	private static final Color COMPLETE_COLOR = new Color(0x4C, 0xAF, 0x50);
	private static final Color DELETE_COLOR = new Color(0xC0, 0x4A, 0x4A);

	/** Pixel width the goal-name HTML wraps at, so long names never truncate. */
	private static final int NAME_WRAP_WIDTH = 165;
	/** Left indent (px) added per prerequisite-depth level, mirroring the app. */
	private static final int INDENT_PER_DEPTH = 14;
	/** Vertical gap (px) between goal rows; the connector verticals bridge it. */
	private static final int ROW_GAP = 4;
	/** Where an elbow branches into a card (px from the card top, ~the name line). */
	private static final int CONNECTOR_Y_OFFSET = 14;
	private static final Color GUIDE_COLOR = new Color(0x5A, 0x5A, 0x5A);
	/** Chevrons for the collapsed / expanded "Completed" header. */
	private static final String COLLAPSED_GLYPH = "+";
	private static final String EXPANDED_GLYPH = "-";

	private final RoadmapManager roadmapManager;
	private final GoalProgressTracker goalProgressTracker;

	private final JComboBox<RoadmapSummary> roadmapSelector = new JComboBox<>();
	private final JButton reloadButton = new JButton("Refresh");
	private final JLabel statusLabel = new JLabel();
	private final GoalsTreePanel goalsPanel = new GoalsTreePanel();

	private boolean suppressSelectorEvents = false;

	public RoadmapPanel(RoadmapManager roadmapManager, GoalProgressTracker goalProgressTracker) {
		super(false);
		this.roadmapManager = roadmapManager;
		this.goalProgressTracker = goalProgressTracker;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		add(buildHeader(), BorderLayout.NORTH);

		goalsPanel.setLayout(new BoxLayout(goalsPanel, BoxLayout.Y_AXIS));
		goalsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Scroll the goals list on its own. Without this, a roadmap with many goals
		// makes the panel's preferred/minimum height grow to fit every row, which
		// forces the RuneLite window taller than the screen and stops it shrinking
		// back. The header and footer stay pinned; only this list scrolls.
		JScrollPane goalsScroll = new JScrollPane(
				goalsPanel,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		goalsScroll.setBorder(BorderFactory.createEmptyBorder());
		goalsScroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		goalsScroll.getVerticalScrollBar().setUnitIncrement(16);
		add(goalsScroll, BorderLayout.CENTER);

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

		reloadButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		reloadButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		reloadButton.setToolTipText("Reload your roadmaps and goals from the Mystix app.");
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
				"<html><body style='width:170px'><i>Progress updates as you play and "
				+ "syncs with the app in the background.</i></body></html>");
		note.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		note.setFont(FontManager.getRunescapeSmallFont());
		notePanel.add(note, BorderLayout.CENTER);
		return notePanel;
	}

	@Override
	public void onActivate() {
		// Refresh whenever the panel is opened so it always shows current data,
		// and re-render whenever a background refresh brings a newer roadmap.
		roadmapManager.setPanelActive(true);
		roadmapManager.setPanelListener(
				roadmap -> SwingUtilities.invokeLater(() -> {
					syncSelector(roadmap.getCollectionId());
					renderGoals(roadmap);
				}));
		loadRoadmaps();
	}

	@Override
	public void onDeactivate() {
		roadmapManager.setPanelListener(null);
		roadmapManager.setPanelActive(false);
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

	/** Points the dropdown at a roadmap the plugin switched to in the background. */
	private void syncSelector(int collectionId) {
		RoadmapSummary current = (RoadmapSummary) roadmapSelector.getSelectedItem();
		if (current != null && current.getCollectionId() == collectionId) {
			return;
		}
		for (int i = 0; i < roadmapSelector.getItemCount(); i++) {
			if (roadmapSelector.getItemAt(i).getCollectionId() == collectionId) {
				suppressSelectorEvents = true;
				roadmapSelector.setSelectedIndex(i);
				suppressSelectorEvents = false;
				return;
			}
		}
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


	private void renderGoals(Roadmap roadmap) {
		goalsPanel.removeAll();
		goalsPanel.setRowGuides(Collections.emptyList());
		List<RoadmapGoal> goals = roadmap.getGoalsSorted();
		if (goals.isEmpty()) {
			JLabel empty = new JLabel("This roadmap has no goals yet.");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			goalsPanel.add(empty);
		} else {
			// Finished goals leave the tree the moment they complete and collect
			// in a collapsible list below.
			GoalPartition split = GoalPartition.of(goals,
					goalProgressTracker::isComplete, goalProgressTracker::completedThisSession);
			if (split.getTree().isEmpty()) {
				JLabel allDone = new JLabel("All goals completed.");
				allDone.setForeground(COMPLETE_COLOR);
				allDone.setFont(FontManager.getRunescapeSmallFont());
				goalsPanel.add(allDone);
			} else {
				renderTree(split.getTree());
			}
			if (!split.getCompleted().isEmpty()) {
				goalsPanel.add(Box.createVerticalStrut(ROW_GAP));
				goalsPanel.add(buildCompletedSection(split.getCompleted()));
			}
		}
		goalsPanel.revalidate();
		goalsPanel.repaint();
	}

	/** Draws the dependency tree for the given goals (prerequisites first, indented by depth). */
	private void renderTree(List<RoadmapGoal> tree) {
		Map<Integer, RoadmapGoal> byId = new HashMap<>();
		for (RoadmapGoal g : tree) {
			byId.put(g.getId(), g);
		}
		// Order so each goal follows its prerequisites, then indent by depth
		// (the longest prerequisite chain), mirroring the app's tree layout.
		// Prerequisites missing from the tree (completed ones) are simply skipped.
		List<RoadmapGoal> ordered = new ArrayList<>();
		Set<Integer> visited = new HashSet<>();
		for (RoadmapGoal g : tree) {
			orderByPrereqs(g, byId, visited, ordered, new HashSet<>());
		}
		Map<Integer, Integer> depthMemo = new HashMap<>();
		int[] depths = new int[ordered.size()];
		for (int i = 0; i < ordered.size(); i++) {
			depths[i] = prereqDepth(ordered.get(i), byId, depthMemo, new HashSet<>());
		}
		Guide[][] guides = computeGuides(depths);
		List<RowGuide> rowGuides = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i++) {
			JComponent rowComp = indentRow(buildGoalRow(ordered.get(i), depths[i]), depths[i]);
			goalsPanel.add(rowComp);
			goalsPanel.add(Box.createVerticalStrut(ROW_GAP));
			rowGuides.add(new RowGuide(rowComp, guides[i]));
		}
		goalsPanel.setRowGuides(rowGuides);
	}

	/**
	 * "Completed (N)" header that toggles a flat, newest-first list of finished
	 * goals. The expanded state is remembered across sessions via the manager.
	 * Lives inside the scrolling goals panel so it scrolls with the tree.
	 */
	private JPanel buildCompletedSection(List<RoadmapGoal> completed) {
		JPanel list = new JPanel();
		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setOpaque(false);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (RoadmapGoal g : completed) {
			list.add(buildGoalRow(g, 0)); // flat: no indent, no connectors
			list.add(Box.createVerticalStrut(ROW_GAP));
		}
		boolean expanded = roadmapManager.isCompletedGoalsExpanded();
		list.setVisible(expanded);

		JLabel arrow = new JLabel(expanded ? EXPANDED_GLYPH : COLLAPSED_GLYPH);
		arrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JLabel title = new JLabel("Completed (" + completed.size() + ")");
		title.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		title.setFont(FontManager.getRunescapeBoldFont());

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
		header.setOpaque(false);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setToolTipText("Show or hide completed goals");
		header.add(arrow);
		header.add(Box.createHorizontalStrut(6));
		header.add(title);
		header.add(Box.createHorizontalGlue());
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
		header.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				boolean show = !list.isVisible();
				roadmapManager.setCompletedGoalsExpanded(show);
				list.setVisible(show);
				arrow.setText(show ? EXPANDED_GLYPH : COLLAPSED_GLYPH);
				goalsPanel.revalidate();
				goalsPanel.repaint();
			}
		});

		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setOpaque(false);
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		section.add(header);
		section.add(list);
		return section;
	}

	/**
	 * Per-row connector guides from the ordered depth sequence (ported from the
	 * app's tree painter). For each row, one guide per gutter column: the node's
	 * own column is a tee (has a following sibling) or corner (last); ancestor
	 * columns continue (through) only while their branch has a later sibling.
	 */
	private static Guide[][] computeGuides(int[] depths) {
		Guide[][] result = new Guide[depths.length][];
		for (int i = 0; i < depths.length; i++) {
			int d = depths[i];
			Guide[] cols = new Guide[d];
			for (int c = 0; c < d; c++) {
				if (c == d - 1) {
					cols[c] = hasFollowingAtLevel(depths, i, d) ? Guide.TEE : Guide.CORNER;
				} else {
					cols[c] = hasFollowingAtLevel(depths, i, c + 1) ? Guide.THROUGH : Guide.NONE;
				}
			}
			result[i] = cols;
		}
		return result;
	}

	/** Whether a later row sits at exactly {@code level} before the branch closes. */
	private static boolean hasFollowingAtLevel(int[] depths, int from, int level) {
		for (int j = from + 1; j < depths.length; j++) {
			if (depths[j] < level) {
				return false;
			}
			if (depths[j] == level) {
				return true;
			}
		}
		return false;
	}

	/** Depth-first order placing each goal after all of its prerequisites. */
	private void orderByPrereqs(RoadmapGoal goal, Map<Integer, RoadmapGoal> byId,
			Set<Integer> visited, List<RoadmapGoal> ordered, Set<Integer> inProgress) {
		if (visited.contains(goal.getId()) || !inProgress.add(goal.getId())) {
			return; // already placed, or a cycle — bail safely
		}
		for (Integer depId : goal.getDependencyIds()) {
			RoadmapGoal dep = byId.get(depId);
			if (dep != null) {
				orderByPrereqs(dep, byId, visited, ordered, inProgress);
			}
		}
		inProgress.remove(goal.getId());
		if (visited.add(goal.getId())) {
			ordered.add(goal);
		}
	}

	/** Longest prerequisite chain length (0 for a goal with no prerequisites). */
	private int prereqDepth(RoadmapGoal goal, Map<Integer, RoadmapGoal> byId,
			Map<Integer, Integer> memo, Set<Integer> inProgress) {
		Integer cached = memo.get(goal.getId());
		if (cached != null) {
			return cached;
		}
		if (!inProgress.add(goal.getId())) {
			return 0; // cycle guard
		}
		int depth = 0;
		for (Integer depId : goal.getDependencyIds()) {
			RoadmapGoal dep = byId.get(depId);
			if (dep != null) {
				depth = Math.max(depth, prereqDepth(dep, byId, memo, inProgress) + 1);
			}
		}
		inProgress.remove(goal.getId());
		memo.put(goal.getId(), depth);
		return depth;
	}

	/** Wraps a card in a left-indented container so deeper goals shift right. */
	private JComponent indentRow(JPanel card, int depth) {
		if (depth <= 0) {
			return card;
		}
		JPanel wrapper = new JPanel();
		wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.X_AXIS));
		// Transparent so the connector lines painted by GoalsTreePanel show
		// through the indent gutter instead of being covered by the row's bg.
		wrapper.setOpaque(false);
		wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
		wrapper.add(Box.createHorizontalStrut(depth * INDENT_PER_DEPTH));
		wrapper.add(card);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getMaximumSize().height));
		return wrapper;
	}

	private JPanel buildGoalRow(RoadmapGoal goal, int depth) {
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Width-constrained HTML so the full goal name wraps instead of truncating;
		// narrow the wrap width to match the card shrinking as it indents.
		int wrapWidth = Math.max(80, NAME_WRAP_WIDTH - depth * INDENT_PER_DEPTH);
		// Local progress (kills, drops, XP seen this session) layered on the
		// server's numbers, so the card matches the overlay.
		GoalProgressView progress = goalProgressTracker.progressFor(goal);
		boolean complete = progress.isComplete();
		JLabel name = new JLabel(wrapGoalName(goalName(goal), wrapWidth));
		name.setForeground(complete ? COMPLETE_COLOR : Color.WHITE);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(name);

		if (complete) {
			JLabel done = new JLabel("Completed");
			done.setForeground(COMPLETE_COLOR);
			done.setFont(FontManager.getRunescapeSmallFont());
			done.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(Box.createVerticalStrut(4));
			row.add(done);
		} else if (progress.isMeasurable()) {
			row.add(Box.createVerticalStrut(5));
			row.add(new GoalProgressBar(GoalProgressLabel.fraction(progress.getPercent())));
			row.add(Box.createVerticalStrut(3));
			JLabel progressLabel = new JLabel(GoalProgressLabel.format(
					progress.getType(), progress.getCurrent(), progress.getTarget(), progress.getPercent()));
			progressLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			progressLabel.setFont(FontManager.getRunescapeSmallFont());
			progressLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(progressLabel);
		}
		if (!complete && progress.getType().isServerDriven() && roadmapManager.isSyncing()) {
			// Only a server read can move this goal; show that one is on its way.
			JLabel syncing = new JLabel("Syncing with server...");
			syncing.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			syncing.setFont(FontManager.getRunescapeSmallFont());
			syncing.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.add(Box.createVerticalStrut(3));
			row.add(syncing);
		}

		// Action row: "Mark complete" (incomplete goals only) + "Delete".
		JPanel actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS));
		actions.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (!complete) {
			JButton markComplete = new JButton("Mark complete");
			markComplete.setFont(FontManager.getRunescapeSmallFont());
			markComplete.setMargin(new Insets(2, 6, 2, 6));
			markComplete.addActionListener(e -> confirmMarkComplete(goal));
			actions.add(markComplete);
			actions.add(Box.createHorizontalStrut(6));
		}
		JButton delete = new JButton("Delete");
		delete.setFont(FontManager.getRunescapeSmallFont());
		delete.setMargin(new Insets(2, 6, 2, 6));
		delete.setForeground(DELETE_COLOR);
		delete.setToolTipText("Remove this goal from the roadmap");
		delete.addActionListener(e -> confirmDelete(goal));
		actions.add(delete);
		actions.add(Box.createHorizontalGlue());
		row.add(Box.createVerticalStrut(6));
		row.add(actions);

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

	/** Confirms, then deletes a goal from the roadmap and re-renders. */
	private void confirmDelete(RoadmapGoal goal) {
		RoadmapSummary selected = (RoadmapSummary) roadmapSelector.getSelectedItem();
		if (selected == null) {
			return;
		}
		int choice = JOptionPane.showConfirmDialog(
				this,
				"Delete \"" + goalName(goal) + "\" from this roadmap?",
				"Delete goal",
				JOptionPane.YES_NO_OPTION);
		if (choice != JOptionPane.YES_OPTION) {
			return;
		}
		setStatus("Deleting...");
		roadmapManager.deleteGoal(selected.getCollectionId(), goal.getId(),
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
	private static String wrapGoalName(String name, int width) {
		String escaped = name
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;");
		return "<html><body style='width:" + width + "px'>" + escaped + "</body></html>";
	}

	private void clearGoals() {
		goalsPanel.removeAll();
		goalsPanel.setRowGuides(Collections.emptyList());
		goalsPanel.revalidate();
		goalsPanel.repaint();
	}

	private void setStatus(String text) {
		statusLabel.setText(text == null ? "" : text);
	}

	/** Connector style for one gutter column of a row (mirrors the app). */
	private enum Guide {
		NONE,
		THROUGH,
		TEE,
		CORNER
	}

	/** A rendered row paired with its per-column connector guides. */
	private static final class RowGuide {
		private final Component component;
		private final Guide[] guides;

		RowGuide(Component component, Guide[] guides) {
			this.component = component;
			this.guides = guides;
		}
	}

	/**
	 * The goals container, which also paints the dependency-tree connector lines
	 * in the indent gutters. Lines are drawn under the cards (cards sit to the
	 * right of the gutters), and verticals extend by {@link #ROW_GAP} to bridge
	 * the strut between rows so a branch reads as one continuous line.
	 */
	private static final class GoalsTreePanel extends JPanel implements Scrollable {
		private List<RowGuide> rowGuides = Collections.emptyList();

		void setRowGuides(List<RowGuide> rowGuides) {
			this.rowGuides = rowGuides;
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			// Report no intrinsic viewport size so the enclosing scroll pane never
			// inflates this panel's preferred height to the full goal-list height —
			// that inflation is what pushed the client window past the screen.
			return new Dimension(0, 0);
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return orientation == SwingConstants.VERTICAL ? visibleRect.height : visibleRect.width;
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			// Track the viewport width so goal names keep wrapping and no horizontal
			// scrollbar is ever needed.
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			// Let the panel grow taller than the viewport so the list scrolls.
			return false;
		}

		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setColor(GUIDE_COLOR);
				g2.setStroke(new BasicStroke(2f));
				g2.setRenderingHint(
						RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				for (RowGuide rg : rowGuides) {
					paintRowGuides(g2, rg);
				}
			} finally {
				g2.dispose();
			}
		}

		private void paintRowGuides(Graphics2D g2, RowGuide rg) {
			Rectangle b = rg.component.getBounds();
			Guide[] guides = rg.guides;
			int depth = guides.length;
			int elbowY = b.y + CONNECTOR_Y_OFFSET;
			int bottom = b.y + b.height + ROW_GAP; // bridge the inter-row strut
			for (int c = 0; c < depth; c++) {
				int cx = c * INDENT_PER_DEPTH + INDENT_PER_DEPTH / 2;
				int cardLeft = depth * INDENT_PER_DEPTH;
				switch (guides[c]) {
					case THROUGH:
						g2.drawLine(cx, b.y, cx, bottom);
						break;
					case TEE:
						g2.drawLine(cx, b.y, cx, bottom);
						g2.drawLine(cx, elbowY, cardLeft, elbowY);
						break;
					case CORNER:
						g2.drawLine(cx, b.y, cx, elbowY);
						g2.drawLine(cx, elbowY, cardLeft, elbowY);
						break;
					case NONE:
					default:
						break;
				}
			}
		}
	}
}
