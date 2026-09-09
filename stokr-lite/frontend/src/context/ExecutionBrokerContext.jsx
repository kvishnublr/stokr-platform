import React, { createContext, useContext, useState, useEffect } from 'react';
import client from '../api/client';


const ExecutionBrokerContext = createContext();

export function useGlobalExecutionBroker() {
  return useContext(ExecutionBrokerContext);
}

export function ExecutionBrokerProvider({ children }) {
  const [executionBroker, setExecutionBroker] = useState('PAPER');
  const [liveBrokerPref, setLiveBrokerPref] = useState('ZERODHA');
  const [mofslModalOpen, setMofslModalOpen] = useState(false);
  
  const changeExecutionBroker = (broker) => {
    setExecutionBroker(broker);
    if (broker !== 'PAPER') {
      setLiveBrokerPref(broker);
    }
  };

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
