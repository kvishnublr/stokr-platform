# Free default API port, then start Spring Boot (Maven must be on PATH).
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
& "$PSScriptRoot\kill-ports.ps1" 8080
Set-Location $repoRoot
mvn -pl stokr-bootstrap spring-boot:run
