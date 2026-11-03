# Öffi

Öffi ("the app for public transport") is an Android app for departure times, trip planning and
network plans in many European transport networks.

This repository is a fork of the [upstream Öffi project](https://gitlab.com/oeffi/oeffi)
(`upstream` remote). The upstream project is licensed under the GPLv3, see [COPYING](oeffi/COPYING).

## Features added in this fork

### 1. Route/stops of a specific vehicle

When viewing the departures of a station (**Stations → station details**), tapping a departure now
shows the stops of that very vehicle – i.e. its route:

* A new `DepartureStopsActivity` displays all stops (departure, intermediate stops and arrival) of
  the tapped train/tram/bus, with planned/predicted times, delays, cancellation marks and platforms.
* The stops are determined by querying a trip from the station to the departure's destination and
  picking the leg that is served by the same line at (almost) the same departure time
  (`DepartureStops.findLeg()`). If the transport network doesn't provide stop information, the app
  falls back to showing the first and the last stop plus a hint.
* Tapping a stop opens the station details of that stop. The destination in the header still opens
  the station details of the destination.

### 2. Faster access to all departures of a station

In the nearby stations list, an expanded station entry now has a second button to the left of the
`…` button that directly opens *Show All Departures* (i.e. the station details), so the context menu
doesn't have to be opened for the most common action.

### 3. Import custom PDF plans ("Fahrpläne")

The plan picker (**Plans**) has a new action bar button to import a PDF plan from the device:

* The PDF is copied into the app's private plans directory and registered in a local index file
  (`plans-custom-index.txt`), so it is listed next to the plans provided by the transport networks,
  ordered first (`CustomPlans`).
* `PlanContentProvider` merges the local plans into its cursor (new `plan_custom` column), so the
  existing plan list, plan viewer and launcher shortcuts work for imported plans, too.
* PDF plans are rendered on a background thread via `PdfRenderer` (`PdfPlans`) and displayed with
  the existing zoomable, tile-based plan viewer. The page raster size adapts to the document size to
  keep memory usage sane; if a document has too many pages, the first pages are shown and a message
  is displayed.
* Imported plans can be removed via the plan's context menu.

## Building

The build requires Gradle 4.4–6.x (e.g. the `gradle` package of Debian), a JDK (17 or 21) and an
Android SDK with platform 36 and build-tools 36.1.0:

```sh
# accept SDK licenses and install required packages
yes | sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.1.0"

# point the build to the SDK
echo "sdk.dir=$HOME/android-sdk" > local.properties

# build (unsigned) APKs
gradle :oeffi:assembleDebug
gradle :oeffi:assembleRelease

# run unit tests
gradle :oeffi:testDebugUnitTest
```

Note that the `public-transport-enabler` submodule is needed, so clone with
`--recurse-submodules` or run `git submodule update --init`.
