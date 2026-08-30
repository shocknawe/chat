# demo-seed-data Specification (Delta)

## Purpose

Defines the seeded demo directory: enough users and conversations that conversation
creation, rail previews, and presence are all demonstrable at runtime, and non-destructive
boot against a database seeded by an earlier version.

## ADDED Requirements

### Requirement: Seeding is step-wise idempotent
WHEN the application boots against a database that has been seeded before, the system SHALL evaluate every seeded entity independently, so that seed data added later is still applied to a database seeded by an earlier version.

#### Scenario: Later-added seed data reaches an already-seeded database
- **WHEN** the application boots against a database seeded by an earlier version
- **THEN** every seeded user, conversation, message, and backfill that is missing is created, and none is skipped because some other seeded entity is already present

### Requirement: Directory contains reachable new-conversation candidates
The system SHALL seed a directory in which at least one user has no conversation with at least one other user.

#### Scenario: A user can start a new conversation
- **WHEN** the application boots with seed data
- **THEN** at least one pair of directory users shares no conversation, so the creation flow is reachable

### Requirement: A user has more than one conversation
The system SHALL seed at least one user whose conversation list contains more than one conversation.

#### Scenario: Rail renders multiple rows
- **WHEN** that seeded user's conversations are listed
- **THEN** more than one conversation is returned

### Requirement: Conversations cover non-empty and empty histories
The system SHALL seed at least one conversation with a non-empty message history and at least one with an empty history.

#### Scenario: Seeded conversation with history
- **WHEN** the seeded conversation with history is listed
- **THEN** it carries a latest-message preview backed by persisted messages

#### Scenario: Seeded conversation without history
- **WHEN** a seeded conversation has no messages
- **THEN** its preview is empty

### Requirement: Existing seed identifiers are unchanged
The system SHALL NOT change the identifier or display name of any user or conversation seeded before this change.

#### Scenario: Pre-existing users keep their ids
- **WHEN** the expanded seed data is applied
- **THEN** every previously seeded user and conversation retains its original identifier and display name

### Requirement: Boot is non-destructive against previously seeded databases
WHEN the application boots against a database seeded by an earlier version, the system SHALL add the new seed data and derive any newly required property of existing rows, without requiring the database to be recreated.

#### Scenario: Backfill of the conversation pair key
- **WHEN** the application boots against a database whose pre-existing 1:1 conversations lack a pair key
- **THEN** the seeder computes and stores the pair key for those rows without recreating the database

#### Scenario: New seed rows are added idempotently
- **WHEN** the application boots more than once
- **THEN** the seed data is not duplicated
