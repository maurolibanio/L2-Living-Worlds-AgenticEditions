# L2 Living Worlds — Agentic Edition

> **Disclaimer:** This fork is an experimental lab built with AI agents. It is not recommended for production use. The main goal is to test ideas for the original L2 Living Worlds project and/or serve as inspiration. Nothing here should be used out of the box without thorough review.

---

## What we did (or tried)

### L2 Control Panel
Web admin panel to manage the server without touching the terminal. Toggle features, adjust counts, save and restart the GameServer in one click. Flask + vanilla JS, dark mode, responsive.

### AI Chat for Fake Players
Bots that talk to players via Ollama or DeepSeek. Each bot knows its level, class, race, gear, zone, party members, nearby mobs, HP/MP, and adena. Context is sent as JSON POST to a Python bridge.

### Knowledge Base
2559 tagged facts (zones, items, buffs, classes, mobs, teleports) that ground the LLM responses so bots don't hallucinate locations or items. Indexed by word for O(1) lookup.

### Bot Personalities
Each bot has a unique voice derived from its name — tone, casing style, filler habits, and spelling. No two bots sound the same.

### Smart Spawn
Phantoms only spawn when a real player is nearby. 30-second grace period before despawning. Built on top of L2J's existing PhantomManager.

### Chat Optimizations
- Rate limiting (45s cooldown per bot, per-bot lock)
- Response cache (LRU 200 entries)
- Global dedup (30s window)
- 95% skip on ambient chatter
- 30% reply chance on /say
- Bot-to-bot banter disabled
- Batch memory writes (every 30s, not per interaction)
- ZONEUPDATE on teleport (no tick loop)

### Java Optimizations
- resolveBot() cache (avoids O(n) world scan)
- BRAIN_URL configurable via FakePlayers.ini
- ZoneNames.ini + ZoneManager for real zone names
- Teleport hook for silent context updates

### Infrastructure
- 5 systemd services (game, login, admin, bridge, brain)
- socat tunnel for forwarding brain requests to a separate LLM host
- L2Admin scripts for start/stop

---

## Repo structure

```
server/     Modified Java source, configs, data, systemd services
brain/      Python LLM bridge + 19 knowledge base files
l2admin/    Flask web admin panel + templates + scripts
```
