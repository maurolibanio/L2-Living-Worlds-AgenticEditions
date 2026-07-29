# L2 Living Worlds — Agentic Edition

> **Disclaimer:** This fork is an experimental lab built with AI agents. It is not recommended for production use. The main goal is to test ideas for the original L2 Living Worlds project and/or serve as inspiration. Nothing here should be used out of the box without thorough review.

---

## What we did (or tried)

### L2 Control Panel
Web admin panel to manage the server without touching the code. Toggle features, add or remove NPCs as Global Gatekeeper, GM Shop, Buffer, adjust rates, save and restart the GameServer in one click.

### Context for Fake Players and Phantom
Bots that talk to players. Each bot knows where they are, its level, class, race, gear, zone, party members, nearby mobs, HP/MP, and adena. Context is sent as JSON POST to a Python bridge.

### Knowledge Base
2559 tagged facts (zones, items, buffs, classes, mobs, teleports) that ground the LLM responses so bots don't hallucinate locations or items. Indexed by word for O(1) lookup. So you can use fake players or phantom to ask questions, where to level, where to buy something, where to get a quest... etc.

### Bot Personalities
Each bot has a unique personality derived from its race and class — tone, casing style, filler habits, and spelling. No two bots sound the same.

### And other stuff here and there..

---

## Repo structure

```
server/     Modified Java source, configs, data, systemd services
brain/      Python LLM bridge + 19 knowledge base files
l2admin/    Flask web admin panel + templates + scripts
```
