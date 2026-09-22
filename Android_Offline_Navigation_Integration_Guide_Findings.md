# Android Offline Navigation Integration Guide Findings

These findings were reproduced while integrating the root document into this demo on 2026-09-21.

## Confirmed issues

1. **The declared Android SDK version cannot currently be resolved.** The guide identifies
   `ai.nextbillion:nb-navigation-android:4.0.0` as the applicable dependency. Gradle searched the
   configured NextBillion Artifact Registry, Google Maven, and Maven Central and returned
   `Could not find ai.nextbillion:nb-navigation-android:4.0.0`. This demo therefore remains pinned
   to the verified `3.0.0` artifact until a resolvable official version is supplied.

2. **The copy-ready repository snippets are insufficient for the dependency that is actually
   available to this project.** Sections 2.1 and 2.2 show only `google()` and `mavenCentral()`.
   The verified artifact is served from the authenticated NextBillion Artifact Registry used by
   the root build. The document mentions that a private repository may be needed, but a user
   cannot compile the advertised copy-ready example without the concrete repository and
   authentication setup provided for the deployment.

3. **The Kotlin callback signatures in Sections 10.3 and 14.3 do not compile against the verified
   Android AAR.** `NBNavigation.SourceAwareRoutingCallback` is a Java API whose Kotlin override is
   `onRoutingReady(routes: MutableList<DirectionsRoute>, source: RouteSource)`. The guide uses
   `List<DirectionsRoute>`. The same mutability correction is required for the plain
   `NextbillionRoutingCallback` example in Section 10.2.

4. **The complete example contains hard line breaks inside quoted Kotlin strings.** In Section
   14.3, the `append("Country: $COUNTRY ...")`, warning lines, and
   `statusView.text = "$diagnostic ... $nextStep"` examples contain Word line-break characters
   inside normal quoted strings. Copying the code produces unterminated string literals. These
   lines should use escaped `\n`, `appendLine`, or triple-quoted strings.

## Verified document behavior

The following guidance matched the available AAR and was exercised in the demo: Maps-first
initialization with `NGLTomTom`, process-wide `NBNavigation.initialize`, cache-first catalog reads,
`AUTO` and `FORCE` synchronization, partial Route/Map results, radius and along-route discovery,
combined download progress, task control, boundary display, source-aware routing, structured
errors, `arm64-v8a`/`x86_64`, and consumer R8 rules.
