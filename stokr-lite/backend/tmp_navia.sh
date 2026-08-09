#!/bin/bash
curl -s -X POST 'http://127.0.0.1:8081/api/option-arbitrage/auto-execute/settings?key=broker&value=NAVIA' | python3 -m json.tool
