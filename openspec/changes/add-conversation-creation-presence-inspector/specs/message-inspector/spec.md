# message-inspector Specification (Delta)

## Purpose

Defines the info drawer: a per-message and thread-level inspector showing the message
record, its observed transport steps, and associated protocol frames, with honest rendering
of unobserved values.

## ADDED Requirements

### Requirement: Every message has an information affordance
The system SHALL present an accessibly named information affordance with every message, including the user's own pending messages, and a thread-header control that opens the drawer with no message selected to show connection and protocol facts.

#### Scenario: Per-message affordance
- **WHEN** a message is displayed, including own pending messages
- **THEN** it carries an accessibly named information affordance

#### Scenario: Thread-header control opens with no selection
- **WHEN** the user activates the thread-header inspector control
- **THEN** the drawer opens with no message selected and shows connection and protocol facts

### Requirement: Inspecting a message distinguishes it
WHEN the user activates a message's information affordance, the system SHALL open the inspector on that message's record, distinguish that message visually, and expose that affordance as expanded.

#### Scenario: Message is inspected
- **WHEN** a message's information affordance is activated
- **THEN** the inspector opens on that message's record, the message is visually distinguished, and the affordance is exposed as expanded

### Requirement: Closing returns focus
WHEN the inspector closes, the system SHALL return focus to the control that opened it, or to the thread-header control if the originator is gone.

#### Scenario: Focus returns to originator
- **WHEN** the inspector closes and its opening control still exists
- **THEN** focus returns to that control

#### Scenario: Originator is gone
- **WHEN** the inspector closes and its opening control no longer exists
- **THEN** focus returns to the thread-header inspector control

### Requirement: Layout mode follows the viewport threshold
WHILE the viewport is at or above the wide-layout threshold, the system SHALL dock the open inspector beside the thread; below the threshold it SHALL overlay the thread with a scrim and confine focus.

#### Scenario: Docked at wide widths
- **WHEN** the viewport is at or above the wide-layout threshold and the inspector is open
- **THEN** the inspector is docked beside the thread

#### Scenario: Overlaid at narrow widths
- **WHEN** the viewport is below the wide-layout threshold and the inspector is open
- **THEN** the inspector overlays the thread with a scrim and focus is confined

### Requirement: Crossing the threshold re-presents rather than closes
WHEN the viewport crosses the threshold while the inspector is open, the system SHALL present it in the other mode, retaining the inspected message.

#### Scenario: Mode switch retains selection
- **WHEN** the viewport crosses the layout threshold while the inspector is open
- **THEN** the inspector is re-presented in the other mode with the inspected message retained

### Requirement: The record shows observed facts
WHERE the inspector shows a message's record, the system SHALL present the ordered transport steps with their observed timestamps, the message identifier, the correlation token from the message itself, the conversation and sender identifiers, server and (for own messages) client creation times, the ordering key, the content length against the documented maximum, and the associated realtime protocol frames using the shapes defined by the API specification.

#### Scenario: Full record for an observed message
- **WHEN** a message with an observed transport record is inspected
- **THEN** the inspector presents its ordered transport steps with timestamps, identifiers, correlation token, timestamps, ordering key, content length, and protocol frames per the API specification

### Requirement: Unobserved values render as absent
The system SHALL derive every value the inspector presents from data the client observed or holds, and SHALL render any unobserved value as absent.

#### Scenario: Nothing is fabricated
- **WHEN** the inspector lacks an observed value for a field
- **THEN** that field renders as absent rather than a fabricated value

### Requirement: Unobserved transport is stated, not dashed
IF no transport record is held for the inspected message, the system SHALL state that its transport was not observed in this session, rather than presenting its steps as absent values.

#### Scenario: Historical message inspected without transport record
- **WHEN** a message loaded from history is inspected and no transport record is held for it
- **THEN** the inspector states its transport was not observed in this session, while its persistent fields from the message itself still render

### Requirement: Acknowledgement semantics are stated honestly
WHILE the inspector shows an acknowledged message, the system SHALL state that acknowledgement confirms persistence, not delivery or reading, and SHALL present fan-out as unverified.

#### Scenario: Acknowledged message caveat
- **WHEN** an acknowledged message is inspected
- **THEN** the inspector states that acknowledgement confirms persistence only and presents fan-out as unverified

### Requirement: Acknowledgement does not close the inspector
WHEN a message being inspected is acknowledged, the system SHALL continue showing its record against its server identity without closing the inspector.

#### Scenario: Pending message acknowledged while inspected
- **WHEN** the inspected message is acknowledged
- **THEN** the inspector keeps showing its record against its server identity without closing

### Requirement: Live updates within a bounded ledger
WHILE the inspector is open, the system SHALL update its contents as the underlying state changes, and SHALL bound the number of retained transport records, discarding oldest first.

#### Scenario: Contents update live
- **WHEN** the inspector is open and underlying state changes
- **THEN** its contents update

#### Scenario: Ledger is bounded
- **WHEN** retained transport records exceed the bound
- **THEN** the oldest records are discarded first

### Requirement: Selection follows conversation and identity
WHEN the user selects a different conversation or the current user changes, the system SHALL clear the inspected message selection.

#### Scenario: Conversation switch clears selection
- **WHEN** the user selects a different conversation or the current user changes
- **THEN** the inspected message selection is cleared
