# message-persistence Specification (Delta)

## Purpose

Exposes the already-persisted client message identifier in API responses and realtime
events, reversing the previously documented deliberate omission.

## ADDED Requirements

### Requirement: Correlation token is exposed in history and realtime events
WHEN a message is returned by the history endpoint or carried by any realtime event, the system SHALL include the correlation token under which it was submitted, including for messages submitted by another participant.

#### Scenario: History returns the token
- **WHEN** conversation history is requested
- **THEN** every returned message includes the client message identifier under which it was submitted

#### Scenario: Realtime events carry the token
- **WHEN** a message is carried by a `NEW_MESSAGE` or `MESSAGE_ACK` event
- **THEN** the event includes the client message identifier, including for messages submitted by another participant

#### Scenario: Token survives a reload for both directions
- **WHEN** the page is reloaded and history is re-fetched
- **THEN** both sent and received messages display the correlation token they were submitted under

### Requirement: Exposure does not change the token's authority
The system SHALL continue to treat the correlation token as a per-sender idempotency key and never as the authoritative message identifier.

#### Scenario: Token remains non-authoritative
- **WHEN** a message's correlation token is exposed to any client
- **THEN** the authoritative message identifier remains the server-generated identifier, and the exposed token permits no action beyond idempotent retry by its own sender
