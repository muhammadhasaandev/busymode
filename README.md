# Busy Mode

An Android app that links to your WhatsApp as a linked device, reads incoming messages, and uses a free Groq AI model to decide what to do:

| Message | What Busy Mode does |
|---|---|
| Casual private message | Auto-replies with your text (once per chat per hour by default) |
| Important / urgent private message | Speaks: "Hasaan, you have an important message from [name]." |
| Group message that tags you, replies to you or says your name | Speaks: "Hasaan, someone is talking about you in [group], please check." |
| Everything else | Ignored silently |

Everything runs on the phone: WhatsApp connection (Baileys on an embedded Node.js runtime), settings, and the activity log. The only outside service is Groq for the AI decision.

## Building the APK

**In the cloud (no setup):** push this folder to a GitHub repository. The included workflow (`.github/workflows/build-apk.yml`) builds the APK and publishes it under the repository's **Releases**.

**On a computer** with Android Studio (or the Android SDK + NDK), JDK 17 and Node.js 20:

```
./scripts/prepare.sh
./gradlew assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`.

## Layout

- `engine/` — JavaScript engine: WhatsApp connection, pairing code, Groq classification, auto-reply. Bundled into the app by `scripts/prepare.sh`.
- `app/` — Kotlin Android app: background service, boot start, voice alerts, settings, activity log.

## Notes

- Baileys is an unofficial WhatsApp client. WhatsApp may restrict or ban numbers that use unofficial clients, especially for automated messages. Auto-replies are rate-limited per chat to keep this low.
- Node.js on Android (nodejs-mobile) is version 18; `engine/src/polyfills.mjs` fills the gaps Baileys needs.
