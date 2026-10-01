package com.mystix.model;

import java.util.List;

/**
 * One death of the local player. Field names match the wire format of
 * POST /api/runelite/deaths/ events entries.
 *
 * <p>The client sends only what it saw: the last thing attacking the player,
 * the map position (template coordinates inside instances) and the world. The
 * backend names the area and decides whether the death was a safe one, so that
 * mapping can change without a plugin update. {@code event_uuid} makes replays
 * idempotent.
 */
public class DeathEvent {
	public static final String KILLER_NPC = "npc";
	public static final String KILLER_PLAYER = "player";
	public static final String KILLER_UNKNOWN = "unknown";

	private final String event_uuid;
	private final String died_at;
	private final String killer_type;
	private final Integer npc_id;
	private final String npc_name;
	private final Integer region_id;
	private final Integer x;
	private final Integer y;
	private final Integer plane;
	private final boolean instanced;
	private final Integer world;
	private final List<String> world_types;

	public DeathEvent(
			String eventUuid,
			String diedAt,
			String killerType,
			Integer npcId,
			String npcName,
			Integer regionId,
			Integer x,
			Integer y,
			Integer plane,
			boolean instanced,
			Integer world,
			List<String> worldTypes) {
		this.event_uuid = eventUuid;
		this.died_at = diedAt;
		this.killer_type = killerType;
		this.npc_id = npcId;
		this.npc_name = npcName;
		this.region_id = regionId;
		this.x = x;
		this.y = y;
		this.plane = plane;
		this.instanced = instanced;
		this.world = world;
		this.world_types = worldTypes;
	}

	public String getEventUuid() {
		return event_uuid;
	}

	public String getDiedAt() {
		return died_at;
	}

	public String getKillerType() {
		return killer_type;
	}

	public Integer getNpcId() {
		return npc_id;
	}

	public String getNpcName() {
		return npc_name;
	}
}
