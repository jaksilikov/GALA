const express = require('express');
const cors = require('cors');
const app = express();

app.use(cors());
app.use(express.json());

// ── Вся логика бота здесь — клиент её не видит ──

function inList(arr, val) {
  return arr.some(x => x.toLowerCase().trim() === val.toLowerCase().trim());
}

function shouldTarget(config, clan, nick) {
  if (config.mode === 'black') {
    return (clan && inList(config.blackClans, clan)) || inList(config.blackNicks, nick);
  } else {
    const safe = (clan && inList(config.whiteClans, clan)) || inList(config.whiteNicks, nick);
    return !safe;
  }
}

function parse353(line, config) {
  const ci = line.indexOf(' :');
  let cleaned = ci >= 0 ? line.substring(ci + 2) : line;
  const tokens = cleaned.split(' ');
  let clan = '';
  const found = [];
  let i = 0;
  while (i < tokens.length - 1) {
    const cur = tokens[i], next = tokens[i + 1];
    const curIsName = /^\D/.test(cur);
    const nextIsID  = /^\d{8,9}$/.test(next);
    if (curIsName && nextIsID) {
      const nick = cur.replace(/[@+\-]/g, ''), id = next;
      if (shouldTarget(config, clan, nick)) found.push({ nick, id, clan });
      i += 2;
      while (i < tokens.length && /^-?\d+$/.test(tokens[i])) i++;
      continue;
    } else if (curIsName && !nextIsID) {
      clan = cur.trim();
    }
    i++;
  }
  return found;
}

// ── Единственный endpoint ──
app.post('/process', (req, res) => {
  const { line, config } = req.body;
  if (!line || !config) return res.json({ action: null });

  const tokens = line.trim().split(' ');
  const cmd = tokens[0];

  // Список игроков в комнате
  if (cmd === '353') {
    const found = parse353(line, config);
    if (found.length > 0) {
      const target = found[Math.floor(Math.random() * found.length)];
      return res.json({ action: 'attack', targetId: target.id, nick: target.nick, count: found.length });
    }
    return res.json({ action: null });
  }

  // Кто-то вошёл в комнату
  if (cmd === 'JOIN' && tokens.length >= 4) {
    const lc = tokens[1], lv = tokens[2], id = tokens[3];
    if (shouldTarget(config, lc, lv)) {
      return res.json({ action: 'join_attack', targetId: id, nick: lv });
    }
    return res.json({ action: null });
  }

  // Ответ на атаку — нельзя
  if (cmd === '850') {
    if (line.includes('Нельзя')) return res.json({ action: 'quit', reason: 'cant_attack' });
    return res.json({ action: null });
  }

  // Неверный код
  if (cmd === '451') return res.json({ action: 'quit', reason: 'bad_code' });

  res.json({ action: null });
});

app.listen(3006, () => {
  console.log('[GalaxyBot] Server running on port 3006');
});
