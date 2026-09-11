package com.mystix;

/**
 * Test double for MystixConfig used in unit tests.
 */
public class TestMystixConfig implements MystixConfig {
	private String mystixAppKey = "";
	private boolean syncTimeTracking = true;
	private boolean syncBankMemory = true;
	private boolean syncCollectionLog = true;
	private boolean syncQuests = true;
	private boolean syncAchievementDiaries = true;
	private boolean syncCombatAchievements = true;
	private boolean syncKillCounts = true;
	private boolean syncSlayer = true;
	private boolean syncHouseLocation = true;
	private boolean syncKingdom = true;
	private boolean showNextGoal = true;
	private boolean showGoalProgress = true;
	private boolean showGoalPopup = true;
	private GoalCompleteSound goalCompleteSound = GoalCompleteSound.IN_GAME;

	@Override
	public String mystixAppKey() {
		return mystixAppKey;
	}

	@Override
	public boolean syncTimeTracking() {
		return syncTimeTracking;
	}

	@Override
	public boolean syncBankMemory() {
		return syncBankMemory;
	}

	@Override
	public boolean syncCollectionLog() {
		return syncCollectionLog;
	}

	@Override
	public boolean syncQuests() {
		return syncQuests;
	}

	@Override
	public boolean syncAchievementDiaries() {
		return syncAchievementDiaries;
	}

	@Override
	public boolean syncCombatAchievements() {
		return syncCombatAchievements;
	}

	@Override
	public boolean syncKillCounts() {
		return syncKillCounts;
	}

	@Override
	public boolean showNextGoal() {
		return showNextGoal;
	}

	public void setMystixAppKey(String key) {
		this.mystixAppKey = key;
	}

	public void setSyncTimeTracking(boolean value) {
		this.syncTimeTracking = value;
	}

	public void setSyncBankMemory(boolean value) {
		this.syncBankMemory = value;
	}

	public void setSyncCollectionLog(boolean value) {
		this.syncCollectionLog = value;
	}

	public void setSyncQuests(boolean value) {
		this.syncQuests = value;
	}

	public void setSyncAchievementDiaries(boolean value) {
		this.syncAchievementDiaries = value;
	}

	public void setSyncCombatAchievements(boolean value) {
		this.syncCombatAchievements = value;
	}

	public void setSyncKillCounts(boolean value) {
		this.syncKillCounts = value;
	}

	public void setShowNextGoal(boolean value) {
		this.showNextGoal = value;
	}

	@Override
	public boolean syncSlayer() {
		return syncSlayer;
	}

	public void setSyncSlayer(boolean syncSlayer) {
		this.syncSlayer = syncSlayer;
	}

	@Override
	public boolean syncHouseLocation() {
		return syncHouseLocation;
	}

	public void setSyncHouseLocation(boolean syncHouseLocation) {
		this.syncHouseLocation = syncHouseLocation;
	}

	@Override
	public boolean syncKingdom() {
		return syncKingdom;
	}

	public void setSyncKingdom(boolean syncKingdom) {
		this.syncKingdom = syncKingdom;
	}

	@Override
	public boolean showGoalProgress() {
		return showGoalProgress;
	}

	public void setShowGoalProgress(boolean value) {
		this.showGoalProgress = value;
	}

	@Override
	public boolean showGoalPopup() {
		return showGoalPopup;
	}

	public void setShowGoalPopup(boolean value) {
		this.showGoalPopup = value;
	}

	@Override
	public GoalCompleteSound goalCompleteSound() {
		return goalCompleteSound;
	}

	public void setGoalCompleteSound(GoalCompleteSound value) {
		this.goalCompleteSound = value;
	}
}
