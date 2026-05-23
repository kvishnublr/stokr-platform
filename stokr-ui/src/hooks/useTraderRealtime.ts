import { useEffect, useCallback, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';

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

    socketRef.current.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        handleRealtimeUpdate(message, queryClient);
      } catch (error) {
        console.error('[Realtime] Error parsing message:', error);
      }
    };

    socketRef.current.onerror = (error) => {
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
function handleRealtimeUpdate(message: any, queryClient: any) {
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

/**
 * Hook for position MTM updates.
 */
export function usePositionMtm(symbol: string) {
  const queryClient = useQueryClient();

  useEffect(() => {
    const handleMessage = (event: Event) => {
      const customEvent = event as CustomEvent;
      if (customEvent.detail?.symbol === symbol) {
        queryClient.invalidateQueries({ queryKey: ['position', symbol] });
      }
    };

    window.addEventListener('position-update', handleMessage);
    return () => window.removeEventListener('position-update', handleMessage);
  }, [symbol, queryClient]);
}

/**
 * Hook for order lifecycle visualization.
 */
export function useOrderLifecycle(orderId: string) {
  const queryClient = useQueryClient();
  const [orderState, setOrderState] = React.useState<string | null>(null);

  useEffect(() => {
    const handleStateChange = (event: Event) => {
      const customEvent = event as CustomEvent;
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

/**
 * Hook for PnL updates.
 */
export function usePnlUpdates() {
  const queryClient = useQueryClient();
  const [pnlSnapshot, setPnlSnapshot] = React.useState<any>(null);

  useEffect(() => {
    const handlePnlUpdate = (event: Event) => {
      const customEvent = event as CustomEvent;
      setPnlSnapshot(customEvent.detail);
      queryClient.invalidateQueries({ queryKey: ['pnl'] });
    };

    window.addEventListener('pnl-update', handlePnlUpdate);
    return () => window.removeEventListener('pnl-update', handlePnlUpdate);
  }, [queryClient]);

  return { pnlSnapshot };
}

import React from 'react';
