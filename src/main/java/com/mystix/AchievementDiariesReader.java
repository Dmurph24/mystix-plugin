package com.mystix;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mystix.model.DiaryTierResult;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/**
 * Reads achievement diary completion from the live game and produces the
 * WikiSync {@code achievement_diaries} shape ({@code {region: {tier: {complete,
 * tasks: [bool, ...]}}}}).
 *
 * <p>The region/tier/task -&gt; varbit mapping is embedded verbatim from the WikiSync
 * API's server-authoritative spec
 * (<a href="https://github.com/weirdgloop/wikisync-api">weirdgloop/wikisync-api</a>,
 * MIT; {@code src/runelite/data/achievementDiariesSpecs.json}). Each task reads a
 * varbit ({@code type: "bits"}, complete when the varbit equals {@code value}) or
 * a bit of a varplayer ({@code type: "player"}, complete when bit {@code offset}
 * is set). Reproducing that spec's order keeps each {@code tasks} array positional
 * in in-game varbit order, matching the backend's
 * {@code OSRSAchievementDiaryTask.sequence}.
 */
@Slf4j
@Singleton
public class AchievementDiariesReader {
	private static final String SPEC_RESOURCE = "achievement_diaries_specs.json";
	private static final String TYPE_BITS = "bits";
	private static final String TYPE_PLAYER = "player";

	// The one task the WikiSync transformer resolves specially (ironman vs
	// non-ironman recorded it in different varps): Desert Medium task index 10.
	private static final String DESERT = "Desert";
	private static final String MEDIUM = "Medium";
	private static final int DESERT_MEDIUM_SPECIAL_INDEX = 10;

	private final Map<String, Map<String, TierSpec>> specs;
	// The varbit / varplayer ids the spec depends on, split by kind so the monitor
	// can filter VarbitChanged (getVarbitId() vs getVarpId()) without cross-namespace
	// false matches, and only re-read diaries when a relevant var actually changes.
	private final Set<Integer> watchedVarbitIds;
	private final Set<Integer> watchedVarpIds;

	@Inject
	public AchievementDiariesReader(Gson gson) {
		this.specs = loadSpecs(gson);
		Set<Integer> varbitIds = new HashSet<>();
		Set<Integer> varpIds = new HashSet<>();
		collectWatchedVars(varbitIds, varpIds);
		this.watchedVarbitIds = varbitIds;
		this.watchedVarpIds = varpIds;
	}

	/** Reads current completion for every diary on the client thread. */
	public Map<String, Map<String, DiaryTierResult>> read(Client client) {
		return read(client::getVarbitValue, client::getVarpValue);
	}

	/**
	 * True if a change to the given varbit / varplayer id could affect diary
	 * completion. The monitor passes {@link net.runelite.api.events.VarbitChanged}'s
	 * {@code getVarbitId()} and {@code getVarpId()} so it re-reads diaries only on a
	 * relevant change instead of on every varbit change during play.
	 */
	public boolean watches(int varbitId, int varpId) {
		return watchedVarbitIds.contains(varbitId) || watchedVarpIds.contains(varpId);
	}

	/**
	 * Evaluates the spec against varbit / varplayer value lookups. Package-private
	 * so unit tests can drive it without a live {@link Client}.
	 */
	Map<String, Map<String, DiaryTierResult>> read(IntUnaryOperator varbit, IntUnaryOperator varp) {
		Map<String, Map<String, DiaryTierResult>> out = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, TierSpec>> regionEntry : specs.entrySet()) {
			String region = regionEntry.getKey();
			Map<String, DiaryTierResult> tiers = new LinkedHashMap<>();
			for (Map.Entry<String, TierSpec> tierEntry : regionEntry.getValue().entrySet()) {
				String tierName = tierEntry.getKey();
				TierSpec tierSpec = tierEntry.getValue();
				boolean complete = evalSpec(tierSpec.complete, varbit, varp);
				List<Boolean> tasks = new ArrayList<>(tierSpec.tasks.size());
				for (int i = 0; i < tierSpec.tasks.size(); i++) {
					tasks.add(evalTask(region, tierName, i, tierSpec.tasks.get(i), varbit, varp));
				}
				tiers.put(tierName, new DiaryTierResult(complete, tasks));
			}
			out.put(region, tiers);
		}
		return out;
	}

	private static boolean evalTask(String region, String tier, int index, VarSpec spec,
			IntUnaryOperator varbit, IntUnaryOperator varp) {
		if (DESERT.equals(region) && MEDIUM.equals(tier) && index == DESERT_MEDIUM_SPECIAL_INDEX) {
			return isBitSet(varp.applyAsInt(1199), 9) || isBitSet(varp.applyAsInt(1198), 22);
		}
		return evalSpec(spec, varbit, varp);
	}

	private static boolean evalSpec(VarSpec spec, IntUnaryOperator varbit, IntUnaryOperator varp) {
		if (spec == null || spec.type == null) {
			return false;
		}
		if (TYPE_BITS.equals(spec.type)) {
			return varbit.applyAsInt(spec.var_id) == spec.value;
		}
		if (TYPE_PLAYER.equals(spec.type)) {
			return isBitSet(varp.applyAsInt(spec.var_id), spec.offset);
		}
		return false;
	}

	private static boolean isBitSet(int bitmap, int index) {
		return (bitmap & (1 << index)) != 0;
	}

	/** Collects every varbit / varplayer id the spec reads, split by kind. */
	private void collectWatchedVars(Set<Integer> varbitIds, Set<Integer> varpIds) {
		for (Map<String, TierSpec> tiers : specs.values()) {
			for (TierSpec tier : tiers.values()) {
				addVar(tier.complete, varbitIds, varpIds);
				if (tier.tasks != null) {
					for (VarSpec task : tier.tasks) {
						addVar(task, varbitIds, varpIds);
					}
				}
			}
		}
		// Desert Medium task 10 is resolved from these varps directly (see evalTask),
		// so watch them explicitly in case no spec entry references them.
		varpIds.add(1198);
		varpIds.add(1199);
	}

	private static void addVar(VarSpec spec, Set<Integer> varbitIds, Set<Integer> varpIds) {
		if (spec == null || spec.type == null) {
			return;
		}
		if (TYPE_BITS.equals(spec.type)) {
			varbitIds.add(spec.var_id);
		} else if (TYPE_PLAYER.equals(spec.type)) {
			varpIds.add(spec.var_id);
		}
	}

	private Map<String, Map<String, TierSpec>> loadSpecs(Gson gson) {
		try (InputStream in = getClass().getResourceAsStream(SPEC_RESOURCE)) {
			if (in == null) {
				log.error("Mystix: achievement diary spec resource {} not found", SPEC_RESOURCE);
				return Collections.emptyMap();
			}
			Type type = new TypeToken<LinkedHashMap<String, LinkedHashMap<String, TierSpec>>>() {
			}.getType();
			Map<String, Map<String, TierSpec>> parsed =
					gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
			return parsed != null ? parsed : Collections.emptyMap();
		} catch (Exception e) {
			log.error("Mystix: failed to load achievement diary specs", e);
			return Collections.emptyMap();
		}
	}

	/** One varbit / varplayer reference from the embedded spec. */
	private static final class VarSpec {
		private String type;
		private int var_id;
		private Integer value;
		private Integer offset;
	}

	/** One tier's completion flag plus its ordered per-task references. */
	private static final class TierSpec {
		private VarSpec complete;
		private List<VarSpec> tasks;
	}
}
