# NextBillion Android Offline Navigation Demo

This repository is a customer-facing reference implementation for integrating the NextBillion.ai
Android Navigation SDK with offline maps and offline routing. It uses the published SDK artifact,
not Navigation SDK source modules.

The demo is intentionally structured around production integration concerns:

- process-wide and lifecycle-safe SDK initialization;
- cache-first region catalog loading and explicit synchronization;
- state/county hierarchy with expandable groups;
- independent Route Network and Map Tile download state;
- region, radius, and along-route download discovery;
- storage and mobile-data preflight;
- installed-region coverage on the route map;
- source-aware online/offline route calculation;
- turn-by-turn Navigation UI launch;
- cancellation, process recreation, R8, diagnostics, and structured errors.

The implementation follows `Android_Offline_Navigation_Integration_Guide.docx`. Differences found
while compiling the guide against the published AAR are recorded in
[`Android_Offline_Navigation_Integration_Guide_Findings.md`](Android_Offline_Navigation_Integration_Guide_Findings.md).

## Verified integration matrix

| Item | Verified value |
| --- | --- |
| Navigation SDK | `ai.nextbillion:nb-navigation-android:3.0.0` |
| Minimum Android version | API 23 |
| Compile / target SDK | API 36 |
| Java / Kotlin JVM target | 17 |
| Supported ABIs in this sample | `arm64-v8a`, `x86_64` |
| UI approach | Android Views + View Binding |
| Minification check | release-equivalent `r8Smoke` variant |

The root integration document currently names `4.0.0`, but that coordinate did not resolve from
the repositories available to this project. Use the SDK version provisioned by NextBillion.ai for
your account; `3.0.0` is the version compiled and tested in this repository.

## Demo screens

| Screen | Reference implementation |
| --- | --- |
| Route | [`MainActivity.kt`](app/src/main/java/ai/nextbillion/offlinedemo/MainActivity.kt) |
| Regions and region detail | [`RegionDownloadActivity.kt`](app/src/main/java/ai/nextbillion/offlinedemo/RegionDownloadActivity.kt) |
| Radius / along-route discovery | [`OfflineDiscoveryActivity.kt`](app/src/main/java/ai/nextbillion/offlinedemo/OfflineDiscoveryActivity.kt) |
| Diagnostics | [`DiagnosticsActivity.kt`](app/src/main/java/ai/nextbillion/offlinedemo/DiagnosticsActivity.kt) |

The Regions list groups catalog rows by `adminL1` (state/province) and renders `adminL2` /
`adminL3` rows beneath expandable group headers. The Route screen renders one GeoJSON
FeatureCollection containing the boundaries of all installed route regions.

## 1. Prerequisites and credentials

Install:

- Android Studio with Android SDK Platform 36;
- JDK 17;
- Git and an Android device/emulator using a supported ABI;
- access to the NextBillion.ai private Maven repository;
- a restricted NextBillion.ai client API key.

The SDK artifact in this project is read from Google Artifact Registry. Authenticate with the
method supplied by NextBillion.ai. For local development this is commonly Application Default
Credentials:

```bash
gcloud auth application-default login
```

Copy the example configuration:

```bash
cp local.properties.example local.properties
```

Then edit `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/sdk
NEXTBILLION_ACCESS_KEY=YOUR_RESTRICTED_CLIENT_API_KEY

# Required only for signed Release builds. The path is relative to the repository root.
RELEASE_STORE_FILE=test.jks
RELEASE_STORE_PASSWORD=YOUR_STORE_PASSWORD
RELEASE_KEY_ALIAS=YOUR_KEY_ALIAS
RELEASE_KEY_PASSWORD=YOUR_KEY_PASSWORD
```

The API key is compiled into the APK and is therefore not a server secret. Restrict it to the
expected Android application id/package and signing identities. Never put a privileged server key,
service-account JSON, or repository credential in the application.

The signing values are resolved in this order: Gradle `-P` property, `local.properties`, then
an environment variable with the same name. Both `local.properties` and `*.jks` are ignored by
Git. An explicit `assembleRelease`, `bundleRelease`, or `packageRelease` fails early when
the signing configuration is missing, preventing an accidentally unsigned customer artifact.

## 2. Add the SDK dependency

The root [`build.gradle`](build.gradle) applies the Artifact Registry plugin and limits the private
repository to the `ai.nextbillion` group:

```groovy
plugins {
    id 'com.android.application' version '8.10.1' apply false
    id 'org.jetbrains.kotlin.android' version '2.2.20' apply false
    id 'com.google.cloud.artifactregistry.gradle-plugin' version '2.2.1'
}

allprojects {
    repositories {
        maven {
            url 'artifactregistry://asia-maven.pkg.dev/nextbillion/mobile-snapshot-maven'
            content { includeGroup 'ai.nextbillion' }
        }
        google()
        mavenCentral()
    }
}
```

Apply the plugin and add the SDK in the app module:

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'com.google.cloud.artifactregistry.gradle-plugin'
}

dependencies {
    implementation "ai.nextbillion:nb-navigation-android:3.0.0"
}
```

This demo makes the version replaceable:

```properties
# gradle.properties
navigationSdkVersion=3.0.0
```

```bash
./gradlew :app:assembleDebug -PnavigationSdkVersion=3.0.0
```

Use only the repository URL and authentication method issued for your deployment.

## 3. Configure the Android module

The essential settings from [`app/build.gradle`](app/build.gradle) are:

```groovy
android {
    compileSdk 36

    defaultConfig {
        minSdk 23
        targetSdk 36
        buildConfigField 'String', 'NB_ACCESS_KEY', "\"YOUR_RESTRICTED_CLIENT_API_KEY\""
        ndk { abiFilters 'arm64-v8a', 'x86_64' }
    }

    buildFeatures {
        buildConfig true
        viewBinding true
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
}
```

The demo reads the key from an uncommitted Gradle/local property and escapes it before generating
`BuildConfig.NB_ACCESS_KEY`; inspect [`app/build.gradle`](app/build.gradle) rather than hard-coding
a real key as shown in the shortened example.

Declare network, location, foreground-service, and notification permissions:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Register a custom Application:

```xml
<application
    android:name=".OfflineNavigationApplication"
    ... />
```

See [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) for the complete manifest.

## 4. Initialize Maps first, then Navigation

Initialization is process-scoped. The Maps SDK must be configured before the offline Navigation
facade:

```kotlin
class OfflineNavigationApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Nextbillion.getInstance(
            applicationContext,
            BuildConfig.NB_ACCESS_KEY,
            NGLWellKnownTileServer.NGLTomTom,
        )

        OfflineNavigationManager.initialize()
    }
}
```

The complete implementation is
[`OfflineNavigationApplication.kt`](app/src/main/java/ai/nextbillion/offlinedemo/OfflineNavigationApplication.kt).
`NGLTomTom` must match the dataset configured for the Navigation SDK account.

Do not initialize or shut down `NBNavigation` independently in every Activity.
[`OfflineNavigationManager.kt`](app/src/main/java/ai/nextbillion/offlinedemo/OfflineNavigationManager.kt)
serializes the first initialization request and shares its result:

```kotlin
private var initializationSubscription: OfflineNavigationManager.Subscription? = null

private fun initializeOfflineFeature() {
    initializationSubscription?.cancel()
    initializationSubscription = OfflineNavigationManager.initialize { result ->
        result.onSuccess {
            // SDK APIs are now ready.
        }.onFailure { throwable ->
            // Convert/report the error and allow a later retry.
        }
    }
}

override fun onDestroy() {
    initializationSubscription?.cancel()
    initializationSubscription = null
    super.onDestroy()
}
```

Why this wrapper matters:

- one native initialization runs at a time;
- all UI callbacks are delivered on the main thread;
- a failed attempt returns to a retryable state;
- a destroyed Activity can unsubscribe, avoiding an Activity/view leak;
- successful initialization stays valid for the process lifetime.

## 5. Bind download pages to the visible lifecycle

The SDK exposes a single active download-page owner. Every Activity that controls or observes
downloads follows this pairing:

```kotlin
override fun onStart() {
    super.onStart()
    NBNavigation.beginDownloadPage(this, savedDownloadPageState)
    savedDownloadPageState = null
}

override fun onStop() {
    progressSubscription?.cancel()
    progressSubscription = null
    NBNavigation.endDownloadPage(this)
    super.onStop()
}
```

Use `onStart/onStop`, not `onCreate/onDestroy`, so a covered Activity does not compete with the
currently visible page. The concrete pattern is in
[`RegionDownloadActivity.kt`](app/src/main/java/ai/nextbillion/offlinedemo/RegionDownloadActivity.kt)
and
[`OfflineDiscoveryActivity.kt`](app/src/main/java/ai/nextbillion/offlinedemo/OfflineDiscoveryActivity.kt).

## 6. Load and synchronize the region catalog

Use a cache-first flow:

1. Call `fetchRegionLists(country, callback)` to paint locally cached rows immediately.
2. Call `syncRegions(RegionListSyncMode.AUTO, callback)` for normal refresh.
3. Read the cache again after sync.
4. Reserve `RegionListSyncMode.FORCE` for an explicit user refresh.

```kotlin
NBNavigation.fetchRegionLists(
    "US",
    object : NBNavigationTaskCallback<OfflineRegionListResult> {
        override fun onSuccess(result: OfflineRegionListResult?) {
            val rows = result?.regionRows.orEmpty()
            // Render rows and report routeError/mapError if either side is partial.
        }

        override fun onError(throwable: Throwable) {
            // Cache read failed.
        }
    },
)
```

`fetchRegionLists()` reads the SDK cache; `syncRegions()` is the operation that may use the
network. Route and Map catalogs are independent. A result may contain usable joined rows together
with `routeError` or `mapError`; do not discard all data solely because one catalog was partial.

The demo sorts rows by `adminL1/adminL2/adminL3/displayName`, groups them by state/province, and
uses stable region ids plus `AsyncListDiffer`. Search expands matching groups. Expansion state,
country, selected region, and network policy survive Activity recreation.

## 7. Observe the real download state

Start observing while the page is visible:

```kotlin
progressSubscription = NBNavigation.observeOfflineProgress { snapshots ->
    snapshots.forEach { region ->
        val routeState = region.routeNetwork?.state
        val mapState = region.mapTile?.state
        val percent = region.overallPercent
    }
}
```

Cancel the returned subscription in `onStop()` and again defensively in `onDestroy()`.

Important: the success callback from `downloadRegion()` means the SDK accepted/orchestrated the
download request. It does **not** mean the files are complete. Treat progress snapshots as the
source of truth. A region is fully offline-ready only when both packages are complete:

```kotlin
val ready =
    progress.routeNetwork?.state == OfflineTaskState.COMPLETED &&
        progress.mapTile?.state == OfflineTaskState.COMPLETED
```

The Route screen uses route-package completion to decide when installed routing boundaries may
have changed, because the installed-boundary API describes installed route regions.

## 8. Download, pause, resume, cancel, and delete

The core task methods demonstrated by `RegionDownloadActivity` are:

- `downloadRegion(regionId, callback)`;
- `pauseRegion(regionId, callback)`;
- `resumeRegion(regionId, callback)`;
- `cancelRegion(regionId, callback)`;
- `deleteRegionAllData(regionId, callback)`.

The UI maps the current state to one safe primary action:

| Current state | Primary action |
| --- | --- |
| no task | Download |
| `QUEUED` / `RUNNING` | Pause |
| `PAUSED` / `PARTIAL` / `FAILED` | Resume / retry |
| `COMPLETED` | Open detail |

Cancel and delete are not equivalent. Cancel stops work while retaining already persisted data.
Delete removes both the Route Network and Map Tile packages and is confirmed before execution.

### Download policy

`OfflineDownloadNetworkPolicy.WIFI_ONLY` is the demo default. `ANY` can use metered cellular
data, so the demo confirms it immediately before every download/resume submission:

```kotlin
NBNavigation.setDownloadNetworkPolicy(selectedPolicy)
NBNavigation.downloadRegion(regionId, callback)
```

`RouteMode` and `OfflineDownloadNetworkPolicy` are independent global settings. When changing
only routing strategy, preserve the existing download policy:

```kotlin
NBNavigation.setRuntimeConfig(
    selectedRouteMode,
    NBNavigation.getDownloadNetworkPolicy(),
)
```

### Size and storage preflight

Route and Map size estimates are nullable. If either is unknown, the combined estimate remains
unknown and the demo asks the user to acknowledge that condition. It never substitutes zero.

For a known estimate the sample requires:

```text
required free bytes = estimated remaining bytes + max(100 MiB, 20% of estimate)
```

This reserve is an application policy for temporary/unpacking headroom, not an SDK requirement.
Adapt it for the host application's storage model. Overflow-safe arithmetic lives in
[`DownloadSizeMath.kt`](app/src/main/java/ai/nextbillion/offlinedemo/DownloadSizeMath.kt).

## 9. Draw region boundaries

For the selected region, request its GeoJSON boundary and draw/focus it on the detail map. For the
Route page, load all installed route boundaries in one request:

```kotlin
NBNavigation.getInstalledRoutesRegionBoundaryFeatureCollection(
    object : NBNavigationTaskCallback<String> {
        override fun onSuccess(result: String?) {
            overlayController.drawInstalledBoundaries(result)
        }

        override fun onError(throwable: Throwable) {
            // Keep the route UI usable and report/log the overlay failure.
        }
    },
)
```

[`MapOverlayController.kt`](app/src/main/java/ai/nextbillion/offlinedemo/MapOverlayController.kt)
accepts GeoJSON Feature, FeatureCollection, Polygon, and MultiPolygon shapes and derives camera
bounds by walking their coordinates. It stores only density plus the currently attached map; call
`detach()` from `onDestroy()` so it cannot retain a destroyed map.

The Route page uses a monotonically increasing request generation. If two asynchronous boundary
requests complete out of order, an older result cannot overwrite the newer installed coverage.

## 10. Discover regions by radius or route

Discovery recommendations are joined with the local Route + Map catalogs. Synchronize with
`AUTO` first, but continue with a previously successful cache if synchronization fails.

### Radius

```kotlin
private var queryTask: OfflineCountyQueryTask? = null

queryTask = NBNavigation.getCountiesInRadius(
    latitude,
    longitude,
    radiusMeters,
    callback,
)

// On replacement or Activity destruction:
queryTask?.cancel()
queryTask = null
```

Validate that the radius is finite and non-negative before converting kilometers to meters.

### Along route

Generate a real SDK route, then pass the returned `DirectionsRoute` directly:

```kotlin
NBNavigation.fetchRoute(
    origin,
    destination,
    "en",
    object : NBNavigation.SourceAwareRoutingCallback {
        override fun onRoutingReady(
            routes: MutableList<DirectionsRoute>,
            source: RouteSource,
        ) {
            val route = routes.firstOrNull() ?: return
            queryTask = NBNavigation.getCountiesAlongRoute(route, callback)
        }

        override fun onRoutingError(throwable: Throwable) {
            // Report the routing error.
        }
    },
)
```

The callback uses `MutableList<DirectionsRoute>` because it overrides a Java API. Using Kotlin
`List<DirectionsRoute>` does not compile against the verified AAR.

The demo temporarily selects `ONLINE_ONLY` to obtain the discovery route, preserves the current
download policy, and restores the previous route mode on success, error, or Activity destruction.
Passing the SDK route object preserves its decoded geometry and precision.

Only discovery rows containing both Route and Map packages can be selected for a combined download.

## 11. Keep batch downloads independent from the Activity

A batch can outlive rotation or navigation to another tab. The demo therefore uses
[`OfflineBatchDownloadCoordinator.kt`](app/src/main/java/ai/nextbillion/offlinedemo/OfflineBatchDownloadCoordinator.kt),
a process-owned coordinator that:

- stores only the Application, region ids, primitive counters, and callbacks;
- never stores an Activity, View, or binding;
- persists the remaining queue before calling the SDK;
- submits one region at a time so a large result does not flood SDK queues;
- resumes pending submissions after process recreation when a download page is visible;
- lets screens cancel observer callbacks;
- uses attempt generations to reject duplicate/stale terminal callbacks.

The queue tracks submission acknowledgements, not completed package bytes. Actual completion still
comes from `observeOfflineProgress()`.

In a production application with requirements for scheduled/background retry, move this
orchestration into the application's approved persistent-work architecture while preserving the
SDK's download-page/lifecycle contract.

## 12. Calculate online/offline routes

The Route screen exposes all supported strategies:

| Mode | Meaning |
| --- | --- |
| `RouteMode.SMART` | SDK selects/falls back between available route sources |
| `RouteMode.ONLINE_ONLY` | Require online routing |
| `RouteMode.OFFLINE_ONLY` | Require an installed offline routing region |

Route calculation uses typed coordinates and therefore does not request live-location permission:

```kotlin
NBNavigation.fetchRoute(
    Point.fromLngLat(originLongitude, originLatitude),
    Point.fromLngLat(destinationLongitude, destinationLatitude),
    "en",
    object : NBNavigation.SourceAwareRoutingCallback {
        override fun onRoutingReady(
            routes: MutableList<DirectionsRoute>,
            source: RouteSource,
        ) {
            val route = routes.firstOrNull() ?: return
            val geometry = route.geometry() ?: return
            overlayController.drawRoute(geometry, route.precision())
            // Show source so SMART behavior can be verified.
        }

        override fun onRoutingError(throwable: Throwable) {
            // Convert and display/log the structured error.
        }
    },
)
```

Retain the returned route and alternatives across configuration changes, but never retain the
`MapView`, map object, Activity, or binding in a ViewModel.

## 13. Launch turn-by-turn navigation

Request precise location only when the user starts navigation. On Android 13+ also request
notification permission. The demo then launches:

```kotlin
val config = NavLauncherConfig.builder(selectedRoute)
    .routes(routesFromTheSameResponse)
    .isRoutePreviewModel(false)
    .shouldSimulateRoute(simulationEnabled)
    .showSpeedometer(true)
    .build()

NavigationLauncher.startNavigation(this, config)
```

Pass alternatives from the same route response as the selected route. The Navigation SDK owns its
navigation Activity after launch.

## 14. MapView and callback lifecycle checklist

Forward every `MapView` callback from its Activity:

```text
Activity.onCreate              -> MapView.onCreate
Activity.onStart               -> MapView.onStart
Activity.onResume              -> MapView.onResume
Activity.onPause               -> MapView.onPause
Activity.onStop                -> MapView.onStop
Activity.onSaveInstanceState   -> MapView.onSaveInstanceState
Activity.onLowMemory           -> MapView.onLowMemory
Activity.onDestroy             -> MapView.onDestroy
```

Use this checklist for every SDK integration screen:

- keep View Binding nullable and clear it in `onDestroy()`;
- cancel initialization, progress, query, and coordinator subscriptions;
- cancel a superseded/discarded `OfflineCountyQueryTask`;
- guard delayed callbacks against a destroyed Activity;
- dispatch view changes to the main thread;
- detach map overlay helpers;
- do not put Activity/MapView/View references in process singletons or ViewModels;
- do not call process-wide SDK shutdown during ordinary Activity destruction;
- preserve independent global configuration values when changing one setting.

The demo's ViewModels retain only route/catalog response data needed after rotation. The process
singletons retain only Application state and cancellable callbacks.

## 15. Structured errors and diagnostics

Convert SDK exceptions before presenting or logging them:

```kotlin
val error = NBNavigation.toNavigationError(throwable)
val diagnostic = buildString {
    append("code=").append(error.code)
    append(", cause=").append(error.causeCode)
    append(", recoverable=").append(error.recoverable)
    append(", message=").append(error.message)
}
```

The Diagnostics page shows:

- SDK version;
- engine version;
- current route mode;
- current download network policy;
- `NBNavigation.healthCheck()` result and component details.

Do not expose API keys, access tokens, credentials, or sensitive user coordinates in customer
diagnostics or production logs.

## 16. R8 and native libraries

The SDK AAR supplies consumer R8/JNI rules. This demo intentionally does not add a broad
`-keep ai.nextbillion.**` rule, because that can hide an incomplete consumer-rule configuration
and substantially increase APK size.

Build the release-equivalent, installable smoke variant:

```bash
./gradlew :app:assembleR8Smoke
adb install -r app/build/outputs/apk/r8Smoke/app-r8Smoke.apk
```

Repeat catalog, download, route, and navigation flows and inspect logcat for:

```text
FATAL EXCEPTION
NoSuchMethodError
ClassNotFoundException
UnsatisfiedLinkError
JNI DETECTED ERROR
```

If the host app supports additional ABIs, confirm that the SDK artifact contains matching native
libraries before adding those ABI filters.

## 17. Build and verify

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleR8Smoke
./gradlew :app:assembleRelease
```

Output APKs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/r8Smoke/app-r8Smoke.apk
app/build/outputs/apk/release/app-release.apk
```

The GitHub Actions workflow runs unit tests, Lint, Debug assembly, and the R8 smoke build. In a
clean CI checkout, `r8Smoke` uses the standard Debug signer because the private Release key is not
present. Run `assembleRelease` only in the protected release job/local environment that provides
all four `RELEASE_*` values and the keystore. Also add a least-privilege
`GCP_ARTIFACT_REGISTRY_CREDENTIALS` repository secret with read-only access to the required
Artifact Registry, or replace this with your organization's workload identity setup.

## 18. End-to-end offline acceptance test

1. Launch the app and confirm the map loads and offline initialization succeeds.
2. Open **Regions**, choose a country, and confirm cached rows appear before/while `AUTO` sync
   completes.
3. Expand a state/province and select a county/region containing both Route and Map metadata.
4. Review the package sizes, versions, update state, and boundary on the detail page.
5. Start the download under `WIFI_ONLY` (or explicitly confirm `ANY`).
6. Confirm the submission callback is not treated as completion.
7. Wait until both Route Network and Map Tile states are `COMPLETED`.
8. Return to **Route** and confirm the installed coverage boundary is drawn.
9. Select `Offline`, enter two coordinates inside the installed region, disable connectivity, and
   calculate a route.
10. Confirm the reported source is offline and the returned geometry is drawn.
11. Tap **Start navigation**, grant precise location, and confirm Navigation UI opens.
12. Repeat the critical flow with the `r8Smoke` APK.
13. Rotate the device during sync, download, discovery, and route result screens; verify that there
    are no duplicate submissions, stale UI callbacks, crashes, or retained screens.

## 19. Integration checklist

- [ ] Obtain the private repository URL, credentials, SDK version, and client API key.
- [ ] Add the Artifact Registry plugin/repository and the Navigation dependency.
- [ ] Select only SDK-supported ABIs.
- [ ] Add required permissions and the custom Application.
- [ ] Initialize Maps with the provisioned tile server before Navigation.
- [ ] Initialize `NBNavigation` once per process and expose cancellable readiness callbacks.
- [ ] Pair `beginDownloadPage/endDownloadPage` with the visible lifecycle.
- [ ] Load cached catalog rows before `AUTO` synchronization.
- [ ] Handle partial Route/Map catalog results and nullable metadata.
- [ ] Observe both download subtasks; do not use submission success as completion.
- [ ] Add storage and mobile-data confirmation appropriate for the product.
- [ ] Cancel subscriptions/tasks and clear Activity-owned references.
- [ ] Preserve the download policy when changing route mode.
- [ ] Render installed coverage and verify offline routes inside it.
- [ ] Convert exceptions with `toNavigationError()` and provide diagnostics.
- [ ] Run unit tests, Lint, Debug assembly, and a minified R8 device test.
- [ ] Restrict client keys and keep repository credentials out of the APK/repository.

## Project structure

```text
OfflineNavigationApplication       Maps-first process setup
OfflineNavigationManager           shared, cancellable SDK initialization
OfflineBatchDownloadCoordinator    persisted process-level batch submission queue
RegionDownloadActivity             hierarchical catalog and single-region task controls
RegionAdapter                      state/county expansion, stable ids, progress rendering
OfflineDiscoveryActivity           radius/route discovery and batch preflight
DiscoveryRegionAdapter             eligible-result selection and live progress
MainActivity                       installed coverage, source-aware routing, Navigation launch
MapOverlayController               GeoJSON boundaries, route line, and camera fitting
DiagnosticsActivity                version, runtime configuration, and health checks
DownloadSizeMath                   nullable and overflow-safe storage calculations
```

## License

See [`LICENSE.md`](LICENSE.md) for the NextBillion.ai sample terms and third-party notices.
