package org.l2jmobius.gameserver.helpers;

public record BotContext(
	String name,
	String level,
	String clazz,
	String race,
	String sex,
	String gear,
	String zone,
	String state,
	String weapon,
	String armor,
	String hpMp,
	long adena,
	String nearbyMobs,
	String partyWith,
	String partyRole,
	boolean partied
)
{
	public static final BotContext EMPTY = new BotContext("", "", "", "", "", "", "", "", "", "", "", 0L, "", "", "", false);
}
