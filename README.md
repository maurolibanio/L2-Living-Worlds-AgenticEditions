# L2 Living Worlds

Fake players com IA, chat com contexto, e um painel web de controle pra L2 Interlude.

---

## L2 Control Panel

Painel web pra gerenciar o servidor sem precisar de terminal:

- Toggle fake players, chat, comportamento, aggro
- Ajustar count de bots, shots, quest credit
- Botão Save + Restart — aplica tudo e reinicia o GameServer
- Dark mode, responsivo, zero dependências

---

## Features

- **Chat com IA** — bots que sabem onde estão, o que tão usando, com quem tão partyados, e o que tão matando. LLM via Ollama ou DeepSeek.
- **Base de conhecimento** — 2559 facts pra bot não inventar zona/item/buff.
- **Personalidade única** — cada bot tem tom, estilo, vício de digitação e spell próprio.
- **Spawn inteligente** — phantoms só aparecem quando um player real tá perto. 30s de grace antes de despawnar.
- **Rate limiting** — cache LRU, dedup global, 45s de cooldown por bot, 95% de skip em fala espontânea.
- **Otimizado** — resolveBot cache, retrieve indexado, save_memory batch, per-bot locks, conversations LRU.
- **Systemd** — 5 serviços (game, login, admin, bridge, brain).

---

## Estrutura

```
server/          Java modificado, configs, data, systemd
brain/           LLM bridge (Python) + knowledge base (19 arquivos, 2559 facts)
l2admin/         Painel web (Flask) + templates + scripts
```

---

## Serviços

```bash
systemctl status l2-game.service      # GameServer :7777
systemctl status l2-login.service     # LoginServer :2106
systemctl status l2-admin.service     # Admin panel :8080
systemctl status l2-brain-tunnel.service  # Tunnel pro LLM
```
