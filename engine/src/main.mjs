// Busy Mode engine — runs inside the Android app on nodejs-mobile.
// It keeps the WhatsApp linked-device connection (Baileys), asks Groq what to do with
// each incoming message, sends auto-replies, and tells the Android side what to announce.
//
// Started as: node main.mjs <bridgePort> <bridgeToken> <dataDir>
// The Android side runs a tiny TCP server on 127.0.0.1:<bridgePort>; we connect to it and
// exchange newline-delimited JSON messages.

import './polyfills.mjs'
import net from 'node:net'
import fs from 'node:fs'
import path from 'node:path'

const [, , PORT_ARG, TOKEN, DATA_DIR_ARG] = process.argv
const PORT = Number(PORT_ARG)
const DATA_DIR = DATA_DIR_ARG || path.join(process.cwd(), 'data')
const AUTH_DIR = path.join(DATA_DIR, 'wa-auth')
fs.mkdirSync(DATA_DIR, { recursive: true })

// ---------------------------------------------------------------- bridge to Android
let bridge = null
let bridgeBuf = ''
const outbox = []

function send (obj) {
  const line = JSON.stringify(obj) + '\n'
  if (bridge && !bridge.destroyed) bridge.write(line)
  else { outbox.push(line); if (outbox.length > 200) outbox.shift() }
}
const log = (message) => { console.log(message); send({ t: 'log', message: String(message) }) }

function connectBridge () {
  const s = net.connect({ port: PORT, host: '127.0.0.1' })
  s.setEncoding('utf8')
  s.on('connect', () => {
    bridge = s
    s.write(JSON.stringify({ t: 'hello', token: TOKEN }) + '\n')
    while (outbox.length) s.write(outbox.shift())
    send({ t: 'status', ...currentStatus() })
  })
  s.on('data', (chunk) => {
    bridgeBuf += chunk
    let i
    while ((i = bridgeBuf.indexOf('\n')) >= 0) {
      const line = bridgeBuf.slice(0, i).trim()
      bridgeBuf = bridgeBuf.slice(i + 1)
      if (!line) continue
      try { handleCommand(JSON.parse(line)) } catch (e) { log('Bad command: ' + e.message) }
    }
  })
  s.on('error', () => {})
  s.on('close', () => { if (bridge === s) bridge = null; bridgeBuf = ''; setTimeout(connectBridge, 1500) })
}

// Never let an error close the app: report it to Android instead.
process.on('uncaughtException', (e) => { console.error('uncaught', e); send({ t: 'log', message: 'Engine error: ' + (e && e.message) }) })
process.on('unhandledRejection', (e) => { console.error('unhandled', e) })

let B = null
let bootError = null
try {
  B = await import('baileys')
} catch (e) {
  bootError = e
  console.error('Failed to load WhatsApp library', e)
}
const makeWASocket = B?.default
const {
  useMultiFileAuthState, DisconnectReason, Browsers, normalizeMessageContent, getContentType,
  jidNormalizedUser, isJidGroup, isJidBroadcast, isJidNewsletter, isJidStatusBroadcast, areJidsSameUser
} = B || {}

// ---------------------------------------------------------------- settings (pushed from Android)
const config = {
  groqKey: '',
  model: 'openai/gpt-oss-20b',
  ownerName: 'Hasaan',
  replyText: 'Hasaan is busy right now, he will get back to you soon.',
  autoReply: true,
  voiceAlerts: true,
  ignoreList: [],
  importantList: [],
  replyCooldownMin: 60
}

function handleCommand (cmd) {
  switch (cmd.t) {
    case 'config':
      Object.assign(config, cmd.config || {})
      break
    case 'start':
      wanted = true
      if (!registered && !pendingPhone) { setState('logged_out'); break }
      startWhatsApp()
      break
    case 'stop':
      wanted = false
      stopWhatsApp()
      break
    case 'pair':
      pairWith(String(cmd.phone || ''))
      break
    case 'logout':
      logout()
      break
    case 'status':
      send({ t: 'status', ...currentStatus() })
      break
    case 'test_message': // used only by the automated tests on a computer
      if (process.env.BUSYMODE_TEST === '1') onMessage(cmd.message).catch((e) => log('test failed: ' + e.message))
      break
  }
}

// ---------------------------------------------------------------- WhatsApp connection
let sock = null
let wanted = false
let state = 'disconnected' // disconnected | connecting | waiting_pair | connected | logged_out
let pendingPhone = null
let retryMs = 2000
let retryTimer = null
let registered = false
let myIds = []
let generation = 0

function currentStatus () {
  return { state: (!registered && state === 'disconnected') ? 'logged_out' : state, registered, me: myIds[0] ? myIds[0].split('@')[0] : null }
}
function setState (s) { state = s; send({ t: 'status', ...currentStatus() }) }

const logger = {
  level: 'silent',
  child () { return logger },
  trace () {}, debug () {}, info () {}, warn () {},
  error (obj, msg) { if (msg) console.error('[baileys]', msg) },
  fatal (obj, msg) { console.error('[baileys fatal]', msg || obj) }
}

async function startWhatsApp () {
  if (sock) return
  if (!B) { send({ t: 'error', message: 'WhatsApp library failed to load: ' + (bootError && bootError.message) }); return }
  clearTimeout(retryTimer)
  const myGen = ++generation
  setState('connecting')
  const { state: authState, saveCreds } = await useMultiFileAuthState(AUTH_DIR)
  if (myGen !== generation) return
  registered = !!authState.creds.registered
  const s = makeWASocket({
    auth: authState,
    logger,
    browser: Browsers.macOS('Chrome'),
    printQRInTerminal: false,
    markOnlineOnConnect: false, // keep your phone receiving its normal notifications
    syncFullHistory: false,
    shouldSyncHistoryMessage: () => false,
    fireInitQueries: true,
    generateHighQualityLinkPreview: false,
    getMessage: async () => undefined
  })
  sock = s

  s.ev.on('creds.update', saveCreds)

  s.ev.on('connection.update', async (u) => {
    if (sock !== s) return
    const { connection, lastDisconnect, qr } = u
    if (qr && !authState.creds.registered) {
      // The server is ready for a new device. Use a pairing code if a number was entered.
      if (pendingPhone) {
        const phone = pendingPhone
        pendingPhone = null
        try {
          const code = await s.requestPairingCode(phone)
          send({ t: 'pairing_code', code })
          setState('waiting_pair')
        } catch (e) {
          send({ t: 'error', message: 'Could not get a pairing code: ' + e.message })
        }
      } else if (state !== 'waiting_pair') {
        setState('waiting_pair')
      }
    }
    if (connection === 'open') {
      retryMs = 2000
      registered = true
      myIds = [s.user?.id, s.user?.lid].filter(Boolean).map((j) => jidNormalizedUser(j))
      setState('connected')
      log('Connected to WhatsApp as ' + (myIds[0] || '?'))
    }
    if (connection === 'close') {
      const code = lastDisconnect?.error?.output?.statusCode
      sock = null
      if (code === DisconnectReason.loggedOut) {
        log('WhatsApp logged this device out.')
        fs.rmSync(AUTH_DIR, { recursive: true, force: true })
        registered = false
        myIds = []
        setState('logged_out')
        if (wanted && pendingPhone) startWhatsApp()
        return
      }
      if (!wanted) { setState('disconnected'); return }
      if (code === DisconnectReason.restartRequired) { startWhatsApp(); return }
      // Unlinked + no pairing in progress: don't loop forever asking for codes.
      if (!authState.creds.registered && !pendingPhone) {
        setState('logged_out')
        return
      }
      setState('connecting')
      const wait = retryMs
      retryMs = Math.min(retryMs * 2, 60000)
      log(`Connection closed (${code ?? 'network'}), retrying in ${Math.round(wait / 1000)}s`)
      retryTimer = setTimeout(() => startWhatsApp(), wait)
    }
  })

  s.ev.on('messages.upsert', ({ messages, type }) => {
    if (type !== 'notify') return
    for (const m of messages) {
      onMessage(m).catch((e) => log('Message handling failed: ' + e.message))
    }
  })
}

function stopWhatsApp () {
  clearTimeout(retryTimer)
  generation++
  const s = sock
  sock = null
  try { s?.end(undefined) } catch {}
  setState('disconnected')
}

async function pairWith (phoneRaw) {
  const phone = phoneRaw.replace(/\D/g, '')
  if (phone.length < 8) { send({ t: 'error', message: 'Enter your full number with country code, e.g. 923001234567' }); return }
  if (registered) { send({ t: 'error', message: 'Already linked. Unlink first to link again.' }); return }
  wanted = true
  pendingPhone = phone
  // Start a fresh socket so the server sends a new "ready for pairing" signal.
  stopWhatsApp()
  fs.rmSync(AUTH_DIR, { recursive: true, force: true })
  startWhatsApp()
}

async function logout () {
  try { await sock?.logout() } catch {}
  stopWhatsApp()
  fs.rmSync(AUTH_DIR, { recursive: true, force: true })
  registered = false
  myIds = []
  setState('logged_out')
}

// ---------------------------------------------------------------- message handling
const groupNames = new Map()
async function groupName (jid) {
  if (groupNames.has(jid)) return groupNames.get(jid)
  if (!sock) return 'a group'
  try {
    const meta = await sock.groupMetadata(jid)
    groupNames.set(jid, meta.subject || 'a group')
  } catch { groupNames.set(jid, 'a group') }
  return groupNames.get(jid)
}

function textOf (content) {
  if (!content) return ''
  const type = getContentType(content)
  const c = content[type]
  switch (type) {
    case 'conversation': return content.conversation || ''
    case 'extendedTextMessage': return c?.text || ''
    case 'imageMessage': return '[photo] ' + (c?.caption || '')
    case 'videoMessage': return '[video] ' + (c?.caption || '')
    case 'documentMessage': return '[document] ' + (c?.fileName || '') + ' ' + (c?.caption || '')
    case 'audioMessage': return c?.ptt ? '[voice note]' : '[audio]'
    case 'stickerMessage': return '[sticker]'
    case 'contactMessage': return '[contact] ' + (c?.displayName || '')
    case 'locationMessage': case 'liveLocationMessage': return '[location]'
    case 'pollCreationMessage': case 'pollCreationMessageV2': case 'pollCreationMessageV3': return '[poll] ' + (c?.name || '')
    case 'buttonsResponseMessage': return c?.selectedDisplayText || ''
    case 'listResponseMessage': return c?.title || ''
    default: return null // reactions, protocol messages, edits, receipts... ignore
  }
}

function contextOf (content) {
  if (!content) return null
  const type = getContentType(content)
  return content[type]?.contextInfo || null
}

const norm = (s) => String(s || '').toLowerCase().trim()
const digits = (s) => String(s || '').replace(/\D/g, '')

function inList (list, ...candidates) {
  for (const raw of list || []) {
    const entry = norm(raw)
    if (!entry) continue
    const d = digits(entry)
    for (const c of candidates) {
      if (!c) continue
      if (d.length >= 6 && digits(c).endsWith(d.slice(-10))) return true
      if (d.length < 6 && norm(c) === entry) return true
      if (d.length < 6 && entry.length >= 3 && norm(c).includes(entry)) return true
    }
  }
  return false
}

function ownerNames () {
  return String(config.ownerName || '').split(',').map((s) => s.trim()).filter(Boolean)
}
function firstName () { return ownerNames()[0] || 'Hasaan' }

function mentionsOwnerByName (text) {
  return ownerNames().some((n) => new RegExp('(^|[^\\p{L}])' + n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '($|[^\\p{L}])', 'iu').test(text))
}

function isMe (jid) { return !!jid && myIds.some((m) => areJidsSameUser(m, jid)) }

const lastReplyAt = new Map() // chat jid -> ms
const pending = new Map() // chat jid -> { timer, items: [] }

async function onMessage (m) {
  const jid = m.key?.remoteJid
  if (!jid || m.key.fromMe) return
  if (isJidStatusBroadcast(jid) || isJidBroadcast(jid) || isJidNewsletter(jid)) return
  const content = normalizeMessageContent(m.message)
  const text = textOf(content)
  if (text === null || text === '') return
  const ts = Number(m.messageTimestamp || 0) * 1000
  if (ts && Date.now() - ts > 10 * 60 * 1000) return // old message delivered after reconnect

  const isGroup = isJidGroup(jid)
  const candidates = isGroup ? [m.key.participant, m.key.participantAlt] : [jid, m.key.remoteJidAlt]
  const pnJid = candidates.find((j) => j && j.endsWith('@s.whatsapp.net'))
  const senderNumber = pnJid ? digits(pnJid.split('@')[0].split(':')[0]) : ''
  const senderName = m.pushName || (senderNumber ? '+' + senderNumber : 'someone')
  const chatName = isGroup ? await groupName(jid) : senderName

  const base = { time: Date.now(), chatJid: jid, chatName, sender: senderName, isGroup, text: text.slice(0, 500) }

  if (inList(config.ignoreList, chatName, senderName, senderNumber)) {
    return record({ ...base, action: 'ignored', reason: 'On your "always ignore" list' })
  }

  if (isGroup) {
    const ctx = contextOf(content)
    const tagged = (ctx?.mentionedJid || []).some(isMe)
    const repliedToMe = isMe(ctx?.participant)
    const named = mentionsOwnerByName(text)
    if (tagged || repliedToMe || named) {
      const why = tagged ? 'You were tagged' : repliedToMe ? 'Someone replied to your message' : 'Your name was mentioned'
      announce(`${firstName()}, someone is talking about you in ${chatName}, please check.`)
      return record({ ...base, action: 'announced', reason: why })
    }
    if (inList(config.importantList, chatName, senderName, senderNumber)) {
      announce(`${firstName()}, you have an important message in ${chatName} from ${senderName}.`)
      return record({ ...base, action: 'announced', reason: 'On your "always important" list' })
    }
    return record({ ...base, action: 'ignored', reason: 'Group message not about you' }, true)
  }

  if (inList(config.importantList, chatName, senderName, senderNumber)) {
    announce(`${firstName()}, you have an important message from ${senderName}.`)
    return record({ ...base, action: 'announced', reason: 'On your "always important" list' })
  }

  // Private chat: wait a few seconds so a burst of messages is judged together.
  let p = pending.get(jid)
  if (!p) { p = { items: [] }; pending.set(jid, p) }
  p.items.push(base)
  clearTimeout(p.timer)
  p.timer = setTimeout(() => {
    pending.delete(jid)
    decidePrivate(jid, p.items).catch((e) => log('Decision failed: ' + e.message))
  }, 6000)
}

async function decidePrivate (jid, items) {
  const last = items[items.length - 1]
  const combined = items.map((i) => i.text).join('\n')
  const verdict = await classify(last.sender, combined)
  const entry = { ...last, text: combined.slice(0, 500), reason: verdict.reason }

  if (verdict.category === 'important') {
    announce(`${firstName()}, you have an important message from ${last.sender}.`)
    return record({ ...entry, action: config.voiceAlerts ? 'announced' : 'noted' })
  }
  if (verdict.category === 'casual') {
    if (!config.autoReply) return record({ ...entry, action: 'noted', reason: verdict.reason + ' (auto-reply is off)' })
    const prev = lastReplyAt.get(jid) || 0
    const cooldown = Math.max(0, Number(config.replyCooldownMin) || 0) * 60 * 1000
    if (Date.now() - prev < cooldown) {
      return record({ ...entry, action: 'noted', reason: 'Already auto-replied to this chat recently' })
    }
    if (!sock || state !== 'connected') return record({ ...entry, action: 'noted', reason: 'Not connected, could not reply' })
    await sock.sendMessage(jid, { text: config.replyText })
    lastReplyAt.set(jid, Date.now())
    return record({ ...entry, action: 'auto_replied' })
  }
  return record({ ...entry, action: 'ignored' })
}

function announce (text) {
  if (config.voiceAlerts) send({ t: 'announce', text })
}

function record (entry, quiet = false) {
  send({ t: 'event', entry: { ...entry, quiet } })
}

// ---------------------------------------------------------------- Groq
const SYSTEM_PROMPT = (name) => `You triage incoming WhatsApp private messages for ${name}, who is busy.
Classify the message(s) into exactly one category:
- "important": urgent or time-sensitive, emergencies, health/safety, money or work matters needing a quick answer, someone asking ${name} to call back urgently, family emergencies, deadlines, anything that would be bad to leave unanswered for hours.
- "casual": greetings, small talk, simple questions, chit-chat, "how are you", "are you free", forwarded jokes, things that can wait — a polite "he is busy" reply is appropriate.
- "ignore": spam, promotions, automated messages, OTP/verification codes, delivery notifications, bulk broadcasts, chain messages, or messages where any reply would be odd (e.g. "ok", "thanks", an emoji alone).
Reply with JSON only: {"category":"important|casual|ignore","reason":"short reason, max 12 words"}`

async function classify (sender, text) {
  if (!config.groqKey) return fallback(text, 'No Groq API key set, used simple rules')
  const models = [config.model || 'openai/gpt-oss-20b', 'openai/gpt-oss-20b', 'openai/gpt-oss-120b']
  const tried = new Set()
  for (const model of models) {
    if (tried.has(model)) continue
    tried.add(model)
    try {
      const body = {
        model,
        temperature: 0,
        max_completion_tokens: 400,
        response_format: { type: 'json_object' },
        messages: [
          { role: 'system', content: SYSTEM_PROMPT(firstName()) },
          { role: 'user', content: `From: ${sender}\nMessage:\n${text.slice(0, 2000)}` }
        ]
      }
      if (model.startsWith('openai/gpt-oss')) body.reasoning_effort = 'low'
      const ctrl = new AbortController()
      const timer = setTimeout(() => ctrl.abort(), 20000)
      const res = await fetch(process.env.BUSYMODE_GROQ_URL || 'https://api.groq.com/openai/v1/chat/completions', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + config.groqKey, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: ctrl.signal
      }).finally(() => clearTimeout(timer))
      if (res.status === 401) return fallback(text, 'Groq rejected the API key, used simple rules')
      if (res.status === 404 || res.status === 400) {
        const errText = await res.text()
        if (/model/i.test(errText)) { log(`Groq model ${model} unavailable, trying another`); continue }
        throw new Error('Groq error ' + res.status)
      }
      if (!res.ok) throw new Error('Groq error ' + res.status)
      const data = await res.json()
      const out = data?.choices?.[0]?.message?.content || ''
      const json = JSON.parse(out.slice(out.indexOf('{'), out.lastIndexOf('}') + 1))
      const category = ['important', 'casual', 'ignore'].includes(json.category) ? json.category : 'casual'
      return { category, reason: 'AI: ' + String(json.reason || category).slice(0, 120) }
    } catch (e) {
      log('Groq request failed: ' + e.message)
      return fallback(text, 'AI unavailable, used simple rules')
    }
  }
  return fallback(text, 'No Groq model available, used simple rules')
}

function fallback (text, reason) {
  const t = text.toLowerCase()
  if (/\b(urgent|emergency|asap|hospital|accident|call me|important|immediately|please call|jaldi|zaroori|foran)\b/.test(t)) {
    return { category: 'important', reason }
  }
  if (/\b(otp|verification code|your code is|promo|offer|discount|unsubscribe)\b/.test(t)) {
    return { category: 'ignore', reason }
  }
  return { category: 'casual', reason }
}

// ---------------------------------------------------------------- go

if (!PORT) {
  console.error('Usage: node main.mjs <port> <token> <dataDir>')
  process.exit(1)
}
connectBridge()
if (bootError) send({ t: 'error', message: 'WhatsApp library failed to load: ' + bootError.message })
// If we were linked before, reconnect straight away; Android also sends "start".
if (fs.existsSync(path.join(AUTH_DIR, 'creds.json'))) {
  try {
    registered = !!JSON.parse(fs.readFileSync(path.join(AUTH_DIR, 'creds.json'), 'utf8')).registered
  } catch {}
}
