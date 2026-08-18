# Building Plain

## What you need

- **Android Studio Ladybug (2024.2) or newer.** The project uses AGP 8.7.2 and
  `compileSdk = 35`; older Studio versions refuse to open it. If you are on
  Koala or earlier, either update Studio or drop `agp` to `8.5.2` and
  `compileSdk`/`targetSdk` to `34` in `gradle/libs.versions.toml` and
  `app/build.gradle.kts`.
- **JDK 17+** — Android Studio bundles a suitable JBR, so this is normally
  already handled.
- The SDK components Studio will offer to install on first sync: Android 35
  platform, build-tools 35, platform-tools.

## Build it

```sh
git clone -b claude/minimalist-launcher-widgets-duuwiu https://github.com/bippityboppity1/marmic
cd marmic
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

Or just open the folder in Android Studio, let Gradle sync, plug in your phone
and hit Run.

The debug build installs as `com.marmic.plain.debug`, so it will not collide
with a release build later.

## First run

1. Launch it once from the app list, or press Home.
2. **Set it as default:** long-press the home screen → *gestures* → *set as
   default launcher*. On Android 10+ this opens the system role prompt.
3. **Optional gestures:** *gestures* → *gesture permission* opens accessibility
   settings, where you can switch Plain on. This is only needed for double-tap
   to lock and swipe-down for notifications; everything else works without it.
4. **Add your widgets:** long-press home → *widgets* → *add widget*, then pick
   Calendar → Month, and Tasks. They stack in the order you add them; long-press
   a widget on the home screen to change its height or move it up and down.
5. **Add your apps:** swipe up for the drawer, long-press an app → *add to home*.

## Getting back out

Plain does not replace or remove your existing launcher — it only becomes the
default. If anything goes wrong:

- **Settings → Apps → Default apps → Home app** switches back. This is reachable
  from the notification shade even if Plain is misbehaving.
- If Plain crashes at startup, Android falls back to the launcher chooser on the
  next Home press.
- `adb uninstall com.marmic.plain.debug` removes it entirely.

## If the build fails

This code has never been compiled — the environment it was written in had no
access to the Android SDK. Expect some errors on the first build; they are far
more likely to be Compose API signature drift than anything structural.

The useful thing to send back is the Gradle output, specifically the lines
starting with `e: file://`. Those name the file, line and problem. The whole
block from the first `e:` to the end of the task is ideal — the errors often
cascade from one root cause, so the first few matter most.

## Known risks to check on device

Three things could not be verified without hardware:

1. **Calendar widget height.** New widgets are sized from the provider's
   `minHeight`, with a floor for anything vertically resizable. If the month
   view still draws as an agenda list, long-press it and step the height up.
2. **Widget long-press.** The detector runs in `dispatchTouchEvent` so it sees
   the whole gesture without consuming anything. If long-pressing a widget does
   nothing, or fires when you meant to tap, that is where to look.
3. **Swipe up for the drawer.** Home scrolls now, so the gesture fires on a drag
   past the bottom of the scroll. If it feels awkward, the threshold is
   `64.dp` in `ui/HomePage.kt`.
