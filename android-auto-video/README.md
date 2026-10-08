# Parked Video: web video on Android Auto (parked only)

A sideloaded Android Auto app that shows a web browser on the car screen, so you
can watch YouTube, Vimeo, Twitch and similar sites **while the car is parked**.

> ⚠️ Video is covered and paused whenever the car is moving or its speed can't
> be read. Don't remove that check: watching video while driving is dangerous
> and illegal almost everywhere.

## What works

| Works | Doesn't work |
|---|---|
| YouTube, Vimeo, Twitch, Dailymotion, TED, most non-DRM web video | Netflix, Disney+, Prime Video, Max and other DRM-protected services. Their copy protection blocks playback on a secondary/projected display. |

## How it works

Android Auto doesn't let ordinary apps draw their own screens. Navigation apps
are the exception: they get a raw surface to draw their map on. This app
registers as a navigation app and puts a `VirtualDisplay` on that surface. It
then shows a `Presentation` window with a `WebView` on that display, and passes
the car's taps, scrolls and pinch-zoom gestures through to the web page.

| File | Role |
|---|---|
| `car/SurfaceRenderer.kt` | Virtual display, WebView, gesture forwarding, blocking overlay |
| `car/ParkingGuard.kt` | Parked / moving / unknown, from car speed (preferred) or phone GPS |
| `car/BrowserScreen.kt` | Main car screen: Sites, Search, Back, Play/Pause, Pan, Scroll ↑/↓ |
| `car/SitesScreen.kt`, `car/AddressScreen.kt` | Site list and keyboard entry (an address, or a YouTube search) |
| `MainActivity.kt` | Phone screen: grant permissions, setup steps |

## Build and install

1. Install [Android Studio](https://developer.android.com/studio) and open this
   `android-auto-video` folder. Let Gradle sync.
2. On your phone, enable **Developer options** and **USB debugging**, then
   plug it in.
3. Press **Run** (or run `./gradlew installDebug`).
4. Open **Parked Video** on the phone and tap **Grant speed & location permissions**.

## Enable it in Android Auto

1. Open Android Auto settings on the phone (*Settings → Connected devices →
   Connection preferences → Android Auto*).
2. Scroll down and tap **Version** about 10 times, then confirm to enable
   developer mode.
3. ⋮ menu → **Developer settings** → turn on **Unknown sources**.
4. Connect to the car. **Parked Video** appears in the Android Auto launcher.
   If it doesn't, use *Customize launcher* in Android Auto settings.

### Testing without a car

Use Google's [Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu).
The DHU doesn't report vehicle speed, so the app relies on the phone's GPS
there. Indoors with no GPS fix it stays blocked with "Checking that the car is
parked…". That's expected.

## Controls on the car screen

- **Tap**: tap on the web page. Needs Car API level 5 on the head unit, which
  current Android Auto versions have.
- **Pan button, then drag**: scroll the page. **↑ / ↓**: scroll by a screen.
- **Pinch**: zoom.
- **Sites**: YouTube, Vimeo, Twitch, Dailymotion, TED, or type an address.
- **Search**: type a web address, or anything else to search YouTube.
- **Back**: browser back, or leave fullscreen video.
- **▶❚❚**: play/pause the first video on the page.

## Caveats

- This relies on a workaround, not an official Android Auto feature. A future
  Android Auto update could break it.
- It can't go on the Play Store. Install it yourself as above.
- Some car head units and Android versions may refuse to show a presentation on
  the projected surface. If you get a blank screen, check `adb logcat -s
  SurfaceRenderer ParkingGuard`.
- Sites can be signed in to through the web page. Cookies stay on the phone.
