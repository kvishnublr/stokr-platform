# Kill every process listening on the given TCP ports (Windows).
# Uses two strategies so it still works when Get-NetTCPConnection misses a row (common with IPv6 / older shells).
# Use from IntelliJ External Tool: powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ProjectDir%\scripts\kill-ports.ps1" 8080
param(
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [int[]] $Ports
)
$ErrorActionPreference = "SilentlyContinue"

function Get-PidsFromNetstat([int] $Port) {
    $pids = New-Object System.Collections.Generic.HashSet[int]
    netstat -ano | ForEach-Object {
        $line = $_
        if ($line -notmatch "LISTENING") { return }
        # Typical: TCP    0.0.0.0:8080    0.0.0.0:0    LISTENING    12345
        if ($line -match ":$Port\s+[^\s]+\s+LISTENING\s+(\d+)\s*$") {
            [void]$pids.Add([int]$Matches[1])
        }
    }
    $pids
}

foreach ($port in $Ports) {
    $pids = New-Object System.Collections.Generic.HashSet[int]

    foreach ($c in (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
        [void]$pids.Add([int]$c.OwningProcess)
    }
    foreach ($n in (Get-PidsFromNetstat $port)) {
        [void]$pids.Add($n)
    }

    foreach ($procId in $pids) {
        if ($procId -le 0) { continue }
        Write-Host "kill-ports: stopping PID $procId (port $port)"
        taskkill /F /PID $procId 2>$null | Out-Null
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
}

# Let the OS release the socket before Tomcat binds (TIME_WAIT / handle teardown).
Start-Sleep -Milliseconds 800
