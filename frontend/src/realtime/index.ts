/**
 * Realtime layer public surface (task 6.1). Task 6.2+ imports from this
 * barrel only; the socket module itself never imports React.
 */

export {
  createChatSocket,
  type ChatSocket,
  type ChatSocketError,
  type ChatSocketOptions,
  type ConnectionState,
  type ReconnectConfig,
} from './chatSocket'
export {
  parseInboundEvent,
  serializeCommand,
  ERROR_CODES,
  type ErrorCode,
  type ErrorEvent,
  type InboundEvent,
  type MessageAckEvent,
  type NewMessageEvent,
  type OutboundCommand,
  type SendMessageCommand,
} from './protocol'
