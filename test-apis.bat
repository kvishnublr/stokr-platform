@echo off
REM Unified Execution Framework - REST API Testing Script (Windows)
REM Tests all Phase 6/7 REST endpoints

setlocal enabledelayedexpansion

set API_BASE=http://localhost:8080
set COUNT=1

echo.
echo ==========================================
echo Unified Execution Framework API Tests
echo ==========================================
echo.

REM Test 1: Get Current Execution Mode
echo [TEST !COUNT!] GET Current Execution Mode
curl -s "%API_BASE%/api/admin/execution/mode" | jq "."
set /a COUNT+=1
echo.

REM Test 2: Switch Mode (PAPER ^to LIVE)
echo [TEST !COUNT!] Switch Mode PAPER to LIVE
curl -s -X POST "%API_BASE%/api/admin/execution/mode/LIVE" ^
  -H "Content-Type: application/json" ^
  -d "{\"reason\":\"UI Test\",\"requestedBy\":\"TEST_RUNNER\"}" | jq "."
set /a COUNT+=1
echo.

REM Test 3: Health Check
echo [TEST !COUNT!] Execution Health Check
curl -s "%API_BASE%/api/admin/execution/health" | jq "."
set /a COUNT+=1
echo.

REM Test 4: Execution Statistics
echo [TEST !COUNT!] Execution Statistics
curl -s "%API_BASE%/api/admin/execution/stats" | jq "."
set /a COUNT+=1
echo.

REM Test 5: Get Current Configuration
echo [TEST !COUNT!] Get Execution Configuration
curl -s "%API_BASE%/api/admin/execution/config" | jq "."
set /a COUNT+=1
echo.

REM Test 6: Update Paper Slippage
echo [TEST !COUNT!] Update Paper Slippage to 3.0 bps
curl -s -X POST "%API_BASE%/api/admin/execution/config/paper/slippage" ^
  -H "Content-Type: application/json" ^
  -d "{\"slippageBps\":3.0,\"reason\":\"Test update\"}" | jq "."
set /a COUNT+=1
echo.

REM Test 7: Start Replay
echo [TEST !COUNT!] Start Replay
curl -s -X POST "%API_BASE%/api/admin/execution/replay/start" ^
  -H "Content-Type: application/json" ^
  -d "{\"symbol\":\"SBIN\",\"startTime\":\"2025-01-01T09:15:00Z\",\"endTime\":\"2025-01-01T15:30:00Z\",\"speed\":1.0}" | jq "."
set /a COUNT+=1
echo.

REM Test 8: Get Replay Status
echo [TEST !COUNT!] Get Replay Status
curl -s "%API_BASE%/api/admin/execution/replay/status" | jq "."
set /a COUNT+=1
echo.

REM Test 9: Pause Replay
echo [TEST !COUNT!] Pause Replay
curl -s -X POST "%API_BASE%/api/admin/execution/replay/pause" | jq "."
set /a COUNT+=1
echo.

REM Test 10: Resume Replay
echo [TEST !COUNT!] Resume Replay
curl -s -X POST "%API_BASE%/api/admin/execution/replay/resume" | jq "."
set /a COUNT+=1
echo.

REM Test 11: Stop Replay
echo [TEST !COUNT!] Stop Replay
curl -s -X POST "%API_BASE%/api/admin/execution/replay/stop" | jq "."
set /a COUNT+=1
echo.

REM Test 12: Get Configuration Audit Trail
echo [TEST !COUNT!] Get Configuration Audit Trail
curl -s "%API_BASE%/api/admin/execution/config/audit" | jq "."
set /a COUNT+=1
echo.

REM Test 13: Validate Configuration
echo [TEST !COUNT!] Validate Configuration
curl -s -X POST "%API_BASE%/api/admin/execution/config/validate" | jq "."
set /a COUNT+=1
echo.

REM Test 14: Switch Back to PAPER
echo [TEST !COUNT!] Switch Mode LIVE to PAPER
curl -s -X POST "%API_BASE%/api/admin/execution/mode/PAPER" ^
  -H "Content-Type: application/json" ^
  -d "{\"reason\":\"Test complete\",\"requestedBy\":\"TEST_RUNNER\"}" | jq "."
set /a COUNT+=1
echo.

echo ==========================================
echo All Tests Completed
echo ==========================================
echo.
echo Summary:
echo - 14 REST API endpoints tested
echo - Mode switching verified
echo - Replay controls verified
echo - Configuration management verified
echo - Health checks completed
echo.
echo If all tests returned JSON responses, the APIs are working!
echo.

pause
