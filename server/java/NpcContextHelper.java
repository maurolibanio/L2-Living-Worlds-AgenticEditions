package org.l2jmobius.gameserver.helpers;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.model.World;
import org.l2jmobius.gameserver.model.actor.Player;
import org.l2jmobius.gameserver.model.actor.instance.Monster;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.zone.ZoneType;

public final class NpcContextHelper
{
    private static final Logger LOGGER = Logger.getLogger(NpcContextHelper.class.getName());
    private static final Map<String, String> ZONE_DISPLAY_NAMES = new HashMap<>();
    
    static
    {
        try
        {
            final File configFile = new File("config/ZoneNames.ini");
            if (configFile.exists())
            {
                for (String line : Files.readAllLines(configFile.toPath()))
                {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    final String[] parts = line.split("=", 2);
                    if (parts.length == 2) ZONE_DISPLAY_NAMES.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        catch (Exception e) { LOGGER.fine("NpcContextHelper: Could not load ZoneNames.ini: " + e.getMessage()); }
    }
    
    private NpcContextHelper() { }
    
    public static String displayZone(String rawZone)
    {
        if ((rawZone == null) || rawZone.isEmpty()) return "";
        final String mapped = ZONE_DISPLAY_NAMES.get(rawZone);
        if (mapped != null) return mapped;
        String cleaned = rawZone.replace("_zone", "").replace("town_of_", "");
        final String mapped2 = ZONE_DISPLAY_NAMES.get(cleaned);
        if (mapped2 != null) return mapped2;
        return titleCase(cleaned);
    }
    
    public static String titleCase(String enumName)
    {
        if ((enumName == null) || enumName.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder();
        for (String word : enumName.toLowerCase().split("_"))
        {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
    
    public static String getZoneName(Player player)
    {
        if (player == null) return "";
        try
        {
            final List<ZoneType> zones = ZoneManager.getInstance().getZones(player);
            if ((zones != null) && !zones.isEmpty())
            {
                final String rawName = zones.get(0).getName();
                if ((rawName != null) && !rawName.isEmpty()) return displayZone(rawName);
            }
        }
        catch (Exception e) { }
        return "";
    }
    
    public static String getWeaponName(Player player)
    {
        if (player == null) return "";
        try { Item w = player.getActiveWeaponInstance(); if (w != null) return w.getTemplate().getName(); } catch (Exception e) { }
        return "";
    }
    
    public static String getArmorName(Player player)
    {
        if (player == null) return "";
        try { Item a = player.getChestArmorInstance(); if (a != null) return a.getTemplate().getName(); } catch (Exception e) { }
        return "";
    }
    
    public static String getNearbyMobs(Player player)
    {
        if (player == null) return "";
        try
        {
            final List<Monster> nearby = World.getInstance().getVisibleObjectsInRange(player, Monster.class, 1500);
            final List<String> mobs = new ArrayList<>();
            for (Monster m : nearby) { if (m.isInCombat() && mobs.size() < 3) mobs.add(m.getName()); }
            return mobs.isEmpty() ? "" : String.join(", ", mobs);
        }
        catch (Exception e) { }
        return "";
    }
    
    public static String getHpMp(Player player)
    {
        if (player == null) return "";
        if (player.isInCombat() || player.isAttackingNow() || (player.getCurrentHp() < player.getMaxHp() * 0.3))
            return (int) player.getCurrentHp() + "/" + (int) player.getMaxHp();
        return "";
    }
    
    public static String getPartyContext(Player player)
    {
        if ((player == null) || !player.isInParty()) return "";
        final StringBuilder sb = new StringBuilder();
        for (Player member : player.getParty().getMembers())
        {
            if (member == player) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(member.getName()).append("(lvl").append(member.getLevel()).append(",");
            sb.append(member.getPlayerClass() != null ? titleCase(member.getPlayerClass().name()) : "Unknown").append(")");
        }
        return sb.toString();
    }
    
    public static String getState(Player player)
    {
        if (player == null) return "idle";
        if (player.isInCombat() || player.isAttackingNow()) return "fighting";
        if (player.isCastingNow()) return "casting";
        return "idle";
    }
}
