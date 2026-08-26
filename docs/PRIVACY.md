# Privacy and Permission Model

## Scope

LINE Radar is designed to observe only messaging UI from the user's own device and own logged-in account session.

It does not require another person's credentials or device access.

## AccessibilityService

Accessibility can expose text and UI structure that is visible to the user's session, including:

- conversation title / display name
- visible read receipt labels
- visible message text or content descriptions
- window / display information needed to identify the secondary display

Radar uses this information locally for:

- read receipt detection
- new message change detection
- target chat identification
- second-display verification
- user notifications

## Local message persistence

Message-body persistence is controlled per monitored target.

Default recommended behavior:

```text
saveMessageContentEnabled = false
```

When OFF:

- notifications may display currently visible text
- history stores only the event type and timestamp

When ON:

- the message preview may be stored in local app preferences/history

## Network

Core monitoring is designed to work without an `INTERNET` permission.

If future versions add any of the following, this document and the in-app disclosure must be updated:

- analytics
- crash reporting
- cloud backup
- account sync
- remote notifications
- remote configuration

## Shizuku

Shizuku is a separate third-party app.

Radar requests Shizuku permission only after an explicit user action to enable background monitoring.

The non-root Shizuku path is intended to use ADB shell identity for:

- launching the user's messaging app onto the secondary display
- limited system-level operations required for the background display workflow

Radar does not use Shizuku to read another app's private `/data/user/0/<package>` files.

## Virtual Display

The Virtual Display is created for the background session.

It is backed by an `ImageReader` surface so Android has a valid surface target. Images are acquired and immediately closed; Radar does not intentionally save the display as video or screenshots.

## Notifications

Radar uses notifications for:

- one required low-priority background session status
- read receipt events
- new message events
- important setup/runtime failures that require user action

Routine health checks should not create repeated notifications.

## No-Read experimental layer

No-Read is an experimental compatibility layer and must remain separate from the core privacy model.

For a future Google Play release, the Play artifact should exclude the Xposed hook and related metadata entirely.

## User controls

Users can:

- stop monitoring a target
- stop all monitoring
- disable background monitoring per target
- revoke Accessibility permission
- revoke Shizuku permission
- stop the secondary display
- clear local event history
- disable message-body persistence
- uninstall the app normally

## Data retention

The current local history is bounded rather than unlimited. Before public release, the UI should document the retention limit and optionally allow a configurable retention period.

## Future platforms

If Instagram, Threads, Messenger or another platform is added, each adapter must document:

- package name
- which visible UI data is read
- which event types are generated
- whether message content can be persisted
- any additional permissions

The global disclosure must be updated whenever the scope of Accessibility data changes.
