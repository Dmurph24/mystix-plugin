package com.mystix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.mystix.model.DiaryTierResult;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for AchievementDiariesReader: the embedded WikiSync spec parses and its
 * varbit / varplayer evaluation produces the expected positional booleans.
 */
public class AchievementDiariesReaderTest {
	private static final IntUnaryOperator ALL_ZERO = id -> 0;

	private AchievementDiariesReader reader;

	@Before
	public void setUp() {
		reader = new AchievementDiariesReader(new Gson());
	}

	private static IntUnaryOperator source(Map<Integer, Integer> values) {
		return id -> values.getOrDefault(id, 0);
	}

	@Test
	public void testSpecLoadsAllRegionsAndTiers() {
		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(ALL_ZERO, ALL_ZERO);

		assertEquals(12, diaries.size());
		assertTrue(diaries.containsKey("Kourend & Kebos"));
		assertTrue(diaries.containsKey("Lumbridge & Draynor"));
		assertTrue(diaries.containsKey("Western Provinces"));

		Map<String, DiaryTierResult> ardougne = diaries.get("Ardougne");
		assertEquals(4, ardougne.size());
		// Task counts must match the catalog (drift tripwire on the backend).
		assertEquals(10, ardougne.get("Easy").getTasks().size());
		assertEquals(12, ardougne.get("Medium").getTasks().size());
		assertEquals(19, diaries.get("Karamja").get("Medium").getTasks().size());
	}

	@Test
	public void testAllFalseWhenNothingSet() {
		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(ALL_ZERO, ALL_ZERO);
		DiaryTierResult ardougneEasy = diaries.get("Ardougne").get("Easy");
		assertFalse(ardougneEasy.isComplete());
		assertTrue(ardougneEasy.getTasks().stream().noneMatch(Boolean::booleanValue));
	}

	@Test
	public void testPlayerOffsetTaskEvaluation() {
		// Ardougne Easy tasks read bits of varplayer 1196; task 0 is offset 0.
		Map<Integer, Integer> varps = new HashMap<>();
		varps.put(1196, 1 << 0);  // set only bit 0
		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(ALL_ZERO, source(varps));

		DiaryTierResult easy = diaries.get("Ardougne").get("Easy");
		assertTrue(easy.getTasks().get(0));
		assertFalse(easy.getTasks().get(1));
	}

	@Test
	public void testBitsTaskEvaluation() {
		// Karamja Easy tasks are varbits; task 0 completes when varbit 3566 == 5.
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(3566, 5);
		varbits.put(3567, 1);
		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(source(varbits), ALL_ZERO);

		DiaryTierResult easy = diaries.get("Karamja").get("Easy");
		assertTrue(easy.getTasks().get(0));
		assertTrue(easy.getTasks().get(1));
		assertFalse(easy.getTasks().get(2));
	}

	@Test
	public void testBitsTaskRequiresExactValue() {
		// varbit 3566 needs value 5; a plain 1 must not count as complete.
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(3566, 1);
		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(source(varbits), ALL_ZERO);
		assertFalse(diaries.get("Karamja").get("Easy").getTasks().get(0));
	}

	@Test
	public void testTierCompleteFlag() {
		// Ardougne Easy completion is varbit 4499 == 1.
		Map<Integer, Integer> varbits = new HashMap<>();
		varbits.put(4499, 1);
		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(source(varbits), ALL_ZERO);
		assertTrue(diaries.get("Ardougne").get("Easy").isComplete());
	}

	@Test
	public void testStaleCompleteImageReadsEverythingComplete() {
		// Reproduces the production bug: the client varp array momentarily holds a prior
		// fully-completed session's values (read during the post-login varp replay). Every
		// player-bit test passes (-1 = all bits set) and each diary varbit reads exactly its
		// completed value (1, or 5 for the three Karamja tasks). The reader is a faithful
		// mirror of that array, so it emits an all-true payload — 492 booleans plus 48
		// complete flags — which is why one bad sample permanently maxes an account. A
		// uniform -1 fill alone could NOT do this: the Karamja == 5 varbits are multi-bit
		// fields that extract to 7 under all-ones, so they must read their real value 5.
		IntUnaryOperator completeVarbit = id -> (id == 3566 || id == 3573 || id == 3607) ? 5 : 1;
		IntUnaryOperator completeVarp = id -> -1;

		Map<String, Map<String, DiaryTierResult>> diaries = reader.read(completeVarbit, completeVarp);

		int tierCount = 0;
		for (Map<String, DiaryTierResult> tiers : diaries.values()) {
			for (DiaryTierResult tier : tiers.values()) {
				tierCount++;
				assertTrue(tier.isComplete());
				assertTrue(tier.getTasks().stream().allMatch(Boolean::booleanValue));
			}
		}
		assertEquals(48, tierCount);
	}

	@Test
	public void testDesertMediumSpecialCaseEitherVarp() {
		// Desert Medium task 10 = bit(varp 1199, 9) OR bit(varp 1198, 22).
		Map<Integer, Integer> viaIronmanVarp = new HashMap<>();
		viaIronmanVarp.put(1199, 1 << 9);
		assertTrue(desertMediumTask10(source(viaIronmanVarp)));

		Map<Integer, Integer> viaMainVarp = new HashMap<>();
		viaMainVarp.put(1198, 1 << 22);
		assertTrue(desertMediumTask10(source(viaMainVarp)));

		assertFalse(desertMediumTask10(ALL_ZERO));
	}

	private boolean desertMediumTask10(IntUnaryOperator varp) {
		return reader.read(ALL_ZERO, varp).get("Desert").get("Medium").getTasks().get(10);
	}
}
