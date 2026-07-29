/*
 * Copyright (c) 2026 L2jMobius
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * Manages social immersion features for FakePlayer NPCs:
 * - NPC→NPC conversations (local chat banter between nearby bots)
 * - Death reactions (NPCs comment when a player dies nearby)
 * - Auto greetings (when players walk near)
 * - Weather/time comments
 * - Gossip about recent player events
 * - Combat reactions (NPCs react to nearby fighting)
 *
 * Runs as a periodic scheduled task independent of the behavior manager's tick cycle.
 */
public class FakePlayerSocialManager
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerSocialManager.class.getName());

	// Scan interval: check every 5 seconds
	private static final long SCAN_INTERVAL_MS = 5000;

	// Ranges
	private static final int NPC_SOCIAL_RANGE = 800; // NPC-to-NPC chat range
	private static final int PLAYER_NEAR_RANGE = 250; // Greeting range
	private static final int DEATH_WITNESS_RANGE = 800; // Death reaction range
	private static final int COMBAT_WITNESS_RANGE = 600; // Combat reaction range

	// Cooldowns (milliseconds)
	private static final long NPC_CHAT_COOLDOWN = 120000; // NPC→NPC conversation every 2 min
	private static final long GREETING_COOLDOWN = 300000; // Greet same player every 5 min
	private static final long WEATHER_COOLDOWN = 600000; // Weather comment every 10 min
	private static final long DEATH_REACTION_COOLDOWN = 60000; // React to death every 1 min
	private static final long GOSSIP_COOLDOWN = 180000; // Gossip every 3 min
	private static final long COMBAT_REACTION_COOLDOWN = 30000; // Combat reaction every 30s

	// NPC→NPC conversation topics by class role
	private static final String[][] FIGHTER_TOPICS =
	{
		{"The training grounds are crowded today.", "Seen any good battles?"},
		{"I need to sharpen my blade.", "My armor is getting heavy."},
		{"Heard there's a tournament soon.", "I'd win for sure."},
		{"These monsters keep getting stronger.", "That's why we train."},
		{"Nice weather for a fight.", "Any day is good for a fight."},
	};

	private static final String[][] MAGE_TOPICS =
	{
		{"I've been studying a new spell.", "The mana flow feels strange today."},
		{"Have you read the latest tome?", "Knowledge is power."},
		{"The arcane energies are shifting.", "I can feel it in the air."},
		{"I need more materials for my research.", "The tower is running low on supplies."},
		{"Magic is the true path to power.", "Brute force has its limits."},
	};

	private static final String[][] HEALER_TOPICS =
	{
		{"Busy day at the temple.", "Many wounded today."},
		{"The goddess watches over us.", "Blessings upon you."},
		{"I've been healing all morning.", "Take care of yourself out there."},
		{"The light guides my hands.", "I hope there will be peace."},
		{"Rest is important for recovery.", "Don't overexert yourself."},
	};

	private static final String[][] MERCHANT_TOPICS =
	{
		{"Prices are rising everywhere.", "Good business today, eh?"},
		{"I got a shipment of rare goods.", "Best quality in town."},
		{"The economy is unstable.", "Better hold onto your adena."},
		{"Trading with the dwarves is profitable.", "They make excellent weapons."},
		{"Supply and demand, my friend.", "I have exactly what you need."},
	};

	// Generic topics for any class
	private static final String[] GENERIC_TOPICS = {
		"Nice weather we're having.",
		"Long day, huh?",
		"I could use a rest.",
		"Have you been to the shop lately?",
		"Quiet around here today.",
		"Think I'll take a walk.",
		"Stay safe out there.",
	};

	// Death reactions by class role
	private static final String[] FIGHTER_DEATH_REACTIONS = {
		"Tsk, another one down...",
		"Should have trained harder.",
		"That looked painful.",
		"Not everyone can handle it out there.",
		"Rest in peace, warrior.",
	};

	private static final String[] HEALER_DEATH_REACTIONS = {
		"Oh no, someone fell!",
		"May the goddess guide their soul.",
		"I should have been there to help.",
		"Such a tragedy.",
		"The light will guide them home.",
	};

	private static final String[] MAGE_DEATH_REACTIONS = {
		"Death is just another transformation.",
		"Foolish to face such danger alone.",
		"The spirits will judge them.",
		"Another soul lost to the darkness.",
		"They should have been more prepared.",
	};

	// Greetings by class role
	private static final String[] FIGHTER_GREETINGS = {
		"Hey there.",
		"Need something?",
		"What's up?",
		"Stay sharp.",
		"Looking for a fight?",
	};

	private static final String[] HEALER_GREETINGS = {
		"Hello there!",
		"Good day!",
		"Blessings of the goddess.",
		"May the light guide you.",
		"How are you today?",
	};

	private static final String[] MAGE_GREETINGS = {
		"Greetings.",
		"Well met, traveler.",
		"The arcane welcomes you.",
		"Interesting, a visitor.",
		"Ah, someone new.",
	};

	private static final String[] MERCHANT_GREETINGS = {
		"Welcome!",
		"Good day to you!",
		"Come take a look!",
		"Best prices in town!",
		"Hello, friend!",
	};

	// Combat reactions
	private static final String[] FIGHTER_COMBAT = {
		"A fight! I want in!",
		"Get em!",
		"Let me at em!",
		"Fight! Fight! Fight!",
		"Show em what you got!",
	};

	private static final String[] HEALER_COMBAT = {
		"Be careful!",
		"Don't get hurt!",
		"I can help if needed!",
		"Stay safe!",
		"Fighting is dangerous!",
	};

	private static final String[] MAGE_COMBAT = {
		"How interesting.",
		"Such primitive combat.",
		"The dance of battle begins.",
		"Let us see their skill.",
		"Brute force, as usual.",
	};

	// Weather/time comments (deterministic by hour)
	private static final String[] MORNING_LINES = {
		"Beautiful morning, isn't it?",
		"A new day begins.",
		"Good morning!",
		"The sun is rising.",
		"Fresh start today.",
	};

	private static final String[] AFTERNOON_LINES = {
		"Lovely afternoon.",
		"The sun is high today.",
		"Perfect weather for adventure.",
		"Warm day, isn't it?",
		"Busy afternoon in town.",
	};

	private static final String[] EVENING_LINES = {
		"The sun is setting.",
		"A beautiful evening.",
		"Time to rest soon.",
		"The stars are coming out.",
		"Peaceful evening.",
	};

	private static final String[] NIGHT_LINES = {
		"Dark night tonight.",
		"Be careful in the dark.",
		"The moon is bright.",
		"Quiet night.",
		"Time for sleep.",
	};

	// Track cooldowns per NPC
	private final Map<Integer, Long> _lastNpcChat = new ConcurrentHashMap<>();
	private final Map<Integer, Map<Integer, Long>> _lastGreeting = new ConcurrentHashMap<>(); // npcId -> playerId -> time
	private final Map<Integer, Long> _lastWeather = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _lastDeathReaction = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _lastCombatReaction = new ConcurrentHashMap<>();

	// Track recently dead players for gossip (player name -> count)
	private final Map<String, Integer> _deathTally = new ConcurrentHashMap<>();
	private final Map<String, Long> _deathTallyTime = new ConcurrentHashMap<>();

	private static FakePlayerSocialManager _instance;

	public static FakePlayerSocialManager getInstance()
	{
		if (_instance == null)
		{
			_instance = new FakePlayerSocialManager();
		}
		return _instance;
	}

	private FakePlayerSocialManager()
	{
		ThreadPool.scheduleAtFixedRate(this::scan, SCAN_INTERVAL_MS, SCAN_INTERVAL_MS);
		LOGGER.info(getClass().getSimpleName() + ": Social immersion features enabled.");
	}

	// ---- Main scan ----

	private void scan()
	{
		final long now = System.currentTimeMillis();
		final int hour = (int) ((System.currentTimeMillis() / 3600000) % 24);

		// Collect all fake player NPCs
		final List<Npc> allNpcs = new ArrayList<>();
		final List<Player> allPlayers = new ArrayList<>();
		for (WorldObject obj : World.getInstance().getVisibleObjects())
		{
			if (obj instanceof Npc)
			{
				final Npc npc = (Npc) obj;
				if (npc.isFakePlayer() && !npc.isDead())
				{
					allNpcs.add(npc);
				}
			}
			else if (obj instanceof Player)
			{
				allPlayers.add((Player) obj);
			}
		}

		if (allNpcs.isEmpty())
		{
			return;
		}

		// Process each NPC
		for (Npc npc : allNpcs)
		{
			final int npcObjId = npc.getObjectId();
			final int classRole = getClassRole(npc);

			// 1. NPC→NPC conversation (every 2 min)
			tryNpcConversation(npc, npcObjId, classRole, allNpcs, now);

			// 2. Auto greetings (every 5 min per player)
			tryGreeting(npc, npcObjId, classRole, allPlayers, now);

			// 3. Death reaction (every 1 min)
			tryDeathReaction(npc, npcObjId, classRole, allPlayers, now);

			// 4. Weather/time comment (every 10 min)
			tryWeatherComment(npc, npcObjId, classRole, hour, now);

			// 5. Combat reaction (every 30s)
			tryCombatReaction(npc, npcObjId, classRole, allPlayers, now);
		}

		// Clean up stale death tally (older than 30 min)
		final long staleThreshold = now - 1800000;
		_deathTallyTime.entrySet().removeIf(e -> e.getValue() < staleThreshold);
		_deathTally.keySet().retainAll(_deathTallyTime.keySet());
	}

	// ---- NPC→NPC conversations ----

	private void tryNpcConversation(Npc npc, int npcObjId, int classRole, List<Npc> allNpcs, long now)
	{
		final Long lastChat = _lastNpcChat.get(npcObjId);
		if ((lastChat != null) && (now - lastChat < NPC_CHAT_COOLDOWN))
		{
			return;
		}
		// Only 30% chance each scan to avoid excessive chatter
		if (Rnd.get(100) >= 30)
		{
			return;
		}

		// Find another NPC nearby
		final Npc target = findNearbyNpc(npc, allNpcs, NPC_SOCIAL_RANGE, npcObjId);
		if (target == null)
		{
			return;
		}

		// Pick a topic based on the NPC's class role
		final String[][] topicPairs = getTopicsForRole(classRole);
		if (topicPairs != null)
		{
			_lastNpcChat.put(npcObjId, now);
			_lastNpcChat.put(target.getObjectId(), now); // also set cooldown for the target

			final String[] pair = topicPairs[Rnd.get(topicPairs.length)];
			final String topic = pair[Rnd.get(pair.length)];
			final String message = "Hey " + target.getName() + ", " + topic;
			npc.broadcastPacket(new CreatureSay(npc, ChatType.GENERAL, npc.getName(), message));
		}
	}

	// ---- Auto greetings ----

	private void tryGreeting(Npc npc, int npcObjId, int classRole, List<Player> players, long now)
	{
		// Only 15% chance per scan
		if (Rnd.get(100) >= 15)
		{
			return;
		}

		Map<Integer, Long> greeted = _lastGreeting.get(npcObjId);
		if (greeted == null)
		{
			greeted = new HashMap<>();
			_lastGreeting.put(npcObjId, greeted);
		}

		for (Player player : players)
		{
			if (player.isInCombat() || player.isDead())
			{
				continue;
			}
			final double dist = npc.calculateDistance2D(player);
			if (dist > PLAYER_NEAR_RANGE)
			{
				continue;
			}

			final Long lastGreet = greeted.get(player.getObjectId());
			if ((lastGreet != null) && (now - lastGreet < GREETING_COOLDOWN))
			{
				continue;
			}

			// Pick greeting based on role
			final String[] greetings = getGreetingsForRole(classRole);
			if (greetings != null)
			{
				greeted.put(player.getObjectId(), now);
				final String greeting = greetings[Rnd.get(greetings.length)];
				player.sendPacket(new CreatureSay(npc, ChatType.WHISPER, npc.getName(), greeting));
				return; // Only greet one player per scan
			}
		}
	}

	// ---- Death reactions ----

	/**
	 * Called externally when a player dies nearby. Records the death for gossip and triggers reactions.
	 */
	public void notifyPlayerDeath(Player player)
	{
		// Track for gossip
		final String name = player.getName();
		_deathTally.merge(name, 1, Integer::sum);
		_deathTallyTime.put(name, System.currentTimeMillis());
	}

	private void tryDeathReaction(Npc npc, int npcObjId, int classRole, List<Player> players, long now)
	{
		final Long lastReaction = _lastDeathReaction.get(npcObjId);
		if ((lastReaction != null) && (now - lastReaction < DEATH_REACTION_COOLDOWN))
		{
			return;
		}
		// Only 25% chance
		if (Rnd.get(100) >= 25)
		{
			return;
		}

		// Find a dead player nearby
		for (Player player : players)
		{
			if (!player.isDead())
			{
				continue;
			}
			final double dist = npc.calculateDistance2D(player);
			if (dist > DEATH_WITNESS_RANGE)
			{
				continue;
			}

			_lastDeathReaction.put(npcObjId, now);
			notifyPlayerDeath(player);

			final String[] reactions = getDeathReactionsForRole(classRole);
			if (reactions != null)
			{
				final String reaction = reactions[Rnd.get(reactions.length)];
				npc.broadcastPacket(new CreatureSay(npc, ChatType.GENERAL, npc.getName(), reaction));
				return;
			}
		}
	}

	// ---- Weather/time comments ----

	private void tryWeatherComment(Npc npc, int npcObjId, int classRole, int hour, long now)
	{
		final Long lastWeather = _lastWeather.get(npcObjId);
		if ((lastWeather != null) && (now - lastWeather < WEATHER_COOLDOWN))
		{
			return;
		}
		// Only 10% chance
		if (Rnd.get(100) >= 10)
		{
			return;
		}

		_lastWeather.put(npcObjId, now);

		final String[] lines;
		if (hour >= 6 && hour < 12)
		{
			lines = MORNING_LINES;
		}
		else if (hour >= 12 && hour < 18)
		{
			lines = AFTERNOON_LINES;
		}
		else if (hour >= 18 && hour < 22)
		{
			lines = EVENING_LINES;
		}
		else
		{
			lines = NIGHT_LINES;
		}

		final String line = lines[Rnd.get(lines.length)];
		npc.broadcastPacket(new CreatureSay(npc, ChatType.GENERAL, npc.getName(), line));
	}

	// ---- Combat reactions ----

	private void tryCombatReaction(Npc npc, int npcObjId, int classRole, List<Player> players, long now)
	{
		final Long lastCombat = _lastCombatReaction.get(npcObjId);
		if ((lastCombat != null) && (now - lastCombat < COMBAT_REACTION_COOLDOWN))
		{
			return;
		}
		// Only 12% chance
		if (Rnd.get(100) >= 12)
		{
			return;
		}

		// Find a player in combat nearby
		for (Player player : players)
		{
			if (!player.isInCombat() && !player.isAttackingNow())
			{
				continue;
			}
			final double dist = npc.calculateDistance2D(player);
			if (dist > COMBAT_WITNESS_RANGE)
			{
				continue;
			}

			_lastCombatReaction.put(npcObjId, now);

			final String[] reactions = getCombatReactionsForRole(classRole);
			if (reactions != null)
			{
				final String reaction = reactions[Rnd.get(reactions.length)];
				npc.broadcastPacket(new CreatureSay(npc, ChatType.GENERAL, npc.getName(), reaction));
				return;
			}
		}
	}

	// ---- Helper methods ----

	private Npc findNearbyNpc(Npc npc, List<Npc> allNpcs, int range, int excludeObjId)
	{
		for (Npc other : allNpcs)
		{
			if (other.getObjectId() == excludeObjId)
			{
				continue;
			}
			final double dist = npc.calculateDistance2D(other);
			if (dist <= range)
			{
				return other;
			}
		}
		return null;
	}

	/**
	 * Determine the class role for an NPC: 0=fighter, 1=mage, 2=healer, 3=merchant, 4=generic.
	 */
	private int getClassRole(Npc npc)
	{
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		if (look == null)
		{
			return 4;
		}
		final int classId = look.getPlayerClass().getId();

		// Healers
		if (classId == 13 || classId == 31 || classId == 44)
		{
			return 2;
		}
		// Mages
		if (classId == 36 || classId == 37 || classId == 38 || classId == 39
			|| classId == 48 || classId == 49 || classId == 50)
		{
			return 1;
		}
		// Merchants (dwarf classes)
		if (classId == 53 || classId == 54 || classId == 55 || classId == 56)
		{
			return 3;
		}
		// Fighters (everything else)
		return 0;
	}

	private String[][] getTopicsForRole(int role)
	{
		switch (role)
		{
			case 0: return FIGHTER_TOPICS;
			case 1: return MAGE_TOPICS;
			case 2: return HEALER_TOPICS;
			case 3: return MERCHANT_TOPICS;
			default: return null;
		}
	}

	private String[] getGreetingsForRole(int role)
	{
		switch (role)
		{
			case 0: return FIGHTER_GREETINGS;
			case 1: return MAGE_GREETINGS;
			case 2: return HEALER_GREETINGS;
			case 3: return MERCHANT_GREETINGS;
			default: return null;
		}
	}

	private String[] getDeathReactionsForRole(int role)
	{
		switch (role)
		{
			case 0: return FIGHTER_DEATH_REACTIONS;
			case 1: return MAGE_DEATH_REACTIONS;
			case 2: return HEALER_DEATH_REACTIONS;
			default: return null;
		}
	}

	private String[] getCombatReactionsForRole(int role)
	{
		switch (role)
		{
			case 0: return FIGHTER_COMBAT;
			case 1: return MAGE_COMBAT;
			case 2: return HEALER_COMBAT;
			default: return null;
		}
	}
}