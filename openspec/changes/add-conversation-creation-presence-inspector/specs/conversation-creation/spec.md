# conversation-creation Specification (Delta)

## Purpose

Defines idempotent creation of 1:1 conversations: the REST endpoint, database-enforced pair
uniqueness, the post-commit `CONVERSATION_CREATED` event, and the frontend creation dialog.

## ADDED Requirements

### Requirement: Authenticated user can create a 1:1 conversation
WHEN an authenticated user requests a conversation with another directory user, the system SHALL create a conversation whose participants are the caller and that user, and return it with a created status.

#### Scenario: New conversation is created
- **WHEN** an authenticated user requests a conversation with a directory user they share no conversation with
- **THEN** the system creates a conversation whose participants are exactly the caller and that user and returns it with a created (201) status

#### Scenario: Caller is an implicit participant
- **WHEN** a creation request is processed
- **THEN** the participants are the caller and the named user, so no request can create a conversation the caller is not part of

### Requirement: Creation is idempotent per pair
IF a conversation already exists between the caller and the named user, the system SHALL return that existing conversation with a success status distinct from creation, without creating a second one.

#### Scenario: Existing conversation is returned
- **WHEN** an authenticated user requests a conversation with a user they already converse with
- **THEN** the system returns the existing conversation with a 200 status and creates no new conversation

### Requirement: Concurrent creation persists exactly one conversation
The system SHALL enforce single-conversation-per-pair with a database constraint rather than an application-level check alone; IF two requests to create a conversation between the same pair arrive concurrently, the system SHALL persist exactly one conversation and answer both requests successfully.

#### Scenario: Race is resolved by constraint and re-read
- **WHEN** two concurrent creation requests for the same pair both pass the existence check
- **THEN** the database constraint rejects the second insert, and the system re-reads the persisted conversation in a fresh transaction and answers both requests successfully

#### Scenario: A missing constraint fails the application, not silently
- **WHEN** the application starts against a database where the pair-uniqueness constraint is absent
- **THEN** startup fails, rather than the application running with an unenforced invariant

### Requirement: Malformed creation requests are rejected
IF a creation request omits the other user, names the caller, or carries a malformed identifier, the system SHALL reject it as a client error and create no conversation.

#### Scenario: Missing or malformed participant
- **WHEN** a creation request omits `participantId` or carries a malformed identifier
- **THEN** the system rejects it with a 400 status and creates no conversation

#### Scenario: Participant is the caller
- **WHEN** a creation request names the caller as the participant
- **THEN** the system rejects it with a 400 status and creates no conversation

#### Scenario: Unknown participant
- **WHEN** a creation request names a user that is not in the directory
- **THEN** the system rejects it with a 404 status and creates no conversation

#### Scenario: Unknown caller identity
- **WHEN** a creation request carries a missing, malformed, or unknown caller identity
- **THEN** the system rejects it with a 401 status

### Requirement: The other participant is notified after commit
WHEN a conversation is created, the system SHALL emit a conversation-created event carrying the new conversation to every active connection of the other participant, after the creating transaction has committed.

#### Scenario: Other participant's connections receive the event
- **WHEN** a conversation is created and committed
- **THEN** every active connection of the other participant receives a `CONVERSATION_CREATED` event carrying the new conversation, whose latest message is represented as empty in the same way the listing represents an empty history

#### Scenario: Creator receives no event
- **WHEN** a conversation is created
- **THEN** the creator's connections receive no `CONVERSATION_CREATED` event for it, as the creator already holds the REST response

### Requirement: Frontend presents a conversation-creation control
WHEN the signed-in application is displayed, the system SHALL present an accessibly named control in the conversation rail's heading that starts a new conversation.

#### Scenario: Control opens a dialog of candidates
- **WHEN** the user activates the creation control
- **THEN** the system opens a modal dialog offering each directory user the current user has no conversation with, excluding the current user

#### Scenario: Exhausted directory is stated
- **WHEN** every other directory user already has a conversation with the current user
- **THEN** the dialog states that and presents no empty selection list

### Requirement: Dialog confines and returns focus
WHILE the creation dialog is open, the system SHALL confine keyboard focus to it; on dismissal it SHALL close and return focus to the opening control.

#### Scenario: Focus is confined while open
- **WHEN** the creation dialog is open
- **THEN** keyboard focus cannot leave the dialog

#### Scenario: Focus returns on dismissal
- **WHEN** the creation dialog is dismissed
- **THEN** focus returns to the opening control

### Requirement: Successful creation selects the conversation
WHEN the user chooses a person and the request succeeds with either creation or an existing conversation, the system SHALL close the dialog, add or select the conversation in the rail, move focus to the composer, and announce to assistive technology that the conversation with that person is open.

#### Scenario: Success closes and selects
- **WHEN** creation succeeds with 200 or 201
- **THEN** the dialog closes, the conversation is added or selected in the rail, focus moves to the composer, and the change is announced

### Requirement: Failed creation keeps the dialog open
IF a creation request fails, the system SHALL keep the dialog open, state the failure in words, allow another attempt, and add no conversation to the rail.

#### Scenario: Failure is recoverable in place
- **WHEN** a creation request fails
- **THEN** the dialog stays open with the failure stated in words, no conversation is added to the rail, and another attempt is possible

### Requirement: Duplicate submission is prevented
WHILE a creation request is in flight, the system SHALL indicate progress and prevent a duplicate submission for the same person.

#### Scenario: In-flight guard
- **WHEN** a creation request for a person is in flight
- **THEN** the system indicates progress and blocks a second submission for that person

### Requirement: Conversation-created events update the rail
WHEN a conversation-created event arrives for a conversation the rail does not list, the system SHALL add it to the rail with an empty-history preview, without changing the user's active conversation.

#### Scenario: Rail updates without refetch or reselection
- **WHEN** a `CONVERSATION_CREATED` event arrives for an unlisted conversation
- **THEN** the rail adds it with an empty preview and the active conversation is unchanged
