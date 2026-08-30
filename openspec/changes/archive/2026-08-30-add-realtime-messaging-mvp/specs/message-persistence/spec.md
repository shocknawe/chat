## ADDED Requirements

### Requirement: Messages are persisted before acknowledgement
The system SHALL persist an accepted message durably before acknowledging its successful creation.

#### Scenario: Persistence precedes acknowledgement
- **WHEN** a valid message is accepted
- **THEN** the system persists the message before acknowledging successful creation

### Requirement: Persisted messages store the required fields
The system SHALL store a message's unique identifier, conversation identifier, sender identifier, client message identifier, content, and server-generated creation timestamp.

#### Scenario: Identifier is stored
- **WHEN** a message is persisted
- **THEN** the system stores its unique identifier

#### Scenario: Conversation identifier is stored
- **WHEN** a message is persisted
- **THEN** the system stores its conversation identifier

#### Scenario: Sender identifier is stored
- **WHEN** a message is persisted
- **THEN** the system stores its sender identifier

#### Scenario: Client message identifier is stored
- **WHEN** a message is persisted
- **THEN** the system stores its client message identifier for correlation and deduplication

#### Scenario: Content is stored
- **WHEN** a message is persisted
- **THEN** the system stores its content

#### Scenario: Server timestamp is stored
- **WHEN** a message is persisted
- **THEN** the system stores its server-generated creation timestamp

### Requirement: Persisted messages survive restarts
The system SHALL retain previously persisted messages across backend application restarts and SHALL return them when their conversation's history is requested afterwards.

#### Scenario: Messages retained after restart
- **WHEN** the backend application restarts
- **THEN** the system retains previously persisted messages

#### Scenario: History available after restart
- **WHEN** a conversation's message history is requested after an application restart
- **THEN** the system returns previously persisted messages

### Requirement: Persisted messages support deterministic ordering
The system SHALL support retrieving a conversation's messages in deterministic chronological order and SHALL apply a deterministic secondary ordering criterion when two messages share the same creation timestamp.

#### Scenario: Messages retrievable in chronological order
- **WHEN** multiple messages belong to the same conversation
- **THEN** the system supports retrieving them in deterministic chronological order

#### Scenario: Ties broken deterministically
- **WHEN** two messages have the same creation timestamp
- **THEN** the system uses a deterministic secondary ordering criterion

### Requirement: Referential integrity is maintained
The system SHALL maintain referential integrity between a message and its conversation, and between a conversation and its participant users.

#### Scenario: Message references a valid conversation
- **WHEN** a message references a conversation
- **THEN** the system maintains referential integrity between the message and conversation

#### Scenario: Conversation references valid participants
- **WHEN** a conversation references users
- **THEN** the system maintains referential integrity between the conversation and its participants

### Requirement: Failed persistence is not reported as created
The system SHALL leave a message whose persistence fails in a state that is never reported to clients as successfully created.

#### Scenario: Persistence failure hidden from clients
- **WHEN** persistence of a message fails
- **THEN** the system leaves the message in a state that is not reported to clients as successfully created

### Requirement: Duplicate commands create at most one message
The system SHALL use the sender identifier and client message identifier as a database-enforced deduplication key, SHALL create at most one logical message when the same `SEND_MESSAGE` command is delivered more than once, and SHALL reject reuse of that key for a conflicting payload.

#### Scenario: Duplicate delivery is idempotent
- **WHEN** the same SEND_MESSAGE command is delivered more than once
- **THEN** the system creates at most one logical message, returns the existing authoritative message to the retrying connection, and does not re-broadcast it as newly created

#### Scenario: Concurrent duplicate delivery is idempotent
- **WHEN** matching SEND_MESSAGE commands with the same sender and client message identifier are handled concurrently
- **THEN** the database constraint permits exactly one logical message to be stored

#### Scenario: Conflicting client message identifier is rejected
- **WHEN** a sender reuses a client message identifier with different content or a different conversation
- **THEN** the system rejects the command and does not acknowledge the existing message as the result of the conflicting command
