// nodejs-mobile ships Node 18. Baileys targets Node 20+, so fill the small gaps here.
// This file must be imported before Baileys is loaded.
import { webcrypto } from 'node:crypto'

if (!globalThis.crypto) {
  Object.defineProperty(globalThis, 'crypto', { value: webcrypto, configurable: true, writable: true })
}

if (!Promise.withResolvers) {
  Promise.withResolvers = function () {
    let resolve, reject
    const promise = new Promise((res, rej) => { resolve = res; reject = rej })
    return { promise, resolve, reject }
  }
}

// The Node.js runtime on Android is built without ICU, so `new TextDecoder(label, { fatal: true })`
// throws. The WhatsApp crypto module (WebAssembly) creates exactly that decoder. Its input is always
// valid UTF-8, so a non-fatal decoder behaves the same — drop the option instead of failing.
import util from 'node:util'
import { syncBuiltinESMExports } from 'node:module'

const NativeTextDecoder = globalThis.TextDecoder
let strictDecoderMissing = false
try { new NativeTextDecoder('utf-8', { fatal: true }) } catch { strictDecoderMissing = true }

if (strictDecoderMissing) {
  class LenientTextDecoder extends NativeTextDecoder {
    constructor (label = 'utf-8', options = {}) {
      const { fatal, ...rest } = options || {}
      super(label, rest)
      Object.defineProperty(this, 'fatal', { value: !!fatal, enumerable: true })
    }
  }
  globalThis.TextDecoder = LenientTextDecoder
  try { util.TextDecoder = LenientTextDecoder; syncBuiltinESMExports() } catch {}
}
