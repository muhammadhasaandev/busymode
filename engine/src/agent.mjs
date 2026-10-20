// Voice assistant brain: understands a spoken request and uses tools to read WhatsApp messages,
// find contacts and send messages. Sending always needs a spoken "yes" in a later turn.

const SESSION_IDLE_MS = 5 * 60 * 1000
const MAX_ROUNDS = 6

// ------------------------------------------------------------------ helpers (exported for tests)

export function simplify (s) {
  return String(s || '').toLowerCase().replace(/[^a-z0-9؀-ۿ ]+/g, ' ').replace(/\s+/g, ' ').trim()
}

/** Rough spelling skeleton so "Sumaya", "Sumayya" and "Somaya" look alike. */
function skeleton (s) {
  return simplify(s).replace(/ee/g, 'i').replace(/oo/g, 'u').replace(/[aeiouy]+/g, 'a').replace(/(.)\1+/g, '$1').replace(/h/g, '')
}

function lev (a, b) {
  if (a === b) return 0
  const m = a.length; const n = b.length
  if (!m) return n
  if (!n) return m
  let prev = Array.from({ length: n + 1 }, (_, i) => i)
  for (let i = 1; i <= m; i++) {
    const cur = [i]
    for (let j = 1; j <= n; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1))
    }
    prev = cur
  }
  return prev[n]
}

export function nameScore (query, name) {
  const a = simplify(query).replace(/^(my|mera|meri|to|ko)\s+/, '')
  const b = simplify(name)
  if (!a || !b) return 0
  if (a === b) return 100
  const words = b.split(' ')
  if (words.includes(a)) return 90
  if (b.startsWith(a)) return 85
  if (words.some((w) => w.startsWith(a))) return 75
  if (a.length >= 3 && b.includes(a)) return 60
  const sa = skeleton(a)
  const targets = [skeleton(b), ...words.map(skeleton)]
  const d = Math.min(...targets.map((t) => lev(sa, t)))
  if (d === 0) return 72
  if (d <= 1 && a.length >= 4) return 55
  if (d <= 2 && a.length >= 7) return 45
  return 0
}

export function normalizeNumber (raw, countryCode) {
  const s = String(raw || '').trim()
  let d = s.replace(/\D/g, '')
  if (!d) return ''
  const cc = String(countryCode || '').replace(/\D/g, '')
  if (s.startsWith('+')) return d
  if (d.startsWith('00')) return d.slice(2)
  if (cc && d.startsWith('0')) return cc + d.replace(/^0+/, '')
  if (cc && d.length <= 10 && !d.startsWith(cc)) return cc + d
  return d
}

export function formatTime (ms, tzOffsetMin = 0) {
  const d = new Date(ms + tzOffsetMin * 60000)
  const now = new Date(Date.now() + tzOffsetMin * 60000)
  const hh = d.getUTCHours(); const mm = String(d.getUTCMinutes()).padStart(2, '0')
  const t = `${((hh + 11) % 12) + 1}:${mm} ${hh < 12 ? 'AM' : 'PM'}`
  const sameDay = d.getUTCFullYear() === now.getUTCFullYear() && d.getUTCMonth() === now.getUTCMonth() && d.getUTCDate() === now.getUTCDate()
  if (sameDay) return t
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  return `${d.getUTCDate()} ${months[d.getUTCMonth()]} ${t}`
}

// ------------------------------------------------------------------ tools

const TOOLS = [
  {
    type: 'function',
    function: {
      name: 'get_recent_messages',
      description: 'Read recent WhatsApp messages (private chats and groups), oldest first. Use it to answer questions like "what are my latest messages", "anything important", "what did Ali say", "what are people talking about in the family group".',
      parameters: {
        type: 'object',
        properties: {
          chat: { type: 'string', description: 'Optional contact or group name to filter by.' },
          hours: { type: 'number', description: 'How far back to look, in hours. Default 24.' },
          limit: { type: 'number', description: 'Maximum messages to return. Default 40, max 80.' },
          include_groups: { type: 'boolean', description: 'Include group messages. Default true.' }
        }
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'find_contact',
      description: 'Find people by name in the phone contacts and recent WhatsApp chats. Returns candidates with a ref to use in prepare_message.',
      parameters: {
        type: 'object',
        properties: { name: { type: 'string', description: 'The name the user said, e.g. "Sumaya".' } },
        required: ['name']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'prepare_message',
      description: 'Prepare (NOT send) a WhatsApp message to one person. After this, read the message and the recipient back to the user and ask them to confirm.',
      parameters: {
        type: 'object',
        properties: {
          ref: { type: 'string', description: 'The ref of the chosen candidate from find_contact.' },
          name: { type: 'string', description: 'Recipient name to say back to the user.' },
          text: { type: 'string', description: 'The exact message text to send, as the user wants it written.' }
        },
        required: ['ref', 'text']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'send_prepared_message',
      description: 'Send a message prepared earlier. Only call this after the user clearly confirmed (e.g. "yes", "send it", "haan bhej do") in their latest reply.',
      parameters: {
        type: 'object',
        properties: { pending_id: { type: 'string' } },
        required: ['pending_id']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'cancel_prepared_message',
      description: 'Discard a prepared message when the user says no or changes their mind.',
      parameters: { type: 'object', properties: { pending_id: { type: 'string' } }, required: ['pending_id'] }
    }
  },
  {
    type: 'function',
    function: {
      name: 'get_pending_approvals',
      description: 'List incoming messages that have AI reply suggestions waiting for the user\'s approval.',
      parameters: { type: 'object', properties: {} }
    }
  }
]

function systemPrompt (cfg) {
  const name = String(cfg.ownerName || 'Hasaan').split(',')[0].trim() || 'Hasaan'
  const now = formatTime(Date.now(), cfg.tzOffsetMin)
  return `You are ${name}'s personal voice assistant, running on his phone and connected to his WhatsApp. It is now ${now}.
Everything you write is spoken aloud by text-to-speech, so:
- Reply in short, natural spoken English (1 to 4 sentences). No lists, markdown, emojis or links.
- Say phone numbers as "ending in" plus the last 4 digits unless asked for the full number.
- Summarise; don't read long messages word for word unless asked. Messages may be in Urdu or Roman Urdu; explain them in English.

What you can do with tools: read recent WhatsApp messages and summarise them, say what is important, find a contact, and send a WhatsApp message.

Sending a message — follow these steps exactly:
1. Call find_contact with the name.
2. If there is exactly one strong match on WhatsApp, use it. If there are several, read them out briefly (name and last 4 digits) and ask which one. If none are on WhatsApp or none match, say so.
3. Call prepare_message with the chosen ref and the message text. Keep the user's words; fix only obvious speech-to-text mistakes. If the user didn't say what to write, ask them first.
4. Read it back: "Should I send '<text>' to <name>?" and wait.
5. Only when the user confirms in a NEW reply, call send_prepared_message. If they say no, call cancel_prepared_message. Never say a message was sent unless send_prepared_message returned ok.

When you need an answer from the user, end your reply with a question. When the task is done, end with a short statement, not a question.`
}

// ------------------------------------------------------------------ agent

export function createAgent ({ groq, store, getContacts, wa, config, getApprovals, log }) {
  const sessions = new Map()

  function session (id) {
    let s = sessions.get(id)
    if (!s || Date.now() - s.lastAt > SESSION_IDLE_MS) {
      s = { history: [], turn: 0, staged: new Map(), lastAt: Date.now(), refs: new Map(), seq: 0 }
      sessions.set(id, s)
    }
    for (const [k, v] of sessions) if (Date.now() - v.lastAt > SESSION_IDLE_MS && k !== id) sessions.delete(k)
    return s
  }

  function chatMatcher (q) {
    return (m) => m.chatJid === q || nameScore(q, m.chatName) >= 55
  }

  async function runTool (s, name, args) {
    const tz = config.tzOffsetMin || 0
    switch (name) {
      case 'get_recent_messages': {
        const hours = Math.min(Math.max(Number(args.hours) || 24, 1), 24 * 14)
        const limit = Math.min(Math.max(Number(args.limit) || 40, 1), 80)
        const list = store.recent({
          hours,
          limit,
          includeGroups: args.include_groups !== false,
          chatFilter: args.chat ? chatMatcher(String(args.chat)) : null
        })
        if (!list.length) return { messages: [], note: args.chat ? `No messages from "${args.chat}" in the last ${hours} hours.` : `No messages in the last ${hours} hours.` }
        const lines = list.map((m) => `${formatTime(m.time, tz)} | ${m.chatName}${m.isGroup ? ' (group)' : ''} | ${m.fromMe ? 'You' : m.sender}: ${String(m.text).slice(0, 300)}`)
        const chats = new Set(list.map((m) => m.chatName))
        return { count: list.length, chats: chats.size, messages: lines }
      }

      case 'find_contact': {
        const q = String(args.name || '')
        const byNumber = new Map()
        for (const c of getContacts()) {
          const score = nameScore(q, c.n)
          if (score < 45) continue
          const num = normalizeNumber(c.p, config.countryCode)
          if (num.length < 8) continue
          const prev = byNumber.get(num)
          if (!prev || prev.score < score) byNumber.set(num, { name: c.n, number: num, score, source: 'phone contacts', jid: null })
        }
        for (const c of store.chats()) {
          if (c.isGroup) continue
          const score = nameScore(q, c.name)
          if (score < 45) continue
          const num = c.number || (c.jid.endsWith('@s.whatsapp.net') ? c.jid.split('@')[0] : '')
          const key = num || c.jid
          const prev = byNumber.get(key)
          const rec = { name: c.name, number: num, score: score + 5, source: 'recent chat', jid: c.jid, lastTime: c.lastTime }
          if (!prev) byNumber.set(key, rec)
          else { prev.jid = prev.jid || c.jid; prev.score = Math.max(prev.score, rec.score); prev.lastTime = c.lastTime }
        }
        const top = [...byNumber.values()].sort((a, b) => b.score - a.score || (b.lastTime || 0) - (a.lastTime || 0)).slice(0, 6)
        if (!top.length) return { candidates: [], note: `Nobody named "${q}" in contacts or recent chats.` }
        const toCheck = top.filter((c) => !c.jid && c.number).map((c) => c.number)
        let checked = new Map()
        if (toCheck.length && wa.connected()) {
          try { checked = await wa.onWhatsApp(toCheck) } catch (e) { log('onWhatsApp failed: ' + e.message) }
        }
        const candidates = top.map((c) => {
          let jid = c.jid
          let onWa = !!jid
          if (!jid && checked.has(c.number)) { const r = checked.get(c.number); onWa = r.exists; jid = r.exists ? r.jid : null }
          const ref = 'c' + (++s.seq)
          if (jid) s.refs.set(ref, { jid, name: c.name, number: c.number })
          return { ref: jid ? ref : null, name: c.name, number_ends_with: c.number ? c.number.slice(-4) : '', full_number: c.number ? '+' + c.number : '', on_whatsapp: onWa, source: c.source, match: c.score >= 85 ? 'strong' : c.score >= 60 ? 'good' : 'weak' }
        })
        return { candidates }
      }

      case 'prepare_message': {
        const target = s.refs.get(String(args.ref || ''))
        if (!target) return { error: 'Unknown ref. Call find_contact first and use a ref of a candidate that is on WhatsApp.' }
        const text = String(args.text || '').trim()
        if (!text) return { error: 'Message text is empty. Ask the user what to write.' }
        const id = 'p' + (++s.seq)
        s.staged.set(id, { ...target, name: args.name || target.name, text, turn: s.turn })
        return { pending_id: id, to: args.name || target.name, number_ends_with: target.number ? target.number.slice(-4) : '', text, next: 'Read this back to the user and ask them to confirm.' }
      }

      case 'send_prepared_message': {
        const p = s.staged.get(String(args.pending_id || ''))
        if (!p) return { error: 'No such prepared message.' }
        if (p.turn >= s.turn) return { error: 'The user has not confirmed yet. Read the message back and ask them to confirm first.' }
        if (!wa.connected()) return { error: 'WhatsApp is not connected right now, so the message was not sent.' }
        await wa.sendText(p.jid, p.text)
        s.staged.delete(String(args.pending_id))
        return { ok: true, sent_to: p.name }
      }

      case 'cancel_prepared_message': {
        s.staged.delete(String(args.pending_id || ''))
        return { ok: true }
      }

      case 'get_pending_approvals': {
        const items = getApprovals().map((a) => ({ from: a.chatName, message: String(a.text).slice(0, 200), suggestions: a.suggestions }))
        return { count: items.length, items }
      }

      default:
        return { error: 'Unknown tool' }
    }
  }

  /** Handle one spoken request. Returns { text, listen }. */
  async function handle (sessionId, userText) {
    const s = session(String(sessionId))
    s.lastAt = Date.now()
    s.turn++
    if (!config.groqKey) return { text: 'Please add your Groq API key in the app settings first.', listen: false }
    s.history.push({ role: 'user', content: String(userText).slice(0, 2000) })
    if (s.history.length > 24) s.history = s.history.slice(-24)

    const models = [config.agentModel, 'openai/gpt-oss-120b', 'openai/gpt-oss-20b']
    for (let round = 0; round < MAX_ROUNDS; round++) {
      let msg
      try {
        msg = await groq.chat({
          messages: [{ role: 'system', content: systemPrompt(config) }, ...s.history],
          tools: TOOLS,
          tool_choice: 'auto',
          temperature: 0.3,
          max_completion_tokens: 900,
          reasoning_effort: 'low'
        }, { models, timeoutMs: 30000 })
      } catch (e) {
        log('Agent Groq call failed: ' + e.message)
        s.history.pop()
        return { text: e.status === 401 ? 'Your Groq API key was rejected. Please check it in settings.' : 'Sorry, I could not reach the AI service. Please try again.', listen: false }
      }
      const calls = msg.tool_calls || []
      if (!calls.length) {
        const text = String(msg.content || '').replace(/[*_#`]/g, '').trim() || 'Sorry, I did not get that.'
        s.history.push({ role: 'assistant', content: text })
        return { text, listen: text.endsWith('?') || s.staged.size > 0 }
      }
      s.history.push({ role: 'assistant', content: msg.content || '', tool_calls: calls })
      for (const call of calls) {
        let args = {}
        try { args = JSON.parse(call.function?.arguments || '{}') } catch {}
        let result
        try { result = await runTool(s, call.function?.name, args) } catch (e) { result = { error: e.message } }
        s.history.push({ role: 'tool', tool_call_id: call.id, content: JSON.stringify(result).slice(0, 12000) })
      }
    }
    return { text: 'Sorry, that took too many steps. Please try asking in a simpler way.', listen: false }
  }

  return { handle }
}
