# Plain

A minimalist Android launcher: everything is text, in black and white or amber,
**and it hosts real app widgets** — including the Google Calendar month view and
Google Tasks.

That last part is the whole reason this exists. Text-only launchers usually drop
widget support along with the icons, which means giving up a full-screen calendar.
Plain keeps the typography and puts the widgets on their own swipeable pages.

## What it does

**Home** — a clock, a date, and a short list of favourites as plain text.
Nothing else.

- swipe **up** → app drawer
- swipe **left / right** → widget pages
- **long-press** → settings
- **double-tap** → lock the screen
- swipe **down** → notification shade

**App drawer** — an alphabetical text list with search at the bottom, where your
thumb is. Search matches a prefix, the start of any word, or initials, so `gc`
finds Google Calendar. Optionally launches as soon as one result remains.

**Widget pages** — as many as you like, each a vertical stack of widgets. Any
widget can be set to **fill the page**, which is what makes a month calendar
usable. Long-press a widget to resize or remove it. The widget picker is itself
a text list — app name, widget name, requested size — rather than a wall of
preview images.

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
agenda row.

One thing worth knowing: widgets draw themselves. They follow the system light
or dark theme, not Plain's palette, so a widget on an amber page will still look
like a Google widget. That is a limit of `RemoteViews`, not a bug.

## Status

Written but **not yet compiled or run on a device** — it was developed in an
environment without access to the Android SDK. Expect to fix a few things on the
first build.
