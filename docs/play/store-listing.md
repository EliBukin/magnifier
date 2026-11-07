# Store listing (draft)

Bukin Magnifier (`bukin.magnifier`). Prepared in step 3 for the owner's review;
nothing here has been entered in Play Console. Every fact matches the code at
the time of writing (see "Facts behind the wording" at the end). Under the
owner's rule (docs/RELEASING.md, "Docs + screenshots match this build"), the
texts are checked again against the release build before every upload.

`python3 scripts/check-listing.py` counts the characters of every text block
below against Play's limits and fails if one is over.

## Fields

| Field | Value |
|---|---|
| Default language | English (United States), `en-US` |
| Translation | Hebrew, `iw-IL` (Play Console lists it as "Hebrew – עברית") |
| App or game | App |
| Free or paid | Free (Play never lets a free app become paid later) |
| Category | Tools |
| Contact email | dev.mechanics998877@gmail.com |
| Website | https://elibukin.github.io/magnifier/ |
| Privacy policy | https://elibukin.github.io/magnifier/privacy-policy.html (English, then Hebrew on the same page) |
| Phone | none |

## English (en-US)

### App name (en-US)

```text
Bukin Magnifier
```

### Short description (en-US)

```text
Magnifying glass with the LED light: zoom, freeze, colours. Free, no ads.
```

### Full description (en-US)

```text
Bukin Magnifier turns your phone into a magnifying glass. Point the rear camera at small print, a medicine leaflet, a price tag or a label, and read it large on the screen. Turn on the phone's LED light when it's dark.

MAGNIFY
• The camera starts at once and fills the screen.
• Zoom by turning the lens ring at the bottom, with two fingers, or with the volume keys.
• Focus is automatic. Tap the picture to focus there and lock it. On phones whose camera allows it, you can also set the focus by hand.
• Make the picture lighter or darker.

LIGHT
Turn the phone's LED light on and off with one tap, while you read.

FREEZE
Freeze takes a sharp photo of what you see. Zoom into it and move around with your fingers, without holding the phone still. Save it to your phone's Pictures folder, or share it with an app you choose.

COLOURS FOR EASIER READING
Normal, high contrast, inverted, and yellow on black.

EASY TO USE
• Big round buttons within reach of one thumb.
• TalkBack labels on every control.
• Remembers your zoom, light and colour mode.
• English and Hebrew.

FREE AND PRIVATE
Completely free: no ads, no purchases, no account. The app has no internet permission and works offline. Nothing leaves your phone unless you share a picture yourself.

GOOD TO KNOW
The zoom range, the light and manual focus depend on your phone's camera. Bukin Magnifier is a reading aid, not a medical device. Requires Android 7.0 or newer and a rear camera.
```

## Hebrew (iw-IL)

### App name (iw-IL)

```text
Bukin Magnifier
```

### Short description (iw-IL)

```text
זכוכית מגדלת עם פנס LED: הגדלה, הקפאה וצבעים. בחינם, בלי פרסומות.
```

### Full description (iw-IL)

```text
Bukin Magnifier הופכת את הטלפון לזכוכית מגדלת. מכוונים את המצלמה האחורית אל אותיות קטנות, עלון של תרופה, תגית מחיר או תווית, וקוראים אותן בגדול על המסך. כשחשוך, מדליקים את פנס ה־LED של הטלפון.

הגדלה
• המצלמה נפתחת מיד וממלאת את המסך.
• מגדילים בסיבוב טבעת העדשה בתחתית המסך, בשתי אצבעות, או במקשי עוצמת הקול.
• המיקוד אוטומטי. הקשה על התמונה ממקדת שם ונועלת את המיקוד. בטלפונים שהמצלמה שלהם מאפשרת זאת, אפשר גם לכוון את המיקוד ידנית.
• אפשר להבהיר או להחשיך את התמונה.

פנס
הדלקה וכיבוי של פנס ה־LED בהקשה אחת, תוך כדי קריאה.

הקפאה
ההקפאה מצלמת תמונה חדה של מה שרואים. אפשר להגדיל אותה ולזוז בה באצבעות, בלי להחזיק את הטלפון יציב. אפשר לשמור אותה בתיקיית התמונות של הטלפון, או לשתף אותה עם אפליקציה שבוחרים.

צבעים לקריאה נוחה יותר
רגיל, ניגודיות גבוהה, צבעים הפוכים, וצהוב על שחור.

נוחה לשימוש
• כפתורים עגולים גדולים, בהישג אגודל אחד.
• תוויות TalkBack לכל פקד.
• זוכרת את ההגדלה, הפנס ומצב הצבעים.
• עברית ואנגלית.

בחינם ופרטית
בחינם לגמרי: בלי פרסומות, בלי רכישות ובלי חשבון. לאפליקציה אין הרשאת אינטרנט, והיא עובדת בלי חיבור לרשת. שום דבר לא יוצא מהטלפון, אלא אם משתפים תמונה בעצמך.

כדאי לדעת
טווח ההגדלה, הפנס והמיקוד הידני תלויים במצלמה של הטלפון. Bukin Magnifier היא עזר לקריאה, לא מכשיר רפואי. דורשת Android 7.0 ואילך ומצלמה אחורית.
```

## Graphics

| Asset | Play's requirement | Status |
|---|---|---|
| App icon | 512 x 512, 32-bit PNG, up to 1024 KB | `assets/icon-512.png` once the owner picks an icon (step 3 drafts A/B/C in `Magnifier-notes/step3/icon-options.png`) |
| Feature graphic | 1024 x 500, JPEG or 24-bit PNG, no alpha | Draft: `assets/draft/feature-graphic-1024x500.png` |
| Phone screenshots | 2 to 8; JPEG or 24-bit PNG, no alpha; each side 320 to 3840 px; the long side at most twice the short | Drafts, EN and HE: `assets/draft/screenshots/`. **Retaken from the release build at step 5a** (owner rule). |

Made by `scripts/store-shots.py` from real A20 screenshots; see
`assets/draft/README.md`.

## Wording safeguards

- No promise of a zoom level, a light or manual focus on every phone: all three
  depend on the camera, and the listing says so.
- "Reading aid, not a medical device": no health or vision claims.
- No "best", "#1", rankings, prices or calls to action. "Free" appears only in
  the descriptions, never in the title.
- Hebrew uses the impersonal forms the app itself uses ("מכוונים", "אפשר"),
  except "שבוחרים" / "בעצמך" where Hebrew needs a person.

## Facts behind the wording

| Claim | Where it holds |
|---|---|
| No internet permission, works offline | `AndroidManifest.xml` (CAMERA, WRITE_EXTERNAL_STORAGE maxSdk 28 only); `scripts/release.sh` allowlist |
| No ads, purchases, account | No ad, billing, sign-in or analytics library (`app/build.gradle.kts`) |
| Zoom: ring, two fingers, volume keys | `LensRingView`, pinch in `MainActivity`, `VolumeKeyZoom` |
| Tap to focus and lock; manual focus where allowed | `FocusMode`, `ManualFocus` (only when the camera reports it) |
| Lighter or darker | exposure live, gain when frozen (`MainActivity`) |
| Freeze: sharp photo, zoom and move | `FrozenView`, `FrozenMath` |
| Save to Pictures, share to a chosen app | `PictureStore`: `Pictures/Bukin Magnifier`; the share sheet |
| Four colour modes | `ColorMode` |
| TalkBack labels on every control | `MainActivityTest.everyControlHasALabelForTalkBack`, `AboutTest` |
| Remembers zoom, light, colour mode | `MagnifierSettings` |
| Android 7.0+, rear camera | `minSdk 24`; `uses-feature android.hardware.camera required` |
