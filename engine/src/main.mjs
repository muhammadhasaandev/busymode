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
import crypto from 'node:crypto'
import { createGroq, parseJsonObject } from './groq.mjs'
import { createStore } from './store.mjs'
import { createAgent, normalizeNumber, nameScore } from './agent.mjs'

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
  agentModel: 'openai/gpt-oss-120b',
  ownerName: 'Hasaan',
  replyText: 'Hasaan is busy right now, he will get back to you soon.',
  replyMode: 'suggest', // suggest | auto | off
  autoReply: true,
  voiceAlerts: true,
  ignoreList: [],
  importantList: [],
  replyCooldownMin: 60,
  countryCode: '92',
  tzOffsetMin: 300,
  assistantLanguage: 'auto', // auto | en | ur
  ttsUrdu: false,
  ttsHindi: false
}
let phoneContacts = [] // [{ n: name, p: number }] from the Android address book

function handleCommand (cmd) {
  switch (cmd.t) {
    case 'config':
      Object.assign(config, cmd.config || {})
      break
    case 'contacts':
      if (Array.isArray(cmd.list)) phoneContacts = cmd.list.filter((c) => c && c.n && c.p)
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
    case 'approve':
      approveReply(cmd).catch((e) => send({ t: 'error', message: 'Could not send the reply: ' + e.message }))
      break
    case 'reject':
      finishApproval(String(cmd.id), 'skipped')
      break
    case 'voice':
      agent.handle(cmd.session, String(cmd.text || ''))
        .then((r) => send({ t: 'say', session: cmd.session, text: r.text, listen: !!r.listen, ui: r.ui || null, lang: r.lang || 'en' }))
        .catch((e) => { log('Voice failed: ' + e.message); send({ t: 'say', session: cmd.session, text: 'Sorry, something went wrong.', listen: false, lang: 'en' }) })
      break
    case 'test_message': // used only by the automated tests on a computer
      if (process.env.BUSYMODE_TEST === '1') handleUpsert([cmd.message], 'notify')
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

  s.ev.on('messages.upsert', ({ messages, type }) => handleUpsert(messages, type))
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
  // Letters of Latin and Urdu/Arabic scripts count as part of a word (no ICU on Android, so no \\p{L}).
  const L = 'A-Za-z0-9\\u00C0-\\u024F\\u0600-\\u06FF\\u0750-\\u077F'
  return ownerNames().some((n) => new RegExp('(^|[^' + L + '])' + n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '($|[^' + L + '])', 'i').test(text))
}

function isMe (jid) { return !!jid && myIds.some((m) => areJidsSameUser(m, jid)) }

const lastReplyAt = new Map() // chat jid -> ms
const pending = new Map() // chat jid -> { timer, items: [] }
const chatNames = new Map() // private chat jid -> best known name

// ---------------------------------------------------------------- shared services
const store = createStore(DATA_DIR)
const groq = createGroq({ getKey: () => config.groqKey, log })
const approvals = new Map() // id -> approval item

const wa = {
  connected: () => !!sock && state === 'connected',
  sendText: (jid, text) => sock.sendMessage(jid, { text }),
  async onWhatsApp (numbers) {
    const out = new Map()
    const res = await sock.onWhatsApp(...numbers.map((n) => n + '@s.whatsapp.net'))
    for (const r of res || []) {
      const num = String(r.jid || '').split('@')[0].split(':')[0]
      out.set(num, { exists: !!r.exists, jid: r.jid })
    }
    for (const n of numbers) if (!out.has(n)) out.set(n, { exists: false, jid: null })
    return out
  }
}

if (process.env.BUSYMODE_TEST === '1') { // automated tests on a computer: pretend WhatsApp is connected
  wa.connected = () => true
  wa.sendText = async (jid, text) => send({ t: 'test_sent', jid, text })
  wa.onWhatsApp = async (nums) => new Map(nums.map((n) => [n, { exists: !n.endsWith('0000'), jid: n + '@s.whatsapp.net' }]))
}

const agent = createAgent({
  groq, store, wa, config, log,
  getContacts: () => phoneContacts,
  getApprovals: () => [...approvals.values()],
  getSuggestions: suggestionsFor,
  sendSuggestion: (item, text) => approveReply({ id: item.id, chatJid: item.chatJid, text, chatName: item.chatName })
})

/** Latest incoming message of a chat (or overall) with 3 reply options; reuses waiting ones. */
async function suggestionsFor (chatQuery) {
  const q = String(chatQuery || '').trim()
  const incoming = store.all().filter((m) => !m.fromMe)
  let target = null
  if (q) {
    let best = 0
    for (let i = incoming.length - 1; i >= 0; i--) {
      const m = incoming[i]
      const score = Math.max(nameScore(q, m.chatName), m.isGroup ? 0 : nameScore(q, m.sender))
      if (score > best + 5) { best = score; target = m }
    }
    if (best < 55) return { error: `No recent message from "${q}".` }
  } else {
    target = incoming.filter((m) => !m.isGroup).pop() || incoming.pop()
    if (!target) return { error: 'No received messages yet.' }
  }
  const existing = [...approvals.values()].find((a) => a.chatJid === target.chatJid)
  if (existing) return { item: existing }
  // The unanswered messages from this chat since my last message.
  const chatMsgs = store.all().filter((m) => m.chatJid === target.chatJid)
  const lastMine = chatMsgs.map((m) => m.fromMe).lastIndexOf(true)
  const unanswered = chatMsgs.slice(lastMine + 1).filter((m) => !m.fromMe).slice(-5)
  const text = (unanswered.length ? unanswered : [target]).map((m) => m.text).join('\n')
  const v = await triage({ sender: target.sender, chatName: target.chatName, isGroup: target.isGroup, text, jid: target.chatJid, wantReplies: true })
  if (!v.replies.length) return { error: 'Could not write reply options right now.' }
  return { item: createApproval({ chatJid: target.chatJid, chatName: target.chatName, sender: target.sender, isGroup: target.isGroup, text, suggestions: v.replies, reason: 'You asked the assistant' }) }
}

function contactNameFor (number) {
  if (!number) return null
  const tail = number.slice(-10)
  for (const c of phoneContacts) {
    const n = normalizeNumber(c.p, config.countryCode)
    if (n && n.slice(-10) === tail) return c.n
  }
  return null
}

// ---------------------------------------------------------------- incoming / outgoing messages
async function toRecord (m) {
  const jid = m.key?.remoteJid
  if (!jid) return null
  if (isJidStatusBroadcast(jid) || isJidBroadcast(jid) || isJidNewsletter(jid)) return null
  const content = normalizeMessageContent(m.message)
  const text = textOf(content)
  if (text === null || text === '') return null
  const isGroup = isJidGroup(jid)
  const fromMe = !!m.key.fromMe
  const candidates = isGroup ? [m.key.participant, m.key.participantAlt] : [jid, m.key.remoteJidAlt]
  const pnJid = candidates.find((j) => j && j.endsWith('@s.whatsapp.net'))
  const number = pnJid ? digits(pnJid.split('@')[0].split(':')[0]) : ''
  const savedName = contactNameFor(number)
  let sender = fromMe ? 'You' : (savedName || m.pushName || (number ? '+' + number : 'someone'))
  let chatName
  if (isGroup) {
    chatName = await groupName(jid)
  } else {
    if (!fromMe) {
      const best = savedName || m.pushName
      if (best) { chatNames.set(jid, best); if (m.key.remoteJidAlt) chatNames.set(m.key.remoteJidAlt, best) }
    }
    chatName = chatNames.get(jid) || savedName || (number ? '+' + number : sender)
  }
  if (!fromMe && !isGroup) sender = chatName
  const ts = Number(m.messageTimestamp || 0) * 1000 || Date.now()
  return { id: m.key.id || crypto.randomUUID(), chatJid: jid, chatName, isGroup, sender, fromMe, number: fromMe ? '' : number, text: text.slice(0, 1000), time: ts, content }
}

function handleUpsert (messages, type) {
  for (const m of messages) {
    (async () => {
      const rec = await toRecord(m)
      if (!rec) return
      const { content, ...plain } = rec
      store.add(plain)
      if (rec.fromMe) {
        // You answered this chat yourself: the waiting suggestions are no longer needed.
        for (const a of approvals.values()) if (a.chatJid === rec.chatJid && Date.now() - rec.time < 60000 && !a.sending) finishApproval(a.id, 'answered')
        return
      }
      if (type === 'notify') await onMessage(rec, content)
    })().catch((e) => log('Message handling failed: ' + e.message))
  }
}

async function onMessage (rec, content) {
  if (Date.now() - rec.time > 10 * 60 * 1000) return // old message delivered after reconnect
  const { chatJid: jid, chatName, sender: senderName, isGroup, number: senderNumber } = rec
  const base = { time: Date.now(), chatJid: jid, chatName, sender: senderName, isGroup, text: rec.text.slice(0, 500) }

  if (inList(config.ignoreList, chatName, senderName, senderNumber)) {
    return record({ ...base, action: 'ignored', reason: 'On your "always ignore" list' })
  }

  if (isGroup) {
    const ctx = contextOf(content)
    const tagged = (ctx?.mentionedJid || []).some(isMe)
    const repliedToMe = isMe(ctx?.participant)
    const named = mentionsOwnerByName(rec.text)
    const important = inList(config.importantList, chatName, senderName, senderNumber)
    if (tagged || repliedToMe || named || important) {
      const why = tagged ? 'You were tagged' : repliedToMe ? 'Someone replied to your message' : named ? 'Your name was mentioned' : 'On your "always important" list'
      announce(important && !(tagged || repliedToMe || named)
        ? `${firstName()}, you have an important message in ${chatName} from ${senderName}.`
        : `${firstName()}, someone is talking about you in ${chatName}, please check.`)
      record({ ...base, action: 'announced', reason: why })
      if (config.replyMode === 'suggest') {
        const v = await triage({ sender: senderName, chatName, isGroup: true, text: rec.text, jid, wantReplies: true })
        if (v.replies.length) createApproval({ ...base, suggestions: v.replies, reason: why })
      }
      return
    }
    return record({ ...base, action: 'ignored', reason: 'Group message not about you' }, true)
  }

  // Private chat: wait a few seconds so a burst of messages is judged together.
  let p = pending.get(jid)
  if (!p) { p = { items: [] }; pending.set(jid, p) }
  p.items.push({ ...base, number: senderNumber })
  clearTimeout(p.timer)
  p.timer = setTimeout(() => {
    pending.delete(jid)
    decidePrivate(jid, p.items).catch((e) => log('Decision failed: ' + e.message))
  }, 6000)
}

async function decidePrivate (jid, items) {
  const last = items[items.length - 1]
  const combined = items.map((i) => i.text).join('\n')
  const entry = { ...last, text: combined.slice(0, 500) }
  delete entry.number
  const alwaysImportant = inList(config.importantList, last.chatName, last.sender, last.number)
  const suggest = config.replyMode === 'suggest'
  const v = await triage({ sender: last.sender, chatName: last.chatName, isGroup: false, text: combined, jid, wantReplies: suggest })
  const category = alwaysImportant ? 'important' : v.category
  const reason = alwaysImportant ? 'On your "always important" list' : v.reason

  if (category === 'important') announce(`${firstName()}, you have an important message from ${last.sender}.`)

  if (category === 'ignore') return record({ ...entry, action: 'ignored', reason })

  if (suggest) {
    if (v.replies.length) {
      createApproval({ ...entry, suggestions: v.replies, reason })
      return record({ ...entry, action: category === 'important' && config.voiceAlerts ? 'announced' : 'suggested', reason: reason + ' — replies waiting for your approval' })
    }
    return record({ ...entry, action: category === 'important' && config.voiceAlerts ? 'announced' : 'noted', reason })
  }

  if (category === 'important') return record({ ...entry, action: config.voiceAlerts ? 'announced' : 'noted', reason })

  // casual
  if (config.replyMode !== 'auto') return record({ ...entry, action: 'noted', reason: reason + ' (replies are off)' })
  const prev = lastReplyAt.get(jid) || 0
  const cooldown = Math.max(0, Number(config.replyCooldownMin) || 0) * 60 * 1000
  if (Date.now() - prev < cooldown) return record({ ...entry, action: 'noted', reason: 'Already auto-replied to this chat recently' })
  if (!wa.connected()) return record({ ...entry, action: 'noted', reason: 'Not connected, could not reply' })
  await wa.sendText(jid, config.replyText)
  lastReplyAt.set(jid, Date.now())
  return record({ ...entry, action: 'auto_replied', reason })
}

// ---------------------------------------------------------------- reply approvals
function createApproval ({ chatJid, chatName, sender, isGroup, text, suggestions, reason }) {
  for (const a of approvals.values()) if (a.chatJid === chatJid) finishApproval(a.id, 'replaced')
  const item = {
    id: crypto.randomUUID(),
    chatJid, chatName, sender, isGroup,
    text: String(text).slice(0, 600),
    suggestions: suggestions.slice(0, 3),
    reason: reason || '',
    time: Date.now()
  }
  approvals.set(item.id, item)
  send({ t: 'approval', item })
  return item
}

function finishApproval (id, status) {
  approvals.delete(id)
  send({ t: 'approval_done', id, status })
}

async function approveReply ({ id, chatJid, text, chatName }) {
  const a = approvals.get(String(id))
  const jid = a?.chatJid || chatJid
  const body = String(text || '').trim()
  if (!jid || !body) return
  if (!wa.connected()) { send({ t: 'error', message: 'WhatsApp is not connected right now. The reply was not sent; try again in a moment.' }); return }
  if (a) a.sending = true
  await wa.sendText(jid, body)
  lastReplyAt.set(jid, Date.now())
  finishApproval(String(id), 'sent')
  record({ time: Date.now(), chatJid: jid, chatName: a?.chatName || chatName || '', sender: 'You', isGroup: !!a?.isGroup, text: body, action: 'replied', reason: 'You approved this reply' })
}

function announce (text) {
  if (config.voiceAlerts) send({ t: 'announce', text })
}

function record (entry, quiet = false) {
  send({ t: 'event', entry: { ...entry, quiet } })
}

// ---------------------------------------------------------------- Groq triage + reply suggestions
function triagePrompt (name, wantReplies, isGroup) {
  const classify = isGroup
    ? `This message is from a WhatsApp group and mentions ${name}. Use category "important".`
    : `Classify the latest incoming message(s) into exactly one category:
- "important": urgent or time-sensitive, emergencies, health/safety, money or work matters needing a quick answer, someone asking ${name} to call back urgently, family emergencies, deadlines, anything bad to leave unanswered for hours.
- "casual": greetings, small talk, simple questions, chit-chat, plans, things that can wait.
- "ignore": spam, promotions, automated messages, OTP/verification codes, delivery notifications, bulk broadcasts, chain messages, or messages that need no reply at all (e.g. "ok", "thanks", an emoji alone).`
  const replies = wantReplies
    ? `
Also write exactly 3 different reply suggestions that ${name} could send back himself (first person, as ${name}):
- Match the sender's language and style (Roman Urdu if they wrote Roman Urdu, Urdu script if Urdu, English if English).
- Natural, friendly WhatsApp tone, under 25 words each, no hashtags.
- Make them different: e.g. one that answers or agrees, one that asks a question or proposes a time, one short polite acknowledgement.
- Don't invent facts (times, places, amounts) that ${name} didn't give; keep them general when unsure.
- For category "ignore", "replies" may be an empty list.`
    : ''
  return `You help ${name}, who is busy, deal with incoming WhatsApp messages.
${classify}${replies}
Reply with JSON only: {"category":"important|casual|ignore","reason":"short reason, max 12 words"${wantReplies ? ',"replies":["...","...","..."]' : ''}}`
}

async function triage ({ sender, chatName, isGroup, text, jid, wantReplies }) {
  const context = store.recent({ hours: 72, limit: 10, chatFilter: (m) => m.chatJid === jid })
    .map((m) => `${m.fromMe ? firstName() + ' (me)' : m.sender}: ${String(m.text).slice(0, 200)}`).join('\n')
  if (!config.groqKey) return { ...fallback(text, 'No Groq API key set, used simple rules'), replies: [] }
  try {
    const msg = await groq.chat({
      temperature: wantReplies ? 0.5 : 0,
      max_completion_tokens: wantReplies ? 900 : 400,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: triagePrompt(firstName(), wantReplies, isGroup) },
        { role: 'user', content: `Chat: ${chatName}${isGroup ? ' (group)' : ''}\nRecent conversation (oldest first):\n${context || '(none)'}\n\nLatest from ${sender}:\n${text.slice(0, 2000)}` }
      ]
    }, { models: [config.model, 'openai/gpt-oss-20b', 'openai/gpt-oss-120b'], timeoutMs: 25000 })
    const json = parseJsonObject(msg.content)
    const category = isGroup ? 'important' : (['important', 'casual', 'ignore'].includes(json.category) ? json.category : 'casual')
    const replies = Array.isArray(json.replies) ? json.replies.map((r) => String(r).trim()).filter(Boolean).slice(0, 3) : []
    return { category, reason: 'AI: ' + String(json.reason || category).slice(0, 120), replies }
  } catch (e) {
    log('Groq request failed: ' + e.message)
    const why = e.status === 401 ? 'Groq rejected the API key, used simple rules' : 'AI unavailable, used simple rules'
    return { ...fallback(text, why), replies: [] }
  }
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
