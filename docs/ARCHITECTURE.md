# LINE Radar Architecture

## Goals

1. Monitor only the user's own logged-in messaging UI.
2. Keep platform-specific parsing isolated from Radar core.
3. Support background monitoring without forcing the main screen to stay on the chat app.
4. Keep all core detection local on device.
5. Make future adapters possible for Instagram, Threads and Messenger without rewriting storage, notifications or onboarding.

## Current v0.6 layers

```text
┌─────────────────────────────┐
│ UI / Onboarding             │
│ MainActivity                │
│ ExperimentalLabActivity     │
└──────────────┬──────────────┘
               │
┌──────────────▼──────────────┐
│ Core state                  │
│ Prefs                       │
│ local history / per target  │
└──────────────┬──────────────┘
               │
┌──────────────▼──────────────┐
│ Background session          │
│ ProjectionForegroundService│
│ (name is legacy; v0.6 uses │
│  Shizuku, not projection)   │
└──────────────┬──────────────┘
               │
      ┌────────┴────────┐
      │                 │
┌─────▼──────┐   ┌──────▼─────────┐
│ Shizuku    │   │ VirtualDisplay │
│ Bridge     │   │ Engine         │
└─────┬──────┘   └──────┬─────────┘
      │                 │
      └────────┬────────┘
               │
┌──────────────▼──────────────┐
│ Platform UI observation     │
│ LineReadAccessibilityService│
└──────────────┬──────────────┘
               │
┌──────────────▼──────────────┐
│ Detection / notification    │
│ ReadDetector                │
│ local event history         │
└─────────────────────────────┘
```

## Background session state machine

The UI must not treat a switch as success.

```text
OFF
 ↓ user enables background monitoring
CHECK_ACCESSIBILITY
 ↓
CHECK_SHIZUKU_INSTALLED
 ↓
CHECK_SHIZUKU_SERVICE
 ↓
REQUEST_SHIZUKU_PERMISSION
 ↓
CREATE_SECOND_DISPLAY
 ↓
LAUNCH_PLATFORM_APP_ON_DISPLAY
 ↓
VERIFY_PLATFORM_WINDOW_ON_DISPLAY
 ↓
ACTIVE
```

Failure at any step produces a specific user-facing next action.

`ACTIVE` requires Accessibility to have seen the target platform package on the exact secondary display ID recently.

## Why Shizuku

The previous MediaProjection design tied Virtual Display lifetime to a screen-capture session. Android may stop that session when the screen locks.

v0.6 uses non-root Shizuku shell identity for privileged activity launch while the app owns the non-mirroring Virtual Display.

Expected identity for non-root Shizuku is shell UID 2000.

## Virtual Display requirements

Current display flags:

```text
PUBLIC
OWN_CONTENT_ONLY
PRESENTATION
```

Rationale:

- `PUBLIC`: allows another app to be launched onto the display when system policy permits.
- `OWN_CONTENT_ONLY`: prevents default mirroring behavior and removes the need for MediaProjection capture semantics.
- `PRESENTATION`: marks the display as suitable for secondary content.

The display is backed by an `ImageReader` surface. Frames are acquired and immediately closed; Radar does not save a screen recording.

## Accessibility observer

`LineReadAccessibilityService`:

- filters to the platform package
- retrieves interactive windows across displays on Android 11+
- verifies the secondary display by display ID
- scans visible chat title and read receipt nodes
- maintains a watchdog scan in addition to accessibility events
- records first-observed timestamps rather than claiming server timestamps

## Current LINE-specific parser

LINE adapter logic is still inside `LineReadAccessibilityService` in v0.6. Before adding a second platform it should be extracted into an adapter.

Proposed interface:

```java
interface PlatformAdapter {
    String id();
    String packageName();
    boolean isChatWindow(AccessibilityNodeInfo root, Target target);
    PlatformSnapshot snapshot(AccessibilityNodeInfo root, Target target);
    boolean openTargetChat(AccessibilityNodeInfo root, Target target);
    Set<String> readReceiptLabels();
}
```

`PlatformSnapshot` should contain platform-neutral values:

```text
chatVisible
readReceiptCount
readReceiptAnchor
incomingSignature
incomingPreview
platformMetadata
```

## Future platform layout

```text
core/
  model/
  storage/
  notification/
  background/
  onboarding/

platform/
  PlatformAdapter.java
  line/
    LineAdapter.java
  instagram/
    InstagramAdapter.java
  threads/
    ThreadsAdapter.java
  messenger/
    MessengerAdapter.java

privilege/
  ShizukuBridge.java

display/
  VirtualDisplayEngine.java

accessibility/
  RadarAccessibilityService.java
```

## Future target model

The current slot stores only a display name. Multi-platform support should migrate to:

```text
Target
- id
- platformId
- displayName
- platformConversationKey?  // only if discoverable from UI, no credentials
- enabled
- readEnabled
- messageEnabled
- notifyEnabled
- vibrateEnabled
- saveMessageContentEnabled
- backgroundEnabled
- baseline
```

This prevents LINE-specific settings from leaking into Instagram or Messenger logic.

## Privacy design

Core rules:

- No credentials are collected.
- No private app data directories are read.
- No INTERNET permission is required for the core monitor.
- Message content persistence is opt-in per target.
- All monitoring is for UI visible to the user's own session.
- Accessibility use must be disclosed before permission setup.

## Notification design

There are two categories only:

1. one low-priority ongoing background status notification required by Android
2. high-priority event notifications for read / new message

Do not create multiple redundant status notifications for the same session.

## No-Read isolation

No-Read is not part of the neutral Radar core.

It should be treated as an optional experimental compatibility layer:

```text
experimental/
  noread/
```

For a future Google Play flavor, the Xposed API dependency, metadata, assets entry point and hook implementation should be excluded entirely.

## Release flavors recommended before public distribution

```text
sideloadExperimental
- Shizuku
- Virtual Display
- Accessibility
- optional No-Read experimental hook

play
- Shizuku
- Virtual Display
- Accessibility with disclosure/consent
- NO Xposed metadata
- NO No-Read hook
```

## Acceptance tests

A background session is accepted only if all are true:

1. Main screen can run unrelated apps.
2. Radar activity can be backgrounded.
3. Secondary display persists.
4. Platform app remains on the secondary display.
5. Accessibility sees platform UI on that display.
6. Read receipt change produces one event.
7. New message change produces one event.
8. Re-entering the same chat does not create a false new-message event.
9. Turning screen off does not destroy the secondary display on the tested device.
10. After reboot, Radar clearly reports Shizuku not running instead of showing a false active state.
