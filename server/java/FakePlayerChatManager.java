/*
 * Copyright (c) 2013 L2jMobius
 * ... (license header unchanged) ...
 */
package org.l2jmobius.gameserver.managers;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.managers.FakePlayerBehaviorManager;
import org.l2jmobius.gameserver.managers.PhantomManager.PartyRole;
import org.l2jmobius.gameserver.managers.PhantomManager.Recruit;
import org.l2jmobius.gameserver.managers.PhantomActionHandler;
import org.l2jmobius.gameserver.model.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.data.holders.FakePlayerChatHolder;
import org.l2jmobius.gameserver.data.xml.FakePlayerData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.WorldObject;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.actor.Npc;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.enums.creature.Race;
import org.l2jmobius.gameserver.model.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerAppearance;
import org.l2jmobius.gameserver.model.actor.holders.npc.FakePlayerStoreItem;
import org.l2jmobius.gameserver.model.item.ItemTemplate;
import org.l2jmobius.gameserver.model.spawns.Spawn;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.L2FriendSay;
import org.l2jmobius.gameserver.helpers.BotContext;

/**
 * @author Mobius
 */
public class FakePlayerChatManager implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerChatManager.class.getName());
	
	private static final List<FakePlayerChatHolder> MESSAGES = new ArrayList<>();
	private static final int MIN_DELAY = 5000;
	private static final int MAX_DELAY = 15000;
	// Friend PMs are a direct 1:1 chat, so they use a much shorter "read + react" pause than the ambient
	// whisper delays above - a friend replies in a beat or two, then the length-scaled typing time is added
	// on top (typingDelayMillis). This lands a reply in roughly 1-4s, the way a person actually messages back.
	private static final int FRIEND_THINK_MIN = 800;
	private static final int FRIEND_THINK_MAX = 2600;
	
	// LLM bridge
	private static final HttpClient BRAIN_HTTP = HttpClient.newHttpClient();
	// FakePlayersConfig.FAKE_PLAYER_BRAIN_URL is now loaded from FakePlayers.ini via FakePlayersConfig.FAKE_PLAYER_FakePlayersConfig.FAKE_PLAYER_BRAIN_URL
	// Per-request timeout waiting for the brain's reply. A local Ollama model can take ~30s per generation, and
	// the old 20s cap silently dropped every reply (Java gave up, then the brain returned 200 to nobody), which
	// looked like "bots never reply in-game". Sized to outlast a slow local model; a fast provider (DeepSeek) or
	// a smaller Ollama model returns well under this, so it only ever bites when generation is genuinely slow.
	private static final int BRAIN_TIMEOUT_SECONDS = 45;
	
	// ===== Social tuning knobs =====
	private static final boolean SOCIAL_ENABLED = true;
	private static final int REPLY_CHANCE_TO_PLAYER = 3; // % chance a nearby bot reacts to a player (throttle 1)
	private static final int REPLY_CHANCE_TO_BOT = 0; // % chance bot-to-bot (throttle 2: damping)
	private static final int MAX_REPLIERS = 1; // at most N bots answer one line
	private static final int REPLY_STAGGER_MS = 4000; // each extra replier waits this much longer, so a 2nd bot
	// clearly chimes in AFTER the first instead of a simultaneous chorus (the most bot-like tell on a channel).
	// Rough human "typing" time for a chat line: a short pause that scales with length, so a one-word reply pops
	// out fast and a long sentence takes a beat longer - bots no longer all answer at one fixed instant speed.
	private static final long TYPE_BASE_MS = 400;
	private static final long TYPE_PER_CHAR_MS = 45;
	private static final long TYPE_MAX_MS = 4000;
	private static final int SOCIAL_RANGE = 3000; // trade: how close a bot must be to react
	private static final int SAY_RANGE = 1250; // say: local hearing/broadcast range
	private static final int MAX_MESSAGES_PER_MINUTE = 3; // public/social rate cap: shout/trade/say/ambient banter
	private static final int MAX_TRADE_OFFERS_PER_MINUTE = 3; // important WTB/WTS responder PM cap
	private static final long AMBIENT_INTERVAL = 900000; // spontaneous trade line every ~4 min
	private static final long SHOUT_AMBIENT_INTERVAL = 1200000; // spontaneous shout (LFM / chit-chat) every ~5 min
	private static final AtomicInteger MESSAGES_THIS_MINUTE = new AtomicInteger();
	private static final AtomicInteger TRADE_OFFERS_THIS_MINUTE = new AtomicInteger();
	private static boolean SOCIAL_STARTED = false;

	// Structured deal context kept while a trade responder is negotiating with a player.
	// Keyed by playerName|botName so follow-up whispers can include the same exact item/count/price.
	private static final Map<String, BrainDealContext> ACTIVE_DEALS = new ConcurrentHashMap<>();

	private static class BrainDealContext
	{
		final String side; // Bot perspective: SELL means bot sells to player, BUY means bot buys from player.
		final String item;
		final int count;
		final int unitPrice;
		final long totalPrice;

		BrainDealContext(String side, String item, int count, int unitPrice)
		{
			this.side = side;
			this.item = item;
			this.count = Math.max(1, count);
			this.unitPrice = Math.max(1, unitPrice);
			totalPrice = (long) this.count * this.unitPrice;
		}
	}

	/**
	 * A bot's real self-knowledge (level, class, race, gear grade), sent to the brain so it answers "what lvl/class
	 * are you?" truthfully instead of inventing an impossible value (an Interlude character tops out at level 80).
	 * Everything is best-effort: any field the source can't provide is left blank and the brain simply stays vague
	 * about it. Fields are display-ready strings so the brain can drop them straight into the prompt.
	 */
	private static final class BotIdentity
	{
		final String level;
		final String clazz;
		final String race;
		final String gear;
		final String sex;

		private BotIdentity(String level, String clazz, String race, String gear, String sex)
		{
			this.level = level;
			this.clazz = clazz;
			this.race = race;
			this.gear = gear;
			this.sex = sex;
		}

		boolean isEmpty()
		{
			return level.isEmpty() && clazz.isEmpty() && race.isEmpty() && gear.isEmpty() && sex.isEmpty();
		}

		/** Identity for an NPC fake player, read from its generated {@link FakePlayerAppearance}. */
		static BotIdentity of(Npc bot)
		{
			if (bot == null)
			{
				return null;
			}
			final FakePlayerAppearance look = bot.getFakePlayerAppearance();
			if (look == null)
			{
				return null;
			}
			final int level = look.getLevel();
			return new BotIdentity(level > 1 ? Integer.toString(level) : "", titleCase(look.getPlayerClass() == null ? "" : look.getPlayerClass().name()), raceName(look.getRace()), npcGearGrade(look), look.isFemale() ? "female" : "male");
		}

		/** Identity for a clientless {@link Player} (a regular friend, buddy, or recruited party member). */
		static BotIdentity of(Player player)
		{
			if (player == null)
			{
				return null;
			}
			return new BotIdentity(Integer.toString(player.getLevel()), titleCase(player.getPlayerClass() == null ? "" : player.getPlayerClass().name()), raceName(player.getRace()), playerGearGrade(player), player.getAppearance().isFemale() ? "female" : "male");
		}
	}

	/** "DARK_ELF" -&gt; "Dark Elf"; blank stays blank. Shared by race and class formatting. */
	private static String titleCase(String enumName)
	{
		if ((enumName == null) || enumName.isEmpty())
		{
			return "";
		}
		final StringBuilder sb = new StringBuilder();
		for (String word : enumName.toLowerCase().split("_"))
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return sb.toString();
	}

	private static String raceName(Race race)
	{
		return race == null ? "" : titleCase(race.name());
	}

	/** The highest gear grade an NPC visibly wears (weapon vs. chest), e.g. "C grade"; blank if no-grade/unknown. */
	private static String npcGearGrade(FakePlayerAppearance look)
	{
		return gradeLabel(Math.max(crystalOrdinal(look.getEquipRHand()), crystalOrdinal(look.getEquipChest())));
	}

	/** The grade of a real phantom's equipped weapon, e.g. "C grade"; blank if unarmed/no-grade. */
	private static String playerGearGrade(Player player)
	{
		final ItemTemplate weapon = player.getActiveWeaponItem();
		return weapon == null ? "" : gradeLabel(weapon.getCrystalType().ordinal());
	}

	private static int crystalOrdinal(int itemId)
	{
		if (itemId <= 0)
		{
			return 0;
		}
		final ItemTemplate template = ItemData.getInstance().getTemplate(itemId);
		return template == null ? 0 : template.getCrystalType().ordinal();
	}

	/** CrystalType ordinal (NONE, D, C, B, A, S) -&gt; a readable grade label, blank for no-grade. */
	private static String gradeLabel(int ordinal)
	{
		switch (ordinal)
		{
			case 1:
			{
				return "D grade";
			}
			case 2:
			{
				return "C grade";
			}
			case 3:
			{
				return "B grade";
			}
			case 4:
			{
				return "A grade";
			}
			case 0:
			{
				return "";
			}
			default:
			{
				return "S grade";
			}
		}
	}

	// Control-tag patterns live in FakePlayerChatParsing (unit-tested there); aliased so call sites are unchanged.
	private static final Pattern MEET_TAG = FakePlayerChatParsing.MEET_TAG;
	private static final Pattern SHOP_TAG = FakePlayerChatParsing.SHOP_TAG;

	// Datapack-verified town centres, so a bot can truthfully say where it is when asked.
	private static final String[] TOWN_NAMES =
	{
		"Talking Island", "Gludin", "Gludio", "Dion", "Giran", "Oren", "Aden", "Heine",
		"Goddard", "Rune", "Schuttgart", "the Elven Village", "the Dark Elf Village",
		"the Dwarven Village", "the Orc Village"
	};
	private static final int[][] TOWN_COORDS =
	{
		{ -83990, 243336 }, { -83520, 150560 }, { -14288, 122752 }, { 15670, 142980 }, { 83400, 147600 },
		{ 82200, 53500 }, { 146680, 25800 }, { 111360, 220890 }, { 147300, -56570 }, { 43800, -47700 },
		{ 87386, -143246 }, { 46926, 51511 }, { 12501, 16768 }, { 115072, -178176 }, { -44316, -113136 }
	};
	
	protected FakePlayerChatManager()
	{
		load();
	}
	
	@Override
	public void load()
	{
		if (FakePlayersConfig.FAKE_PLAYERS_ENABLED)
		{
			FakePlayerData.getInstance().report();
			if (FakePlayersConfig.FAKE_PLAYER_CHAT)
			{
				MESSAGES.clear();
				parseDatapackFile("data/FakePlayerChatData.xml");
				LOGGER.info(getClass().getSimpleName() + ": Loaded " + MESSAGES.size() + " chat templates.");
				startSocial();
			}
		}
	}
	
	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "fakePlayerChat", fakePlayerChatNode ->
		{
			final StatSet set = new StatSet(parseAttributes(fakePlayerChatNode));
			MESSAGES.add(new FakePlayerChatHolder(set.getString("fpcName"), set.getString("searchMethod"), set.getString("searchText"), set.getString("answers")));
		}));
	}
	
	public void manageChat(Player player, String fpcName, String message)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(MIN_DELAY, MAX_DELAY));
	}
	
	public void manageChat(Player player, String fpcName, String message, int minDelay, int maxDelay)
	{
		ThreadPool.schedule(() -> manageResponce(player, fpcName, message), Rnd.get(minDelay, maxDelay));
	}
	
	private void manageResponce(Player player, String fpcName, String message)
	{
		if (player == null)
		{
			return;
		}

		// Static AFK store vendors are treated as offline shops: they do not answer whispers. A bot running a
		// temporary deal store (a trade arranged from chat) DOES still answer, so the player can renegotiate
		// or call the trade off.
		final Npc bot = resolveBot(fpcName);
		if ((bot != null) && isStoreVendor(bot) && !FakePlayerBehaviorManager.getInstance().isDealVendor(bot))
		{
			return;
		}

		// LLM brain hook (private whisper). Falls back to canned chat if the bridge is offline.
		// The bot's whereabouts go along so it can truthfully answer "where are you?".
		final String aiReply = askBrain(player.getName(), fpcName, message, bot);
		if (aiReply != null)
		{
			// Check if brain returned a JSON action command
			if (aiReply.startsWith("{"))
			{
				final String action = extractJsonValue(aiReply, "action");
				if (action != null)
				{
					PhantomActionHandler.getInstance().handleBrainAction(player, fpcName, action);
					return;
				}
			}
			sendChat(player, fpcName, handleMeetRequest(aiReply, player, bot));
			return;
		}
		
		final String text = message.toLowerCase();
		
		if (text.contains("can you see me"))
		{
			if (bot != null)
			{
				if (bot.calculateDistance2D(player) < 3000)
				{
					if (GeoEngine.getInstance().canSeeTarget(bot, player) && !player.isInvisible())
					{
						sendChat(player, fpcName, Rnd.nextBoolean() ? "i am not blind" : Rnd.nextBoolean() ? "of course i can" : "yes");
					}
					else
					{
						sendChat(player, fpcName, Rnd.nextBoolean() ? "i know you are around" : Rnd.nextBoolean() ? "not at the moment :P" : "no, where are you?");
					}
				}
				else
				{
					sendChat(player, fpcName, Rnd.nextBoolean() ? "nope, can't see you" : Rnd.nextBoolean() ? "nope" : "no");
				}
				return;
			}
		}
		
		for (FakePlayerChatHolder chatHolder : MESSAGES)
		{
			if (!chatHolder.getFpcName().equals(fpcName) && !chatHolder.getFpcName().equals("ALL"))
			{
				continue;
			}
			switch (chatHolder.getSearchMethod())
			{
				case "EQUALS":
				{
					if (text.equals(chatHolder.getSearchText().get(0)))
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
				case "STARTS_WITH":
				{
					if (text.startsWith(chatHolder.getSearchText().get(0)))
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
				case "CONTAINS":
				{
					boolean allFound = true;
					for (String word : chatHolder.getSearchText())
					{
						if (!text.contains(word))
						{
							allFound = false;
						}
					}
					if (allFound)
					{
						sendChat(player, fpcName, chatHolder.getAnswers().get(Rnd.get(chatHolder.getAnswers().size())));
					}
					break;
				}
			}
		}
	}
	
	// ===== Social: trade (global) + say (local) + ambient + bot banter =====
	
	private void startSocial()
	{
		if (!SOCIAL_ENABLED || SOCIAL_STARTED)
		{
			return;
		}
		SOCIAL_STARTED = true;
		ThreadPool.scheduleAtFixedRate(() ->
		{
			MESSAGES_THIS_MINUTE.set(0);
			TRADE_OFFERS_THIS_MINUTE.set(0);
		}, 60000, 60000); // reset rate caps each minute
		ThreadPool.scheduleAtFixedRate(this::ambientTradeChat, AMBIENT_INTERVAL, AMBIENT_INTERVAL);
		ThreadPool.scheduleAtFixedRate(this::ambientShoutChat, SHOUT_AMBIENT_INTERVAL, SHOUT_AMBIENT_INTERVAL);
		LOGGER.info(getClass().getSimpleName() + ": Server-wide social chat enabled.");
	}
	
	// Called from ChatTrade when a real player uses trade (+) chat.
	public void overheardTradeChat(Player speaker, String text)
	{
		if (SOCIAL_ENABLED && (speaker != null) && (text != null) && !text.isEmpty())
		{
			// A WTS/WTB ad may make one relevant bot PM the player to set up a real trade. The responder
			// calls the LLM (to read slang) so it runs off-thread; plain banter handles everything else.
			if (TRADE_AD.matcher(text).find())
			{
				final Player who = speaker;
				// DISABLED: ThreadPool.schedule(() -> respondToTradeAd(who, text), ...);
			}
			else
			{
				reactToChat(speaker, speaker.getName(), text, false, "TRADE");
			}
		}
	}

	// Matches a trade ad and captures the direction (sell/buy) plus the item phrase after it.
	// Pattern + quantity parsing live in FakePlayerChatParsing (unit-tested there); aliased so call sites are unchanged.
	private static final Pattern TRADE_AD = FakePlayerChatParsing.TRADE_AD;

	/**
	 * Handles a WTS/WTB ad (off the network thread): the AI translates the slang to an item name, we
	 * ground it to a real item, then arm a nearby roaming bot to PM the player, walk to a meet spot, and
	 * open a real store on arrival. Falls back to plain trade banter if nothing usable matched.
	 */
	private void respondToTradeAd(Player player, String text)
	{
		if (player == null)
		{
			return;
		}
		if (TRADE_OFFERS_THIS_MINUTE.get() >= MAX_TRADE_OFFERS_PER_MINUTE)
		{
			return;
		}
		final Matcher matcher = TRADE_AD.matcher(text);
		if (!matcher.find())
		{
			return;
		}
		final String token = matcher.group(1).toLowerCase();
		final boolean playerSelling = token.startsWith("wts") || token.startsWith("selling") || token.equals("s>");

		// Stage 1: ask the AI to turn shorthand ("ssd") into a plain item name; fall back to the raw words.
		final String rawPhrase = matcher.group(2).replaceAll("[0-9]+(k|kk)?", " ").replaceAll("[^a-zA-Z ]", " ").trim();
		final String aiName = askBrainItem(text);
		// Stage 2: ground whatever we got to a real datapack item (deterministic search).
		ItemTemplate item = aiName == null ? null : FakePlayerStoreFactory.findItemByName(aiName);
		if (item == null)
		{
			item = FakePlayerStoreFactory.findItemByName(rawPhrase);
		}
		if (item == null)
		{
			reactToChat(player, player.getName(), text, false, "TRADE"); // nothing recognisable -> banter
			return;
		}

		final Npc bot = FakePlayerBehaviorManager.getInstance().pickTradeResponder(player);
		if (bot == null)
		{
			reactToChat(player, player.getName(), text, false, "TRADE"); // no roaming bot around -> banter
			return;
		}

		// Player selling -> bot buys it; player buying -> bot sells it.
		final int storeType = playerSelling ? PrivateStoreType.BUY.getId() : PrivateStoreType.SELL.getId();
		final int requestedCount = parseTradeQuantity(matcher.group(2), item);
		final List<FakePlayerStoreItem> stock = playerSelling ? FakePlayerStoreFactory.dealBuyStock(item.getId(), 0, requestedCount) : FakePlayerStoreFactory.dealSellStock(item.getId(), 0, requestedCount);
		if (stock.isEmpty())
		{
			return;
		}
		final String title = FakePlayerStoreFactory.title(playerSelling ? "BUY" : "SELL", stock);
		// Stash the deal terms and reserve the bot, but do NOT walk yet. The bot quotes a price and waits;
		// the player agrees (or haggles) and picks a meet spot over whisper. The whisper handler then walks
		// the bot once a [[MEET:spot]] is agreed, applying any haggled price from the [[SHOP:...]] tag.
		FakePlayerBehaviorManager.getInstance().setupDeal(bot, storeType, stock, title);

		final int unit = stock.get(0).getPrice();
		final int actualCount = stock.get(0).getCount();
		final String botSide = playerSelling ? "BUY" : "SELL";
		final String deal = (playerSelling ? "buy their " : "sell them ")
			+ (actualCount > 0 ? (actualCount + "x ") : "")
			+ item.getName() + " for about " + unit
			+ " adena each; state your price and ask if they want to deal and where to meet (gatekeeper, warehouse or shop) - do not commit to walking anywhere yet";
		final String fpcName = bot.getName();
		final BrainDealContext dealContext = new BrainDealContext(botSide, item.getName(), actualCount, unit);
		ACTIVE_DEALS.put(dealKey(player.getName(), fpcName), dealContext);
		final String line = callBridgeJson(fpcName, "OFFER", player.getName(), "", text, nearestLocation(bot), deal, false, dealContext, BotIdentity.of(bot), bot);
		sendChat(player, fpcName, (line == null) || line.isEmpty() //
			? ("saw ur post - i " + (playerSelling ? "buy " : "sell ") + item.getName() + " for " + unit + " adena each, wanna deal? where u wanna meet?") //
			: line);
		TRADE_OFFERS_THIS_MINUTE.incrementAndGet();
	}

	/** Asks the brain to translate trade-chat shorthand into a plain item name; {@code null} if unclear. */
	private String askBrainItem(String adText)
	{
		final String reply = callBridge("", "ITEM", "", "", adText, "", "");
		if (reply == null)
		{
			return null;
		}
		final String name = reply.trim();
		return (name.isEmpty() || name.equalsIgnoreCase("none")) ? null : name;
	}

	/**
	 * Drops the stored structured trade context for a player/bot deal. Called when the deal lifecycle ends
	 * (store sold out / closed, meet hard-cap, grace timeout, or cancel - all funnel through the behavior
	 * manager's endMeet) so a finished deal's stale X-Deal-* terms never get injected into a later, unrelated
	 * whisper to the same bot. Safe no-op when there is no active deal for that pair.
	 * @param playerName the player the deal was with
	 * @param fpcName the bot that held the deal
	 */
	public void clearDeal(String playerName, String fpcName)
	{
		if ((playerName != null) && (fpcName != null))
		{
			ACTIVE_DEALS.remove(dealKey(playerName, fpcName));
		}
	}

	private static String dealKey(String playerName, String fpcName)
	{
		return ((playerName == null ? "" : playerName.trim().toLowerCase()) + "|" + (fpcName == null ? "" : fpcName.trim().toLowerCase()));
	}

	private static int parseTradeQuantity(String phrase, ItemTemplate item)
	{
		return FakePlayerChatParsing.parseTradeQuantity(phrase, (item != null) && item.isStackable());
	}
	
	// Called from ChatGeneral when a real player uses normal (say) chat.
	public void overheardSay(Player speaker, String text)
	{
		if (SOCIAL_ENABLED && (speaker != null) && (text != null) && !text.isEmpty())
		{
			// Check if this is an action request (buff, heal, res, etc.)
			if (PhantomActionHandler.getInstance().handlePlayerChat(speaker, text))
			{
				return; // Action was handled, don't also trigger chat response
			}
			reactToChat(speaker, speaker.getName(), text, false, "SAY");
		}
	}

	// Called from ChatShout when a real player uses shout (!) chat - the global world channel for chit-chat
	// and LFM/looking-for-party ads. Bots banter back or answer the call (party/raid mechanics are not wired
	// yet, so this is conversational fluff for now).
	public void overheardShout(Player speaker, String text)
	{
		if (!SOCIAL_ENABLED || (speaker == null) || (text == null) || text.isEmpty())
		{
			return;
		}

		// LFM/LFP: a real player looking for party members. Parse the wanted roles and recruit a party that walks
		// over (works brain-off via keywords; with the brain online a free-form call is classified for its roles).
		// On a recruit we do NOT also run plain shout banter - the answer IS the bots showing up.
		final List<String> unresolved = new ArrayList<>();
		final List<Recruit> roles = parseLfp(text, unresolved);
		final int wantedLevel = parseLfpLevel(text); // optional "lvl 57"; 0 = match the recruiter's level
		if (!roles.isEmpty())
		{
			PhantomPartyManager.getInstance().recruitFromShout(speaker, roles, wantedLevel);
			if (!unresolved.isEmpty())
			{
				whisperRecruitClarification(speaker, unresolved); // recruited what we could read; ask about the garbled one(s)
			}
			return;
		}
		if (!unresolved.isEmpty())
		{
			whisperRecruitClarification(speaker, unresolved); // a party call whose class word(s) we couldn't read - ask, don't spawn a default
			return;
		}
		if (looksLikeLfp(text))
		{
			final Player who = speaker;
			ThreadPool.execute(() ->
			{
				final List<Recruit> aiRoles = askBrainLfp(text);
				if (!aiRoles.isEmpty())
				{
					PhantomPartyManager.getInstance().recruitFromShout(who, aiRoles, wantedLevel);
				}
				else
				{
					reactToPlayerShout(who, text); // brain says it wasn't really an LFP -> normal banter
				}
			});
			return;
		}

		// Shout is a GLOBAL world channel, so a real player's shout should reach bots anywhere - not
		// just the (mostly store-vendor) crowd within SOCIAL_RANGE. Pull from a world-wide pool and
		// guarantee at least one bot answers a human.
		reactToPlayerShout(speaker, text);
	}

	// LFM/LFP triggers - a line must contain one of these to be treated as a party call (so ordinary chat that
	// happens to mention "tank" or "healer" is not mistaken for recruiting).
	/** @return {@code true} if the shout reads like a party call (has an LFM/LFP trigger). */
	private static boolean looksLikeLfp(String text)
	{
		return FakePlayerChatParsing.looksLikeLfp(text);
	}

	/** @return the level requested in an LFP shout (1-80), or 0 when none is given (match the recruiter). */
	private static int parseLfpLevel(String text)
	{
		return FakePlayerChatParsing.parseLfpLevel(text);
	}

	// Specific occupations a player can request by name ("shillien elder", "gladiator") OR by community alias
	// ("se", "wc", "ee"). An alias resolves to the EXACT class it means, so "lfm se" spawns a Shillien Elder and
	// "lfm wc" a Warcryer instead of the role's default class (an Elven Elder / a Prophet) - the tester's "aliases
	// give the wrong mob" bug. Matched longest-first so a multi-word class wins over a shorter substring.
	private static final Map<String, PlayerClass> CLASS_BY_NAME = new LinkedHashMap<>();
	private static final Pattern CLASS_PATTERN;
	// Single-word class names/aliases a one-typo fuzzy match may correct to, restricted to the DISTINCTIVE ones
	// (6+ letters) so an ordinary shout word is never mistaken for a class: e.g. "party"/"more"/"danger"/"fast"
	// stay clear of "pally"/"muse"/"dancer"/"fist". Generic role words ("tank", "dd", "healer") are matched only
	// exactly by PartyRole.fromToken - they are short, common, and easy to type, and fuzzing them collides with
	// everyday words. Multi-word class names ("shillien elder") are matched only exactly, by CLASS_PATTERN.
	private static final Set<String> CLASS_FUZZY;
	static
	{
		for (PlayerClass pc : PlayerClass.values())
		{
			if (PhantomManager.isSelectableClass(pc)) // 2nd+ occupations only; base/1st fall through to role tokens
			{
				CLASS_BY_NAME.put(pc.name().toLowerCase().replace('_', ' '), pc);
			}
		}
		// Community aliases -> the exact class they name (the value must be a full-name key added just above).
		putClassAlias("pp", "prophet");
		putClassAlias("wc", "warcryer");
		putClassAlias("dc", "doomcryer");
		putClassAlias("ol", "overlord");
		putClassAlias("dom", "dominator");
		putClassAlias("ee", "elder"); // Elven Elder
		putClassAlias("se", "shillien elder");
		putClassAlias("shil", "shillien elder");
		putClassAlias("shillien", "shillien elder"); // in a support call "shillien" reads as Shillien Elder
		putClassAlias("bp", "bishop");
		putClassAlias("bish", "bishop");
		putClassAlias("card", "cardinal");
		putClassAlias("hiero", "hierophant");
		putClassAlias("eva", "eva saint");
		putClassAlias("evas", "eva saint");
		putClassAlias("muse", "sword muse");
		putClassAlias("sws", "swordsinger");
		putClassAlias("bd", "bladedancer");
		putClassAlias("spectral", "spectral dancer");
		// Build the exact-match pattern from every key (names + aliases), longest first so a multi-word class wins
		// over a shorter substring. Trailing "s?" so a plural request ("2 bishops", "3 hawkeyes") still matches.
		final List<String> keys = new ArrayList<>(CLASS_BY_NAME.keySet());
		keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
		final StringBuilder sb = new StringBuilder();
		for (String key : keys)
		{
			if (sb.length() > 0)
			{
				sb.append('|');
			}
			sb.append(Pattern.quote(key));
		}
		CLASS_PATTERN = Pattern.compile("\\b(" + sb + ")s?\\b", Pattern.CASE_INSENSITIVE);
		// Fuzzy targets: distinctive single-word class keys/aliases only (6+ letters).
		final Set<String> fuzzy = new HashSet<>();
		for (String key : CLASS_BY_NAME.keySet())
		{
			if ((key.indexOf(' ') < 0) && (key.length() >= 6))
			{
				fuzzy.add(key);
			}
		}
		CLASS_FUZZY = Set.copyOf(fuzzy);
	}

	/** Registers a community alias -&gt; the class named by an existing full-name key (no-op if the name is unknown). */
	private static void putClassAlias(String alias, String canonicalName)
	{
		final PlayerClass pc = CLASS_BY_NAME.get(canonicalName);
		if (pc != null)
		{
			CLASS_BY_NAME.put(alias, pc);
		}
	}

	/**
	 * Deterministically extracts the recruits a player is shouting for. Specific class names ("lfm 1 shillien
	 * elder") become that exact class; generic role words ("2 dd + healer") become level-appropriate roles.
	 * Numbers before a class/role repeat it. Empty when the line is not an explicit party call.
	 */
	private static List<Recruit> parseLfp(String text, List<String> unresolvedOut)
	{
		final List<Recruit> recruits = new ArrayList<>();
		if (!looksLikeLfp(text))
		{
			return recruits;
		}
		// Phase 1: specific class names. Replace each match with spaces so phase 2 doesn't re-read its words
		// (e.g. "shillien elder" must not also count as a generic "elder").
		final StringBuilder working = new StringBuilder(text.toLowerCase());
		final Matcher matcher = CLASS_PATTERN.matcher(working.toString());
		while (matcher.find())
		{
			final PlayerClass pc = CLASS_BY_NAME.get(matcher.group(1).toLowerCase());
			if (pc != null)
			{
				final int count = countBefore(working, matcher.start());
				final PartyRole role = PhantomManager.roleForClass(pc);
				for (int i = 0; (i < count) && (recruits.size() < 8); i++)
				{
					recruits.add(new Recruit(role, pc.getId()));
				}
			}
			for (int i = matcher.start(); i < matcher.end(); i++)
			{
				working.setCharAt(i, ' ');
			}
		}

		// Phase 2: generic role words + one-typo tolerance on whatever text is left. The pure counting state machine
		// (level-token skipping, per-word counts) lives in FakePlayerChatParsing; here we resolve each token to a
		// class/role, forgive a single typo, and collect the ones that look like a garbled class so the caller can
		// ask instead of silently spawning a wrong default.
		for (FakePlayerChatParsing.RoleRequest request : FakePlayerChatParsing.parseRoleRequests(working.toString()))
		{
			final Recruit resolved = resolveRecruitToken(request.token);
			if (resolved != null)
			{
				for (int i = 0; (i < request.count) && (recruits.size() < 8); i++)
				{
					recruits.add(resolved);
				}
			}
			else if ((unresolvedOut != null) && looksLikeGarbledClass(request.token))
			{
				unresolvedOut.add(request.token); // close to a class/role but too garbled to be sure - ask, don't guess
			}
			// else: an ordinary non-role word ("cruma", "pls", "for") - ignore silently as before
		}
		return recruits;
	}

	/**
	 * Resolves one phase-2 recruit token to a specific recruit: an exact class name/alias (spawns that class), an
	 * exact generic role word (spawns the role's default class), or a single-typo correction of either. Plurals
	 * fall back to their singular. Returns {@code null} when the word names no class/role - the caller decides
	 * whether it is a garbled class worth a clarification, or just noise.
	 */
	private static Recruit resolveRecruitToken(String token)
	{
		// Exact class name/alias (phase 1 blanks these, but a plural or an alias by punctuation can survive).
		PlayerClass pc = classByNameOrSingular(token);
		if (pc != null)
		{
			return new Recruit(PhantomManager.roleForClass(pc), pc.getId());
		}
		// Exact generic role word.
		PartyRole role = roleByTokenOrSingular(token);
		if (role != null)
		{
			return new Recruit(role, 0); // 0 = the role's default level-appropriate class
		}
		// One-typo correction against the distinctive class names ("warcyer" -> "warcryer", "bishpo" -> "bishop").
		final String near = FakePlayerChatParsing.nearestWithin(token, CLASS_FUZZY, FakePlayerChatParsing.fuzzyBudget(token.length()));
		if (near != null)
		{
			pc = CLASS_BY_NAME.get(near);
			if (pc != null)
			{
				return new Recruit(PhantomManager.roleForClass(pc), pc.getId());
			}
		}
		return null;
	}

	private static PlayerClass classByNameOrSingular(String token)
	{
		PlayerClass pc = CLASS_BY_NAME.get(token);
		if ((pc == null) && (token.length() > 1) && token.endsWith("s"))
		{
			pc = CLASS_BY_NAME.get(token.substring(0, token.length() - 1));
		}
		return pc;
	}

	private static PartyRole roleByTokenOrSingular(String token)
	{
		PartyRole role = PartyRole.fromToken(token);
		if ((role == null) && (token.length() > 1) && token.endsWith("s"))
		{
			role = PartyRole.fromToken(token.substring(0, token.length() - 1));
		}
		return role;
	}

	/**
	 * @return {@code true} if an unresolved token is close enough to a distinctive class name to be a garbled
	 *         request worth a clarification whisper (rather than an ordinary word in the shout) - within one edit
	 *         over the auto-correct budget of a 6+ letter class name. Ordinary shout words ("party", "more",
	 *         "danger") stay clear of the distinctive class set, so they never trigger a spurious "which class?".
	 */
	private static boolean looksLikeGarbledClass(String token)
	{
		if ((token == null) || (token.length() < 4))
		{
			return false; // too short to tell a typo'd class from an ordinary word
		}
		return FakePlayerChatParsing.minDistance(token, CLASS_FUZZY) <= (FakePlayerChatParsing.fuzzyBudget(token.length()) + 1);
	}

	/** @return the count number immediately before position {@code pos} in the text (e.g. "2 dd" -> 2), else 1. */
	private static int countBefore(CharSequence text, int pos)
	{
		return FakePlayerChatParsing.countBefore(text, pos);
	}

	/** Asks the brain (LFP mode) to classify a free-form party call into role tokens; empty when it isn't one. */
	private List<Recruit> askBrainLfp(String text)
	{
		final List<Recruit> recruits = new ArrayList<>();
		final String reply = callBridge("", "LFP", "", "", text, "", "");
		if (reply == null)
		{
			return recruits;
		}
		for (String token : reply.toLowerCase().split("[^a-z]+"))
		{
			final PartyRole role = PartyRole.fromToken(token);
			if ((role != null) && (recruits.size() < 8))
			{
				recruits.add(new Recruit(role, 0));
			}
		}
		return recruits;
	}

	/**
	 * A nearby fake player whispers the recruiter to clarify the class word(s) the recruit parser couldn't read,
	 * rather than silently spawning the wrong "default" class. Handles the mixed case too: when a shout asked for
	 * several classes and only one or two were garbled, the readable ones are already being recruited and this only
	 * asks about the rest. Rate-limited like other public bot chatter; if no fake player is around to voice it, a
	 * plain "Party" system whisper still gives the player an answer.
	 */
	private void whisperRecruitClarification(Player speaker, List<String> tokens)
	{
		if ((speaker == null) || (tokens == null) || tokens.isEmpty() || (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE))
		{
			return;
		}
		final String line = recruitClarifyLine(tokens);
		final Npc voice = pickShoutResponder(speaker);
		MESSAGES_THIS_MINUTE.incrementAndGet();
		if (voice != null)
		{
			sendChat(speaker, voice.getName(), line); // reuses the length-scaled "typing" whisper path
		}
		else
		{
			speaker.sendPacket(new CreatureSay(speaker, ChatType.WHISPER, "Party", line));
		}
	}

	/** Builds the clarification line naming the class word(s) that couldn't be read (de-duped, at most three). */
	private static String recruitClarifyLine(List<String> tokens)
	{
		final List<String> shown = new ArrayList<>();
		for (String token : tokens)
		{
			if (!shown.contains(token))
			{
				shown.add(token);
			}
			if (shown.size() >= 3)
			{
				break;
			}
		}
		if (shown.size() == 1)
		{
			return "which class did you want? didn't catch '" + shown.get(0) + "'";
		}
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < shown.size(); i++)
		{
			if (i > 0)
			{
				sb.append((i == (shown.size() - 1)) ? " or " : ", ");
			}
			sb.append('\'').append(shown.get(i)).append('\'');
		}
		return "which classes did you want? didn't catch " + sb;
	}

	/** Picks a fake player to voice a recruit clarification: one near the recruiter, else any online fake player. */
	private Npc pickShoutResponder(Player speaker)
	{
		final String speakerName = speaker.getName();
		final List<Npc> near = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(speaker, Npc.class, SOCIAL_RANGE, npc ->
		{
			if (npc.isFakePlayer() && !isStoreVendor(npc) && !npc.getName().equals(speakerName))
			{
				near.add(npc);
			}
		});
		if (!near.isEmpty())
		{
			return near.get(Rnd.get(near.size()));
		}
		final List<Npc> all = new ArrayList<>();
		final Set<String> seen = new HashSet<>();
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object.isNpc())
			{
				final Npc npc = object.asNpc();
				if (npc.isFakePlayer() && !isStoreVendor(npc) && !npc.getName().equals(speakerName) && seen.add(npc.getName()))
				{
					all.add(npc);
				}
			}
		}
		return all.isEmpty() ? null : all.get(Rnd.get(all.size()));
	}

	/**
	 * A real player shouted on the global '!' channel. Gather non-vendor fake players world-wide, then
	 * schedule a guaranteed first reply (the brain is told not to "pass" to a human) plus up to one extra
	 * bot on the usual chance. Bot-to-bot shout banter still flows through {@link #reactToChat} (local).
	 */
	private void reactToPlayerShout(Player speaker, String text)
	{
		if (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE)
		{
			return;
		}
		final String speakerName = speaker.getName();
		final List<Npc> bots = new ArrayList<>();
		final Set<String> seenNames = new HashSet<>();
		for (WorldObject object : World.getInstance().getVisibleObjects()) // world-wide: shout is global
		{
			if (object.isNpc())
			{
				final Npc npc = object.asNpc();
				// Dedupe by name; AFK store vendors stay silent.
				if (npc.isFakePlayer() && !isStoreVendor(npc) && !npc.getName().equals(speakerName) && seenNames.add(npc.getName()))
				{
					bots.add(npc);
				}
			}
		}
		if (bots.isEmpty())
		{
			return;
		}
		Collections.shuffle(bots);
		int repliers = 0;
		for (Npc bot : bots)
		{
			if (repliers >= MAX_REPLIERS)
			{
				break;
			}
			// The first eligible bot always answers a real player's shout; any extra rolls the normal chance.
			if ((repliers > 0) && (Rnd.get(100) >= REPLY_CHANCE_TO_PLAYER))
			{
				continue;
			}
			final int slot = repliers++;
			final Npc replier = bot;
			// Stagger extra repliers so a 2nd bot answers a few seconds after the 1st, not in lockstep.
			ThreadPool.schedule(() -> botSpeaks(replier, speakerName, text, "SHOUT", true), Rnd.get(MIN_DELAY, MAX_DELAY) + ((long) slot * REPLY_STAGGER_MS));
		}
	}
	
	private void reactToChat(Creature origin, String speakerName, String text, boolean speakerIsBot, String channel)
	{
		if (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE)
		{
			return; // throttle 3: global cap
		}
		
		final int range = channel.equals("SAY") ? SAY_RANGE : SOCIAL_RANGE;
		final List<Npc> bots = new ArrayList<>();
		final Set<String> seenNames = new HashSet<>();
		World.getInstance().forEachVisibleObjectInRange(origin, Npc.class, range, npc ->
		{
			// Dedupe by name: several spawns can share one fake player name, but a given
			// character should answer a message only once. AFK store vendors stay silent.
			if (npc.isFakePlayer() && !isStoreVendor(npc) && (npc != origin) && !npc.getName().equals(speakerName) && seenNames.add(npc.getName()))
			{
				bots.add(npc);
			}
		});
		if (bots.isEmpty())
		{
			return;
		}
		
		final int chance = speakerIsBot ? REPLY_CHANCE_TO_BOT : REPLY_CHANCE_TO_PLAYER;
		Collections.shuffle(bots);
		int repliers = 0;
		for (Npc bot : bots)
		{
			if (repliers >= MAX_REPLIERS)
			{
				break;
			}
			if (Rnd.get(100) >= chance) // throttles 1 & 2
			{
				continue;
			}
			final int slot = repliers++;
			final Npc replier = bot;
			// Stagger extra repliers so a 2nd bot answers a few seconds after the 1st, not in lockstep.
			ThreadPool.schedule(() -> botSpeaks(replier, speakerName, text, channel, !speakerIsBot), Rnd.get(MIN_DELAY, MAX_DELAY) + ((long) slot * REPLY_STAGGER_MS));
		}
	}

	private void botSpeaks(Npc bot, String speakerName, String overheard, String channel, boolean human)
	{
		if (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE)
		{
			return;
		}
		// SAY is local: don't waste an LLM call if no player is close enough to hear it.
		if (channel.equals("SAY") && !hasPlayerInRange(bot, SAY_RANGE))
		{
			return;
		}
		final String line = askBrainPublic(bot, speakerName, overheard, channel, human);
		if ((line == null) || line.isEmpty())
		{
			return;
		}
		// Defer the broadcast by a length-scaled "typing" time so the line doesn't appear the instant the brain
		// returns, and follow-up bot banter only kicks off once the line is actually visible.
		ThreadPool.schedule(() ->
		{
			if (channel.equals("SAY"))
			{
				sendSayChat(bot, line);
			}
			else if (channel.startsWith("SHOUT"))
			{
				sendShoutChat(bot, line); // SHOUT and SHOUTAMBIENT broadcast to the global shout channel
			}
			else
			{
				sendTradeChat(bot, line); // TRADE and AMBIENT both broadcast to global trade
			}
			MESSAGES_THIS_MINUTE.incrementAndGet();

			// bot-to-bot banter DISABLED
		}, typingDelayMillis(line));
	}
	
	private boolean hasPlayerInRange(Npc npc, int range)
	{
		final boolean[] found =
		{
			false
		};
		World.getInstance().forEachVisibleObjectInRange(npc, Player.class, range, player -> found[0] = true);
		return found[0];
	}

	private void sendTradeChat(Npc npc, String text)
	{
		final CreatureSay cs = new CreatureSay(npc, ChatType.TRADE, npc.getName(), text);
		for (Player player : World.getInstance().getPlayers()) // GLOBAL
		{
			player.sendPacket(cs);
		}
	}

	private void sendSayChat(Npc npc, String text)
	{
		final CreatureSay cs = new CreatureSay(npc, ChatType.GENERAL, npc.getName(), text);
		World.getInstance().forEachVisibleObjectInRange(npc, Player.class, SAY_RANGE, player -> player.sendPacket(cs)); // LOCAL
	}

	private void sendShoutChat(Npc npc, String text)
	{
		final CreatureSay cs = new CreatureSay(npc, ChatType.SHOUT, npc.getName(), text);
		for (Player player : World.getInstance().getPlayers()) // GLOBAL world channel
		{
			player.sendPacket(cs);
		}
	}
	
	private void ambientTradeChat()
	{
		if (!SOCIAL_ENABLED || (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE))
		{
			return;
		}
		final List<Player> players = new ArrayList<>(World.getInstance().getPlayers());
		if (players.isEmpty())
		{
			return; // nobody online to hear it
		}
		final Player witness = players.get(Rnd.get(players.size()));
		final List<Npc> bots = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(witness, Npc.class, SOCIAL_RANGE, npc ->
		{
			if (npc.isFakePlayer() && !isStoreVendor(npc))
			{
				bots.add(npc);
			}
		});
		if (!bots.isEmpty())
		{
			botSpeaks(bots.get(Rnd.get(bots.size())), "", "", "AMBIENT", false);
		}
	}

	/** Spontaneous shout: a random bot posts global chit-chat or an LFM/looking-for-party ad now and then. */
	private void ambientShoutChat()
	{
		if (!SOCIAL_ENABLED || (MESSAGES_THIS_MINUTE.get() >= MAX_MESSAGES_PER_MINUTE))
		{
			return;
		}
		final List<Player> players = new ArrayList<>(World.getInstance().getPlayers());
		if (players.isEmpty())
		{
			return; // nobody online to hear it
		}
		final Player witness = players.get(Rnd.get(players.size()));
		final List<Npc> bots = new ArrayList<>();
		World.getInstance().forEachVisibleObjectInRange(witness, Npc.class, SOCIAL_RANGE, npc ->
		{
			if (npc.isFakePlayer() && !isStoreVendor(npc))
			{
				bots.add(npc);
			}
		});
		if (!bots.isEmpty())
		{
			botSpeaks(bots.get(Rnd.get(bots.size())), "", "", "SHOUTAMBIENT", false);
		}
	}
	
	private String askBrain(String playerName, String fpcName, String message, Npc bot)
	{
		return callBridgeJson(fpcName, "WHISPER", playerName, "", message, nearestLocation(bot), "", false, ACTIVE_DEALS.get(dealKey(playerName, fpcName)), BotIdentity.of(bot), bot);
	}

	private String askBrainPublic(Npc bot, String speakerName, String overheard, String mode, boolean human)
	{
		return callBridgeJson(bot.getName(), mode, "", speakerName, overheard, nearestLocation(bot), "", human, null, BotIdentity.of(bot), bot);
	}

	private String callBridge(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal)
	{
		// Utility calls (ITEM / LFP) carry no bot identity.
		return callBridge(fpcName, mode, playerName, speakerName, body, location, deal, (BotIdentity) null);
	}

	private String callBridge(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal, BotIdentity identity)
	{
		return callBridge(fpcName, mode, playerName, speakerName, body, location, deal, false, null, identity);
	}

	private String callBridge(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal, BrainDealContext dealContext, BotIdentity identity)
	{
		return callBridge(fpcName, mode, playerName, speakerName, body, location, deal, false, dealContext, identity);
	}

	private String callBridge(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal, boolean human, BotIdentity identity)
	{
		return callBridge(fpcName, mode, playerName, speakerName, body, location, deal, human, null, identity);
	}

	private String callBridge(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal, boolean human, BrainDealContext dealContext, BotIdentity identity)
	{
		// Legacy plain-text mode — no extra context sent.
		// Callers with an Npc reference should use the JSON overload.
		try
		{
			final HttpRequest request = HttpRequest.newBuilder() //
				.uri(URI.create(FakePlayersConfig.FAKE_PLAYER_BRAIN_URL)) //
				.timeout(Duration.ofSeconds(BRAIN_TIMEOUT_SECONDS)) //
				.header("X-FPC", fpcName) //
				.header("X-Mode", mode) //
				.header("X-Player", playerName) //
				.header("X-Speaker", speakerName) //
				.header("X-Location", location == null ? "" : location) //
				.header("X-Deal", deal == null ? "" : deal) //
				.header("X-Human", human ? "true" : "false")
				.header("Content-Type", "text/plain; charset=utf-8") //
				.POST(HttpRequest.BodyPublishers.ofString(body)) //
				.build();

			final HttpResponse<String> response = BRAIN_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200)
			{
				final String reply = response.body().trim();
				if (!reply.isEmpty())
				{
					return reply;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("FakePlayerChatManager.sendJsonToBrain: " + e.getMessage());
		}
		return null;
	}
	
	/**
	 * Sends a pre-built JSON payload to the brain bridge and returns the reply, or null if offline.
	 */
	public static String sendJsonToBrain(String jsonPayload)
	{
		try
		{
			final HttpRequest request = HttpRequest.newBuilder() //
				.uri(URI.create(FakePlayersConfig.FAKE_PLAYER_BRAIN_URL)) //
				.timeout(Duration.ofSeconds(BRAIN_TIMEOUT_SECONDS)) //
				.header("Content-Type", "application/json; charset=utf-8") //
				.POST(HttpRequest.BodyPublishers.ofString(jsonPayload)) //
				.build();
			final HttpResponse<String> response = BRAIN_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200)
			{
				final String reply = response.body().trim();
				if (!reply.isEmpty())
				{
					return reply;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("FakePlayerChatManager.sendJsonToBrain: " + e.getMessage());
		}
		return null;
	}
	
	/**
	 * JSON POST overload — sends full context payload (bot identity, dynamic state, chat metadata).
	 * Used by callers that have an {@link Npc} reference (fake player bots).
	 */
	private String callBridgeJson(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal, boolean human, BrainDealContext dealContext, BotIdentity identity, Npc bot)
	{
		try
		{
			final String payload = buildJsonPayload(fpcName, mode, playerName, speakerName, body, location, deal, human, dealContext, identity, bot);
			final HttpRequest request = HttpRequest.newBuilder() //
				.uri(URI.create(FakePlayersConfig.FAKE_PLAYER_BRAIN_URL)) //
				.timeout(Duration.ofSeconds(BRAIN_TIMEOUT_SECONDS)) //
				.header("Content-Type", "application/json; charset=utf-8") //
				.POST(HttpRequest.BodyPublishers.ofString(payload)) //
				.build();

			final HttpResponse<String> response = BRAIN_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 200)
			{
				final String reply = response.body().trim();
				if (!reply.isEmpty())
				{
					return reply;
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("FakePlayerChatManager.sendJsonToBrain: " + e.getMessage());
		}
		return null;
	}
	
	/**
	 * @return a human-like "typing" delay in ms for {@code line}: a short base pause plus time proportional to
	 *         length, capped, with +-25% jitter so two bots typing the same-length line don't fire in lockstep.
	 */
	private static long typingDelayMillis(String line)
	{
		final int len = (line == null) ? 0 : line.length();
		long ms = TYPE_BASE_MS + (len * TYPE_PER_CHAR_MS);
		if (ms > TYPE_MAX_MS)
		{
			ms = TYPE_MAX_MS;
		}
		final int jitter = (int) (ms * 0.25);
		return Math.max(0, ms + Rnd.get(-jitter, jitter));
	}

	public void sendChat(Player player, String fpcName, String message)
	{
		if ((player == null) || (message == null) || message.isEmpty())
		{
			return;
		}
		// Defer the whisper by a length-scaled "typing" time so a reply doesn't pop out instantly (the bot tell):
		// a quick "np" lands fast, a longer sentence takes a beat. Bot is resolved at send time in case it despawns.
		ThreadPool.schedule(() ->
		{
			final Npc npc = resolveBot(fpcName);
			if (npc != null)
			{
				player.sendPacket(new CreatureSay(npc, ChatType.WHISPER, fpcName, message));
			}
		}, typingDelayMillis(message));
	}

	/**
	 * Answers a friend private-message sent to a persistent "regular" phantom (Phase 3 friend tier). A regular
	 * is a clientless Player, not an Npc, so it sits outside the normal whisper path ({@link #resolveBot} only
	 * finds Npc fake players): we call the brain in FRIEND mode with the regular's name - the same stable
	 * persona a whisper would get (the brain's {@code _voice()} hashes the name), but with explicit friendship
	 * awareness (warmer tone, remembered in the brain's memory) - and send the reply back
	 * over the friend channel as an {@link L2FriendSay}, after a short human "read + react" pause plus the
	 * length-scaled typing time (so a friend replies in roughly 1-4s, not the slow ambient-whisper delay).
	 * Falls back to a short canned line if the brain is offline, so a friend PM is never met with silence.
	 * @param player the real player who sent the friend PM
	 * @param regular the regular phantom being messaged
	 * @param message the player's message text
	 */
	public void handleFriendMessage(Player player, Player regular, String message)
	{
		if ((player == null) || (regular == null) || (message == null) || message.isEmpty())
		{
			return;
		}
		final String regularName = regular.getName();
		final String playerName = player.getName();
		ThreadPool.schedule(() ->
		{
			// FRIEND mode: same stable persona as a whisper, but the brain knows you two are friends (warmer
			// tone) and writes the friendship into its memory so other channels pick it up too.
			final String aiReply = callBridge(regularName, "FRIEND", playerName, "", message, nearestLocation(regular), "", BotIdentity.of(regular));
			final String reply = ((aiReply != null) && !aiReply.isEmpty()) //
				? aiReply //
				: (Rnd.nextBoolean() ? "hey :)" : Rnd.nextBoolean() ? "sup" : "one sec, kinda busy");
			ThreadPool.schedule(() ->
			{
				if (player.isOnline())
				{
					player.sendPacket(new L2FriendSay(regularName, playerName, reply));
				}
			}, typingDelayMillis(reply));
		}, Rnd.get(FRIEND_THINK_MIN, FRIEND_THINK_MAX));
	}

	/**
	 * Answers a whisper (PM) sent to a befriended regular phantom - the same FRIEND-mode brain call as
	 * {@link #handleFriendMessage}, but the reply returns over the whisper channel the player used (before
	 * this hook a PM to a friend hit the clientless-target guard and reported "Player is in offline mode").
	 * @param player the real player who whispered
	 * @param regular the befriended regular being messaged
	 * @param message the player's message text
	 */
	public void handleFriendWhisper(Player player, Player regular, String message)
	{
		if ((player == null) || (regular == null) || (message == null) || message.isEmpty())
		{
			return;
		}
		final String regularName = regular.getName();
		final String playerName = player.getName();
		ThreadPool.schedule(() ->
		{
			final String aiReply = callBridge(regularName, "FRIEND", playerName, "", message, nearestLocation(regular), "", BotIdentity.of(regular));
			final String reply = ((aiReply != null) && !aiReply.isEmpty()) //
				? aiReply //
				: (Rnd.nextBoolean() ? "hey :)" : Rnd.nextBoolean() ? "sup" : "one sec, kinda busy");
			ThreadPool.schedule(() ->
			{
				if (player.isOnline())
				{
					player.sendPacket(new CreatureSay(regular, ChatType.WHISPER, regularName, reply));
				}
			}, typingDelayMillis(reply));
		}, Rnd.get(FRIEND_THINK_MIN, FRIEND_THINK_MAX));
	}

	/**
	 * @return the proper-cased name of a whisper-able roaming bot, or {@code null} if the name is not a
	 *         live fake player or it is an AFK store vendor (those are treated as offline shops)
	 */
	public String talkableBotName(String name)
	{
		final Npc bot = resolveBot(name);
		if (bot == null)
		{
			return null;
		}
		// AFK vendors are unreachable; a temporary deal vendor stays reachable for cancel/renegotiate.
		if (isStoreVendor(bot) && !FakePlayerBehaviorManager.getInstance().isDealVendor(bot))
		{
			return null;
		}
		return bot.getName();
	}

	/**
	 * Resolves a fake player by name: template bots through the spawn table, procedurally generated bots
	 * by scanning the live world (their names are not in {@link FakePlayerData}).
	 */
	// Cache for resolveBot() — maps lowercase bot name to live Npc reference.
	// Avoids O(n) scan of World.getVisibleObjects() on every whisper.
	private static final ConcurrentHashMap<String, Npc> BOT_NAME_CACHE = new ConcurrentHashMap<>();
	
	/**
	 * Resolves a fake player by name, using a fast cache and falling back to the world scan
	 * only when the cached reference is stale or missing.
	 */
	private Npc resolveBot(String name)
	{
		if ((name == null) || name.isEmpty())
		{
			return null;
		}
		
		// 1. Check cache first (fast path)
		final String lowerName = name.toLowerCase();
		final Npc cached = BOT_NAME_CACHE.get(lowerName);
		if ((cached != null) && cached.isSpawned() && name.equalsIgnoreCase(cached.getName()))
		{
			return cached;
		}
		
		// 2. Template fake players (registered in FakePlayerData)
		final String proper = FakePlayerData.getInstance().getProperName(name);
		if (proper != null)
		{
			final Spawn spawn = SpawnTable.getInstance().getAnySpawn(FakePlayerData.getInstance().getNpcIdByName(proper));
			if ((spawn != null) && (spawn.getLastSpawn() != null))
			{
				final Npc bot = spawn.getLastSpawn();
				BOT_NAME_CACHE.put(lowerName, bot);
				return bot;
			}
		}
		
		// 3. Procedurally generated bots — scan the live world (slow path, cached after first hit)
		for (WorldObject object : World.getInstance().getVisibleObjects())
		{
			if (object.isNpc())
			{
				final Npc npc = object.asNpc();
				if (npc.isFakePlayer() && name.equalsIgnoreCase(npc.getName()))
				{
					BOT_NAME_CACHE.put(lowerName, npc);
					return npc;
				}
			}
		}
		return null;
	}
	
	/**
	 * Call when a fake player despawns or is converted to a phantom, so the cache does not
	 * return a stale reference.
	 */
	public static void invalidateBotCache(String name)
	{
		if (name != null)
		{
			BOT_NAME_CACHE.remove(name.toLowerCase());
		}
	}

	private static String normalizeMeetSpot(String spot)
	{
		return FakePlayerChatParsing.normalizeMeetSpot(spot);
	}

	/**
	 * If the bot's whisper reply carries a {@code [[MEET:spot]]} tag, send it walking to that spot, then
	 * strip the tag so the player only sees the natural line.
	 * @return the cleaned reply text
	 */
	private String handleMeetRequest(String reply, Player player, Npc bot)
	{
		if (reply == null)
		{
			return "";
		}

		// RECRUIT tag: the bot agreed to leave town and join the player as a party member.
		// Despawns the FakePlayer NPC and converts it into a full phantom party member.
		if ((bot != null) && (player != null) && FakePlayerChatParsing.RECRUIT_TAG.matcher(reply).find())
		{
			PhantomActionHandler.getInstance().convertToPhantom(player, bot);
			return "Let's go!";
		}

		// FOLLOW tag: bot agrees to follow the player for N minutes.
		if ((bot != null) && (player != null) && (reply.contains("FOLLOW") || reply.contains("follow")))
		{
			final java.util.regex.Matcher followM = FakePlayerChatParsing.FOLLOW_TAG.matcher(reply);
			if (followM.find())
			{
				int minutes = 10;
				if ((followM.group(1) != null) && !followM.group(1).isEmpty())
				{
					try { minutes = Integer.parseInt(followM.group(1)); }
					catch (Exception e) { minutes = 10; }
					if (minutes < 1) minutes = 1;
					if (minutes > 120) minutes = 120;
				}
				FakePlayerBehaviorManager.getInstance().requestFollow(bot, player, minutes);
				final String cleaned = FakePlayerChatParsing.FOLLOW_TAG.matcher(reply).replaceAll("").trim();
				return cleaned.isEmpty() ? ("ok follow for " + minutes + " min") : cleaned;
			}
		}

		boolean cancelled = false;
		boolean handledShop = false;

		// Roaming bots and bots running a temporary deal store both negotiate here (the latter so the player
		// can renegotiate or cancel mid-deal); only static AFK vendors are excluded.
		if ((bot != null) && (!isStoreVendor(bot) || FakePlayerBehaviorManager.getInstance().isDealVendor(bot)))
		{
			// SHOP tag: the bot commits to a real store for a specific item/price. Open it now if it is
			// already waiting with the player, otherwise arm it and make sure it walks over to meet.
			final Matcher shop = SHOP_TAG.matcher(reply);
			if (shop.find())
			{
				final boolean botSells = "SELL".equalsIgnoreCase(shop.group(1));
				final ItemTemplate item = FakePlayerStoreFactory.findItemByName(shop.group(2));
				if (item != null)
				{
					final int price = FakePlayerChatParsing.applyShopPriceMultiplier(Integer.parseInt(shop.group(3)), shop.group(4));

					final int storeType = botSells ? PrivateStoreType.SELL.getId() : PrivateStoreType.BUY.getId();
					final FakePlayerBehaviorManager behavior = FakePlayerBehaviorManager.getInstance();
					final int requestedCount = behavior.getPendingDealCount(bot, item.getId());
					final List<FakePlayerStoreItem> stock = botSells ? FakePlayerStoreFactory.dealSellStock(item.getId(), price, requestedCount) : FakePlayerStoreFactory.dealBuyStock(item.getId(), price, requestedCount);
					if (!stock.isEmpty())
					{
						final String title = FakePlayerStoreFactory.title(botSells ? "SELL" : "BUY", stock);
						handledShop = true;
						if (behavior.isWaitingAtMeet(bot))
						{
							behavior.openDealNow(bot, storeType, stock, title); // already here -> open immediately
						}
						else
						{
							behavior.setupDeal(bot, storeType, stock, title);
							final Matcher meet = MEET_TAG.matcher(reply);
							final String spot = (meet.find() && !"cancel".equalsIgnoreCase(normalizeMeetSpot(meet.group(1)))) ? normalizeMeetSpot(meet.group(1)) : "gatekeeper";
							behavior.requestMeet(bot, spot, player);
						}
					}
				}
			}

			if (!handledShop)
			{
				// Plain MEET handling (no shop committed this line).
				final Matcher meet = MEET_TAG.matcher(reply);
				if (meet.find())
				{
					final String spot = normalizeMeetSpot(meet.group(1));
					if ("cancel".equalsIgnoreCase(spot))
					{
						FakePlayerBehaviorManager.getInstance().cancelMeet(bot);
						ACTIVE_DEALS.remove(dealKey(player.getName(), bot.getName()));
						cancelled = true;
					}
					else
					{
						FakePlayerBehaviorManager.getInstance().requestMeet(bot, spot, player);
					}
				}
				else
				{
					// No tag: if already waiting for this player, they are still engaged -> keep waiting.
					FakePlayerBehaviorManager.getInstance().noteMeetInteraction(bot, player);
				}
			}
		}

		final String cleaned = MEET_TAG.matcher(SHOP_TAG.matcher(reply).replaceAll("")).replaceAll("").trim();
		if (!cleaned.isEmpty())
		{
			return cleaned;
		}
		return cancelled ? "k np" : "omw";
	}

	/** A seated private-store vendor is an AFK shop and never chats. */
	private static boolean isStoreVendor(Npc npc)
	{
		final FakePlayerAppearance look = npc.getFakePlayerAppearance();
		return (look != null) && (look.getPrivateStoreType() != 0);
	}

	/** @return a short phrase like "in Giran" or "near Aden" for the bot's current position (NPC or phantom). */
	public static String jsonEscape(String s)
	{
		if ((s == null) || s.isEmpty())
		{
			return "";
		}
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
	
	/**
	 * Builds a JSON context payload for the brain, merging bot identity, dynamic state, and chat metadata.
	 */
	public static String buildJsonPayload(String fpcName, String mode, String playerName, String speakerName, String body, String location, String deal, boolean human, BrainDealContext dealContext, BotIdentity identity, Npc bot)
	{
		final StringBuilder json = new StringBuilder();
		json.append("{\"message\":\"").append(jsonEscape(body)).append("\",");
		json.append("\"bot\":{");
		json.append("\"name\":\"").append(jsonEscape(fpcName)).append("\"");
		if (identity != null)
		{
			json.append(",\"level\":\"").append(jsonEscape(identity.level)).append("\"");
			json.append(",\"class\":\"").append(jsonEscape(identity.clazz)).append("\"");
			json.append(",\"race\":\"").append(jsonEscape(identity.race)).append("\"");
			json.append(",\"sex\":\"").append(jsonEscape(identity.sex)).append("\"");
			json.append(",\"gear\":\"").append(jsonEscape(identity.gear)).append("\"");
		}
		if (bot != null)
		{
			json.append(",\"state\":\"").append(jsonEscape(botState(bot))).append("\"");
			json.append(",\"sitting\":\"").append(bot.getFakePlayerAppearance() != null && bot.getFakePlayerAppearance().isSitting() ? "true" : "false").append("\"");
		}
		json.append(",\"zone\":\"").append(jsonEscape(location)).append("\"");
		json.append("},");
		json.append("\"chat\":{");
		json.append("\"mode\":\"").append(jsonEscape(mode)).append("\"");
		json.append(",\"speaker\":\"").append(jsonEscape(speakerName)).append("\"");
		json.append(",\"player\":\"").append(jsonEscape(playerName)).append("\"");
		json.append(",\"human\":\"").append(human ? "true" : "false").append("\"");
		json.append(",\"deal\":\"").append(jsonEscape(deal)).append("\"");
		json.append("}}");
		return json.toString();
	}
	
	/**
	 * Overload of buildJsonPayload that accepts a BotContext record for rich context.
	 */
	public static String buildJsonPayload(String body, String mode, String playerName, String speakerName, BotContext ctx)
	{
		if (ctx == null) ctx = BotContext.EMPTY;
		final StringBuilder json = new StringBuilder();
		json.append("{\"message\":\"").append(jsonEscape(body)).append("\",");
		json.append("\"bot\":{");
		json.append("\"name\":\"").append(jsonEscape(ctx.name())).append("\"");
		if (!ctx.level().isEmpty()) json.append(",\"level\":\"").append(jsonEscape(ctx.level())).append("\"");
		if (!ctx.clazz().isEmpty()) json.append(",\"class\":\"").append(jsonEscape(ctx.clazz())).append("\"");
		if (!ctx.race().isEmpty()) json.append(",\"race\":\"").append(jsonEscape(ctx.race())).append("\"");
		if (!ctx.sex().isEmpty()) json.append(",\"sex\":\"").append(jsonEscape(ctx.sex())).append("\"");
		if (!ctx.gear().isEmpty()) json.append(",\"gear\":\"").append(jsonEscape(ctx.gear())).append("\"");
		if (!ctx.state().isEmpty()) json.append(",\"state\":\"").append(jsonEscape(ctx.state())).append("\"");
		if (!ctx.zone().isEmpty()) json.append(",\"zone\":\"").append(jsonEscape(ctx.zone())).append("\"");
		if (!ctx.weapon().isEmpty()) json.append(",\"weapon\":\"").append(jsonEscape(ctx.weapon())).append("\"");
		if (!ctx.armor().isEmpty()) json.append(",\"armor\":\"").append(jsonEscape(ctx.armor())).append("\"");
		if (!ctx.hpMp().isEmpty()) json.append(",\"hp_mp\":\"").append(jsonEscape(ctx.hpMp())).append("\"");
		if (ctx.adena() > 0) json.append(",\"adena\":\"").append(ctx.adena()).append("\"");
		if (!ctx.nearbyMobs().isEmpty()) json.append(",\"nearby_mobs\":\"").append(jsonEscape(ctx.nearbyMobs())).append("\"");
		if (!ctx.partyWith().isEmpty()) json.append(",\"party_with\":\"").append(jsonEscape(ctx.partyWith())).append("\"");
		if (!ctx.partyRole().isEmpty()) json.append(",\"party_role\":\"").append(jsonEscape(ctx.partyRole())).append("\"");
		if (ctx.partied()) json.append(",\"partied\":\"true\"");
		json.append("},");
		json.append("\"chat\":{");
		json.append("\"mode\":\"").append(jsonEscape(mode)).append("\"");
		json.append(",\"speaker\":\"").append(jsonEscape(speakerName)).append("\"");
		json.append(",\"player\":\"").append(jsonEscape(playerName)).append("\"");
		json.append(",\"human\":\"true\"");
		json.append(",\"deal\":\"\"");
		json.append("}}");
		return json.toString();
	}
	
	/**
	 * Derives a human-readable state string from the bot's current activity.
	 */
	public static String botState(Npc bot)
	{
		if (bot == null) return "idle";
		final FakePlayerAppearance look = bot.getFakePlayerAppearance();
		if (look != null && look.isSitting()) return "sitting";
		if (bot.isInCombat() || bot.isAttackingNow()) return "fighting";
		if (bot.isCastingNow()) return "casting";
		return "idle";
	}

		private static String nearestLocation(Creature creature)
	{
		if (creature == null)
		{
			return "";
		}
		int best = -1;
		long bestDistanceSq = Long.MAX_VALUE;
		for (int i = 0; i < TOWN_COORDS.length; i++)
		{
			final long dx = creature.getX() - TOWN_COORDS[i][0];
			final long dy = creature.getY() - TOWN_COORDS[i][1];
			final long distanceSq = (dx * dx) + (dy * dy);
			if (distanceSq < bestDistanceSq)
			{
				bestDistanceSq = distanceSq;
				best = i;
			}
		}
		if (best < 0)
		{
			return "";
		}
		return (Math.sqrt(bestDistanceSq) < 3000 ? "in " : "near ") + TOWN_NAMES[best];
	}
	
		private String extractJsonValue(String json, String key)
	{
		final String search = "\"" + key + "\":\"";
		final int start = json.indexOf(search);
		if (start < 0) return null;
		final int valueStart = start + search.length();
		final int end = json.indexOf("\"", valueStart);
		if (end < 0) return null;
		return json.substring(valueStart, end);
	}
	
	public static FakePlayerChatManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}
	
	private static class SingletonHolder
	{
		protected static final FakePlayerChatManager INSTANCE = new FakePlayerChatManager();
	}
}