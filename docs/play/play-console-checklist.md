# Play Console checklist (owner)

Everything to fill in for Bukin Magnifier (`bukin.magnifier`) before the first
upload. Only the owner does these, in Play Console, account "bukin". Drafts and
facts are in this folder. Tick here as you go; the
`release-docs-checklist.md` gate for each upload is separate.

## 1. Create the app

- [ ] **Create app**: name **Bukin Magnifier**; default language **English
      (United States) – en-US**; **App**; **Free**.
- [ ] Declarations: Developer Program Policies, US export laws.
- [ ] Note: a free app can never be made paid later. This app stays free.

## 2. App content (Policy → App content)

| Item | Answer | Source |
|---|---|---|
| Privacy policy | https://elibukin.github.io/magnifier/privacy-policy.html | after the master publishes `gh-pages` (owner OK) |
| Ads | **No**, the app has no ads | no ad library |
| App access | **All functionality is available without special access** (no login) | |
| Content rating | Questionnaire, category **All other app types**, all "No" | `content-rating-notes.md` |
| Target audience and content | Owner decides. Suggested: **18 and over** (or 13+), so the Families policy doesn't apply. The app is for a general audience and collects nothing from anyone. | |
| Data safety | **No data collected, no data shared** | `data-safety.md` |
| News app | No | |
| Government app | No | |
| Financial features | None | |
| Health | Not a health app: a reading aid, not a medical device. If the list offers a category that fits a magnifier (for example accessibility or assistive tools), the owner decides; otherwise "my app has no health features". | |
| Advertising ID | **No** (no ads or analytics; Android 13+ apps that don't use it declare so) | |
| Accessibility services API | Not used | |
| Foreground service, exact alarms, photo/video permissions | Not used: the app asks only for Camera and, on Android 9 and older, storage | manifest |

## 3. Store presence

- [ ] **Store settings**: category **Tools**; contact email
      **dev.mechanics998877@gmail.com**; website
      **https://elibukin.github.io/magnifier/**; no phone.
- [ ] **Main store listing (en-US)**: app name, short and full description from
      `store-listing.md`.
- [ ] **Translation: Hebrew – iw-IL**: the Hebrew texts from `store-listing.md`
      (add the language under "Manage translations", then paste; don't use
      Google's machine translation).
- [ ] **Graphics, both languages**: the 512 x 512 icon
      (`assets/icon-512.png`), the 1024 x 500 feature graphic, and 4 to 8 phone
      screenshots each. **Only the step 5a set, taken from the release build**
      (owner rule), not the step 3 drafts.
- [ ] Tags (optional): up to five, chosen in Play Console from its list, for
      example "Magnifier" and "Flashlight" if offered.

## 4. Release

- [ ] **Countries / regions**: owner's choice (all, as for the other apps).
- [ ] Release notes: `release-notes-1.0.md` (EN + HE).
- [ ] Upload key and signing: `docs/RELEASING.md` (owner only).
- [ ] Internal test first, then **closed test: 12+ testers for 14 days** (needed
      before a personal account can apply for production).
- [ ] Production access application: honest answers only.
- [ ] Record the content rating Play gives: ______

## 5. After publishing the policy

- [ ] Open both URLs signed out, on a phone too:
      https://elibukin.github.io/magnifier/ and
      https://elibukin.github.io/magnifier/privacy-policy.html
- [ ] Check that the app's About → Privacy policy opens the same page.
