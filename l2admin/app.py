#!/usr/bin/env python3
"""L2 Living Worlds Control Panel - Simplified"""

from flask import Flask, render_template_string, request, jsonify
import subprocess
import os
import re
import time

app = Flask(__name__)
CONFIG_DIR = "/root/l2server-runtime/game/config"
SPAWN_DIR = "/root/l2server-runtime/game/data/spawns/Custom"
EVENT_DIR = "/root/l2server-runtime/game/data/scripts/custom/events"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def read_val(path, key):
    try:
        with open(path) as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#"):
                    continue
                if "=" in s:
                    k, v = s.split("=", 1)
                    if k.strip() == key:
                        return v.strip()
    except Exception:
        pass
    return None


def write_val(path, key, val):
    try:
        with open(path) as f:
            lines = f.readlines()
        found = False
        for i, line in enumerate(lines):
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if "=" in s:
                k, _ = s.split("=", 1)
                if k.strip() == key:
                    lines[i] = key + " = " + str(val) + "\n"
                    found = True
                    break
        if not found:
            lines.append(key + " = " + str(val) + "\n")
        with open(path, "w") as f:
            f.writelines(lines)
        return True
    except Exception:
        return False


def read_spawn_enabled(filepath):
    try:
        with open(filepath) as f:
            content = f.read()
        m = re.search(r'<list\s+enabled="(true|false)"', content)
        if m:
            return m.group(1) == "true"
        return True
    except Exception:
        return True


def write_spawn_enabled(filepath, enabled):
    val = "true" if enabled else "false"
    try:
        with open(filepath) as f:
            content = f.read()
        content = re.sub(
            r'(<list\s+enabled=")(true|false)(")',
            r"\g<1>" + val + r"\g<3>",
            content,
        )
        with open(filepath, "w") as f:
            f.write(content)
        return True
    except Exception:
        return False


def read_event_enabled(event_dir):
    path = os.path.join(event_dir, "config.xml")
    try:
        with open(path) as f:
            for line in f:
                if "<schedule" not in line or "pattern=" not in line:
                    continue
                stripped = line.strip()
                if stripped.startswith("<!--") or "<!--" in stripped:
                    return False
                return True
        return False
    except Exception:
        return False


def write_event_enabled(event_dir, enabled):
    path = os.path.join(event_dir, "config.xml")
    try:
        with open(path) as f:
            content = f.read()
        currently_enabled = read_event_enabled(event_dir)
        if enabled == currently_enabled:
            return True
        if enabled:
            content = re.sub(r"<!--\s*(<schedule[^>]*/>)\s*-->", r"\1", content)
        else:
            content = re.sub(r"(<schedule[^>]*/>)", r"<!-- \1 -->", content)
        with open(path, "w") as f:
            f.write(content)
        return True
    except Exception:
        return False

# ---------------------------------------------------------------------------
# NPC definitions
# ---------------------------------------------------------------------------

NPC_TOGGLES = [
    ("npc_gatekeeper", "Global Gatekeeper", "Teras - teleport between all towns", "TerasGlobalGatekeeper.xml"),
    ("npc_buffer", "Scheme Buffer", "Thiago - free buffs in Giran", "SchemeBuffer.xml"),
    ("npc_noblesse", "Noblesse Master", "Kadmos - noblesse management", "NoblesseMaster.xml"),
    ("npc_delevel", "Delevel Manager", "Jeadin - delevel/reset character", "DelevelManager.xml"),
    ("npc_transmog", "Transmogrifier", "Zumzi - weapon/armor appearance", "Transmog.xml"),
    ("npc_wedding", "Wedding Manager", "Andromeda - marriage system", "WeddingManager.xml"),
    ("npc_core_tp", "Core Teleporter", "Teleport to hunting zones", "CoreTeleporter.xml"),
]

EVENTS = [
    ("event_tvt", "Team vs Team", "Scheduled PvP event - teams fight for points", "TeamVsTeam"),
    ("event_ctf", "Capture the Flag", "Scheduled PvP event - capture the enemy flag", "CaptureTheFlag"),
    ("event_dm", "Deathmatch", "Scheduled PvP event - free-for-all arena", "Deathmatch"),
    ("event_race", "Race", "Scheduled race event - run for prizes", "Race"),
    ("event_elpies", "Elpies", "Scheduled event - hunt Elpies for rewards", "Elpies"),
]

# ---------------------------------------------------------------------------
# Categories — simplified: only what the GM actually uses
# ---------------------------------------------------------------------------

CATS = (
    (
        "rates",
        "Rates",
        "Server multipliers (1 = retail)",
        (
            ("rate_xp", "XP Rate", "Rates.ini", "RateXp", "range", 1, 100, 1),
            ("rate_sp", "SP Rate", "Rates.ini", "RateSp", "range", 1, 100, 1),
            ("rate_drop", "Drop Rate", "Rates.ini", "DeathDropChanceMultiplier", "range", 1, 10, 1),
            ("rate_spoil", "Spoil Rate", "Rates.ini", "SpoilDropChanceMultiplier", "range", 1, 10, 1),
            ("rate_quest_xp", "Quest XP", "Rates.ini", "RateQuestRewardXP", "range", 1, 50, 1),
            ("rate_quest_adena", "Quest Adena", "Rates.ini", "RateQuestRewardAdena", "range", 1, 50, 1),
        ),
    ),
    (
        "progression",
        "Progression",
        "Auto-learn skills, auto-loot, phantom XP",
        (
            ("autolearn_skills", "Auto-Learn Skills", "Player.ini", "AutoLearnSkills", "toggle", "True", "False", "False"),
            ("autoloot", "Auto-Loot Items", "Player.ini", "AutoLoot", "toggle", "True", "False", "False"),
            ("phantom_xp", "Phantom Party XP", "Custom/FakePlayers.ini", "PhantomPartyXp", "toggle", "True", "False", "False"),
        ),
    ),
    (
        "custom",
        "Custom Features",
        "Extra server systems",
        (
            ("premium", "Premium / VIP System", "Custom/PremiumSystem.ini", "EnablePremiumSystem", "toggle", "True", "False", "False"),
            ("banking", "Banking System", "Custom/Banking.ini", "BankingEnabled", "toggle", "True", "False", "False"),
            ("wedding", "Wedding System", "Custom/Wedding.ini", "AllowWedding", "toggle", "True", "False", "False"),
            ("faction", "Faction System", "Custom/FactionSystem.ini", "FactionSystemEnabled", "toggle", "True", "False", "False"),
            ("custom_cb", "Custom Community Board", "Custom/CommunityBoard.ini", "CustomCommunityBoard", "toggle", "True", "False", "False"),
            ("transmog", "Transmog System", "Custom/Transmog.ini", "TransmogEnabled", "toggle", "True", "False", "False"),
            ("noblesse", "Noblesse Master System", "Custom/NoblessMaster.ini", "Enabled", "toggle", "True", "False", "False"),
            ("delevel", "Delevel Manager System", "Custom/DelevelManager.ini", "Enabled", "toggle", "True", "False", "False"),
            ("starting_title", "Starting Title", "Custom/StartingTitle.ini", "EnableStartingTitle", "toggle", "True", "False", "False"),
            ("gm_shop", "GM Shop (Giran)", "Custom/FakePlayers.ini", "GmShopEnabled", "toggle", "True", "False", "False"),
        ),
    ),
    (
        "npcs",
        "Special NPCs",
        "Toggle custom NPC spawns in the world",
        tuple((nid, name, desc, fname, "npc_toggle") for nid, name, desc, fname in NPC_TOGGLES),
    ),
    (
        "events",
        "Auto Events",
        "Scheduled automatic events",
        tuple((eid, name, desc, path, "event_toggle") for eid, name, desc, path in EVENTS),
    ),
)

# ============================================================================
# HTML template
# ============================================================================

TMPL = r"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>L2 Living Worlds Control Panel</title>
<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">
<style>
:root{--bg:#f4f5f8;--card:#ffffff;--card-hover:#fafbfd;--txt:#1a1d2e;--sub:#6b7094;--accent:#2d7d6f;--accent-light:#e8f3f0;--green:#1a9d5e;--red:#d14545;--orange:#d48a2b;--border:#e2e4ec;--shadow:rgba(0,0,0,0.06)}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--txt);min-height:100vh}
.header{background:linear-gradient(135deg,#1a3a34,#2d7d6f);padding:18px 28px;display:flex;align-items:center;justify-content:space-between;position:sticky;top:0;z-index:100;box-shadow:0 2px 12px rgba(0,0,0,0.12)}
.header h1{font-size:20px;font-weight:700;color:#fff}.header h1 span{font-weight:300;opacity:.8}
.status-badge{display:inline-flex;align-items:center;gap:6px;padding:5px 14px;border-radius:100px;background:rgba(255,255,255,0.15);color:#fff;font-size:11px;font-weight:600;text-transform:uppercase;backdrop-filter:blur(4px)}
.status-badge.offline{background:rgba(255,255,255,0.1)}
.container{max-width:700px;margin:0 auto;padding:24px 20px 80px}
.card{background:var(--card);border-radius:14px;border:1px solid var(--border);margin-bottom:12px;overflow:hidden;box-shadow:0 1px 4px var(--shadow)}
.card:hover{box-shadow:0 3px 12px var(--shadow);border-color:#c8ccd8}
.card-header{padding:14px 20px;display:flex;align-items:center;gap:10px;cursor:pointer;user-select:none}
.card-header:hover{background:var(--card-hover)}
.card-header h2{font-size:14px;font-weight:600;color:var(--txt)}
.card-header .count{font-size:11px;color:var(--sub);margin-left:auto;background:var(--bg);padding:2px 10px;border-radius:100px;font-weight:500}
.card-body{padding:0 20px 14px;display:none}.card-body.open{display:block}
.card-desc{font-size:12px;color:var(--sub);margin:6px 0 10px;line-height:1.5}
.row{display:flex;align-items:center;justify-content:space-between;padding:10px 0;border-bottom:1px solid #f0f1f5;gap:12px;min-height:46px}
.row:last-child{border-bottom:none}
.row-label{flex:1;min-width:0}.row-label .name{font-size:13px;font-weight:500}.row-label .desc{font-size:11px;color:var(--sub);margin-top:1px}
.row-control{flex-shrink:0;display:flex;align-items:center;gap:8px}
.toggle{position:relative;width:40px;height:22px;cursor:pointer;display:inline-block;flex-shrink:0}
.toggle input{display:none}
.toggle .slider{position:absolute;inset:0;background:#d1d4dd;border-radius:11px;transition:background .25s}
.toggle .slider::before{content:'';position:absolute;width:16px;height:16px;left:3px;top:3px;background:#fff;border-radius:50%;box-shadow:0 1px 3px rgba(0,0,0,0.15);transition:transform .25s}
.toggle input:checked+.slider{background:var(--accent)}
.toggle input:checked+.slider::before{transform:translateX(18px);background:#fff}
.range-wrap{display:flex;align-items:center;gap:8px}
.range-wrap input[type=range]{width:90px;height:4px;-webkit-appearance:none;appearance:none;background:#e2e4ec;border-radius:2px;outline:none}
.range-wrap input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:16px;height:16px;border-radius:50%;background:var(--accent);cursor:pointer;border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,0.2)}
.range-wrap input[type=range]::-moz-range-thumb{width:16px;height:16px;border-radius:50%;background:var(--accent);border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,0.2)}
.range-wrap .val{font-size:12px;font-weight:700;color:var(--accent);min-width:28px;text-align:center;background:var(--accent-light);padding:2px 7px;border-radius:6px}
.save-bar{position:fixed;bottom:0;left:0;right:0;background:rgba(255,255,255,0.95);border-top:1px solid var(--border);padding:14px 24px;text-align:center;z-index:100;backdrop-filter:blur(12px)}
.btn-save{background:linear-gradient(135deg,var(--accent),#1a5c50);color:#fff;border:none;padding:11px 36px;border-radius:10px;font-size:14px;font-weight:600;cursor:pointer;letter-spacing:0.2px;box-shadow:0 2px 8px rgba(45,125,111,0.3)}
.btn-save:hover{transform:translateY(-1px);box-shadow:0 4px 16px rgba(45,125,111,0.35)}
.btn-save:disabled{opacity:.4;cursor:default;box-shadow:none}
#save-status{font-size:12px;color:var(--sub);margin-left:12px;transition:color .3s}
#save-status.done{color:var(--green)}#save-status.error{color:var(--red)}#save-status.saving{color:var(--orange)}
</style>
</head>
<body>
<div class="header">
<div><h1>L2 Living Worlds <span>Control Panel</span></h1></div>
<div><span class="status-badge" id="stBadge"><span id="stDot">&#9679;</span> <span id="stTxt">Checking...</span></span></div>
</div>
<div class="container">
{% for cid, cname, cdesc, citems in cats %}
{% set icons = {"rates":"⚙️","progression":"📈","custom":"🔧","npcs":"🧙","events":"🎉"} %}
<div class="card">
<div class="card-header" onclick="toggleCat('{{ cid }}')">
<div class="icon" style="background:var(--accent-light);width:28px;height:28px;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:15px;flex-shrink:0">{{ icons.get(cid,"?") }}</div>
<h2>{{ cname }}</h2>
<span class="count">{{ citems|length }}</span>
</div>
<div class="card-body" id="b{{ cid }}">
{% if cdesc %}<div class="card-desc">{{ cdesc }}</div>{% endif %}
{% for item in citems %}
{% set iid = item[0] %}{% set iname = item[1] %}{% set idesc = item[2] %}{% set itype = item[4] %}
<div class="row">
<div class="row-label"><div class="name">{{ iname }}</div>{% if idesc %}<div class="desc">{{ idesc }}</div>{% endif %}</div>
<div class="row-control">
{% if itype == "toggle" %}
<label class="toggle"><input type="checkbox" class="ti" data-id="{{ iid }}" data-file="{{ item[2] }}" data-key="{{ item[3] }}" data-on="{{ item[5] }}" data-off="{{ item[6] }}"><span class="slider"></span></label>
{% elif itype == "range" %}
<div class="range-wrap"><input type="range" class="ri" data-id="{{ iid }}" min="{{ item[5] }}" max="{{ item[6] }}" oninput="showVal('v{{ iid }}',this.value)"><span class="val" id="v{{ iid }}">{{ item[7] }}</span></div>
{% elif itype == "npc_toggle" %}
<label class="toggle"><input type="checkbox" class="npc" data-id="{{ iid }}" data-file="{{ item[3] }}"><span class="slider"></span></label>
{% elif itype == "event_toggle" %}
<label class="toggle"><input type="checkbox" class="evt" data-id="{{ iid }}" data-path="{{ item[3] }}"><span class="slider"></span></label>
{% endif %}
</div>
</div>
{% endfor %}
</div>
</div>
{% endfor %}
</div>
<div class="save-bar"><button class="btn-save" id="svBtn" onclick="save()">Save &amp; Restart</button><span id="save-status">Changes need a server restart</span></div>
<script>
function showVal(id,v){document.getElementById(id).textContent=v}
async function loadData(){try{let r=await fetch('/api/features');let d=await r.json();if(d.v)for(let[k,v]of Object.entries(d.v)){let e=document.querySelector('[data-id="'+k+'"]');if(!e)continue;if(e.type==='checkbox')e.checked=v===e.dataset.on||v==='True'||v==='1';else if(e.type==='range'){e.value=v;let l=document.getElementById('v'+k);if(l)l.textContent=v}}if(d.n)for(let[k,v]of Object.entries(d.n)){let e=document.querySelector('[data-id="'+k+'"]');if(e)e.checked=v}}catch(e){}}
async function save(){let b=document.getElementById('svBtn');let s=document.getElementById('save-status');b.disabled=true;s.className='saving';s.textContent='Saving...';let d={};document.querySelectorAll('.ti').forEach(function(e){d[e.dataset.id]=e.checked?'1':'0'});document.querySelectorAll('.ri').forEach(function(e){d[e.dataset.id]=e.value});document.querySelectorAll('.npc').forEach(function(e){d[e.dataset.id]=e.checked?'1':'0'});document.querySelectorAll('.evt').forEach(function(e){d[e.dataset.id]=e.checked?'1':'0'});try{let r=await fetch('/api/save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)});let j=await r.json();if(j.ok){s.className='done';s.textContent='Saved! Restarting...';setTimeout(function(){b.disabled=false;s.className='';s.textContent='Done.'},35000)}else{s.className='error';s.textContent='Error';b.disabled=false}}catch(e){s.className='error';s.textContent='Error';b.disabled=false}}
async function checkStatus(){try{let r=await fetch('/api/status');let j=await r.json();let badge=document.getElementById('stBadge');let dot=document.getElementById('stDot');let txt=document.getElementById('stTxt');if(j.s==='active'){badge.className='status-badge';dot.style.color='#fff';txt.textContent='Online'}else{badge.className='status-badge offline';dot.style.color='rgba(255,255,255,0.5)';txt.textContent='Offline'}}catch(e){}}
function toggleCat(id){document.getElementById('b'+id).classList.toggle('open')}
document.addEventListener('DOMContentLoaded',function(){var f=document.querySelector('.card-body');if(f)f.classList.add('open');loadData();checkStatus();setInterval(checkStatus,15000)})
</script>
</body>
</html>"""

# ============================================================================
# Routes
# ============================================================================

@app.route("/")
def index():
    return render_template_string(TMPL, cats=CATS)


@app.route("/api/features")
def features():
    configs = {}
    npcs = {}
    for cid, cname, cdesc, citems in CATS:
        for item in citems:
            iid = item[0]
            itype = item[4]
            if itype == "toggle":
                val = read_val(os.path.join(CONFIG_DIR, item[2]), item[3])
                if val is None:
                    val = item[6]  # default (off value)
                configs[iid] = val
            elif itype == "range":
                val = read_val(os.path.join(CONFIG_DIR, item[2]), item[3])
                if val is None:
                    val = item[7]  # default
                configs[iid] = val
            elif itype == "npc_toggle":
                npcs[iid] = read_spawn_enabled(os.path.join(SPAWN_DIR, item[3]))
            elif itype == "event_toggle":
                npcs[iid] = read_event_enabled(os.path.join(EVENT_DIR, item[3]))
    return jsonify({"v": configs, "n": npcs})


@app.route("/api/save", methods=["POST"])
def save():
    data = request.get_json()
    if not data:
        return jsonify({"ok": False, "error": "no data"})

    errors = []
    for key, val in data.items():
        # Find this item in CATS
        found = False
        for cid, cname, cdesc, citems in CATS:
            for item in citems:
                if item[0] != key:
                    continue
                found = True
                itype = item[4]
                if itype == "toggle":
                    ini = item[2]
                    cfg_key = item[3]
                    on_val = item[5]
                    off_val = item[6]
                    write_val(os.path.join(CONFIG_DIR, ini), cfg_key, on_val if val in ("1", "true", "True") else off_val)
                elif itype == "range":
                    ini = item[2]
                    cfg_key = item[3]
                    write_val(os.path.join(CONFIG_DIR, ini), cfg_key, str(val))
                elif itype == "npc_toggle":
                    enabled = val in ("1", "true", "True")
                    if not write_spawn_enabled(os.path.join(SPAWN_DIR, item[3]), enabled):
                        errors.append(f"Failed to write spawn {item[3]}")
                elif itype == "event_toggle":
                    enabled = val in ("1", "true", "True")
                    if not write_event_enabled(os.path.join(EVENT_DIR, item[3]), enabled):
                        errors.append(f"Failed to write event {item[3]}")
                break
        if not found:
            errors.append(f"Unknown key: {key}")

    # Restart game server via systemd (clean, tracks PID correctly)
    try:
        subprocess.run(["systemctl", "restart", "l2-game.service"], timeout=30)
        time.sleep(5)
    except Exception as e:
        errors.append(f"Restart failed: {e}")

    return jsonify({"ok": len(errors) == 0, "errors": errors if errors else None})


@app.route("/api/status")
def status():
    try:
        r = subprocess.run(["systemctl", "is-active", "l2-game.service"],
                           timeout=10, capture_output=True, text=True)
        active = "active" in r.stdout.strip()
    except Exception:
        active = False
    return jsonify({"s": "active" if active else "inactive"})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080, debug=False)