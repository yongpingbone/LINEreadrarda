# Google Play Readiness

LINE Radar v0.6 is still an experimental sideload build. This document lists the work required before a Google Play submission.

## 1. AccessibilityService policy

LINE Radar is **not** an accessibility tool for people with disabilities and must not declare `isAccessibilityTool=true`.

Because the app uses `AccessibilityService` for app functionality, a Play build must:

- complete the Accessibility API declaration in Play Console
- provide an in-app prominent disclosure before directing users to Android accessibility settings
- explain exactly what data can be accessed through Accessibility
- explain how that data is used and whether it is shared
- require affirmative user consent
- include the Accessibility API use in the Play Store listing
- provide the review video requested by Play Console

The disclosure must not exist only in README, privacy policy or service description.

Current v0.6 onboarding already includes a separate in-app disclosure dialog, but final wording and screenshots must be reviewed before submission.

Official policy reference:

- https://support.google.com/googleplay/android-developer/answer/10964491

## 2. No-Read / Xposed must not ship in Play flavor

The current experimental repository contains a No-Read Xposed hook.

A Play release should exclude all of the following from the final App Bundle:

- `NoReadXposedHook`
- `de.robv.android.xposed:api`
- `assets/xposed_init`
- `xposedmodule` metadata
- `xposeddescription` metadata
- `xposedminversion` metadata
- `xposedscope` metadata

Recommended Gradle flavors:

```text
play
sideloadExperimental
```

The Play flavor should contain only documented Android / Shizuku / Accessibility functionality.

## 3. Shizuku onboarding

Shizuku is available on Google Play and GitHub.

For a Google Play release, recommended onboarding order:

1. offer the official Google Play Shizuku listing first
2. optionally provide GitHub Releases as an explicit alternative chosen by the user
3. never silently download or install another APK
4. clearly explain that Shizuku is a separate third-party open-source app

Shizuku package:

```text
moe.shizuku.privileged.api
```

Official links:

- Google Play: https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api
- GitHub: https://github.com/RikkaApps/Shizuku/releases
- Guide: https://shizuku.rikka.app/guide/setup.html

## 4. Executable code policy

A Play-distributed Radar build must not download dex/JAR/.so executable code outside Google Play for itself.

Do not implement self-update APK download or dynamic external code loading in the Play flavor.

Official policy reference:

- https://support.google.com/googleplay/android-developer/answer/16559646

## 5. Privacy Policy and Data Safety

Before submission, publish a public HTTPS Privacy Policy and link it in:

- Play Console
- App settings/about screen

Data Safety must accurately describe:

- Accessibility access to visible chat UI
- local message event processing
- optional local message-content persistence
- notification data
- whether analytics/crash reporting is later added

Do not claim “no data collected” if a future analytics SDK, crash reporter, cloud sync or account feature is added.

## 6. Foreground Service policy

The background session uses a foreground service.

Before Play submission:

- confirm the chosen foreground service type is valid for the public feature
- provide an accurate user-visible notification
- ensure the service begins only after an explicit user action
- stop it immediately when no background targets remain
- avoid duplicate status notifications

## 7. User control

The user must always be able to:

- disable an individual target
- disable background monitoring
- disable Accessibility in Android settings
- revoke Shizuku permission
- stop the secondary display
- clear local history
- disable message-content persistence
- uninstall Radar normally

## 8. Store description language

Avoid claims such as:

- “exact read time”
- “100% invisible”
- “undetectable monitoring”
- “bypasses Android security”

Use precise wording:

- “first observed read time”
- “estimated read interval”
- “monitors UI visible to your own logged-in session”
- “background secondary-display mode requires Shizuku and device compatibility”

## 9. Third-party trademarks

The app is not affiliated with LINE, Meta, Instagram, Threads or Messenger.

Before expanding to other platforms, add a clear trademark / affiliation disclaimer to the store listing and About page.

## 10. Release checklist

Before the first Play upload:

- [ ] create `play` Gradle flavor
- [ ] remove all Xposed code from Play artifact
- [ ] verify manifest of Play artifact
- [ ] Accessibility prominent disclosure reviewed
- [ ] Play Accessibility declaration prepared
- [ ] review video recorded
- [ ] Privacy Policy published
- [ ] Data Safety completed
- [ ] third-party notices added
- [ ] foreground service declaration reviewed
- [ ] no self-update / external executable code loading
- [ ] screenshots show normal user flows
- [ ] app behavior tested with Shizuku from Google Play
- [ ] reboot / Shizuku stopped state tested
- [ ] screen-off behavior tested on supported device matrix

## 11. Policy note

Google Play policy changes over time. Re-check the current Play Console policy immediately before submission rather than treating this file as permanent legal guidance.
