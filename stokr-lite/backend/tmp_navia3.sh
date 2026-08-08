#!/bin/bash
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=broker&value=NAVIA' > /dev/null
curl -s 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings' | python3 -m json.tool
