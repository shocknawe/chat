# connection-state Specification (Delta)

## Purpose

Defines how the frontend surfaces realtime connection state: an always-visible indicator,
an offline path with immediate-reconnect control, recovery confirmation, waiting-message
wording, and correlated-error wording.

This capability owns the *surfaced form* of connection state. It tightens
`realtime-messaging`'s "Frontend reconnects and signals unavailability" — which is satisfied
today by rendering nothing while connected — and restates its idempotent-retry requirement
from the user-visible side. Where the two overlap, this capability is the stricter one.

## ADDED Requirements

### Requirement: Connection state is always visible in words
The system SHALL display the current realtime connection state at all times while a user is signed in, including while connected, in words and never by colour alone.

#### Scenario: Connected state is stated
- **WHEN** a user is signed in and the connection is open
- **THEN** the connection state is visible in words plus an indicator, never colour alone

### Requirement: Offline state is stated inside the conversation
WHILE realtime messaging is unavailable, the system SHALL state inside the conversation that messaging is unavailable and reconnection is being attempted, keep the composer usable with waiting copy, and mark each waiting message as waiting for connection rather than sending.

#### Scenario: Offline banner and waiting copy
- **WHEN** realtime messaging is unavailable
- **THEN** the conversation states that messaging is unavailable and reconnection is being attempted, the composer remains usable with waiting copy, and each queued message is marked as waiting for connection

### Requirement: Immediate reconnect is offered
WHILE realtime messaging is unavailable, the system SHALL offer a control that cancels the scheduled delay and reconnects at once; activated while connected, after an intentional close, or while a connection attempt is already in flight, it SHALL do nothing.

#### Scenario: Reconnect now cancels the backoff
- **WHEN** the user activates the reconnect control while a reconnect delay is scheduled
- **THEN** the system cancels the delay and attempts reconnection immediately

#### Scenario: No-op while connected, terminated, or already attempting
- **WHEN** the reconnect control is activated while connected, after an intentional close, or while a connection attempt is already in flight
- **THEN** nothing happens and no additional connection is opened

### Requirement: At most one realtime connection is live per client
The system SHALL ensure that opening a connection first releases any previous one, so a client never holds two live realtime connections at once.

#### Scenario: A new attempt releases the previous socket
- **WHEN** a new connection attempt begins while a previous socket is still attached
- **THEN** the previous socket is detached and closed before the new one is opened, so the user is never registered twice and no event is delivered twice

### Requirement: Recovery is confirmed transiently
WHEN realtime messaging becomes available after being unavailable, the system SHALL confirm the recovery in the conversation and withdraw that confirmation without user action.

#### Scenario: Recovery confirmation appears and withdraws
- **WHEN** the connection is restored after an outage
- **THEN** a recovery confirmation appears in the conversation and is later withdrawn without user action

### Requirement: Waiting messages flush under original tokens on restore
WHEN the connection is restored, the system SHALL send each waiting message with its original correlation token and acknowledge each exactly once.

#### Scenario: Queue flushes idempotently
- **WHEN** the connection is restored with waiting messages
- **THEN** each waiting message is sent under its original client message identifier and acknowledged exactly once

### Requirement: Rejected messages are retried only on demand
WHILE a message is shown as rejected, the system SHALL offer a control to re-submit it under its original correlation token, and SHALL never re-submit it automatically.

#### Scenario: Manual retry under original token
- **WHEN** a message is shown as rejected and the user activates its retry control
- **THEN** the message is re-submitted under its original client message identifier

#### Scenario: No automatic retry
- **WHEN** a message is shown as rejected without user action
- **THEN** it is not re-submitted

### Requirement: Correlated errors are worded from the code
WHEN a correlated error arrives for a submitted command, the system SHALL retain that error's code and reason against the message and word the rejection from the code, not the reason text.

#### Scenario: Rejection wording comes from the error code
- **WHEN** a correlated error arrives for a submitted message
- **THEN** the message retains the error's code and reason, and the rejection is worded from the code rather than the reason text

### Requirement: No connection-severing control in production builds
The system SHALL NOT include a control that intentionally severs the connection in a production build.

#### Scenario: Drop control is dev-only
- **WHEN** a production build is created
- **THEN** it contains no control that intentionally severs the realtime connection
