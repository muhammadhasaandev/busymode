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
      description: 'Find people by name in the phone contacts and recent WhatsApp chats. Returns numbered candidates (also shown on screen) with a ref to use in prepare_message.',
      parameters: {
        type: 'object',
        properties: { name: { type: 'string', description: 'The name in Latin letters as it is likely saved in contacts (e.g. "Sumaya"), even if the user said it in Urdu or it arrived in Urdu/Devanagari script.' } },
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
          ref: { type: 'string', description: 'The ref of the chosen candidate from find_contact (the user may say "number 2" or tap option 2).' },
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
      name: 'get_reply_suggestions',
      description: 'Get the latest incoming message from a chat plus 3 numbered reply options (also shown on screen). Use when the user wants to reply to someone ("reply to Ali", "Ali ko jawab do", "reply to the last message").',
      parameters: {
        type: 'object',
        properties: { chat: { type: 'string', description: 'Contact or group name. Leave empty for the most recent incoming message.' } }
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'send_suggestion',
      description: 'Send one of the reply options from get_reply_suggestions. Only after the user picked an option (by number, by tapping it on screen, or by repeating its words) in a NEW reply. Picking an option counts as confirmation.',
      parameters: {
        type: 'object',
        properties: {
          approval_id: { type: 'string' },
          option: { type: 'number', description: '1, 2 or 3' },
          edited_text: { type: 'string', description: 'Optional: the final text if the user asked to change the option.' }
        },
        required: ['approval_id', 'option']
      }
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

function urduScript (cfg) {
  if (cfg.ttsUrdu) return 'Urdu script (اردو)'
  if (cfg.ttsHindi) return 'Devanagari script (simple everyday Hindustani that sounds like Urdu when spoken)'
  return 'Roman Urdu (Latin letters)'
}

function languageRule (cfg) {
  const ur = urduScript(cfg)
  if (cfg.assistantLanguage === 'ur') return `Always reply in Urdu, written in ${ur}.`
  if (cfg.assistantLanguage === 'en') return 'Always reply in English, even if the user speaks Urdu.'
  return `Reply in the language of the user's latest message: if they spoke Urdu/Hindi (it may arrive in Urdu script, Devanagari or Roman Urdu), reply in Urdu written in ${ur}; if they spoke English, reply in English. If unclear, use the language of your previous reply.`
}

function systemPrompt (cfg) {
  const name = String(cfg.ownerName || 'Hasaan').split(',')[0].trim() || 'Hasaan'
  const now = formatTime(Date.now(), cfg.tzOffsetMin)
  return `You are ${name}'s personal voice assistant, running on his phone and connected to his WhatsApp. It is now ${now}.
Your words are spoken aloud and also shown on screen, so:
- ${languageRule(cfg)}
- Be short and natural: 1 to 3 sentences. No markdown, lists, emojis or links.
- The user's words come from speech recognition and can contain mistakes or mixed Urdu/English; work out what they meant.
- Say phone numbers as "ending in" plus the last 4 digits.
- Numbered options (contacts, reply options) are shown on screen. Don't read long lists aloud: say how many there are and the names or a short version, then ask which number. The user may answer "number 2", "dusra wala", "the second one", say a name, or tap an option (it arrives as "Option 2").

Tools: read and summarise recent WhatsApp messages, find contacts, write a new message, reply to a received message.

Sending a NEW message:
1. find_contact. If exactly one strong match on WhatsApp, use it; if several, ask which number; if none, say so.
2. prepare_message with the user's words (fix only obvious speech-recognition errors; if they didn't say what to write, ask first). Write it in the language and script the user would use in a WhatsApp chat (Roman Urdu if they spoke Urdu, unless they ask otherwise).
3. Ask "Should I send it?" — the text is shown on screen.
4. Only after a clear yes in a NEW reply (yes, haan, bhej do, send, Send button) call send_prepared_message. If no, cancel_prepared_message.

Replying to a received message:
1. get_reply_suggestions (with the chat name, or empty for the latest message). Briefly say who wrote and what about, and that 3 options are on screen; ask which number.
2. When the user picks one in a NEW reply, call send_suggestion. If they want changes, pass edited_text.

Never say something was sent unless the tool returned ok. When you need an answer, end with a question; when done, end with a short statement.`
}

export function detectLang (text) {
  if (/[؀-ۿ]/.test(text)) return 'ur'
  if (/[ऀ-ॿ]/.test(text)) return 'hi'
  return 'en'
}

// ------------------------------------------------------------------ agent

export function createAgent ({ groq, store, getContacts, wa, config, getApprovals, getSuggestions, sendSuggestion, log }) {
  const sessions = new Map()

  function session (id) {
    let s = sessions.get(id)
    if (!s || Date.now() - s.lastAt > SESSION_IDLE_MS) {
      s = { history: [], turn: 0, staged: new Map(), lastAt: Date.now(), refs: new Map(), seq: 0, presented: new Map(), ui: null }
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
        s.ui = {
          type: 'messages',
          title: args.chat ? `Messages: ${args.chat}` : 'Latest messages',
          items: list.slice(-20).reverse().map((m) => ({ chat: m.chatName + (m.isGroup ? ' (group)' : ''), sender: m.fromMe ? 'You' : m.sender, text: String(m.text).slice(0, 200), time: formatTime(m.time, tz) }))
        }
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
        const candidates = top.map((c, i) => {
          let jid = c.jid
          let onWa = !!jid
          if (!jid && checked.has(c.number)) { const r = checked.get(c.number); onWa = r.exists; jid = r.exists ? r.jid : null }
          const ref = 'c' + (++s.seq)
          if (jid) s.refs.set(ref, { jid, name: c.name, number: c.number })
          const last = jid ? store.recent({ hours: 24 * 30, limit: 1, chatFilter: (m) => m.chatJid === jid }).pop() : null
          return {
            option: i + 1,
            ref: jid ? ref : null,
            name: c.name,
            number_ends_with: c.number ? c.number.slice(-4) : '',
            full_number: c.number ? '+' + c.number : '',
            on_whatsapp: onWa,
            source: c.source,
            match: c.score >= 85 ? 'strong' : c.score >= 60 ? 'good' : 'weak',
            last_message: last ? `${last.fromMe ? 'You' : last.sender}: ${String(last.text).slice(0, 80)} (${formatTime(last.time, tz)})` : ''
          }
        })
        s.ui = {
          type: 'contacts',
          title: `People named "${q}"`,
          items: candidates.map((c) => ({ option: c.option, name: c.name, detail: (c.full_number || '') + (c.on_whatsapp ? '' : ' · not on WhatsApp'), preview: c.last_message, enabled: !!c.ref }))
        }
        return { candidates }
      }

      case 'prepare_message': {
        const target = s.refs.get(String(args.ref || ''))
        if (!target) return { error: 'Unknown ref. Call find_contact first and use a ref of a candidate that is on WhatsApp.' }
        const text = String(args.text || '').trim()
        if (!text) return { error: 'Message text is empty. Ask the user what to write.' }
        const id = 'p' + (++s.seq)
        s.staged.set(id, { ...target, name: args.name || target.name, text, turn: s.turn })
        s.ui = { type: 'confirm', title: `To ${args.name || target.name}`, detail: target.number ? '+' + target.number : '', text }
        return { pending_id: id, to: args.name || target.name, number_ends_with: target.number ? target.number.slice(-4) : '', text, next: 'Read this back to the user and ask them to confirm.' }
      }

      case 'send_prepared_message': {
        const p = s.staged.get(String(args.pending_id || ''))
        if (!p) return { error: 'No such prepared message.' }
        if (p.turn >= s.turn) return { error: 'The user has not confirmed yet. Read the message back and ask them to confirm first.' }
        if (!wa.connected()) return { error: 'WhatsApp is not connected right now, so the message was not sent.' }
        await wa.sendText(p.jid, p.text)
        s.staged.delete(String(args.pending_id))
        s.ui = { type: 'done', title: `Sent to ${p.name}`, text: p.text }
        return { ok: true, sent_to: p.name }
      }

      case 'cancel_prepared_message': {
        s.staged.delete(String(args.pending_id || ''))
        return { ok: true }
      }

      case 'get_reply_suggestions': {
        const r = await getSuggestions(String(args.chat || ''))
        if (r.error) return { error: r.error }
        const a = r.item
        s.presented.set(a.id, { turn: s.turn, item: a })
        s.ui = {
          type: 'replies',
          title: `Reply to ${a.chatName}`,
          detail: `${a.sender}: ${a.text}`,
          items: a.suggestions.map((t, i) => ({ option: i + 1, name: t, enabled: true }))
        }
        return { approval_id: a.id, from: a.sender, chat: a.chatName, message: a.text, options: a.suggestions.map((t, i) => ({ option: i + 1, text: t })) }
      }

      case 'send_suggestion': {
        const p = s.presented.get(String(args.approval_id || ''))
        if (!p) return { error: 'Unknown approval_id. Call get_reply_suggestions first.' }
        if (p.turn >= s.turn) return { error: 'The user has not picked an option yet. Ask which number.' }
        const idx = Math.round(Number(args.option)) - 1
        const text = String(args.edited_text || p.item.suggestions[idx] || '').trim()
        if (!text) return { error: 'No such option.' }
        if (!wa.connected()) return { error: 'WhatsApp is not connected right now, so the reply was not sent.' }
        await sendSuggestion(p.item, text)
        s.presented.delete(p.item.id)
        s.ui = { type: 'done', title: `Sent to ${p.item.chatName}`, text }
        return { ok: true, sent_to: p.item.chatName, text }
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
    s.ui = null
    if (!config.groqKey) return { text: 'Please add your Groq API key in the app settings first.', listen: false, lang: 'en' }
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
        return { text: e.status === 401 ? 'Your Groq API key was rejected. Please check it in settings.' : 'Sorry, I could not reach the AI service. Please try again.', listen: false, lang: 'en' }
      }
      const calls = msg.tool_calls || []
      if (!calls.length) {
        const text = String(msg.content || '').replace(/[*_#`]/g, '').trim() || 'Sorry, I did not get that.'
        s.history.push({ role: 'assistant', content: text })
        const asks = /[?؟]\s*$/.test(text) || s.staged.size > 0 || (s.ui && ['contacts', 'replies', 'confirm'].includes(s.ui.type))
        return { text, listen: !!asks, ui: s.ui, lang: detectLang(text) }
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
    return { text: 'Sorry, that took too many steps. Please try asking in a simpler way.', listen: false, ui: s.ui, lang: 'en' }
  }

  return { handle }
}
