# LapFold — design (approved 2026-11-26)

## Intent
Make a Galaxy Z Fold work like a laptop: top half = any app (often Splashtop to a PC),
bottom half = Windows-style keyboard + trackpad. On demand, any hinge angle, no PC/ADB setup.

## Goals
- Works in any app; Gboard stays the default keyboard (never switched).
- Toggle: persistent notification button, Quick Settings tile, opening the app.
- Samsung split screen: other app top, LapFold bottom. Panel fills LapFold's pane.
  Without split screen, panel floats over the bottom half.
- Windows layout, dark/light theme.
- Trackpad: on-screen pointer, tap/left click, right click (long press), 2-finger scroll.

## Non-goals
Hinge auto-activation, ADB/Shizuku, becoming an IME, PC software.

## Approaches considered
- A/IME: be the keyboard (needs default switch -> needs ADB grant). Rejected: user won't use a computer.
- B/PC receiver: rejected by user (wants it to work on anything).
- Chosen: Accessibility service. Types via AccessibilityService.InputMethod (API 33+) into the
  focused editor while Gboard stays default; hides Gboard via SoftKeyboardController while on;
  pointer clicks/scrolls via dispatchGesture.

## Builder calls
- Panel is an accessibility overlay (non-focusable, so the top app keeps text focus). In split
  screen, the LapFold activity is a placeholder that reports its pane bounds; overlay is drawn
  over those bounds.
- minSdk 33. Kotlin, no external deps beyond AndroidX core.
