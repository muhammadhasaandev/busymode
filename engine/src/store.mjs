// Recent WhatsApp messages kept on the phone, so the voice assistant can read and summarise them.
import fs from 'node:fs'
import path from 'node:path'

const MAX_MESSAGES = 800

export function createStore (dataDir) {
  const file = path.join(dataDir, 'messages.json')
  let messages = []
  try { messages = JSON.parse(fs.readFileSync(file, 'utf8')) } catch {}
  if (!Array.isArray(messages)) messages = []
  const seen = new Set(messages.map((m) => m.chatJid + '|' + m.id))
  let saveTimer = null

  function save () {
    clearTimeout(saveTimer)
    saveTimer = setTimeout(() => {
      fs.writeFile(file, JSON.stringify(messages), () => {})
    }, 4000)
  }

  function add (rec) {
    const key = rec.chatJid + '|' + rec.id
    if (rec.id && seen.has(key)) return false
    seen.add(key)
    messages.push(rec)
    if (messages.length > MAX_MESSAGES) {
      const removed = messages.splice(0, messages.length - MAX_MESSAGES)
      for (const r of removed) seen.delete(r.chatJid + '|' + r.id)
    }
    messages.sort((a, b) => a.time - b.time)
    save()
    return true
  }

  /** Messages from the last `hours`, oldest first. */
  function recent ({ hours = 24, chatFilter = null, includeGroups = true, limit = 40 } = {}) {
    const since = Date.now() - hours * 3600 * 1000
    let list = messages.filter((m) => m.time >= since && (includeGroups || !m.isGroup))
    if (chatFilter) list = list.filter((m) => chatFilter(m))
    return list.slice(-limit)
  }

  /** Distinct chats seen, newest first: { jid, name, isGroup, lastTime }. */
  function chats () {
    const map = new Map()
    for (const m of messages) {
      const c = map.get(m.chatJid) || { jid: m.chatJid, name: m.chatName, isGroup: m.isGroup, lastTime: 0, number: '' }
      if (m.time >= c.lastTime) { c.lastTime = m.time; if (m.chatName && !m.chatName.startsWith('+')) c.name = m.chatName }
      if (!m.isGroup && !m.fromMe && m.number) c.number = m.number
      map.set(m.chatJid, c)
    }
    return [...map.values()].sort((a, b) => b.lastTime - a.lastTime)
  }

  return { add, recent, chats, all: () => messages }
}
