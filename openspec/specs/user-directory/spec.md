# user-directory Specification

## Purpose

Defines how the fixed set of MVP users is exposed to clients and how a window-scoped current application user is established, validated, and isolated per browser window.

## Requirements

### Requirement: Available users are exposed to clients
The system SHALL provide the fixed set of MVP users so that clients can present selectable identities.

#### Scenario: Backend returns configured users
- **WHEN** a client requests the available users
- **THEN** the system returns the users configured for the MVP

#### Scenario: Frontend displays available users on load
- **WHEN** the application loads
- **THEN** the system displays the available users

### Requirement: Current application user is established by selection
The system SHALL establish a window-scoped current application user based on a configured identity selected by the person using the frontend, SHALL validate that identity at the backend boundary, and SHALL use it for subsequent REST and WebSocket interactions without sharing the selection across browser windows.

#### Scenario: Selecting an identity establishes the current user
- **WHEN** a user selects an identity
- **THEN** the system establishes that user as the current application user

#### Scenario: Established user drives conversation retrieval
- **WHEN** the current user is established
- **THEN** the system displays the conversations available to that user

#### Scenario: Unknown selected identity is rejected
- **WHEN** a REST request identifies a user that is not configured for the MVP
- **THEN** the backend rejects the request

#### Scenario: Selection is isolated per browser window
- **WHEN** separate browser windows select different configured users
- **THEN** changing the selection in one window does not change the other window's current user
