# Start local Redis (redis-windows) on port 6379 for Spring Boot JWT auth.
$ErrorActionPreference = "Stop"

$redisServer = Get-Command redis-server -ErrorAction SilentlyContinue
if (-not $redisServer) {
    Write-Host "redis-server not found. Install with:"
    Write-Host "  winget install taizod1024.redis-windows-fork"
    exit 1
}

$ping = redis-cli ping 2>$null
if ($ping -eq "PONG") {
    Write-Host "Redis is already running."
    exit 0
}

redis-server --port 6379 --daemonize yes
Start-Sleep -Seconds 1

if ((redis-cli ping 2>$null) -eq "PONG") {
    Write-Host "Redis started on localhost:6379"
} else {
    Write-Host "Failed to start Redis."
    exit 1
}
