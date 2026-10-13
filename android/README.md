# H Reader for Android

The H Reader app for Android phones, in Kotlin and Jetpack Compose. It works
on its own: it fetches feeds itself and keeps everything on the device.
Signing in with Google turns on sync with the web app and other devices.

- `core/` — plain JVM, no Android: feed parsing, filters, ids, the checking
  schedule and feed discovery (ports of `../shared/src`), and the reader's
  state rules (`ReaderModel`). Its tests read `../shared/fixtures` in place.
  `core/sync/` ports `../web/src/lib/sync`: the same Firestore documents,
  merge rule and steps, behind interfaces the tests fake.
- `app/` — the Android app: Room storage, a WorkManager job that checks due
  feeds, the Compose screens, and Firebase (`app/.../sync/`).

## Sync

Sync reads the Firebase project from
`app/src/main/kotlin/cc/hafa/reader/sync/SyncConfig.kt`, whose values for the
`hafa-reader` project are committed. To set up a project again, after the steps
in `../firebase/README.md`:

1. In the Firebase console, Project settings, Your apps, add an Android app
   with package `cc.hafa.reader`, and add the SHA-1 fingerprints of the upload
   key, the Play app signing key (Play Console, App integrity) and the debug key
   (`./gradlew :app:signingReport`). Google sign-in fails for a build whose key
   is not listed.
2. Copy the API key, the Android app id (`1:…:android:…`) and the project id
   into `SyncConfig`, and the OAuth client id of the project's web client
   (Authentication, Sign-in method, Google, Web SDK configuration) into
   `WEB_CLIENT_ID`. They are public, like the web app's settings; no
   `google-services.json` is used.

The sync logic is tested against fakes; signing in and syncing with the
`hafa-reader` project has not been tested end to end on a device.

The database keeps one schema version until the first release; a build that
changes the tables needs the app reinstalled on devices that have an older
build.

## Build

Gradle needs a JDK to start (set `JAVA_HOME` to any JDK 17 or newer, such as
the one inside Android Studio) and runs its build on JDK 21
(`gradle/gradle-daemon-jvm.properties`), which it must find installed.
`local.properties` points at the Android SDK; Android Studio writes it.

```sh
./gradlew assembleDebug test lint
```

A release build is signed with the upload key when `keystore.properties`
(git-ignored) sets `storeFile`, `storePassword`, `keyAlias` and `keyPassword`,
or when Gradle gets them as `-Preader.<name>`; otherwise it is debug-signed.

## Run

```sh
./gradlew installDebug
adb shell am start -n cc.hafa.reader/.MainActivity
```

Or open this folder in Android Studio and run the `app` configuration.
