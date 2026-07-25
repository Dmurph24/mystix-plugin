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
	private boolean showNextGoal = false;

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
}
