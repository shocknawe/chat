# app-shell Specification (Delta)

## Purpose

Defines the re-skinned application shell matching the approved one-shot design: header,
rail, thread, and composer presentation, theme, mobile layout, and accessibility behavior —
with every existing loading, error, retry, and empty state preserved.

"Empty submissions are unavailable" and the reading-position requirements below restate
behaviour `realtime-messaging` already requires; they appear here because the re-skin touches
the composer and the scroll container, and a re-skin that silently breaks them would
otherwise pass review.

## ADDED Requirements

### Requirement: Signed-in shell preserves all existing states
The system SHALL present the signed-in application as an accessibly named header above a conversation rail and a message thread, keeping all existing loading, error, retry, and empty states.

#### Scenario: Re-skin is not a rewrite
- **WHEN** the re-skinned shell is displayed
- **THEN** the header, rail, and thread are accessibly named and every pre-existing loading, error, retry, and empty state still functions

### Requirement: Thread header describes the other participant
WHEN a conversation is selected, the system SHALL present the other participant's initials, name, a supporting line, and the inspector control at the head of the thread.

#### Scenario: Thread header content
- **WHEN** a conversation is selected
- **THEN** the thread header shows the other participant's initials, name, a supporting line, and the inspector control

### Requirement: Message rows state status and distinguish authorship
WHEN a message is displayed, the system SHALL state its delivery status in words, and SHALL visually distinguish own messages from received ones without relying on colour alone.

#### Scenario: Status in words
- **WHEN** a message is displayed
- **THEN** its delivery status is stated in words

#### Scenario: Authorship without colour reliance
- **WHEN** own and received messages are displayed together
- **THEN** they are visually distinguished without relying on colour alone

### Requirement: Reading position is stable
WHILE the user is reading earlier messages and a message arrives, the system SHALL NOT change the user's reading position.

#### Scenario: Arrival does not scroll a reading user
- **WHEN** the user is scrolled up reading earlier messages and a message arrives
- **THEN** the user's reading position does not change

### Requirement: Submission positions the thread
WHEN the user submits a message, the system SHALL position the thread to show that message, trimmed at its boundaries only.

#### Scenario: Sent message is shown
- **WHEN** the user submits a message
- **THEN** the thread positions to show that message, trimmed only at its boundaries

### Requirement: Empty submissions are unavailable
IF the composer's content is empty or whitespace only, the system SHALL make submission unavailable.

#### Scenario: Whitespace-only composer cannot submit
- **WHEN** the composer holds only whitespace
- **THEN** submission is unavailable

### Requirement: Theme supports light, dark, and system default
The system SHALL present a light and a dark form, defaulting to the operating system preference and persisting an explicit override.

#### Scenario: OS preference is the default
- **WHEN** no explicit theme override exists
- **THEN** the theme follows the operating system preference

#### Scenario: Override persists
- **WHEN** the user explicitly chooses a theme
- **THEN** that choice persists across reloads

### Requirement: Compact viewports use exclusive overlays
WHILE the viewport is below the compact threshold, the system SHALL present the rail as an overlay dismissed by selection, dismiss key, or scrim; opening any overlay SHALL dismiss any other.

#### Scenario: Rail overlay dismissal
- **WHEN** the viewport is below the compact threshold and the rail overlay is open
- **THEN** selecting a conversation, pressing the dismiss key, or activating the scrim dismisses it

#### Scenario: Overlays are exclusive
- **WHEN** an overlay opens while another overlay is open
- **THEN** the previously open overlay is dismissed

### Requirement: Accessibility affordances are present
The system SHALL offer a skip-to-conversation link, announce conversation, identity, and delivery-status changes to assistive technology, show a visible focus indicator on every operable control, and honour reduced-motion preferences.

#### Scenario: Skip link reaches the conversation
- **WHEN** the user activates the skip-to-conversation link
- **THEN** focus moves to the conversation

#### Scenario: Changes are announced
- **WHEN** the conversation, current identity, or a delivery status changes
- **THEN** the change is announced to assistive technology

#### Scenario: Focus is always visible
- **WHEN** any operable control receives keyboard focus
- **THEN** a visible focus indicator is shown

#### Scenario: Reduced motion is honoured
- **WHEN** the user prefers reduced motion
- **THEN** motion is reduced accordingly
