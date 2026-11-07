# Data safety form (draft answers)

Play Console → **App content** → **Data safety**, for Bukin Magnifier
(`bukin.magnifier`). Based on the code and the merged release manifest at step
3. Re-check before every upload (the `data-safety` item of
`release-docs-checklist.md`), and at once if the app ever gains a permission, a
library that talks to the network, or a new way to send data off the phone.

Google's definitions ([Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)):

- **Collected** means transmitting data from the app off the user's device.
  Data processed only on the device doesn't need to be declared.
- A transfer the user starts, to an app or place the user picks (such as
  Android's share sheet), where the app itself doesn't access or send the data
  elsewhere, isn't collection or sharing by the app.
- Apps that collect and share nothing still fill in the form and link a
  privacy policy.

## Why the answer is "no data collected or shared"

| Fact (code and merged release manifest) | Effect |
|---|---|
| Permissions: `CAMERA`, and `WRITE_EXTERNAL_STORAGE` with `maxSdkVersion 28`. **No `INTERNET`.** `ACCESS_NETWORK_STATE` (from media3) is removed in the manifest; `scripts/release.sh` fails a release with any other permission. | The app can't send anything off the phone. |
| No ads, analytics, crash-reporting, billing or sign-in library (`app/build.gradle.kts`: AndroidX, Material, CameraX only). | Nothing collects data in the background. |
| The camera picture is shown on the screen and processed in memory. A frozen photo stays in memory. | Processed on the device only. |
| **Save** writes a JPEG to `Pictures/Bukin Magnifier` through MediaStore, only when the user taps Save. No location or other metadata is added (the picture is re-encoded from pixels; there is no location permission). | Stays on the device, in the user's own photos. |
| **Share** writes a temporary copy to the app's cache (`cache/shared/`, deleted after 10 minutes on a later launch) and opens Android's share sheet with a read grant for that one file. The user picks the receiving app. | User-initiated transfer to an app the user chooses: not collection or sharing by the app. |
| The About screen opens the privacy policy in the user's browser, and an email draft (subject only) in the user's email app. The app sends nothing itself. | Not collection: the user's own browser and email app. |
| Android Auto Backup / device transfer includes only `magnifier_settings.xml`: the zoom, light and colour mode (`backup_rules.xml`, `data_extraction_rules.xml`). | Android's backup, not app-initiated transmission. The privacy policy describes it anyway. |

## Answers

1. **Does your app collect or share any of the required user data types?**
   **No.**
2. The security-practice questions (encryption in transit, deletion requests)
   don't appear after "No".
3. **Privacy policy URL:**
   https://elibukin.github.io/magnifier/privacy-policy.html (once published;
   required even with "No").

The Data safety section on the store page will then read "No data shared with
third parties" and "No data collected".

## Keep consistent with

- `docs/privacy-policy.md` / `.he.md` and the published site (the
  `website-privacy` checklist item);
- **Ads:** "No, my app does not contain ads";
- **App access:** everything is available without login or special access;
- the permissions the store page lists from the manifest: Camera, and on
  Android 9 and older the storage permission ("modify or delete the contents of
  your shared storage").
