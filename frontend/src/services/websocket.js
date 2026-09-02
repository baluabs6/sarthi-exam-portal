import { Client } from '@stomp/stompjs'
import SockJS from 'sockjs-client'

/**
 * Opens a STOMP-over-SockJS connection to queue-service and subscribes
 * to live position updates for a single queue ticket.
 *
 * Backend broadcasts to: /topic/queue/{queueId}
 * Payload shape: { position, aheadOfYou, estimatedWaitSeconds, status, admitted, resultReady }
 */
export function subscribeToQueue(queueId, { onUpdate, onAdmitted, onConnectionChange }) {
  const client = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      onConnectionChange?.('connected')
      client.subscribe(`/topic/queue/${queueId}`, (message) => {
        const payload = JSON.parse(message.body)
        if (payload.admitted) {
          onAdmitted?.(payload)
        } else {
          onUpdate?.(payload)
        }
      })
    },
    onWebSocketClose: () => onConnectionChange?.('disconnected'),
    onStompError: () => onConnectionChange?.('error'),
  })

  client.activate()
  return () => client.deactivate()
}
