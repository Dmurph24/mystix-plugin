package com.mystix.api;

import com.google.gson.Gson;
import com.mystix.MystixConfig;
import com.mystix.SyncHealth;
import com.mystix.TestMystixConfig;
import com.mystix.model.BankSyncPayload;
import com.mystix.model.LootDropPayload;
import com.mystix.model.LootSyncPayload;
import com.mystix.model.PlayerSkillsSyncPayload;
import com.mystix.model.Roadmap;
import com.mystix.model.RoadmapList;
import com.mystix.model.TimerSyncItem;
import com.mystix.model.TimersSyncPayload;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that MystixApiClient handles config correctly and payload structure.
 */
public class MystixApiClientTest {
	private static MystixConfig emptyKeyConfig() {
		com.mystix.TestMystixConfig c = new com.mystix.TestMystixConfig();
		c.setMystixAppKey("");
		return c;
	}

	@Test
	public void testSendWithEmptyAppKeyDoesNotThrow() {
		MystixApiClient client = new MystixApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		TimerSyncItem item = new TimerSyncItem("bird house", "fossil island", "bird house",
				Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, null, null, 0);
		client.sendTimersSync(Collections.singletonList(item));
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testPayloadStructure() {
		TimerSyncItem item = new TimerSyncItem("tree", "farming guild", "Oak tree", Instant.ofEpochSecond(1700000000L),
				true, "TestPlayer", null, null, null, 0);
		String json = TimersSyncPayload.toJson(Collections.singletonList(item));
		assertNotNull(json);
		assertTrue(json.contains("\"timers\""));
		assertTrue(json.contains("\"timer_type\":\"tree\""));
		assertTrue(json.contains("\"region\":\"farming guild\""));
		assertTrue(json.contains("\"entity\":\"oak tree\""));
		assertTrue(json.contains("\"completed_at\""));
		assertTrue(json.contains("\"notifications_enabled\":true"));
		assertTrue(json.contains("\"player_username\":\"TestPlayer\""));
	}

	@Test
	public void testSendPlayerSkillsWithEmptyAppKeyDoesNotThrow() {
		MystixApiClient client = new MystixApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 145, 85);
		client.sendPlayerSkillsSync(payload);
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testSendBankSyncWithEmptyAppKeyDoesNotThrow() {
		MystixApiClient client = new MystixApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		Map<String, java.util.List<BankSyncPayload.BankItem>> items = new HashMap<>();
		items.put("bank", Arrays.asList(new BankSyncPayload.BankItem(4151, 1)));
		BankSyncPayload payload = new BankSyncPayload("TestPlayer", items);
		client.sendBankSync(payload);
		// Should not throw; with empty key it returns early
	}

	@Test
	public void testPlayerSkillsPayloadStructure() {
		Map<String, PlayerSkillsSyncPayload.SkillData> skills = new HashMap<>();
		skills.put("Attack", new PlayerSkillsSyncPayload.SkillData(75, 1200000));
		skills.put("Defence", new PlayerSkillsSyncPayload.SkillData(70, 800000));
		skills.put("Strength", new PlayerSkillsSyncPayload.SkillData(80, 2000000));
		PlayerSkillsSyncPayload payload = new PlayerSkillsSyncPayload("TestPlayer", skills, 225, 95);
		String json = payload.toJson(new Gson());

		assertNotNull(json);
		assertTrue(json.contains("\"player\":\"TestPlayer\""));
		assertTrue(json.contains("\"skills\""));
		/* API expects skills as {"SkillName": {"level": N, "current_xp": N}} */
		assertTrue(json.contains("Attack"));
		assertTrue(json.contains("\"level\":75"));
		assertTrue(json.contains("\"level\":70"));
		assertTrue(json.contains("\"level\":80"));
		assertTrue(json.contains("\"current_xp\":1200000"));
		assertTrue(json.contains("\"current_xp\":800000"));
		assertTrue(json.contains("\"current_xp\":2000000"));
	}

	@Test
	public void testGetRoadmapsWithEmptyKeyCallsOnError() {
		MystixApiClient client = new MystixApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		AtomicReference<String> error = new AtomicReference<>();
		AtomicBoolean success = new AtomicBoolean(false);
		client.getRoadmaps("TestPlayer", new MystixApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				success.set(true);
			}

			@Override
			public void onError(String message) {
				error.set(message);
			}
		});
		// No key => synchronous onError, no network call.
		assertFalse(success.get());
		assertNotNull(error.get());
	}

	@Test
	public void testRecomputeRoadmapWithEmptyKeyCallsOnError() {
		MystixApiClient client = new MystixApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		AtomicReference<String> error = new AtomicReference<>();
		client.recomputeRoadmap(1, "TestPlayer", new MystixApiClient.RoadmapCallback<Roadmap>() {
			@Override
			public void onSuccess(Roadmap result) {
			}

			@Override
			public void onError(String message) {
				error.set(message);
			}
		});
		assertEquals("No Mystix App Key configured", error.get());
	}

	@Test
	public void testIsDuplicateReturnsFalseWhenPreviousNull() {
		/* First-ever send for a syncType always goes out. */
		assertFalse(MystixApiClient.isDuplicate(null, "{\"timers\":[]}"));
	}

	@Test
	public void testIsDuplicateReturnsTrueWhenBytesEqual() {
		String body = "{\"timers\":[{\"timer_type\":\"tree\"}]}";
		assertTrue(MystixApiClient.isDuplicate(body, body));
	}

	@Test
	public void testIsDuplicateReturnsFalseWhenBytesDiffer() {
		/* Bodies differing only by player_username are not duplicates (multi-account safety). */
		String bodyA = "{\"player_username\":\"PlayerA\",\"timers\":[]}";
		String bodyB = "{\"player_username\":\"PlayerB\",\"timers\":[]}";
		assertFalse(MystixApiClient.isDuplicate(bodyA, bodyB));
	}

	@Test
	public void testDuplicateSendWithEmptyAppKeyDoesNotThrow() {
		/* The dedupe guard runs after the app-key guard, so the empty-key path is unaffected. */
		MystixApiClient client = new MystixApiClient(emptyKeyConfig(), new Gson(), new OkHttpClient());
		TimerSyncItem item = new TimerSyncItem("bird house", "fossil island", "bird house",
				Instant.ofEpochSecond(1700000000L), true, "TestPlayer", null, null, null, 0);
		client.sendTimersSync(Collections.singletonList(item));
		client.sendTimersSync(Collections.singletonList(item));
		// Should not throw; with empty key both return early before the dedupe check
	}

	/** A base client that answers every call itself and records what it was sent. */
	private static OkHttpClient recordingClient(AtomicReference<Request> sent, CountDownLatch done) {
		return new OkHttpClient.Builder()
				.addInterceptor(chain -> {
					sent.set(chain.request());
					done.countDown();
					return new Response.Builder()
							.request(chain.request())
							.protocol(Protocol.HTTP_1_1)
							.code(200)
							.message("OK")
							.body(ResponseBody.create(null, "{\"roadmaps\":[]}"))
							.build();
				})
				.build();
	}

	@Test
	public void everyRequestCarriesThePluginVersion() throws InterruptedException {
		com.mystix.TestMystixConfig config = new com.mystix.TestMystixConfig();
		config.setMystixAppKey("test-key");
		AtomicReference<Request> sent = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		MystixApiClient client = new MystixApiClient(config, new Gson(), recordingClient(sent, done));

		client.getRoadmaps("Zezima", new MystixApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
			}

			@Override
			public void onError(String message) {
			}
		});

		assertTrue(done.await(5, TimeUnit.SECONDS));
		assertEquals(MystixApiClient.PLUGIN_VERSION, sent.get().header(MystixApiClient.VERSION_HEADER));
		assertEquals("test-key", sent.get().header("X-RuneLite-Key"));
	}

	/** A base client that answers every call with {@code code}, then runs {@code beforeReply}. */
	private static OkHttpClient answeringClient(int code, Runnable beforeReply, CountDownLatch done) {
		return new OkHttpClient.Builder()
				.addInterceptor(chain -> {
					beforeReply.run();
					return new Response.Builder()
							.request(chain.request())
							.protocol(Protocol.HTTP_1_1)
							.code(code)
							.message("status " + code)
							.body(ResponseBody.create(null, "{\"roadmaps\":[]}"))
							.build();
				})
				.build();
	}

	private static MystixApiClient.RoadmapCallback<RoadmapList> countDown(CountDownLatch done) {
		return new MystixApiClient.RoadmapCallback<RoadmapList>() {
			@Override
			public void onSuccess(RoadmapList result) {
				done.countDown();
			}

			@Override
			public void onError(String message) {
				done.countDown();
			}
		};
	}

	private static SyncHealth.Status statusAfter(int code, TestMystixConfig config, Runnable beforeReply)
			throws InterruptedException {
		SyncHealth health = new SyncHealth();
		CountDownLatch done = new CountDownLatch(1);
		MystixApiClient client = new MystixApiClient(
				config, new Gson(), answeringClient(code, beforeReply, done), health);
		client.getRoadmaps("Zezima", countDown(done));
		assertTrue(done.await(5, TimeUnit.SECONDS));
		return health.status();
	}

	private static TestMystixConfig keyed(String key) {
		TestMystixConfig config = new TestMystixConfig();
		config.setMystixAppKey(key);
		return config;
	}

	@Test
	public void rejectedKeyIsReported() throws InterruptedException {
		assertEquals(SyncHealth.Status.KEY_REJECTED, statusAfter(403, keyed("old-key"), () -> { }));
	}

	@Test
	public void acceptedKeyIsReported() throws InterruptedException {
		assertEquals(SyncHealth.Status.OK, statusAfter(200, keyed("good-key"), () -> { }));
	}

	@Test
	public void lateRejectionOfAnEditedKeyIsIgnored() throws InterruptedException {
		TestMystixConfig config = keyed("old-key");
		// The player pastes a new key while the old key's request is in flight.
		assertEquals(SyncHealth.Status.OK, statusAfter(403, config, () -> config.setMystixAppKey("new-key")));
	}

	@Test
	public void pluginVersionIsHeaderSafe() {
		assertTrue(MystixApiClient.PLUGIN_VERSION.matches("[0-9A-Za-z._-]{1,32}"));
	}

	private static String sentLootDropsBody(LootDropPayload... drops) throws Exception {
		AtomicReference<Request> sent = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		MystixApiClient client = new MystixApiClient(keyed("test-key"), new Gson(), recordingClient(sent, done));
		client.sendLootDrops(Arrays.asList(drops));
		assertTrue(done.await(5, TimeUnit.SECONDS));
		Buffer body = new Buffer();
		sent.get().body().writeTo(body);
		return body.readUtf8();
	}

	private static LootDropPayload drop(String npcName, Map<String, Object> context) {
		return new LootDropPayload("Zezima", "client-1", 1, npcName, 1, "2026-10-10T00:00:00Z",
				Collections.singletonList(new LootSyncPayload.LootItem(995, 100)), context);
	}

	@Test
	public void lootDropContextIsSentOnlyWhenPresent() throws Exception {
		Map<String, Integer> varbits = new LinkedHashMap<>();
		varbits.put("9858", 1);
		varbits.put("9859", 0);
		varbits.put("9860", 1);
		Map<String, Object> varbitContext = new LinkedHashMap<>();
		varbitContext.put("varbits", varbits);
		Map<String, Object> intMetadata = new LinkedHashMap<>();
		intMetadata.put("metadata", 7);
		Map<String, Object> arrayMetadata = new LinkedHashMap<>();
		arrayMetadata.put("metadata", new int[]{3, 4});

		String json = sentLootDropsBody(drop("Lunar Chest", varbitContext), drop("Chest A", intMetadata),
				drop("Chest B", arrayMetadata), drop("Goblin", null));

		assertTrue(json, json.contains("\"npc_name\":\"Lunar Chest\",\"kill_count\":1,"
				+ "\"dropped_at\":\"2026-10-10T00:00:00Z\",\"items\":[{\"item_id\":995,\"quantity\":100}],"
				+ "\"context\":{\"varbits\":{\"9858\":1,\"9859\":0,\"9860\":1}}}"));
		assertTrue(json, json.contains("\"context\":{\"metadata\":7}"));
		assertTrue(json, json.contains("\"context\":{\"metadata\":[3,4]}"));
		String goblin = json.substring(json.indexOf("\"npc_name\":\"Goblin\""));
		assertFalse(json, goblin.contains("context"));
	}
}
