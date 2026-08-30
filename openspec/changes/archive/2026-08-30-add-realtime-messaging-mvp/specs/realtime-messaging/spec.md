## ADDED Requirements

### Requirement: WebSocket connection is bound to the current user
The system SHALL establish a WebSocket connection once a current user is established, SHALL validate the selected user during the handshake, and SHALL associate realtime events with that user on both client and server.

#### Scenario: Frontend connects when current user is established
- **WHEN** the current user is established
- **THEN** the system establishes a WebSocket connection to the backend

#### Scenario: Frontend associates events with the current user
- **WHEN** the WebSocket connection is established
- **THEN** the system associates realtime events with the current user

#### Scenario: Backend associates the connection with an application user
- **WHEN** a WebSocket connection is established
- **THEN** the system associates the connection with an application user

#### Scenario: Unknown user cannot establish a connection
- **WHEN** a WebSocket handshake identifies a user that is not configured for the MVP
- **THEN** the system rejects the handshake

#### Scenario: Browser windows can select identities independently
- **WHEN** two browser windows on the same origin select different configured users
- **THEN** each window's REST requests and WebSocket connection remain associated with its own selection

### Requirement: Connection registry tracks active sessions per user
The system SHALL register each established WebSocket connection as active, track multiple connections of the same user independently, and remove a connection from the registry when it closes.

#### Scenario: Established connection is registered as active
- **WHEN** a WebSocket connection is established
- **THEN** the system registers the connection as active

#### Scenario: Closed connection is removed from the registry
- **WHEN** a WebSocket connection closes
- **THEN** the system removes the connection from the active connection registry

#### Scenario: Multiple connections per user are tracked independently
- **WHEN** the same user establishes multiple WebSocket connections
- **THEN** the system tracks each connection independently

### Requirement: Sender identity is derived from the validated connection
The system SHALL identify the sender of a `SEND_MESSAGE` command from the user identity validated and bound during the WebSocket handshake and SHALL NOT trust a sender identifier supplied in the command payload.

#### Scenario: Sender is taken from the connection
- **WHEN** the backend receives a SEND_MESSAGE command
- **THEN** the system identifies the sender using the validated connection identity

#### Scenario: Client-supplied sender identifier is ignored
- **WHEN** the backend receives a message with a sender identifier in the payload
- **THEN** the system derives the sender identity from the authenticated connection rather than the message payload data

### Requirement: SEND_MESSAGE commands are validated before acceptance
The system SHALL validate `SEND_MESSAGE` command content and conversation references, SHALL reject empty, whitespace-only, or over-length content, and SHALL reject commands that fail validation.

#### Scenario: Content is validated
- **WHEN** the backend receives a SEND_MESSAGE command
- **THEN** the system validates the message content

#### Scenario: Empty or whitespace-only content is rejected
- **WHEN** the message content is empty or whitespace-only
- **THEN** the system rejects the command

#### Scenario: Over-length content is rejected
- **WHEN** the message content exceeds the configured maximum length
- **THEN** the system rejects the command before persisting or broadcasting it

#### Scenario: Sender participation is verified
- **WHEN** the backend receives a SEND_MESSAGE command
- **THEN** the system verifies that the sender participates in the referenced conversation

#### Scenario: Non-participating sender is rejected
- **WHEN** the sender does not participate in the referenced conversation
- **THEN** the system rejects the command

#### Scenario: Missing conversation is rejected
- **WHEN** the referenced conversation does not exist
- **THEN** the system rejects the command

### Requirement: Accepted messages are server-authoritative
The system SHALL treat the client message identifier only as a correlation and deduplication token, SHALL generate the authoritative message identifier and creation timestamp for an accepted message, and SHALL commit the message before reporting it as successfully created.

#### Scenario: Client message identifier is not authoritative
- **WHEN** a valid message command supplies a client message identifier
- **THEN** the system uses it for correlation and deduplication but generates a separate authoritative message identifier

#### Scenario: Server generates the identifier
- **WHEN** a valid message is accepted
- **THEN** the system generates the authoritative message identifier

#### Scenario: Server generates the timestamp
- **WHEN** a valid message is accepted
- **THEN** the system generates the authoritative creation timestamp

#### Scenario: Persistence precedes success reporting
- **WHEN** a valid message is accepted
- **THEN** the system commits the message transaction before reporting it as successfully created

### Requirement: Confirmation and delivery follow successful persistence
The system SHALL send an acknowledgement containing the authoritative message to the originating connection and a new-message event to every other active connection of the conversation participants only after the message transaction commits.

#### Scenario: Originator receives confirmation
- **WHEN** message persistence succeeds
- **THEN** the system sends a confirmation to the originating WebSocket connection

#### Scenario: Participants receive the new-message event
- **WHEN** message persistence succeeds
- **THEN** the system sends a new-message event to participant connections other than the originating connection

#### Scenario: Origin receives one authoritative delivery path
- **WHEN** the originating connection receives a successful acknowledgement
- **THEN** the system does not also send that connection a new-message event for the same command

#### Scenario: Each active session of a participant is delivered to
- **WHEN** a participant has multiple active browser sessions
- **THEN** the system delivers realtime events to each active session

#### Scenario: One failed session send is isolated
- **WHEN** delivery to one active or stale WebSocket session fails
- **THEN** the system removes or skips that session and continues delivery to other active sessions

#### Scenario: Offline recipient still persists
- **WHEN** the recipient has no active WebSocket connection
- **THEN** the system still persists the message successfully

#### Scenario: Message recoverable after missed delivery
- **WHEN** a user disconnects before receiving a realtime message
- **THEN** the system allows the message to be recovered later through conversation history

### Requirement: Persistence failure is reported as an error
The system SHALL NOT report a message as successfully sent when persistence fails, and SHALL return an error to the originating connection.

#### Scenario: Failed persistence is not reported as sent
- **WHEN** message persistence fails
- **THEN** the system does not report the message as successfully sent

#### Scenario: Originator receives an error on failure
- **WHEN** message persistence fails
- **THEN** the system returns an error to the originating connection

### Requirement: Protocol errors are isolated to the offending command
The system SHALL return a protocol error for unsupported or invalid commands without terminating unrelated WebSocket connections.

#### Scenario: Unsupported command yields a protocol error
- **WHEN** the backend receives an unsupported WebSocket command
- **THEN** the system returns a protocol error

#### Scenario: Invalid command does not affect other connections
- **WHEN** one WebSocket command is invalid
- **THEN** the system rejects that command without terminating unrelated WebSocket connections

### Requirement: Frontend submits and tracks message state optimistically
The system SHALL submit non-empty messages with a client message identifier, display them as pending until the backend responds, replace a pending item with the authoritative acknowledged message, and transition their state to sent or failed based on the backend response, while preventing empty submissions.

#### Scenario: Non-empty message is submitted
- **WHEN** the user enters a non-empty message and submits it
- **THEN** the system sends the message to the backend

#### Scenario: Submitted message is shown as pending
- **WHEN** the user submits a message
- **THEN** the system displays the message as pending until the backend confirms it

#### Scenario: Confirmed message is marked as sent
- **WHEN** the backend confirms a submitted message
- **THEN** the system replaces the correlated pending item with the authoritative message and marks it as successfully sent

#### Scenario: Rejected message is marked as failed
- **WHEN** the backend rejects a submitted message
- **THEN** the system marks the message as failed

#### Scenario: Empty submission is prevented
- **WHEN** the user enters an empty or whitespace-only message
- **THEN** the system prevents the message from being submitted

### Requirement: Frontend renders incoming realtime messages
The system SHALL reconcile REST history, acknowledgements, and new-message events by authoritative message identifier, SHALL render new-message events for the active conversation immediately, and SHALL update other conversations' state without changing the active conversation.

#### Scenario: Repeated authoritative message is not duplicated
- **WHEN** the same authoritative message is observed through more than one REST or WebSocket path
- **THEN** the system renders one message item for its authoritative identifier

#### Scenario: New message in active conversation appears without refresh
- **WHEN** the backend sends a new-message event for the active conversation
- **THEN** the system displays the message without requiring a page refresh

#### Scenario: New message in another conversation updates state only
- **WHEN** the backend sends a new-message event for another conversation
- **THEN** the system updates the relevant conversation state without changing the user's active conversation

### Requirement: Frontend reconnects and signals unavailability
The system SHALL attempt to reconnect after an unexpected disconnect, SHALL retry unacknowledged commands with their original client message identifiers, SHALL recover messages missed while offline from history, and SHALL indicate that realtime messaging is temporarily unavailable while the connection is unavailable.

#### Scenario: Reconnect after unexpected disconnect
- **WHEN** the WebSocket connection is unexpectedly lost
- **THEN** the system attempts to reconnect

#### Scenario: Unacknowledged command is retried idempotently
- **WHEN** the connection is restored while a submitted command remains unacknowledged
- **THEN** the system retries that command using its original client message identifier

#### Scenario: Messages missed while offline are recovered
- **WHEN** the WebSocket connection is restored after realtime events may have been missed
- **THEN** the system refreshes conversation history from the backend

#### Scenario: Unavailability is surfaced to the user
- **WHEN** the WebSocket connection is unavailable
- **THEN** the system indicates that realtime messaging is temporarily unavailable
