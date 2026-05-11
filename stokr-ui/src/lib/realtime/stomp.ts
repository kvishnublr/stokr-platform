import SockJS from "sockjs-client";
import { Client, type IMessage } from "@stomp/stompjs";

export type RealtimeHandlers = {
  onOrder?: (msg: IMessage) => void;
  onPnl?: (msg: IMessage) => void;
  onStrategy?: (msg: IMessage) => void;
};

const activeClients = new Set<Client>();

/**
 * STOMP over SockJS (`/ws` endpoint). Tear down via returned deactivate().
 */
export function connectStomp(token: string | null, userId: string | null, handlers: RealtimeHandlers): () => void {
  const client = new Client({
    reconnectDelay: 5000,
    heartbeatIncoming: 15000,
    heartbeatOutgoing: 15000,
    webSocketFactory: () => {
      const qs = token ? `?access_token=${encodeURIComponent(token)}` : "";
      return new SockJS(`/ws${qs}`) as unknown as WebSocket;
    },
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    debug: () => {},
    onConnect: () => {
      if (!userId) return;
      if (handlers.onOrder) client.subscribe(`/topic/orders.${userId}`, handlers.onOrder);
      if (handlers.onPnl) client.subscribe(`/topic/pnl.${userId}`, handlers.onPnl);
      if (handlers.onStrategy) client.subscribe(`/topic/strategies.${userId}`, handlers.onStrategy);
    },
  });
  activeClients.add(client);
  client.activate();
  return () => {
    try {
      client.deactivate();
    } catch {
      /* ignore */
    }
    activeClients.delete(client);
  };
}

export function disconnectAllStomp(): void {
  activeClients.forEach((client) => {
    try {
      client.deactivate();
    } catch {
      /* ignore */
    }
  });
  activeClients.clear();
}
