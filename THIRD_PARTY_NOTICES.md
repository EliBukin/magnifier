# Third-party notices

The release build of Bukin Magnifier includes the open-source software below.
The app shows the same list, the notices and the licence texts in **About →
Open-source licences** (`app/src/main/res/raw/third_party_notices.txt` and
`apache_license_2_0.txt`, taken from CameraX's own AAR). Keep the two in step:
`AboutTest.theNoticesInTheAppMatchThirdPartyNotices` checks that every
component the app lists is named here.

Checked 2026-11-08 against every artifact on the release runtime classpath:
the licence in each POM, and every `LICENSE` and `NOTICE` file in the JARs and
AARs.

## Apache License 2.0

| Component | Maven groups |
|---|---|
| AndroidX (Android Jetpack) libraries, including CameraX and Media3 | `androidx.*` (Media3 comes with camera-view → camera-video; the app doesn't use it, and R8 removes its code) |
| Material Components for Android | `com.google.android.material` |
| Kotlin standard library, kotlinx.coroutines, atomicfu | `org.jetbrains.kotlin`, `org.jetbrains.kotlinx` |
| JetBrains Java Annotations | `org.jetbrains:annotations` |
| Guava, failureaccess, ListenableFuture | `com.google.guava` (through CameraX's camera2-pipe) |
| Dagger, javax.inject, Jakarta Dependency Injection | `com.google.dagger`, `javax.inject`, `jakarta.inject` |
| AutoValue annotations, Error Prone annotations, J2ObjC annotations, JSR 305 annotations, JSpecify | `com.google.auto.value`, `com.google.errorprone`, `com.google.j2objc`, `com.google.code.findbugs`, `org.jspecify` |

Only `jakarta.inject-api` ships a `NOTICE` file (`META-INF/NOTICE.md`); the app
shows its text, as Apache 2.0 §4(d) asks. It is also in the APK's
`META-INF/NOTICE.md`.

## BSD 3-Clause

| Component | Where |
|---|---|
| libyuv, in CameraX's image processing library | `androidx.camera:camera-core` (its POM declares Apache 2.0 and BSD-3-Clause); the APK's `lib/*/libimage_processing_util_jni.so` |

## MIT

| Component | Where |
|---|---|
| Checker Framework qualifiers | `org.checkerframework:checker-qual` (annotations, through Guava) |

Many of the annotation libraries leave nothing in the shrunk release (R8
removes them); they are listed anyway, to be safe.

Test-only libraries (JUnit, Robolectric, AndroidX Test) and build tools aren't
distributed with the app.

## When dependencies change

List the release runtime classpath, and check each new artifact's licence and
any `LICENSE` / `NOTICE` file:

```bash
./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath
```

Then update this file and `app/src/main/res/raw/third_party_notices.txt`.

The app's own source code is public on GitHub without a licence file: all
rights are reserved until the owner picks one.
