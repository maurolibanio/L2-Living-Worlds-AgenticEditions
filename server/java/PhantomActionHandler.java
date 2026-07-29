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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.managers.FakePlayerChatManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * Handles player requests for phantom actions: buff, heal, res, follow, party, trade, etc.
 * Any eligible NPC in range can respond. NPCs walk to the player before executing the action.
 * Eligibility is determined by the NPC's classId (buffer/healer classes).
 * Skills are cast directly from SkillData, not from the NPC's skill list.
 *
 * Phase 1 features:
 * - MP/HP/death checks before committing (honest failure messages)
 * - Repeating walk-to-player loop (works with behavior manager's tick override)
 * - Honest messages: "I'm in combat", "Out of mana", "Come closer"
 */
public class PhantomActionHandler
{
	private static final Logger LOGGER = Logger.getLogger(PhantomActionHandler.class.getName());

	private static final int ACTION_RANGE = 1200;
	private static final int EXECUTE_RANGE = 250;
	private static final int MAX_RESPONDERS = 3;

	// Walk loop: re-assert movement every 150ms to fight the behavior manager's tick override
	private static final long WALK_INTERVAL_MS = 150;
	private static final long WALK_TIMEOUT_MS = 8000; // give up after 8s of walking

	// Skill IDs and levels for Interlude
	private static final int HEAL_SKILL_ID = 1011;
	private static final int HEAL_SKILL_LEVEL = 35;
	private static final int BATTLE_HEAL_SKILL_ID = 1219;
	private static final int BATTLE_HEAL_LEVEL = 25;
	private static final int GREATER_HEAL_SKILL_ID = 1217;
	private static final int GREATER_HEAL_LEVEL = 25;
	private static final int RESURRECT_SKILL_ID = 1016;
	private static final int RESURRECT_LEVEL = 35;

	// Buff skill IDs and levels
	private static final int MIGHT_ID = 101;
	private static final int MIGHT_LEVEL = 35;
	private static final int SHIELD_ID = 104;
	private static final int SHIELD_LEVEL = 35;
	private static final int WIND_WALK_ID = 105;
	private static final int WIND_WALK_LEVEL = 35;
	private static final int HASTE_ID = 106;
	private static final int HASTE_LEVEL = 35;
	private static final int BLESS_BODY_ID = 107;
	private static final int BLESS_BODY_LEVEL = 35;
	private static final int BLESS_SOUL_ID = 108;
	private static final int BLESS_SOUL_LEVEL = 35;
	private static final int ACUMEN_ID = 109;
	private static final int ACUMEN_LEVEL = 35;
	private static final int BERSERKER_ID = 110;
	private static final int BERSERKER_LEVEL = 4;
	private static final int GREATER_MIGHT_ID = 268;
	private static final int GREATER_MIGHT_LEVEL = 4;
	private static final int GREATER_SHIELD_ID = 269;
	private static final int GREATER_SHIELD_LEVEL = 4;
	private static final int BLESSED_SOUL_ID = 270;
	private static final int BLESSED_SOUL_LEVEL = 4;

	// Item IDs
	private static final int ADENA_ID = 57;
	private static final int SOULSHOT_NO_GRADE_ID = 1835;
	private static final int SPIRITSHOT_NO_GRADE_ID = 2509;
	private static final int HP_POTION_ID = 1061;
	private static final int MP_POTION_ID = 1062;

	// Class IDs that can buff/heal/res (Interlude)
	private static final Set<Integer> BUFFER_CLASSES = new HashSet<>();
	private static final Set<Integer> HEALER_CLASSES = new HashSet<>();
	private static final Set<Integer> RES_CLASSES = new HashSet<>();
	static
	{
		BUFFER_CLASSES.add(13); // Elder
		BUFFER_CLASSES.add(31); // Elven Elder
		BUFFER_CLASSES.add(44); // Shillien Elder
		BUFFER_CLASSES.add(48); // Prophet
		BUFFER_CLASSES.add(49); // Warcryer
		BUFFER_CLASSES.add(50); // Overlord
		BUFFER_CLASSES.add(36); // Sorcerer
		BUFFER_CLASSES.add(37); // Necromancer / Spellhowler
		BUFFER_CLASSES.add(38); // Spellsinger
		BUFFER_CLASSES.add(39); // Spellhowler

		HEALER_CLASSES.add(13); // Elder
		HEALER_CLASSES.add(31); // Elven Elder
		HEALER_CLASSES.add(44); // Shillien Elder

		RES_CLASSES.add(13); // Elder
		RES_CLASSES.add(31); // Elven Elder
		RES_CLASSES.add(44); // Shillien Elder
	}

	private static PhantomActionHandler _instance;

	public static PhantomActionHandler getInstance()
	{
		if (_instance == null)
		{
			_instance = new PhantomActionHandler();
		}
		return _instance;
	}

	private PhantomActionHandler()
	{
	}

	// ---- Class eligibility helpers ----

	private int getFakePlayerClassId(Npc npc)
	{
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		if (look != null)
		{
			return look.getPlayerClass().getId();
		}
		return -1;
	}

	private boolean canBuff(Npc npc)
	{
		return BUFFER_CLASSES.contains(getFakePlayerClassId(npc));
	}

	private boolean canHeal(Npc npc)
	{
		return HEALER_CLASSES.contains(getFakePlayerClassId(npc));
	}

	private boolean canResurrect(Npc npc)
	{
		return RES_CLASSES.contains(getFakePlayerClassId(npc));
	}

	// ---- Request detection ----

	private boolean isBuffRequest(String text)
	{
		return text.contains("buff") || text.contains("might") || text.contains("shield") || text.contains("haste")
			|| text.contains("wind walk") || text.contains("acumen") || text.contains("berserker")
			|| text.contains("bless");
	}

	private boolean isHealRequest(String text)
	{
		return text.contains("heal") || text.contains("cure") || text.contains("hp") || text.contains("hurt")
			|| text.contains("injured") || text.contains("recover");
	}

	private boolean isResRequest(String text)
	{
		return text.contains("res") || text.contains("ress") || text.contains("resurrect") || text.contains("dead")
			|| text.contains("revive") || text.contains("death") || text.contains("die");
	}

	private boolean isFollowRequest(String text)
	{
		return text.contains("follow") || text.contains("come") || text.contains("with me");
	}

	private boolean isPartyRequest(String text)
	{
		return text.contains("party") || text.contains("group") || text.contains("team") || text.contains("invite");
	}

	private boolean isAdenaRequest(String text)
	{
		return text.contains("adena") || text.contains("money") || text.contains("gold") || text.contains("poor")
			|| text.contains("broke") || text.contains("need adena") || text.contains("donate");
	}

	private boolean isItemRequest(String text)
	{
		return text.contains("item") || text.contains("soulshot") || text.contains("spiritshot") || text.contains("potion")
			|| text.contains("shot") || text.contains("give") || text.contains("equip") || text.contains("weapon");
	}

	// ---- NPC discovery ----

	private List<Npc> findAllEligiblePhantoms(Player player)
	{
		final List<Npc> result = new ArrayList<>();
		final List<WorldObject> visibleObjects = new ArrayList<>(World.getInstance().getVisibleObjects());
		for (WorldObject obj : visibleObjects)
		{
			if (!(obj instanceof Npc))
			{
				continue;
			}
			final Npc npc = (Npc) obj;
			if (!npc.isFakePlayer())
			{
				continue;
			}
			if (npc.isInCombat() || npc.isDead())
			{
				continue;
			}
			final double dist = npc.calculateDistance2D(player);
			if (dist <= ACTION_RANGE)
			{
				result.add(npc);
			}
		}
		return result;
	}

	private Npc findNamedPhantom(Player player, String name)
	{
		final List<WorldObject> visibleObjects = new ArrayList<>(World.getInstance().getVisibleObjects());
		for (WorldObject obj : visibleObjects)
		{
			if (!(obj instanceof Npc))
			{
				continue;
			}
			final Npc npc = (Npc) obj;
			if (npc.isFakePlayer() && npc.getName().equalsIgnoreCase(name))
			{
				final double dist = npc.calculateDistance2D(player);
				if (dist <= ACTION_RANGE)
				{
					return npc;
				}
			}
		}
		return null;
	}

	/**
	 * Converts a FakePlayer NPC into a full phantom party member. The NPC is despawned from the world
	 * and a clientless Player character with the same name, class, level and appearance is created via
	 * PhantomManager, spawned at the FakePlayer's location, befriended with the player, and immediately
	 * recruited into the player's party.
	 */
	public void convertToPhantom(Player player, Npc fakePlayer)
	{
		if ((player == null) || (fakePlayer == null) || !fakePlayer.isFakePlayer())
		{
			return;
		}

		final String name = fakePlayer.getName();
		final int level = fakePlayer.getLevel();
		final FakePlayerAppearance look = fakePlayer.getFakePlayerAppearance();
		if (look == null)
		{
			sendWhisper(fakePlayer, name, player, "I can't right now...");
			return;
		}

		final int classId = look.getPlayerClass().getId();
		final int sex = look.isFemale() ? 1 : 0;
		final int face = look.getFace();
		final int hairStyle = look.getHairStyle();
		final int hairColor = look.getHairColor();

		// Despawn the FakePlayer Npc - it is about to become a real Player.
		final String fpcName = name;
		fakePlayer.deleteMe();
		FakePlayerChatManager.invalidateBotCache(fpcName);

		// Create a phantom with the same identity via PhantomManager.
		final String result = PhantomManager.getInstance().craftFriend(player, name, String.valueOf(classId), level, sex, face, hairStyle, hairColor);
		if (!result.startsWith("Created your friend"))
		{
			player.sendPacket(new CreatureSay(player, ChatType.WHISPER, fpcName, "Something went wrong... " + result));
			return;
		}

		// Find the newly-spawned phantom by name in the world.
		final Player phantom = World.getInstance().getPlayer(name);
		if (phantom == null)
		{
			return;
		}

		// Determine the party role from the character's class.
		final PartyRole role = PhantomManager.roleForClass(look.getPlayerClass());
		if (role == null)
		{
			return;
		}

		// Recruit the phantom into the player's party.
		PhantomPartyManager.getInstance().onConvertedFriend(player, phantom, role);
	}

	// ---- Generosity ----

	private boolean isGenerous()
	{
		return Rnd.get(100) < 70;
	}

	// ---- Walk-to-player with repeating loop ----
	// Uses a scheduled repeating task that calls moveToLocation() every 150ms.
	// The behavior manager's tick() overrides AI intentions, but our loop re-asserts
	// the movement frequently enough that the NPC will still walk toward the player.

	private void walkTowardAndExecute(Player player, Npc phantom, Runnable action)
	{
		final double dist = phantom.calculateDistance2D(player);
		if (dist <= EXECUTE_RANGE)
		{
			// Already close enough, execute immediately
			action.run();
			return;
		}

		final long startTime = System.currentTimeMillis();
		final ScheduledFuture<?>[] taskRef = new ScheduledFuture<?>[1];

		final Runnable walkLoop = new Runnable()
		{
			@Override
			public void run()
			{
				// Safety checks
				if (phantom.isDead() || phantom.isDecayed())
				{
					cancelTask();
					return;
				}
				if (System.currentTimeMillis() - startTime > WALK_TIMEOUT_MS)
				{
					// Couldn't reach in time - give up
					final String name = phantom.getName();
					sendWhisper(phantom, name, player, "I can't reach you, come closer!");
					cancelTask();
					return;
				}

				final double currentDist = phantom.calculateDistance2D(player);
				if (currentDist <= EXECUTE_RANGE)
				{
					// Arrived! Execute the action
					cancelTask();
					action.run();
					return;
				}

				// Re-assert movement toward the player
				phantom.setRunning();
				phantom.moveToLocation(player.getX(), player.getY(), player.getZ(), 40);
				// Also set AI intention as a fallback
				phantom.getAI().setIntention(Intention.MOVE_TO, player);
			}

			private void cancelTask()
			{
				if ((taskRef[0] != null) && !taskRef[0].isCancelled())
				{
					taskRef[0].cancel(false);
				}
			}
		};

		// Start the first movement immediately
		phantom.setRunning();
		phantom.moveToLocation(player.getX(), player.getY(), player.getZ(), 40);
		phantom.getAI().setIntention(Intention.MOVE_TO, player);

		// Schedule the repeating loop
		taskRef[0] = ThreadPool.scheduleAtFixedRate(walkLoop, WALK_INTERVAL_MS, WALK_INTERVAL_MS);
	}

	// ---- Casting helpers ----

	private Skill getSkill(int skillId, int level)
	{
		return SkillData.getInstance().getSkill(skillId, level);
	}

	private void castSkillOnPlayer(Player player, Npc phantom, int skillId, int level)
	{
		final Skill skill = getSkill(skillId, level);
		if (skill != null)
		{
			phantom.setTarget(player);
			phantom.doCast(skill);
		}
	}

	/**
	 * Check if the NPC has enough MP to cast a skill. If not, send a message and return false.
	 */
	private boolean hasMpForSkill(Npc phantom, int skillId, int level)
	{
		final Skill skill = getSkill(skillId, level);
		if (skill == null)
		{
			return false;
		}
		final int mpCost = skill.getMpConsume() + skill.getMpInitialConsume();
		if (phantom.getCurrentMp() < mpCost)
		{
			sendWhisper(phantom, phantom.getName(), null, "I'm out of mana, sorry!");
			return false;
		}
		return true;
	}

	/**
	 * Check if the NPC has enough HP to cast a skill.
	 */
	private boolean hasHpForSkill(Npc phantom, int skillId, int level)
	{
		final Skill skill = getSkill(skillId, level);
		if (skill == null)
		{
			return false;
		}
		if (phantom.getCurrentHp() <= skill.getHpConsume())
		{
			return false;
		}
		return true;
	}

	// ---- Main entry points ----

	public boolean handlePlayerChat(Player player, String text)
	{
		if ((player == null) || (text == null))
		{
			return false;
		}

		final String lower = text.toLowerCase().trim();
		final List<Npc> phantoms = findAllEligiblePhantoms(player);

		if (phantoms.isEmpty())
		{
			return false;
		}

		if (isBuffRequest(lower))
		{
			int count = 0;
			for (Npc phantom : phantoms)
			{
				if (count >= MAX_RESPONDERS)
				{
					break;
				}
				if (canBuff(phantom))
				{
					if (!hasMpForSkill(phantom, HASTE_ID, HASTE_LEVEL))
					{
						continue; // skip, try another buffer
					}
					executeBuff(player, phantom);
					count++;
				}
			}
			return count > 0;
		}
		if (isHealRequest(lower))
		{
			// Check if the player actually needs healing
			if (!player.isInCombat() && (player.getCurrentHp() >= player.getMaxHp() * 0.95))
			{
				// Player is fine, don't respond
				return false;
			}

			int count = 0;
			for (Npc phantom : phantoms)
			{
				if (count >= MAX_RESPONDERS)
				{
					break;
				}
				if (canHeal(phantom))
				{
					if (!hasMpForSkill(phantom, GREATER_HEAL_SKILL_ID, GREATER_HEAL_LEVEL))
					{
						continue;
					}
					executeHeal(player, phantom);
					count++;
				}
			}
			return count > 0;
		}
		if (isResRequest(lower))
		{
			if (!player.isDead())
			{
				// Player is not dead, don't respond to res requests
				return false;
			}
			for (Npc phantom : phantoms)
			{
				if (canResurrect(phantom))
				{
					if (!hasMpForSkill(phantom, RESURRECT_SKILL_ID, RESURRECT_LEVEL))
					{
						continue;
					}
					executeResurrect(player, phantom);
					return true;
				}
			}
			return false;
		}
		if (isFollowRequest(lower))
		{
			// Use the behavior manager's follow system for reliable continuous following
			final Npc nearest = phantoms.get(0);
			final boolean ok = FakePlayerBehaviorManager.getInstance().requestFollow(nearest, player, 2);
			if (ok)
			{
				sendWhisper(nearest, nearest.getName(), player, "Coming!");
			}
			return ok;
		}
		if (isPartyRequest(lower))
		{
			final Npc nearest = phantoms.get(0);
			executePartyInvite(player, nearest);
			return true;
		}
		if (isAdenaRequest(lower))
		{
			if (!isGenerous())
			{
				return false;
			}
			final Npc chosen = phantoms.get(Rnd.get(phantoms.size()));
			executeGiveAdena(player, chosen);
			return true;
		}
		if (isItemRequest(lower))
		{
			if (!isGenerous())
			{
				return false;
			}
			final Npc chosen = phantoms.get(Rnd.get(phantoms.size()));
			executeGiveItem(player, chosen);
			return true;
		}

		return false;
	}

	public boolean handleBrainAction(Player player, String fpcName, String actionType)
	{
		if ((player == null) || (actionType == null))
		{
			return false;
		}

		final Npc phantom = findNamedPhantom(player, fpcName);
		if (phantom == null)
		{
			return false;
		}

		switch (actionType.toUpperCase())
		{
			case "BUFF":
			{
				if (canBuff(phantom))
				{
					if (!hasMpForSkill(phantom, HASTE_ID, HASTE_LEVEL))
					{
						return false;
					}
					executeBuff(player, phantom);
					return true;
				}
				return false;
			}
			case "HEAL":
			{
				if (canHeal(phantom))
				{
					if (!hasMpForSkill(phantom, GREATER_HEAL_SKILL_ID, GREATER_HEAL_LEVEL))
					{
						return false;
					}
					executeHeal(player, phantom);
					return true;
				}
				return false;
			}
			case "RES":
			case "RESS":
			case "RESURRECT":
			{
				if (canResurrect(phantom))
				{
					if (!player.isDead())
					{
						return false;
					}
					if (!hasMpForSkill(phantom, RESURRECT_SKILL_ID, RESURRECT_LEVEL))
					{
						return false;
					}
					executeResurrect(player, phantom);
					return true;
				}
				return false;
			}
			case "FOLLOW":
			{
				final boolean ok = FakePlayerBehaviorManager.getInstance().requestFollow(phantom, player, 2);
				if (ok)
				{
					sendWhisper(phantom, fpcName, player, "Coming!");
				}
				return ok;
			}
			case "PARTY":
			{
				executePartyInvite(player, phantom);
				return true;
			}
			case "ADENA":
			{
				executeGiveAdena(player, phantom);
				return true;
			}
			case "ITEM":
			{
				executeGiveItem(player, phantom);
				return true;
			}
			case "RECRUIT":
			case "CONVERT":
			case "CONVERT_TO_PHANTOM":
			{
				convertToPhantom(player, phantom);
				return true;
			}
		}

		return false;
	}

	// ---- Action execution ----

	private void executeBuff(Player player, Npc phantom)
	{
		final String fpcName = phantom.getName();
		final int classId = getFakePlayerClassId(phantom);
		sendWhisper(phantom, fpcName, player, "I will buff you!");

		walkTowardAndExecute(player, phantom, () ->
		{
			castSkillOnPlayer(player, phantom, MIGHT_ID, MIGHT_LEVEL);
			sleep(400);
			castSkillOnPlayer(player, phantom, SHIELD_ID, SHIELD_LEVEL);
			sleep(400);
			castSkillOnPlayer(player, phantom, WIND_WALK_ID, WIND_WALK_LEVEL);
			sleep(400);
			castSkillOnPlayer(player, phantom, HASTE_ID, HASTE_LEVEL);
			sleep(400);

			if (HEALER_CLASSES.contains(classId))
			{
				castSkillOnPlayer(player, phantom, BLESS_BODY_ID, BLESS_BODY_LEVEL);
				sleep(400);
				castSkillOnPlayer(player, phantom, BLESS_SOUL_ID, BLESS_SOUL_LEVEL);
				sleep(400);
			}

			castSkillOnPlayer(player, phantom, ACUMEN_ID, ACUMEN_LEVEL);
			sleep(400);
			castSkillOnPlayer(player, phantom, BERSERKER_ID, BERSERKER_LEVEL);
			sleep(400);

			if (HEALER_CLASSES.contains(classId))
			{
				castSkillOnPlayer(player, phantom, GREATER_MIGHT_ID, GREATER_MIGHT_LEVEL);
				sleep(400);
				castSkillOnPlayer(player, phantom, GREATER_SHIELD_ID, GREATER_SHIELD_LEVEL);
				sleep(400);
				castSkillOnPlayer(player, phantom, BLESSED_SOUL_ID, BLESSED_SOUL_LEVEL);
				sleep(400);
			}

			sendWhisper(phantom, fpcName, player, "You are buffed!");
		});
	}

	private void executeHeal(Player player, Npc phantom)
	{
		final String fpcName = phantom.getName();
		sendWhisper(phantom, fpcName, player, "Healing you!");

		walkTowardAndExecute(player, phantom, () ->
		{
			castSkillOnPlayer(player, phantom, GREATER_HEAL_SKILL_ID, GREATER_HEAL_LEVEL);
			sleep(400);
			castSkillOnPlayer(player, phantom, BATTLE_HEAL_SKILL_ID, BATTLE_HEAL_LEVEL);
			sleep(400);
			castSkillOnPlayer(player, phantom, HEAL_SKILL_ID, HEAL_SKILL_LEVEL);
			sendWhisper(phantom, fpcName, player, "Feeling better?");
		});
	}

	private void executeResurrect(Player player, Npc phantom)
	{
		if (!player.isDead())
		{
			return;
		}

		final String fpcName = phantom.getName();
		sendWhisper(phantom, fpcName, player, "Resurrecting you!");

		walkTowardAndExecute(player, phantom, () ->
		{
			castSkillOnPlayer(player, phantom, RESURRECT_SKILL_ID, RESURRECT_LEVEL);
			sendWhisper(phantom, fpcName, player, "Welcome back!");
		});
	}

	private void executePartyInvite(Player player, Npc phantom)
	{
		if (player.isInParty())
		{
			sendWhisper(phantom, phantom.getName(), player, "You are already in a party!");
			return;
		}
		sendWhisper(phantom, phantom.getName(), player, "I cannot join a party. Ask other players!");
	}

	private void executeGiveAdena(Player player, Npc phantom)
	{
		final String fpcName = phantom.getName();
		final int amount = 500 + Rnd.get(1500);
		sendWhisper(phantom, fpcName, player, "Here, take this!");

		walkTowardAndExecute(player, phantom, () ->
		{
			player.addAdena(ItemProcessType.REWARD, amount, phantom, true);
			sendWhisper(phantom, fpcName, player, "Here is " + amount + " adena. Hope it helps!");
		});
	}

	private void executeGiveItem(Player player, Npc phantom)
	{
		final String fpcName = phantom.getName();
		final int r = Rnd.get(4);
		final int itemId;
		final int count;
		final String itemName;
		switch (r)
		{
			case 0:
				itemId = SOULSHOT_NO_GRADE_ID;
				count = 200 + Rnd.get(300);
				itemName = "Soulshot";
				break;
			case 1:
				itemId = SPIRITSHOT_NO_GRADE_ID;
				count = 200 + Rnd.get(300);
				itemName = "Spiritshot";
				break;
			case 2:
				itemId = HP_POTION_ID;
				count = 5 + Rnd.get(15);
				itemName = "HP Potion";
				break;
			default:
				itemId = MP_POTION_ID;
				count = 3 + Rnd.get(10);
				itemName = "MP Potion";
				break;
		}

		sendWhisper(phantom, fpcName, player, "Here, take this!");

		walkTowardAndExecute(player, phantom, () ->
		{
			player.addItem(ItemProcessType.REWARD, itemId, count, phantom, true);
			sendWhisper(phantom, fpcName, player, "Here is " + count + "x " + itemName + "!");
		});
	}

	// ---- Chat helpers ----

	/**
	 * Send a whisper (private chat) from the NPC to the player.
	 * If player is null, broadcasts to all nearby players (for NPC→NPC conversations).
	 */
	private void sendWhisper(Npc phantom, String fpcName, Player player, String message)
	{
		if ((message == null) || message.isEmpty())
		{
			return;
		}
		if (player != null)
		{
			player.sendPacket(new CreatureSay(phantom, ChatType.WHISPER, fpcName, message));
		}
	}

	/**
	 * Send a SAY chat from the NPC (visible to everyone nearby).
	 */
	void sendSay(Npc phantom, String message)
	{
		if ((message == null) || message.isEmpty())
		{
			return;
		}
		phantom.broadcastPacket(new CreatureSay(phantom, ChatType.GENERAL, phantom.getName(), message));
	}

	private void sleep(long ms)
	{
		try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
	}
}