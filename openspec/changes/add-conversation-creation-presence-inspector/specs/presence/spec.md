# presence Specification (Delta)

## Purpose

Defines online presence derived from the live connection registry, delivered as snapshots
scoped to each recipient's conversation partners, and rendered in the rail in words plus
colour.

## ADDED Requirements

### Requirement: Online state is derived from live connections
The system SHALL consider a user online exactly while at least one realtime connection is bound to that user. Detection is best-effort: with no heartbeat, a half-open connection continues to count as bound until a delivery to it fails.

#### Scenario: One open session means online
- **WHEN** a user holds at least one open realtime session
- **THEN** the system considers that user online

#### Scenario: No open session means offline
- **WHEN** a user holds no open realtime session
- **THEN** the system considers that user offline

### Requirement: Every connection-removal path can change presence
The system SHALL derive presence from the live connection registry at the moment of broadcast, and SHALL treat every path that removes a connection — including removal caused by a delivery failure — as a possible online-state change.

#### Scenario: Eviction updates presence
- **WHEN** a connection is removed because delivery to it failed
- **THEN** the system re-evaluates the affected user's online state and announces any change

#### Scenario: Explicit close updates presence
- **WHEN** a connection is unregistered on close
- **THEN** the system re-evaluates the affected user's online state and announces any change

### Requirement: Newly connected sockets receive presence first
WHEN a realtime connection is established, the system SHALL send that socket the current set of online user identifiers as its first event.

#### Scenario: First event is a presence snapshot
- **WHEN** a realtime connection is established
- **THEN** the first event it receives carries the current online user identifiers scoped to that user

### Requirement: Transitions broadcast complete scoped snapshots
WHEN a user's online state changes, the system SHALL send an updated complete set to every connected user who shares a conversation with them; the system SHALL limit each presence set to users with whom the recipient shares a conversation.

#### Scenario: Partner receives a fresh snapshot on transition
- **WHEN** a user's online state changes
- **THEN** every connected user sharing a conversation with them receives an updated complete presence set

#### Scenario: Presence is scoped to conversation partners
- **WHEN** a presence set is delivered to a recipient
- **THEN** it contains no user with whom that recipient shares no conversation

#### Scenario: Snapshots replace rather than merge
- **WHEN** a presence snapshot arrives at a client
- **THEN** the client replaces its online set wholesale rather than merging

#### Scenario: Snapshots are delivered in the order they were computed
- **WHEN** two presence transitions occur in close succession
- **THEN** the recipient's last-applied snapshot is the more recently computed one, so wholesale replacement cannot leave a client holding stale presence

### Requirement: Presence evaluation never blocks connection bookkeeping
The system SHALL NOT perform database access or message delivery while holding the lock that guards a user's connection set.

#### Scenario: Broadcast happens after the registry mutation completes
- **WHEN** an online-state transition is detected during a connection registration, unregistration, or eviction
- **THEN** the partner-set lookup and the broadcast occur after that registry mutation has completed, so a delivery failure occurring during the broadcast can evict its session without re-entering the same lock

### Requirement: A connection is never half-removed from the registry
WHEN a connection is removed for any reason, the system SHALL remove it from every index that tracks it.

#### Scenario: Removal cannot strand a user as permanently online
- **WHEN** a connection is removed and its owning user cannot be resolved from the session itself
- **THEN** the system still removes it from that user's connection set, so no user is reported online by a connection that is gone

### Requirement: Multiple connections suppress a false offline announcement
WHILE a user holds more than one connection, the system SHALL NOT announce that user as offline when one of those connections closes.

#### Scenario: One of two connections closes
- **WHEN** a user holding two connections closes one of them
- **THEN** that user is not announced as offline

#### Scenario: Last connection closes
- **WHEN** a user's last connection closes
- **THEN** that user is announced as offline to their connected conversation partners

### Requirement: Presence is rendered in words plus colour
WHILE a user is online or offline, the system SHALL mark that user's rail rows accordingly using text in addition to any colour.

#### Scenario: Rail rows show presence accessibly
- **WHEN** a rail row represents a conversation partner
- **THEN** the row marks that partner's online state with text in addition to colour
