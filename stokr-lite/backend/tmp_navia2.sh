#!/bin/bash
# Increase maxOpenPositions, enable MIDCPNIFTY, lower min edges
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=maxOpenPositions&value=5'
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=midcpniftyEnabled&value=true'
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=finniftyEnabled&value=true'
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=niftyMinEdge&value=500'
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=bankniftyMinEdge&value=500'
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=midcpniftyMinEdge&value=300'
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=finniftyMinEdge&value=300'
curl -s 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings' | python3 -m json.tool
