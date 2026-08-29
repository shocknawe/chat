## ADDED Requirements

### Requirement: Conversations are scoped to the participating user
The system SHALL return only the conversations in which the requesting authenticated user participates.

#### Scenario: Backend returns only the user's conversations
- **WHEN** an authenticated user requests their conversations
- **THEN** the system returns only conversations in which that user participates

#### Scenario: Frontend displays the current user's conversations
- **WHEN** the current user is established
- **THEN** the system displays the conversations available to that user

### Requirement: Message history is authorized by participation
The system SHALL verify that the requesting user participates in a conversation before returning its message history, and SHALL reject requests from non-participants.

#### Scenario: Participant retrieves history
- **WHEN** an authenticated user requests the message history of a conversation in which they participate
- **THEN** the system verifies participation and returns the conversation's persisted message history

#### Scenario: Non-participant is rejected
- **WHEN** a user requests a conversation in which they do not participate
- **THEN** the system rejects the request

### Requirement: History is returned in deterministic chronological order
The system SHALL return persisted messages for a conversation in deterministic chronological order.

#### Scenario: Authorized history request is ordered
- **WHEN** an authorized user requests conversation history
- **THEN** the system returns persisted messages in deterministic chronological order

#### Scenario: Frontend renders history chronologically
- **WHEN** message history is displayed
- **THEN** the system orders messages chronologically

### Requirement: History is retrieved on demand and after reload
The system SHALL retrieve a conversation's message history when the conversation is opened and SHALL retrieve previously persisted messages after a page reload.

#### Scenario: Opening a conversation loads its history
- **WHEN** the user selects a conversation
- **THEN** the system retrieves and displays the conversation's message history

#### Scenario: Reload retains message history
- **WHEN** the user reloads the page
- **THEN** the system retrieves previously persisted messages from the backend
