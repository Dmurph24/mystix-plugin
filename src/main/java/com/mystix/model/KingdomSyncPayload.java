package com.mystix.model;

import com.google.gson.Gson;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payload for syncing Managing Miscellania state to the Mystix API.
 * Matches the format expected by POST /api/runelite/kingdom/
 *
 * <p>Carries the raw kingdom varbits (approval points out of 127, coffer coins,
 * subjects per labour and the three allocation choice values); the backend owns
 * the decoding and the daily decay maths so a correction never needs a plugin
 * release. There is no client timestamp: the server stamps receipt time, which
 * keeps the client-side dedupe effective.
 */
public class KingdomSyncPayload {
	private final String player_username;
	private final boolean in_kingdom;
	private final boolean throne_completed;
	private final boolean royal_trouble_completed;
	private final int approval_points;
	private final int coffer;
	private final int workers_wood;
	private final int workers_herb;
	private final int workers_fish;
	private final int workers_mine;
	private final int workers_rarewood;
	private final int workers_farm;
	private final int cooked_fish;
	private final int rarewood_type;
	private final int herbs_or_flax;
	private final Map<String, Integer> extra;

	public KingdomSyncPayload(
			String playerUsername,
			boolean inKingdom,
			boolean throneCompleted,
			boolean royalTroubleCompleted,
			int approvalPoints,
			int coffer,
			int workersWood,
			int workersHerb,
			int workersFish,
			int workersMine,
			int workersRarewood,
			int workersFarm,
			int cookedFish,
			int rarewoodType,
			int herbsOrFlax,
			Map<String, Integer> extra) {
		this.player_username = playerUsername;
		this.in_kingdom = inKingdom;
		this.throne_completed = throneCompleted;
		this.royal_trouble_completed = royalTroubleCompleted;
		this.approval_points = approvalPoints;
		this.coffer = coffer;
		this.workers_wood = workersWood;
		this.workers_herb = workersHerb;
		this.workers_fish = workersFish;
		this.workers_mine = workersMine;
		this.workers_rarewood = workersRarewood;
		this.workers_farm = workersFarm;
		this.cooked_fish = cookedFish;
		this.rarewood_type = rarewoodType;
		this.herbs_or_flax = herbsOrFlax;
		this.extra = extra == null ? Collections.emptyMap() : new LinkedHashMap<>(extra);
	}

	public String getPlayerUsername() {
		return player_username;
	}

	public boolean isInKingdom() {
		return in_kingdom;
	}

	public boolean isThroneCompleted() {
		return throne_completed;
	}

	public boolean isRoyalTroubleCompleted() {
		return royal_trouble_completed;
	}

	public int getApprovalPoints() {
		return approval_points;
	}

	public int getCoffer() {
		return coffer;
	}

	public int getWorkersWood() {
		return workers_wood;
	}

	public int getWorkersHerb() {
		return workers_herb;
	}

	public int getWorkersFish() {
		return workers_fish;
	}

	public int getWorkersMine() {
		return workers_mine;
	}

	public int getWorkersRarewood() {
		return workers_rarewood;
	}

	public int getWorkersFarm() {
		return workers_farm;
	}

	public int getCookedFish() {
		return cooked_fish;
	}

	public int getRarewoodType() {
		return rarewood_type;
	}

	public int getHerbsOrFlax() {
		return herbs_or_flax;
	}

	public Map<String, Integer> getExtra() {
		return Collections.unmodifiableMap(extra);
	}

	public String toJson(Gson gson) {
		return gson.toJson(this);
	}
}
