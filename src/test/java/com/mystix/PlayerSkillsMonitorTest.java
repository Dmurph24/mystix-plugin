package com.mystix;

import com.mystix.api.MystixApiClient;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlayerSkillsMonitorTest
{
	private Client mockClient;
	private MystixConfig mockConfig;
	private MystixApiClient mockApiClient;
	private EventBus mockEventBus;
	private PlayerSkillsMonitor monitor;

	@Before
	public void setUp()
	{
		mockClient = mock(Client.class);
		mockConfig = mock(MystixConfig.class);
		mockApiClient = mock(MystixApiClient.class);
		mockEventBus = mock(EventBus.class);

		monitor = new PlayerSkillsMonitor(mockClient, mockConfig, mockApiClient, mockEventBus);
	}

	@Test
	public void testStartRegistersEventBus()
	{
		monitor.start();
		verify(mockEventBus).register(monitor);
	}

	@Test
	public void testStopUnregistersEventBus()
	{
		monitor.start();
		monitor.stop();
		verify(mockEventBus).unregister(monitor);
	}

	@Test
	public void testSyncOnLogin()
	{
		when(mockConfig.syncPlayerSkills()).thenReturn(true);
		when(mockConfig.mystixAppKey()).thenReturn("test-key");

		Player mockPlayer = mock(Player.class);
		when(mockPlayer.getName()).thenReturn("TestPlayer");
		when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
		when(mockClient.getRealSkillLevel(any(Skill.class))).thenReturn(99);

		monitor.start();

		GameStateChanged loginEvent = new GameStateChanged();
		loginEvent.setGameState(GameState.LOGGED_IN);
		monitor.onGameStateChanged(loginEvent);

		verify(mockApiClient, times(1)).sendPlayerSkillsSync(any());
	}

	@Test
	public void testSyncOnLogout()
	{
		when(mockConfig.syncPlayerSkills()).thenReturn(true);
		when(mockConfig.mystixAppKey()).thenReturn("test-key");

		Player mockPlayer = mock(Player.class);
		when(mockPlayer.getName()).thenReturn("TestPlayer");
		when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);
		when(mockClient.getRealSkillLevel(any(Skill.class))).thenReturn(50);

		monitor.start();

		GameStateChanged loginEvent = new GameStateChanged();
		loginEvent.setGameState(GameState.LOGGED_IN);
		monitor.onGameStateChanged(loginEvent);

		GameStateChanged logoutEvent = new GameStateChanged();
		logoutEvent.setGameState(GameState.LOGIN_SCREEN);
		monitor.onGameStateChanged(logoutEvent);

		verify(mockApiClient, times(2)).sendPlayerSkillsSync(any());
	}

	@Test
	public void testNoSyncWhenDisabled()
	{
		when(mockConfig.syncPlayerSkills()).thenReturn(false);
		when(mockConfig.mystixAppKey()).thenReturn("test-key");

		Player mockPlayer = mock(Player.class);
		when(mockPlayer.getName()).thenReturn("TestPlayer");
		when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);

		monitor.start();

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGGED_IN);
		monitor.onGameStateChanged(event);

		verify(mockApiClient, never()).sendPlayerSkillsSync(any());
	}

	@Test
	public void testNoSyncWithoutAppKey()
	{
		when(mockConfig.syncPlayerSkills()).thenReturn(true);
		when(mockConfig.mystixAppKey()).thenReturn("");

		Player mockPlayer = mock(Player.class);
		when(mockPlayer.getName()).thenReturn("TestPlayer");
		when(mockClient.getLocalPlayer()).thenReturn(mockPlayer);

		monitor.start();

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGGED_IN);
		monitor.onGameStateChanged(event);

		verify(mockApiClient, never()).sendPlayerSkillsSync(any());
	}

	@Test
	public void testNoSyncWithoutPlayer()
	{
		when(mockConfig.syncPlayerSkills()).thenReturn(true);
		when(mockConfig.mystixAppKey()).thenReturn("test-key");
		when(mockClient.getLocalPlayer()).thenReturn(null);

		monitor.start();

		GameStateChanged event = new GameStateChanged();
		event.setGameState(GameState.LOGGED_IN);
		monitor.onGameStateChanged(event);

		verify(mockApiClient, never()).sendPlayerSkillsSync(any());
	}
}
