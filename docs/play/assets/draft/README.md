# Store graphics: DRAFTS (step 3)

**Not for upload.** Under the owner's rule (2026-11-07, `docs/RELEASING.md`,
"Docs + screenshots match this build"), the graphics uploaded with a version
are retaken from that exact release build at step 5a. These drafts show the
plan: which screens, which captions, the frame.

| File | What |
|---|---|
| `raw/en/NN-*.png` | Real screenshots, English: Galaxy A20 (Android 11, 720 x 1560), debug build of step 3. Its camera looks at a wooden shelf: there was no printed page within its view. |
| `raw/he/NN-*.png` | The same screens in Hebrew: the API 36 emulator (720 x 1600), the app's language set to Hebrew. Its emulated camera shows coloured blocks, not a real scene (the A20's language can't be changed: device rule). |
| `screenshots/en-US/`, `screenshots/iw-IL/` | 1080 x 1920 store screenshots: a caption over the real screenshot |
| `feature-graphic-1024x500-en-US.png`, `-iw-IL.png` | The feature graphic, with the launcher icon's art |

Rebuild everything from the raw pictures:

```bash
python3 scripts/store-shots.py --icon <A|B|C>
```

The script checks Play's rules on its output: 24-bit PNG without alpha, each
side 320 to 3840 px, the long side at most twice the short one.

## What the drafts still lack

- **`01-read`** ("Read small print"), the most important shot: no raw picture
  yet, so the script skips it. It needs a printed page (a leaflet, a label, a
  book) under the A20's camera, in good light. The emulator's cameras can't
  show one: the virtual scene has no zoom and its posters aren't in view.
- The About shot says "Bukin Magnifier (debug)": the release build says
  "Bukin Magnifier".
- The Hebrew shots' coloured blocks: at 5a, either the emulator again (honest,
  but abstract) or Hebrew on a phone the owner can set to Hebrew.

## At step 5a

1. Install the release build (signed by the owner) on the A20 and the emulator.
2. Retake every `raw/` picture from it, the same screens, the same names.
3. Rerun the script; check the captions still describe what the screens show.
4. Copy the result out of `draft/` (for example to `assets/1.0/`) and tick
   `screenshots-en` and `screenshots-he` in `release-docs-checklist.md`.
