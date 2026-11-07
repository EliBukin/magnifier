# Bukin Magnifier

A free magnifying-glass app for Android (`bukin.magnifier`): the rear camera as a
magnifier. No ads, no purchases, no internet permission. Nothing leaves the
phone unless you share a picture yourself.

## Features

- **Live magnifier:** the rear camera fills the screen. The screen stays on while it shows.
- **The lens ring:** a big arc at the bottom, like the ring on a camera lens. It
  adjusts one thing at a time, named in its middle with the value in big yellow
  ("Zoom 2.50×", "Brightness +3", "Focus 20 cm"):
  - turn it by dragging anywhere along it; the whole arc is the whole range;
  - tap it beside the thumb, or tap the icon at either end, for one step that way;
  - with TalkBack it is a slider (swipe up or down to step);
  - in Hebrew it runs from right to left.
  - It shows the **zoom** unless you choose Brightness or Focus. To go back to
    zoom, tap the middle of the ring or the chosen button again. The volume keys
    and pinch zoom too, and bring the zoom back to the ring.
- **Zoom:** the ring, pinch or the volume keys, across the camera's whole range.
  The number shows two decimals below 2× ("1.04×"), so every step is visible.
- **Focus:** auto focus at start. Tap the picture to focus there and lock it.
  The Focus button shows Auto / Locked / Manual:
  - from Locked or Manual, one tap goes back to Auto;
  - on phones whose lens allows manual focus, in Auto it puts the focus distance
    on the ring (near to far); turning the ring is Manual. Tap Focus again, or the
    middle of the ring, to go back to zoom;
  - on other phones, in Auto it locks the focus on the middle of the picture.
- **Light:** the LED torch on and off.
- **Freeze:** shows the screen's picture at once, then a sharp full-resolution
  photo in its place. The ring, pinch, drag, double tap or the volume keys zoom
  and move around it. **Live** goes back. The light stays as it was.
- **Colours:** Normal, High contrast, Inverted, and Yellow on black. They apply
  to the live picture, the frozen picture and saved pictures, never to the controls.
- **Brightness:** the Brightness button puts it on the ring and shows the level
  ("+3"). Live, the camera's exposure; frozen, the picture's brightness. "Lighter"
  always makes the screen lighter, in every colour mode. On cameras without
  exposure control the button says why it can't (live only).
- **Save and share** (frozen picture only): the **whole** frozen photo, not just
  the zoomed-in part, with the colours and brightness as shown.
  - Save goes to `Pictures/Bukin Magnifier`. Android 10+ needs no permission;
    Android 7-9 ask for storage the first time you save.
  - Share opens the system share sheet.
- **Remembers** the zoom, the light and the colour mode. Brightness, focus and
  what the ring shows start fresh every time.
- **Easy to use:** one row of big round buttons under the ring, within reach of
  one thumb: Brightness, Light, **Freeze** (the biggest, in the middle), Focus,
  Colours; frozen, Save and Share take the Light's and Focus's places. Each side
  button is a circle with its icon and a word under it (the circle and the word
  are one touch target): 58dp circles on most phones, 64dp from 400dp wide, 56dp
  under 360dp. The ring's touch band is 56dp thick. The panel is nearly opaque,
  so the ring and the words stay readable over a white page, and it covers under
  40% of a 720 x 1560 screen. TalkBack labels and states on every control.
  English and Hebrew (right to left).

- **About and privacy:** the round "i" button in the top end corner (top right;
  top left in Hebrew), on every screen including the camera permission one. It
  opens a page with the version, "No internet: nothing leaves your phone", and
  three big buttons: the privacy policy (in your browser), an email to the
  developer (in your email app, subject "Bukin Magnifier") and the open-source
  licences. Back, or the arrow at the top, returns to the camera.

## Privacy

No internet permission, no ads, no analytics, no accounts. The camera picture is
used only on the phone. A picture is saved only when you tap Save (into
`Pictures/Bukin Magnifier`) and shared only when you tap Share, with the app you
pick. Android's own backup may copy the three remembered settings.

Live once the `gh-pages` branch is published (owner's OK):

- Privacy policy: https://elibukin.github.io/magnifier/privacy-policy.html
  (English and Hebrew; source `docs/privacy-policy.md` and `.he.md`)
- Website: https://elibukin.github.io/magnifier/ (source `docs/site/`)
- Contact: dev.mechanics998877@gmail.com

The site is the `gh-pages` branch, built with
`python3 scripts/render-site.py <gh-pages checkout>` and checked with
`python3 scripts/test-site.py` (the pages, and that the policy's facts still
hold in the manifest and the code).

## Play store

`docs/play/`: the store listing in English and Hebrew (`store-listing.md`,
checked by `python3 scripts/check-listing.py`), Data safety, release notes,
content-rating notes, the owner's Play Console checklist, and the graphics
(`assets/`; `python3 scripts/store-shots.py` composes the screenshots and the
feature graphic). The launcher icon, Play's 512 px icon and the site's favicon
all come from `scripts/icon_art.py` through `python3 scripts/make-icon.py
<A|B|C>`. Open-source licences: `THIRD_PARTY_NOTICES.md`.

## Building

- Minimum Android 7.0 (API 24), target API 36.
- Debug builds install beside the Play app as `bukin.magnifier.debug`.
- Releases: see `docs/RELEASING.md`.
