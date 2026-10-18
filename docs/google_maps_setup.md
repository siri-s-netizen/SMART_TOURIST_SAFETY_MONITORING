# Turning on the real Google Map

The app ships with **two** map surfaces and picks one automatically:

| Condition | Map used |
| --- | --- |
| A Google Maps API key is configured **and** Google Play services are present | **Google Maps** (the "real" map) |
| No key configured (the default), or Play services missing | Built-in key-less map — **CARTO Voyager** by default, styled to look like the familiar Google/Apple street map |

Both paths draw the **same** zones as polygons (green / yellow / red), the live position, the
breadcrumb trail, the draft polygon, and the **distance-to-boundary pill**. The only difference is *who
supplies the tiles*.

The key-less path is not one server: it tries **three** independent key-free providers in order —
OpenStreetMap (default), Esri World Street Map, OpenTopoMap — and falls over to the next when one fails,
remembering the last one that worked. OpenStreetMap is the canonical key-free source. (CARTO Voyager was
tried and **removed**: it now serves a watermark instead of a map, so it is not offered.) The top-left
provider caption (`OpenStreetMap ▸`) opens **map diagnostics**, including **Check map servers**, which
probes each provider and reports `ok (host)` or the HTTP status.

The code for the Google path is already in the app (`ui/components/SafetyMap.kt` →
`GoogleSafetyMap`). To switch it on you only need an API key — no code changes.

---

## 1. Get a free key (about 5 minutes)

1. Open <https://console.cloud.google.com/> and sign in with any Google account.
2. Create a project (top bar → project picker → **New project**). Name it e.g. `TouristSafety`.
3. In the search bar type **Maps SDK for Android** and open it, then press **Enable**.
4. Go to **APIs & Services → Credentials → Create credentials → API key**.
5. Copy the key. (Optional but recommended: **Edit** the key → *Application restrictions* →
   *Android apps* → add your app's package name `com.touristsafety.app` and your signing SHA-1 so
   nobody else can use your key.)

> **Billing:** Google requires a billing account to be attached, but the Maps SDK for Android has a
> large free monthly credit, so a personal project normally costs nothing. Google shows the current
> numbers on the **Maps SDK for Android → Pricing** page.

---

## 2. Give the key to the app

The key is read at build time from **exactly one** of these places (never hard-coded in source,
never committed):

**Option A — `local.properties` (recommended).** Add one line to
`D:\TouristSafetyMonitoring-src\local.properties`:

```properties
MAPS_API_KEY=AIza...your-key-here
```

**Option B — a Gradle property on the command line:**

```powershell
.\gradlew.bat :app:assembleDebug -PMAPS_API_KEY=AIza...your-key-here
```

Both are wired in `app/build.gradle.kts` (`manifestPlaceholders["MAPS_API_KEY"]` →
`AndroidManifest.xml` → `com.google.android.geo.API_KEY`).

---

## 3. Rebuild and install

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\build.ps1" ":app:assembleDebug" "--console=plain"
```

The new APK is at `app\build\outputs\apk\debug\app-debug.apk`. Install it and open
**Create Geo-Fence** — you should now see Google's own tiles, centred on your position, with the
Google "my location" button.

If you prefer, paste the key to the assistant and the APK will be rebuilt with it already in place
(the key is only written to the git-ignored `local.properties`).

---

## 4. If Google Maps still shows grey tiles

- **"Google Play services are not available"** → the phone/tablet has no Play services (some
  emulators and non-Play devices). The app automatically falls back to the key-less map.
- **Grey grid with a Google logo** → the key was rejected. Check that **Maps SDK for Android** is
  enabled on the key's project and that any *Android app restriction* matches the installed build's
  package name **and** signing certificate.
- **No internet** → neither map can download tiles. The app keeps every geo-fencing feature working
  and shows the queued/alerts state; only the basemap is blank.

---

## 5. Why the key-less map used to look blank

The key-less map is drawn by this project's own code (`ui/components/OfflineOsmMap.kt`). It used to
open at latitude/longitude `(0, 0)` — a point called **Null Island** in the middle of the Atlantic
Ocean — because the Create screen did not tell it where the user was. Open-ocean tiles are a flat
light-blue slab, which looked like "a blank map".

The fix prefers, in order: the **live GPS fix** → the newest **draft polygon vertex** → a saved
**zone** → the **phone's last-known location**. Only if all four are missing does it show a
"Finding your location…" card. So the map now opens on your own area in both the Google and the
OpenStreetMap paths.
