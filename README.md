# YMusic Voice Bridge

Keeps Gemini as your assistant and adds one thing: when you say **"play [song] on YMusic"**, the
song plays in YMusic V4. Every other command (smart home, questions, …) stays with Gemini.

Gemini only plays music in its partner apps, so it answers "I can't control third-party apps like
YMusic V4". This app is an accessibility service that reads Gemini's on-screen transcript. When a
new line is a play command for YMusic, it closes Gemini and asks YMusic to play the query. Nothing
leaves the device.

Personal / testing use only: Play Store policy doesn't allow this kind of accessibility use.

## Build

Android Studio (or the command line) with JDK 17+:

```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest   # phrase parser tests
```

Toolchain matches YMusic V4: Gradle 9.4.1, AGP 9.2.1, Kotlin 2.4.0, compileSdk 36, minSdk 26.
No library dependencies.

## Setup on the phone

1. Install **YMusic V4** (`io.ymusic.android`, 4.0.9 or newer) and play any song once.
2. Install this app and open it.
3. **Android 13+ (sideloaded apps):** Settings → Apps → YMusic Voice Bridge → ⋮ (top right) →
   **Allow restricted settings**. Without it the accessibility switch stays greyed out.
4. Tap **Open accessibility settings** → Installed apps → **YMusic Voice Bridge** → turn on.
5. On Xiaomi/Oppo/Vivo/Realme etc.: set battery to **No restrictions** and allow autostart, or the
   service gets killed in the background.
6. For the lock screen: in Gemini settings, allow using Gemini without unlocking.

## Use

Say "Hey Google, play Arijit Singh on YMusic" (or "…on YMusic V4"). Hinglish and Hindi work too:
"YMusic pe Arijit Singh chalao", "वाई म्यूजिक पर केसरिया बजाओ".

## Test without Gemini

- **Test play** sends a query straight to YMusic — checks the player hand-off on its own.
- **Test phrase** shows whether a sentence would be recognized.

## Troubleshooting with the log

The debug log (on by default) records the text it reads from the assistant's windows, newest first.
Use **Share** to send it.

| Log shows | Meaning / fix |
|---|---|
| No `[com.google…]` lines at all | Gemini's window lives in another package. Turn on **Watch all apps**, talk to Gemini, find its package in the log, add it under Assistant packages, turn Watch all apps off. |
| Your sentence appears, but no `MATCH` | The transcript is worded differently (e.g. "Y music version 4"). Add that spelling under **App names to listen for**. |
| `MATCH`, then `Session: nothing started playing` | The media session route was refused; it falls back to opening YMusic. If that fails too on the lock screen, switch to **Open YMusic (intent)** and compare. |
| `MATCH`, but Gemini stays open | Turn on **Close the assistant after a match**; some Gemini versions need a second back press. |

## Limits

- Depends on Gemini showing your words on screen; a Gemini UI update can change that.
- Gemini may start saying "I can't…" before it's closed.
- "Hey Google" itself stays Gemini's; this app never listens to the microphone.
