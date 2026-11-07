# Releasing to Google Play

How to build, sign, record and upload a release of Bukin Magnifier
(`bukin.magnifier`). Read it all before the first upload: some decisions, like
the application ID and the upload key, are permanent.

## Keys: Play App Signing and the upload key

The app uses **Play App Signing**, Google's default for new apps:

- **App signing key.** Generated and kept by Google. It signs the APKs that
  Play delivers to users. You never hold it.
- **Upload key.** Generated and kept by you. You sign each upload with it,
  and Play checks it before accepting the bundle. If it's lost or leaked, you
  can ask Google to reset it from Play Console. That's possible because the
  app signing key stays safe with Google.

Rules for the upload key:

- Generate it yourself, on a machine you control. This repository does not
  generate it and does not choose its certificate details.
- Keep the keystore **outside this repository**, for example in
  `~/secure/magnifier/`. The script below refuses a keystore inside the
  working tree, and `.gitignore` ignores keystore file types anywhere in the
  repo as a second line of defence.
- Keep the keystore and its passwords in a password manager or another
  encrypted backup. Don't email them, don't put them in chat or tickets, and
  don't write them in shell commands, scripts, `gradle.properties` or this
  documentation.
- Use a 2048-bit or larger RSA key, valid for 25+ years.

Generate it with `keytool` (part of the JDK). It asks for the passwords and
certificate details interactively, so nothing secret goes on the command line:

```bash
mkdir -p ~/secure/magnifier && chmod 700 ~/secure/magnifier
```

```bash
keytool -genkeypair -v -keystore ~/secure/magnifier/upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

The certificate details (name, organization, country) are visible to anyone
who inspects an APK. Use the publisher identity you want to be associated with.

After generating it, record the certificate's **SHA-256 fingerprint**. Play
Console shows it for comparison, and you'll need it to recognize the key later:

```bash
keytool -list -v -keystore ~/secure/magnifier/upload-keystore.jks -alias upload
```

## Build variants and what is signed

| Artifact | How it's produced | Signed with | Use |
|---|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `./gradlew :app:assembleDebug` | Gradle's local debug key | Development and side-loading (`bukin.magnifier.debug`). Never upload. |
| `app/build/outputs/bundle/release/app-release.aab` | `./gradlew :app:bundleRelease` | **Unsigned** | Diagnostic only: inspection, size checks. Never signed or uploaded. |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `./gradlew :app:assembleRelease` | **Unsigned** | Diagnostic only. Can't be installed. |
| `dist/<release>/app-release-unsigned.aab` | `scripts/release.sh build` (below) | **Unsigned** | A verified release candidate, built from one commit. |
| `dist/<release>/<package>-<version>-vc<code>-upload-signed.aab` | `scripts/release.sh sign` (below) | Your **upload key** | The only artifact to upload to Play. |

The `release` build type deliberately has **no signing config**. Gradle never
signs a release build, and it never falls back to the debug key. Debug builds
and every unit test work without any production secret, including on a
working tree with uncommitted changes.

## Permissions: the camera, storage on old Android, and nothing else

The app asks for two permissions, and no internet:

| Permission | Why | When it is asked |
|---|---|---|
| `android.permission.CAMERA` | The magnifier is the camera's picture. | At the first start, after an explanation. |
| `android.permission.WRITE_EXTERNAL_STORAGE`, `maxSdkVersion="28"` | Saving a frozen picture to `Pictures/Bukin Magnifier` on Android 7-9. Android 10 and later save through MediaStore without any permission, so the manifest limits it to API 28. | Only when the user taps Save on Android 7-9, never at start. |

Nothing leaves the phone unless the user shares a picture through the
system's share sheet, to an app they pick. So Play's Data safety answer is
"no data collected".

Libraries can add permissions of their own through manifest merging. Two do
today, and `app/src/main/AndroidManifest.xml` removes both with
`tools:node="remove"`:

| Permission | Added by | Why it can go |
|---|---|---|
| `android.permission.ACCESS_NETWORK_STATE` | media3-common, through camera-view → camera-video | The app records and plays no video; media3's code is unused (R8 drops it). |
| `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (declared and requested) | androidx.core | Only `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` needs it before Android 13, and nothing in the app calls it. |

`scripts/release.sh` enforces this on every release, on the bundle's own
merged manifest:

- **Permission allowlist.** Every `uses-permission`, `uses-permission-sdk-23`
  and `permission` element must match an entry of `ALLOWED_PERMISSIONS` at
  the top of the script exactly (name, and `maxSdkVersion` when there is
  one). Anything else stops the release, naming the permission. A library
  update that brings `INTERNET` fails here.
- **Receiver code.** The release's dex must not contain androidx.core's
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` string: that code would need the
  permission removed above.

`ALLOWED_PERMISSIONS` holds exactly the two lines above:
`"uses-permission android.permission.CAMERA"` and
`"uses-permission android.permission.WRITE_EXTERNAL_STORAGE maxSdkVersion=28"`.
The storage line only matches with its `maxSdkVersion=28`, so a build that
dropped the limit would fail. To allow another permission, add its exact line
there, and update Play's Data safety form and the privacy policy to match. Check the
merged manifest of a release build by hand with:

```bash
grep -E 'uses-permission|<permission' app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
```

## Docs + screenshots match this build

Owner rule (2026-11-07), for every app: "when a version is uploaded ALL THE
DOCUMENT MUST BE UPDATED". Play must never show screenshots or text from an
older version. So before **any** upload to **any** Play track (internal,
closed, open or production), and before the owner is asked to sign, all of
these must match the exact build being released:

| Item (key in the checklist) | What "matches this build" means |
|---|---|
| `screenshots-en` | Play screenshots in English, taken fresh from this build |
| `screenshots-he` | Play screenshots in Hebrew, taken fresh from this build |
| `short-description` | The Play short description, English and Hebrew |
| `full-description` | The Play full description, English and Hebrew |
| `release-notes` | The release notes ("What's new") for this version, English and Hebrew |
| `data-safety` | Play's Data safety form: this build's permissions and data use |
| `website-privacy` | The gh-pages site and the privacy policy |
| `in-app-help` | The in-app help / About text |
| `readme` | `README.md` |

How it's enforced:

- **The checklist.** `docs/play/release-docs-checklist.md` names a
  `versionCode` and `versionName` and has one line per item above,
  `- [ ] <key>: …`. For each new version: set the two version lines to the
  values in `app/build.gradle.kts`, untick everything, do each item for this
  build and tick it (`[x]`), then commit.
- **`scripts/release.sh build`** reads the checklist from the commit it builds,
  so uncommitted edits don't count. It refuses before the Gradle build starts,
  and again after it against the bundle's own version, unless:
  - the file is there;
  - its `versionCode` and `versionName` are this build's;
  - every item is listed exactly once and ticked.

  The error names each problem: the version that doesn't match, each item that
  is missing or not ticked. The build record notes the checklist was complete.
- **`scripts/release.sh sign`** checks the checklist at the recorded commit
  again.
- **New item?** Add its key to `RELEASE_DOCS_ITEMS` in `scripts/release.sh`, a
  line to the checklist, and a row to the table above.

## Version codes

- `versionCode` (in `app/build.gradle.kts`) must increase with **every** upload
  to Play, on any track (internal, closed, open or production). Play
  permanently rejects a version code it has already seen for this app, even
  if that upload was deleted or never rolled out.
- Before the first upload: `versionCode` is `1` and nothing has been uploaded
  to Play. So **1 is valid for the first upload**.
- After that, bump `versionCode` by 1 for each upload. Bump `versionName`
  (shown to users) as you like, for example `1.0` → `1.0.1` → `1.1`.
- Commit the bump. Releases are only ever built from a commit.

## Building and signing an upload

A release goes through `scripts/release.sh`. It never signs a bundle it didn't
build and verify itself. It needs `JAVA_HOME` (JDK 21) and `ANDROID_HOME` (the
SDK) set.

1. **Docs + screenshots first.** Do everything in "Docs + screenshots match this
   build" above for this version, and commit the completed checklist. Without
   it, `build` refuses.

2. **Build and verify one commit.** Name the commit (or a branch or tag that
   points at it) explicitly:

   ```bash
   scripts/release.sh build <commit>
   ```

   - **Isolated checkout.** It exports exactly that commit (`git archive`) into
     a fresh temporary directory, so uncommitted edits, untracked files and
     earlier build outputs can't reach the release.
   - **Docs checklist.** It checks that commit's
     `docs/play/release-docs-checklist.md` before building (above).
   - **Gates.** It runs them all in that directory:
     `./gradlew --no-daemon --continue clean check lintRelease bundleRelease`
     (unit tests for debug and release, lint, the minified release bundle).
     If anything fails, it stops: nothing is kept or signed, and the isolated
     checkout and its log are left for inspection.
   - **Permissions.** The allowlist and receiver-code checks above.
   - **Mapping.** It requires the R8 mapping embedded in the bundle
     (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`) to
     be byte-for-byte identical to the mapping that build wrote, and archives
     that mapping.
   - **Release directory.** It keeps the verified outputs together in a new
     directory, `dist/<package>-<versionName>-vc<versionCode>-<commit>/`. It
     refuses if one already exists for that commit, and makes every file
     read-only:
     - `app-release-unsigned.aab` and `mapping.txt`;
     - `build.log`, the lint reports and the unit test results;
     - `build-record.txt`: the full commit and its tree, toolchain versions
       (Gradle, Android Gradle plugin, Kotlin, JDK, aapt2), test and lint
       results, package and version, the permissions, SHA-256 of the bundle
       and the mapping, whether owner placeholders are still in the
       bundle, and that the docs checklist was complete.

3. **Sign that release directory.** `jarsigner` asks for the keystore
   password, and the key password if it's different, at the prompt:

   ```bash
   scripts/release.sh sign dist/<release> ~/secure/magnifier/upload-keystore.jks upload
   ```

   `sign` takes only a release directory written by `build`, never a loose
   bundle. Before signing, it re-checks that:
   - the bundle and mapping still match the SHA-256 hashes in
     `build-record.txt`;
   - the recorded commit exists here with the recorded tree;
   - the bundle's embedded mapping is identical to the archived `mapping.txt`;
   - the bundle isn't already signed;
   - no `[[PLACEHOLDER: …]]` owner details are left;
   - the permissions are still all in the allowlist;
   - the docs checklist at the recorded commit is still complete for this
     version;
   - the keystore lives outside the repository.

   It then signs, verifies the signature, checks the signed bundle still embeds
   the same mapping, and writes `sign-record.txt`: the upload certificate's
   SHA-256 fingerprint and the signed bundle's SHA-256. A directory is never
   signed twice.

4. Before uploading, check that the fingerprint `sign` prints matches the upload
   key certificate on Play Console's **App signing** page. That's after the
   first upload: for the first upload, Play records whichever upload key you
   use.

5. Upload `dist/<release>/…-upload-signed.aab` in Play Console, with the
   release notes, listing text and screenshots from the checklist.

## Keep a release record for every upload

**Copy the whole `dist/<release>/` directory for each upload to durable
storage outside the repository**, such as an encrypted backup. `dist/` is
ignored by Git.

| Item | Why |
|---|---|
| Source commit and tree (full hashes) | Rebuild or audit exactly what shipped |
| Toolchain versions, test and lint results | Know how it was built and verified |
| `versionCode` / `versionName` | Match Play Console and crash reports to source |
| SHA-256 of the unsigned and signed bundles | Prove which file was uploaded |
| Upload certificate SHA-256 fingerprint | Confirm the right key was used |
| R8 `mapping.txt` (and its SHA-256) | Decode obfuscated stack traces |

The archived `mapping.txt` is the one embedded in that exact bundle, which Play
also uses for its own crash reports.

## The privacy policy

Set up in step 3 of the plan: the policy is served from a small public repo's
`gh-pages` branch, the same way as the other apps. Publishing it needs the
owner's OK.
