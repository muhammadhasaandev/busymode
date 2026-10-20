// Small Groq chat-completions client with model fallback.
// Responses are read as bytes (no TextDecoder) because Android's Node has no ICU.

const DEFAULT_URL = 'https://api.groq.com/openai/v1/chat/completions'

export class GroqError extends Error {
  constructor (message, status) { super(message); this.status = status }
}

export function createGroq ({ getKey, log }) {
  const url = () => process.env.BUSYMODE_GROQ_URL || DEFAULT_URL

  async function post (body, timeoutMs) {
    const ctrl = new AbortController()
    const timer = setTimeout(() => ctrl.abort(), timeoutMs)
    try {
      const res = await fetch(url(), {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + getKey(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: ctrl.signal
      })
      const raw = Buffer.from(await res.arrayBuffer()).toString('utf8')
      if (!res.ok) throw new GroqError(`Groq error ${res.status}: ${raw.slice(0, 300)}`, res.status)
      return JSON.parse(raw)
    } catch (e) {
      if (e.name === 'AbortError') throw new GroqError('Groq timed out', 0)
      throw e
    } finally {
      clearTimeout(timer)
    }
  }

  /**
   * Run one chat completion, trying each model in turn if one is retired or unavailable.
   * Returns the assistant message object.
   */
  async function chat (body, { models, timeoutMs = 25000 }) {
    if (!getKey()) throw new GroqError('No Groq API key set', 401)
    const tried = new Set()
    let lastErr = null
    for (const model of models) {
      if (!model || tried.has(model)) continue
      tried.add(model)
      const b = { ...body, model }
      if (model.startsWith('openai/gpt-oss')) b.reasoning_effort = body.reasoning_effort || 'low'
      try {
        const data = await post(b, timeoutMs)
        return data?.choices?.[0]?.message || { role: 'assistant', content: '' }
      } catch (e) {
        lastErr = e
        if (e.status === 401) throw e
        const modelProblem = (e.status === 404 || e.status === 400) && /model/i.test(e.message)
        const busy = e.status === 429 || e.status >= 500 || e.status === 0
        if (modelProblem || busy) { log(`Groq ${model}: ${e.message.slice(0, 120)} — trying next model`); continue }
        throw e
      }
    }
    throw lastErr || new GroqError('No Groq model available', 404)
  }

  return { chat }
}

/** Pull the first JSON object out of a model reply. */
export function parseJsonObject (text) {
  const s = String(text || '')
  const a = s.indexOf('{')
  const b = s.lastIndexOf('}')
  if (a < 0 || b <= a) throw new Error('No JSON in reply')
  return JSON.parse(s.slice(a, b + 1))
}
