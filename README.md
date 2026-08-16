# Plain

A minimalist Android launcher: everything is text, in black and white or amber,
**and it hosts real app widgets** — including the Google Calendar month view and
Google Tasks.

That last part is the whole reason this exists. Text-only launchers usually drop
widget support along with the icons, which means giving up a month calendar.
Plain keeps the typography and puts the widgets on the home screen itself.

## What it does

**Home** — one scrolling screen: the clock, then your widgets in order, then
your apps. A month calendar with a task list under it and a handful of apps
below that is the layout it was built around. No widget pages, nothing to swipe
sideways to.

- swipe **up** (or past the bottom of the list) → app drawer
- **long-press** → settings
- **double-tap** → lock the screen
- swipe **down** at the top → notification shade

Because home scrolls, the swipe gestures fire when a drag runs past either end
of it — so they work the same whether or not your widgets make the page taller
than the screen.

**App drawer** — A→Z from the top, with an **alphabet rail** down the right
edge: tap a letter or slide your finger along it to jump, one haptic tick per
letter. The **search bar sits at the bottom**, in thumb reach. Search matches a
prefix, the start of any word, or initials, so `gc` finds Google Calendar.
Optionally launches as soon as one result remains.

**Widgets** — added from a text-only picker (app name, widget name, requested
size) rather than a wall of preview images. Long-press a widget on the home
screen to change its height, move it up or down the stack, or remove it.

**Palettes**

| | background | text |
|---|---|---|
| `black on white` | white | black |
| `white on black` | black | white |
| `amber on black` | black | amber `#FFB000` |
| `warm on paper` | warm off-white | dark warm ink |

Amber-on-black has no blue channel at all, which is the point of it at night;
`warm on paper` is the daylight equivalent. Also configurable: typeface
(sans/serif/mono), text size, lowercase-everything, clock size and format,
battery readout, and whether the wallpaper shows through.

**Apps** — rename anything, hide anything, favourite up to 12.

## Building

```sh
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run it. Then set Plain as the default
launcher from *settings → gestures → set as default launcher*.

The lock-screen and notification-shade gestures need the optional accessibility
service switched on in Android's settings; everything else works without it.
The service is configured with `canRetrieveWindowContent="false"` — it can only
push two global actions out, it cannot read the screen.

- minSdk 26 (Android 8.0), targetSdk 35
- Kotlin 2.0.21, Compose (foundation only — no Material, so nothing imposes its
  own colours on the text)
- No analytics, no network permission, no `QUERY_ALL_PACKAGES`

## How the widget hosting works

The interesting file is `widget/WidgetInstaller.kt`. A normal app cannot hold
`BIND_APPWIDGET`, so adding a widget is a three-step dance:

1. allocate an id from our `AppWidgetHost`;
2. try `bindAppWidgetIdIfAllowed`, and when that is refused, ask the system to
   get the user's consent via `ACTION_APPWIDGET_BIND`;
3. run the provider's configure activity if it declares one — through
   `startAppWidgetConfigureActivityForResult`, since that activity is often not
   exported.

Any step can be cancelled, and the allocated id is released if it is.

`PlainWidgetHostView` reports its real size to the provider
(`updateAppWidgetSize`). Without that, widgets lay out at their declared
minimum — which is why a month calendar can otherwise come up showing a single
agenda row. Give it 360 dp of height and it draws a month.

One thing worth knowing: widgets draw themselves. They follow the system light
or dark theme, not Plain's palette, so a widget on an amber page will still look
like a Google widget. That is a limit of `RemoteViews`, not a bug.

## Status

Written but **not yet compiled or run on a device** — it was developed in an
environment without access to the Android SDK. Expect to fix a few things on the
first build.
