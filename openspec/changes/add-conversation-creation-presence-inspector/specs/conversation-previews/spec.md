# conversation-previews Specification (Delta)

## Purpose

Defines the server-provided latest-message preview on every conversation and how the
frontend rail renders it, including the client-side pending/failed override.

## ADDED Requirements

### Requirement: Conversation listing includes the latest message preview
WHEN a user's conversations are listed, the system SHALL include in each conversation its latest message by the ordering used for message history, or no message when the history is empty.

#### Scenario: Listing carries a preview
- **WHEN** an authenticated user's conversations are listed
- **THEN** each conversation includes its latest message by the documented history ordering (`createdAt ASC, id ASC`)

#### Scenario: Empty history carries no preview
- **WHEN** a listed conversation has no messages
- **THEN** it carries no latest message, and a client SHALL treat an absent and a null preview identically

#### Scenario: Preview matches history tail
- **WHEN** a conversation has a non-empty history
- **THEN** its preview equals the last message of its history under the documented ordering

### Requirement: Creation responses carry the same preview shape as the listing
WHEN a creation response returns a conversation, the system SHALL represent its latest message exactly as the listing does, including how an empty history is represented.

#### Scenario: New conversation has no preview
- **WHEN** a conversation creation response is returned for a newly created conversation
- **THEN** it carries no latest message, in the same representation the listing uses for an empty history

### Requirement: Rail rows render the server preview
WHEN a conversation's most recent message exists, the system SHALL summarise it in that conversation's rail row using the server-provided preview.

#### Scenario: Preview is rendered
- **WHEN** a conversation has a most recent message
- **THEN** its rail row summarises that message from the server-provided preview

#### Scenario: Empty history is stated
- **WHEN** a conversation has no messages
- **THEN** its rail row states that there are no messages yet

### Requirement: Pending or failed own messages override the preview
WHEN the current user's newest message in a conversation is awaiting acknowledgement or was rejected, the system SHALL let that client-side state override the preview wording as sending or not sent. The override stands only while the pending or failed item is itself the newest activity in the conversation: a newer confirmed or received message renders as the preview even if a failed item remains.

#### Scenario: Sending state overrides preview
- **WHEN** the current user's newest message in a conversation is awaiting acknowledgement
- **THEN** the rail preview reads as sending rather than the server-provided preview

#### Scenario: Failed state overrides preview
- **WHEN** the current user's newest message in a conversation was rejected
- **THEN** the rail preview reads as not sent rather than the server-provided preview

### Requirement: Previews update without a listing refetch
WHEN a message is acknowledged or received for a conversation, the system SHALL update that conversation's rail preview without refetching the listing.

#### Scenario: Acknowledgement updates the preview
- **WHEN** a submitted message is acknowledged
- **THEN** that conversation's rail preview updates from the acknowledged message without a listing refetch

#### Scenario: Received message updates the preview
- **WHEN** a new-message event arrives for a conversation
- **THEN** that conversation's rail preview updates from the received message without a listing refetch
