# Bukin Magnifier

A free magnifying-glass app for Android (`bukin.magnifier`): the rear camera as a
magnifier. No ads, no purchases, no internet permission. Nothing leaves the
phone unless you share a picture yourself.

## Features

- **Live magnifier:** the rear camera fills the screen. The screen stays on while it shows.
- **Zoom:** a slider with − and + buttons, pinch, or the volume keys, across
  the camera's whole range. The number shows two decimals below 2× ("1.04×"),
  so every step is visible.
- **Focus:** auto focus at start. Tap the picture to focus there and lock it.
  The Focus button switches Auto / Locked, and a near-to-far focus slider
  appears on phones whose lens allows it (Manual).
- **Light:** the LED torch on and off.
- **Freeze:** shows the screen's picture at once, then a sharp full-resolution
  photo in its place. Pinch, drag, double tap, −/+ or the volume keys zoom
  and move around it. **Live** goes back. The light stays as it was.
- **Colours:** Normal, High contrast, Inverted, and Yellow on black. They apply
  to the live picture, the frozen picture and saved pictures, never to the controls.
- **Brightness:** live, the camera's exposure; frozen, the picture's brightness.
  It is hidden live (with the reason) on cameras without exposure control.
- **Save and share** (frozen picture only): the **whole** frozen photo, not just
  the zoomed-in part, with the colours and brightness as shown.
  - Save goes to `Pictures/Bukin Magnifier`. Android 10+ needs no permission;
    Android 7-9 ask for storage the first time you save.
  - Share opens the system share sheet.
- **Remembers** the zoom, the light and the colour mode. Brightness and focus
  start fresh every time.
- **Easy to use:** big controls (56-76 dp) in three rows at the bottom, within
  reach of one thumb, with Freeze the biggest. TalkBack labels and states on
  every control. English and Hebrew (right to left).

## Building

- Minimum Android 7.0 (API 24), target API 36.
- Debug builds install beside the Play app as `bukin.magnifier.debug`.
- Releases: see `docs/RELEASING.md`.
