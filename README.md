# L2 Living Worlds — Agentic Edition

> **Lineage 2 Interlude (C6)** private server with AI-powered NPCs, smart phantom populations, and adaptive world simulation.

A fork of [L2J Mobius CT 0 Interlude](https://www.l2jmobius.org/) extended with:
- **LLM-driven NPC chat** — 989 bots that talk like real players (Ollama / DeepSeek)
- **Smart phantom populations** — hunters that spawn/despawn based on player proximity
- **L2 Control Panel** — web admin for server management

---

## 🧠 AI Chat System

### Architecture

```
┌──────────────┐     socat tunnel     ┌──────────────────┐
│  GameServer  │ ◄──────────────────► │  Brain (Python)  │
│  (Java, CT)  │   127.0.0.1:5000     │  (Alienware)     │
│  :7777       │                      │  Ollama/DeepSeek │
└──────┬───────┘                      └────────┬─────────┘
       │                                       │
       │  JSON POST (full context)             │ chat.completions
       │  { bot, chat, message }               │
       │                                       │
       │  Legacy headers fallback               │
       │  X-FPC, X-Mode, X-Location           │
```

### Key Features

| Feature | Detail |
|---------|--------|
| **Provider** | Ollama (llama3.1) or DeepSeek, configurable via env |
| **Rate Limit** | 45s cooldown per bot, per-bot lock (no global contention) |
| **Response Cache** | LRU 200 entries, MD5-keyed, auto-evict |
| **Global Dedup** | 30s window — prevents multiple bots answering the same message |
| **Ambient Skip** | 95% — spontaneous chatter is sparse and natural |
| **Say Chance** | 30% — local proximity chat is responsive but not spammy |
| **Knowledge Base** | 2559 tagged facts, indexed by word (O(1) lookup) |
| **Memory** | Player-global persistent memory, batch-flushed every 30s |
| **Metrics** | `/metrics` endpoint — llm_calls, cache_hits, rate_limited |

### Chat Modes

| Mode | Description | Priority |
|------|-------------|----------|
| `WHISPER` | Private message to a bot | High (never rate-limited) |
| `FRIEND` | Friend-list PM, warmer tone | High |
| `BUDDY` | Personal support buddy chat | High |
| `PARTY` | Combat party member chat | High |
| `SAY` | Local proximity chat | Medium |
| `SHOUT` | Global `!` world channel | Medium |
| `TRADE` | Public trade channel | Medium |
| `OFFER` | Proactive trade whisper | High |
| `ITEM` | Item name resolution | Internal |
| `LFP` | Party role classification | Internal |
| `AMBIENT` | Spontaneous trade ad | Low (95% skip) |
| `SHOUTAMBIENT` | Spontaneous shout | Low (95% skip) |

### Bot Context (JSON POST)

Each chat request carries full bot identity:

```json
{
  "message": "wts ssd 40k each",
  "bot": {
    "name": "Haldor",
    "level": "52",
    "class": "Warlord",
    "race": "Orc",
    "sex": "Male",
    "gear": "Composite Armor + Dual SLS",
    "state": "idle",
    "zone": "Giran Castle Town",
    "weapon": "Great Sword",
    "armor": "Composite Armor",
    "hp_mp": "1200/1200",
    "adena": "45000000",
    "nearby_mobs": "Leto Lizardman, Breka Warlock",
    "party_with": "Mauro(lvl72,PhantomRanger)",
    "party_role": "dd",
    "partied": "true"
  },
  "chat": {
    "mode": "TRADE",
    "speaker": "Mauro",
    "player": "Mauro",
    "human": "true"
  }
}
```

### Bot Personality System

Each bot has a **stable, unique voice** derived from hashing its name:
- 10 tones (chill veteran, grumpy, hyper, sarcastic, business, newbie, elitist, joker, terse, grinder)
- 4 casing styles (lowercase, tidy, no-punctuation, caps-emphasis)
- 6 filler habits (lol, :P, ..., mate, plain, txt-speak)
- 3 spelling levels (clean, occasional typo, heavy txt-speak)
- Temperature range 0.8–1.25 per bot

---

## 👻 Phantom Population System

### Proximity-Aware Spawning

Phantoms (AI-controlled player characters) exist only when real players are nearby:

```
         ┌─────────────────────────────────────────────────┐
         │           PhantomManager.supervisor              │
         │  (runs every tick)                              │
         │                                                 │
         │  1. Get all online players (observers)           │
         │  2. For each Population:                        │
         │     ├─ Is any player within                      │
         │     │  center + radius + ACTIVATION_MARGIN(2000)?│
         │     │  └─ Yes → activate() → spawn phantoms      │
         │     └─ No one near for 30s (DEACTIVATE_DELAY)?   │
         │        └─ Yes → deactivate() → despawn phantoms  │
         └─────────────────────────────────────────────────┘
```

### Population Types

| Type | Behavior | Spawn Rule |
|------|----------|-----------|
| **Hunters** | Auto-hunt mobs in their zone | Proximity to zone center |
| **Buddies** | Support chars (healer/buffer) | Follow owner, grace on logout |
| **Town NPCs** | Static vendors, idle | Permanently active |
| **Recruited** | Party members joined via chat | Owned by player, no despawn |

### Configuration

Defined in `server/data/PhantomPopulations.xml`:

```xml
<population name="Giran Hunters" count="8">
  <center x="82000" y="145000" z="-3400" />
  <radius value="3000" />
  <level min="35" max="45" />
  <respawn value="true" />
</population>
```

---

## 💬 Chat System — Java Side

### Key Files

| File | Role |
|------|------|
| `FakePlayerChatManager.java` | Chat dispatch, rate limiting, JSON POST builder, HTTP bridge |
| `FakePlayerChatParsing.java` | MEET/SHOP/RECRUIT tag parsing, price multiplier |
| `FakePlayerSocialManager.java` | Social timer, ambient chat scheduling |
| `FakePlayerBehaviorManager.java` | Bot deploy, despawn, behavior profiles |
| `FakePlayerStoreFactory.java` | Shop item stock generation |
| `BotContext.java` | Bot state record (15 fields) |
| `NpcContextHelper.java` | Zone name, weapon, armor, mob, party context builders |
| `FakePlayersConfig.java` | Config loader from `FakePlayers.ini` |

### Chat Flow

```
1. Player whispers bot → resolveBot(name) → cache hit/miss
2. buildJsonPayload() → full context JSON
3. sendJsonToBrain() → HTTP POST to brain:5000
4. Brain processes → rate limit check → cache check → LLM call
5. Reply returned → handleMeetRequest() → sendChat() with typing delay
6. remember_from_exchange() → memory update
7. ZoneUPDATE on teleport → silent context update (no reply)
```

### Rate Limiting (Java Side)

| Setting | Value |
|---------|-------|
| REPLY_CHANCE_TO_PLAYER | 3% |
| MAX_MESSAGES_PER_MINUTE | 3 |
| MAX_REPLIERS | 1 |
| REPLY_STAGGER_MS | 4000 |
| Typing delay | 400ms + 45ms/char, capped 4000ms |
| Ambient interval | 15min (trade), 20min (shout) |

### Configurable (FakePlayers.ini)

```ini
BrainUrl = http://127.0.0.1:5000/chat
FakePlayerChat = True
FakePlayerBehavior = True
FakePlayerUseShots = True
FakePlayerAggroMonsters = True
FakePlayerPartyQuestCredit = True
PhantomPartyXp = True
```

---

## 🎮 L2 Control Panel

A web-based admin panel for server management, built with Flask + vanilla JS.

### Features

| Feature | Description |
|---------|-------------|
| **Server Status** | Real-time GameServer health, uptime, port status |
| **Fake Player Toggle** | Enable/disable 989 bots without restart |
| **Chat Toggle** | Enable/disable LLM chat bridge |
| **Behavior Toggle** | Enable/disable autonomous bot movement |
| **Aggro Settings** | Bot aggro vs monsters, players, other bots |
| **Count Control** | Adjust bot deploy count live |
| **Shots Toggle** | Enable/disable bot shot usage |
| **Quest Credit** | Party quest credit toggle + range config |
| **Save + Restart** | Apply changes and trigger GameServer restart |

### Systemd Integration

```bash
# Service management
systemctl status l2-game.service    # GameServer (port 7777)
systemctl status l2-login.service   # LoginServer (port 2106)
systemctl status l2-admin.service   # Admin panel (port 8080)
systemctl status l2-brain-tunnel.service  # Socat tunnel to LLM

# Logs
journalctl -u l2-game.service -f
journalctl -u l2-admin.service -f
```

---

## 🧪 Performance Optimizations

| Optimization | Before | After |
|-------------|--------|-------|
| BRAIN_URL | Hardcoded `127.0.0.1:5000` | Configurable via `FakePlayers.ini` |
| save_memory() | Write to disk on every fact | Batch flush every 30s |
| GLOBAL_RULES injection | ~750 chars per request | ~400 chars per request |
| resolveBot() | O(n) scan over all world objects | O(1) cache lookup |
| Knowledge retrieval | O(n) scan over 2559 facts | O(1) per-word index lookup |
| Conversations | Unbounded growth | LRU cache, max 500 entries |
| Rate limiting | Global lock contention | Per-bot lock |
| Bot spawn | All active always | Proximity-based (30s grace) |
| Zone context | `nearestLocation()` heuristic | `ZoneManager.getZones()` + `ZoneNames.ini` |

---

## 🚀 Deployment

### Prerequisites

- Java 25 (Temurin) for GameServer
- Python 3.10+ for brain + admin panel
- Ollama (or DeepSeek API key) for LLM
- MariaDB/MySQL for game database
- socat for brain tunnel

### Quick Start

```bash
# 1. GameServer
systemctl enable --now l2-login.service
systemctl enable --now l2-game.service

# 2. Brain (on LLM host)
cp brain/fpc_brain.py /opt/l2-brain/
pip install flask openai python-dotenv
python3 /opt/l2-brain/fpc_brain.py

# 3. Admin Panel
systemctl enable --now l2-admin.service

# 4. Brain Tunnel (if brain on separate host)
systemctl enable --now l2-brain-tunnel.service
```

### Environment Variables (Brain)

| Variable | Default | Description |
|----------|---------|-------------|
| `PROVIDER` | `deepseek` | `ollama` or `deepseek` |
| `DEEPSEEK_API_KEY` | — | Required for DeepSeek |
| `OLLAMA_MODEL` | `llama3.1` | Ollama model name |

---

## 📁 Repository Structure

```
L2-Living-Worlds-AgenticEditions/
├── README.md                 # This file
├── LICENSE                   # GPLv3
├── server/
│   ├── java/                 # Modified L2J Mobius Java source
│   │   ├── FakePlayerChatManager.java
│   │   ├── PhantomManager.java
│   │   ├── PhantomPartyManager.java
│   │   └── ... (12 files)
│   ├── config/
│   │   ├── Custom/
│   │   │   ├── FakePlayers.ini
│   │   │   └── ... (40+ config files)
│   │   └── ZoneNames.ini
│   ├── data/
│   │   ├── PhantomPopulations.xml
│   │   ├── FakePlayerBehavior.xml
│   │   └── FakePlayerChatData.xml
│   └── systemd/
│       ├── l2-game.service
│       ├── l2-login.service
│       ├── l2-admin.service
│       └── l2-brain-tunnel.service
├── brain/
│   ├── fpc_brain.py           # LLM bridge (1195 lines)
│   ├── knowledge/              # 19 fact files (2559 facts)
│   │   ├── 00_general.txt
│   │   ├── 10_locations.txt
│   │   ├── 50_items.txt
│   │   └── ... (19 files)
│   └── requirements.txt
└── l2admin/
    ├── app.py                 # Flask admin panel (396 lines)
    ├── templates/
    │   └── index.html
    └── scripts/
        ├── kill_game.sh
        └── start_game.sh
```

---

## 📜 License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

Original L2J Mobius project: [github.com/L2jMobius](https://github.com/L2jMobius)

---

*Built with ❤️ by the L2 Living Worlds team. Bots that feel like people, worlds that feel alive.*