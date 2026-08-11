# Play store assets

What Play Console needs under **Main store listing → Graphics**, and where it
comes from. Texts (app name, short and full description, release notes) live in
`docs/STORE_LISTING.md`.

| Asset | File | Spec | State |
|---|---|---|---|
| App icon | `icon-512.png` | 512×512 PNG, <1 MB | ✅ 512×512, RGB, 19 KB |
| Feature graphic | `feature-graphic-1024x500.png` | 1024×500 PNG/JPG, no alpha | ✅ 1024×500, RGB, 167 KB |
| Phone screenshots | — | 2–8 images, each side 320–3840 px | ❌ needs a device |
| Tablet screenshots | — | same, on the 7"/10" slots | ❌ needs a device |

## Regenerating the icon and feature graphic

Both are rendered from the `*.src.html` files next to them, so they stay
reproducible and stay in step with the launcher icon. The icon SVG is the same
geometry as `app/src/main/res/drawable/ic_launcher_foreground.xml` on the same
`@color/ic_launcher_background` (`#FF0F1417`), with one deliberate difference:
the launcher's `scale 0.55` group is dropped, because the launcher crops to the
inner 72 dp safe zone and a store icon does not — at 0.55 the glyph would read as
a small mark floating in a dark square. Native scale fills ~67 % of the tile,
which is where store icons sit.

```bash
google-chrome --headless=new --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=1 \
  --screenshot=icon-512.png --window-size=512,512 \
  "file://$PWD/icon.src.html"
```

Same command for the feature graphic with `--window-size=1024,500`. Fonts: the
feature graphic uses **Lato** (installed system-wide here); substituting another
font changes the wordmark, so check the render if you change machines.

If a promo video is ever attached to the listing, Play overlays a play button
over the middle of the feature graphic, which lands on the wordmark. Either skip
the promo video or re-cut the graphic with the text off-centre.

## Where the captured screenshots live

**Not in this repo** — `~/wakeproof-store-assets/screenshots/`, with a contact
sheet one level up. They are real captures from the S25 Edge, so they contain
real app-usage data (which apps, how many minutes) and real alarm labels. That
is fine on a Play listing you chose to publish, but this repo is public, so the
frames stay out of git until you have looked at each one.

## Screenshots — capture recipe

Needs a connected device (`adb devices` must list one). Both dev devices are on
Android 16: S25 Edge for phone, Galaxy Tab S9 FE+ for tablet.

```bash
adb -s <serial> exec-out screencap -p > shot-01-home.png
```

Capture with **real data on screen** — a live streak, real focus minutes, a real
exam countdown. Screenshots of an empty app are the single most common reason a
good listing looks abandoned.

The eight phone screens, in the order they should appear on the listing (Play
shows them in upload order, and the first two carry the install decision):

1. Alarm ringing — the full-screen alarm with the verify prompt
2. Pushup verification — camera with the skeleton overlay and the rep count
3. Pomodoro — running timer with subject chips and the sound picker
4. Water verification — before/after capture screen
5. Phone usage — screen-time chart plus the Daily limits section
6. Flashcards — a card mid-review with Again/Hard/Good/Easy
7. Home dashboard — streak, today's focus vs goal, exam countdown
8. Settings — sync across devices, or the Agenda screen

Lead with the alarm and the pushup camera, not the dashboard. Those two are the
only things in this app no other alarm clock does, and neither depends on
accumulated history — whereas Home is exactly the screen that looks empty on a
device without a streak yet.

Tablet: shoot it in **portrait**. The layout is phone-width with no two-pane
adaptation, so in landscape the cards stretch the full 2560 px and the screen
reads mostly empty. Portrait is close to the phone composition and looks
deliberate. `adb shell settings put system accelerometer_rotation 0` then
`user_rotation 0` forces it; put `accelerometer_rotation` back to 1 afterwards.

For the tablet slots, four are enough: Home, Pomodoro, Phone usage, Flashcards.
Take them in landscape if the layout adapts — Play's large-screen surfaces
favour landscape.

Two cautions before uploading: frame 3 is a live camera feed, so shoot it with
something you don't mind publishing worldwide, and check every frame for a
notification shade, a real contact name, or an email address in the status bar.
