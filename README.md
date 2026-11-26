# LapFold

Turn your Galaxy Z Fold into a laptop. Unfold it, bend it like a laptop, and the bottom half becomes a **Windows-style keyboard and trackpad** while the top half runs any app — perfect with Splashtop, a browser, or docs.

## Features
- Full Windows keyboard: Esc, F1–F12, Tab, Caps, Shift, Ctrl, Win, Alt, arrows, key repeat
- Trackpad with on-screen pointer: tap to click, right-click, two-finger scroll, drag with the left button
- Mouse-wheel scroll strip, plus Back and Home buttons
- Auto split screen: your app on top, LapFold below
- Turn on/off any time: notification button, Quick Settings tile, or open the app
- **Gboard stays your keyboard** — nothing to switch, no computer or ADB needed
- Dark and light themes
- Built-in update check

## Requirements
Android 13 or newer. Made for the Galaxy Z Fold, works on any Android 13+ phone or tablet.

## Install
1. Download the latest `LapFold-vX.apk` from [Releases](https://github.com/ilukezippo/LapFold/releases).
2. If Play Protect blocks it: Play Store → profile → Play Protect → ⚙️ → turn off *Scan apps with Play Protect*, install, then turn it back on.
3. Open LapFold → **Open Accessibility settings** → turn on LapFold.
   If the switch is blocked: Settings → Apps → LapFold → ⋮ → **Allow restricted settings**, then try again.

## Why Accessibility?
LapFold uses the Accessibility permission only to type into the text box you selected, move its pointer, and tap/scroll where the pointer is. It doesn't read, store or send anything on your screen.

## Tips
- Shift / Ctrl / Alt / Win: tap once for the next key, twice to lock, again to release.
- Splashtop: use touch mode so clicks land where the pointer is.

## Build
```
gradle :app:assembleRelease
```
Kotlin, no third-party libraries.
