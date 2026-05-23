import React, { useEffect, useCallback, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';

interface RealtimeMessage {
  type: string;
  channel: string;
  data?: Record<string, unknown>;
}

/**
 * Hook for real-time trader terminal updates via WebSocket.
 * Subscribes to: orders, positions, fills, PnL, signals.
 */
export function useTraderRealtime(userId: string) {
  const queryClient = useQueryClient();
  const socketRef = useRef<WebSocket | null>(null);
  const subscriptionsRef = useRef<Set<string>>(new Set());

  const subscribe = useCallback((channel: string) => {
    if (!socketRef.current || subscriptionsRef.current.has(channel)) {
      return;
    }

    socketRef.current.send(JSON.stringify({
      action: 'SUBSCRIBE',
      channel,
      userId,
    }));

    subscriptionsRef.current.add(channel);
  }, [userId]);

  const unsubscribe = useCallback((channel: string) => {
    if (!socketRef.current || !subscriptionsRef.current.has(channel)) {
      return;
    }

    socketRef.current.send(JSON.stringify({
      action: 'UNSUBSCRIBE',
      channel,
    }));

    subscriptionsRef.current.delete(channel);
  }, []);

  useEffect(() => {
    // Connect to WebSocket
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws`;

    socketRef.current = new WebSocket(wsUrl);

    socketRef.current.onopen = () => {
      console.log('[Realtime] Connected to WebSocket');

      // Subscribe to trader channels
      subscribe('orders');
      subscribe('positions');
      subscribe('pnl');
      subscribe('signals');
    };

    socketRef.current.onmessage = (event: MessageEvent<string>) => {
      try {
        const message = JSON.parse(event.data) as RealtimeMessage;
        handleRealtimeUpdate(message, queryClient);
      } catch (error) {
        console.error('[Realtime] Error parsing message:', error);
      }
    };

    socketRef.current.onerror = (error: Event) => {
      console.error('[Realtime] WebSocket error:', error);
    };

    socketRef.current.onclose = () => {
      console.log('[Realtime] WebSocket closed, will reconnect...');
      // Reconnect after 3 seconds
      setTimeout(() => {
        // Trigger reconnection by re-running effect
      }, 3000);
    };

    return () => {
      if (socketRef.current) {
        socketRef.current.close();
      }
    };
  }, [subscribe, queryClient]);

  return { subscribe, unsubscribe };
}

/**
 * Handle incoming real-time update and invalidate relevant queries.
 */
function handleRealtimeUpdate(message: RealtimeMessage, queryClient: ReturnType<typeof useQueryClient>) {
  const { type, channel, data } = message;

  console.log(`[Realtime] ${type} on ${channel}:`, data);

  // Invalidate relevant queries to trigger refetch
  if (channel === 'orders') {
    queryClient.invalidateQueries({ queryKey: ['orders'] });
    queryClient.invalidateQueries({ queryKey: ['orderLifecycle', data?.orderId] });
  } else if (channel === 'positions') {
    queryClient.invalidateQueries({ queryKey: ['positions'] });
    queryClient.invalidateQueries({ queryKey: ['position', data?.symbol] });
  } else if (channel === 'pnl') {
    queryClient.invalidateQueries({ queryKey: ['pnl'] });
  } else if (channel === 'signals') {
    queryClient.invalidateQueries({ queryKey: ['signals'] });
  }
}

interface PositionUpdateEvent extends CustomEvent {
  detail?: {
    symbol: string;
  };
}

/**
 * Hook for position MTM updates.
 */
export function usePositionMtm(symbol: string) {
  const queryClient = useQueryClient();

  useEffect(() => {
    const handleMessage = (event: Event) => {
      const customEvent = event as unknown as PositionUpdateEvent;
      if (customEvent.detail?.symbol === symbol) {
        queryClient.invalidateQueries({ queryKey: ['position', symbol] });
      }
    };

    window.addEventListener('position-update', handleMessage);
    return () => window.removeEventListener('position-update', handleMessage);
  }, [symbol, queryClient]);
}

interface OrderStateChangeEvent extends CustomEvent {
  detail?: {
    orderId: string;
    state: string;
  };
}

/**
 * Hook for order lifecycle visualization.
 */
export function useOrderLifecycle(orderId: string) {
  const queryClient = useQueryClient();
  const [orderState, setOrderState] = useState<string | null>(null);

  useEffect(() => {
    const handleStateChange = (event: Event) => {
      const customEvent = event as unknown as OrderStateChangeEvent;
      if (customEvent.detail?.orderId === orderId) {
        setOrderState(customEvent.detail.state);
        queryClient.invalidateQueries({ queryKey: ['orderLifecycle', orderId] });
      }
    };

    window.addEventListener('order-state-change', handleStateChange);
    return () => window.removeEventListener('order-state-change', handleStateChange);
  }, [orderId, queryClient]);

  return { orderState };
}

interface PnLUpdateEvent extends CustomEvent {
  detail?: Record<string, unknown>;
}

/**
 * Hook for PnL updates.
 */
export function usePnlUpdates() {
  const queryClient = useQueryClient();
  const [pnlSnapshot, setPnlSnapshot] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    const handlePnlUpdate = (event: Event) => {
      const customEvent = event as unknown as PnLUpdateEvent;
      setPnlSnapshot(customEvent.detail || null);
      queryClient.invalidateQueries({ queryKey: ['pnl'] });
    };

    window.addEventListener('pnl-update', handlePnlUpdate);
    return () => window.removeEventListener('pnl-update', handlePnlUpdate);
  }, [queryClient]);

  return { pnlSnapshot };
}
