import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import client from '../api/client';

const ExecutionBrokerContext = createContext();

export function useGlobalExecutionBroker() {
  return useContext(ExecutionBrokerContext);
}

export function ExecutionBrokerProvider({ children }) {
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  const [liveBrokerPref, setLiveBrokerPref] = useState('ZERODHA');
  const [mofslModalOpen, setMofslModalOpen] = useState(false);

  useEffect(() => {
    client.get('/brokers/decoupled-routing')
      .then(res => {
        if (res.data?.executionBroker) {
          setExecutionBroker(res.data.executionBroker);
          if (res.data.executionBroker !== 'PAPER') {
            setLiveBrokerPref(res.data.executionBroker);
          }
        }
      })
      .catch(() => {});
  }, []);

  const changeExecutionBroker = useCallback((broker) => {
    setExecutionBroker(broker);
    if (broker !== 'PAPER') {
      setLiveBrokerPref(broker);
    }
    client.post('/brokers/decoupled-routing', { executionBroker: broker }).catch(() => {});
  }, []);

  return (
    <ExecutionBrokerContext.Provider value={{
      executionBroker,
      changeExecutionBroker,
      liveBrokerPref,
      setLiveBrokerPref,
      mofslModalOpen,
      setMofslModalOpen
    }}>
      {children}
    </ExecutionBrokerContext.Provider>
  );
}
