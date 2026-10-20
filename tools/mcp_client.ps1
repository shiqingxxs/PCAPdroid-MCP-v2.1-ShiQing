<#
  mcp_client.ps1 - minimal MCP client for the PCAPdroid embedded server.

  It speaks the MCP "streamable HTTP/SSE" transport:
    1) GET  $Base/sse      -> server emits  event: endpoint  +  data: <messages URL>
    2) POST <messages URL> -> send a JSON-RPC request (tools/list | tools/call)
    3) read the matching "data:" SSE line, strip the prefix, Trim() (removes the
       CRLF trailing '\\r'), parse the JSON, and unwrap result.content[0].text.

  Discovery principle: tools/list returns each tool's {name, description,
  inputSchema}. The client reads inputSchema to know what arguments to pass to
  tools/call, so you never have to hand-roll the JSON or re-parse the shape.

  Tools on the server:
    list-recent-connections   (no arguments) -> JSON array of connection objects
    get-connection-payload    (-IncrId, [-Direction], [-MaxBytes]) -> JSON object

  Example (run from the repo root):
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\mcp_client.ps1 -Command list
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\mcp_client.ps1 -Command conns
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\mcp_client.ps1 -Command conns -Raw
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\mcp_client.ps1 -Command conns | Where-Object { $_.l7_protocol -eq 'HTTP' }
    powershell -NoProfile -ExecutionPolicy Bypass -File tools\mcp_client.ps1 -Command payload -IncrId 786
#>
[CmdletBinding()]
param(
    [string]$Base = "http://<device-ip>:8080",
    [ValidateSet('list','conns','payload')]
    [string]$Command = 'conns',
    [int]$IncrId = -1,
    [ValidateSet('request','response','both')]
    [string]$Direction = 'both',
    [int]$MaxBytes = 32768,
    [switch]$Raw
)

$ErrorActionPreference = 'Stop'
$curl = "$env:SystemRoot\System32\curl.exe"

# Open an SSE event stream and POST a single JSON-RPC request to it, then return
# the parsed JSON-RPC response object ({jsonrpc, result, id}).
function Invoke-Mcp {
    param([string]$Method, $Params)

    $sseFile = Join-Path $env:TEMP ("mcp_" + [guid]::NewGuid().ToString('N') + ".txt")
    $proc = Start-Process $curl -ArgumentList '-sN','--max-time','30', ($Base + '/sse') `
        -RedirectStandardOutput $sseFile -NoNewWindow -PassThru
    try {
        # 1) obtain the session endpoint from the "endpoint" event
        $endpoint = $null
        for ($i = 0; $i -lt 40 -and -not $endpoint; $i++) {
            Start-Sleep -Milliseconds 250
            if (Test-Path $sseFile) {
                $t = Get-Content $sseFile -Raw -Encoding UTF8
                if ($t -match 'data:\s*(http://\S+?/messages\?sessionId=\S+)') {
                    $endpoint = $Matches[1]
                }
            }
        }
        if (-not $endpoint) { throw "No SSE endpoint from $Base/sse (server reachable? MCP enabled?)" }

        # 2) build the request body with ConvertTo-Json (no manual escaping)
        $id = Get-Random -Minimum 1000 -Maximum 2147000000
        $body = @{ jsonrpc = '2.0'; id = $id; method = $Method; params = $Params } |
                ConvertTo-Json -Depth 6 -Compress
        Invoke-WebRequest -Uri $endpoint -Method Post -ContentType 'application/json' `
            -Body $body -TimeoutSec 12 -UseBasicParsing | Out-Null

        # 3) wait for the matching SSE "data:" line, then strip prefix + Trim
        $line = $null
        for ($i = 0; $i -lt 24 -and -not $line; $i++) {
            Start-Sleep -Milliseconds 250
            $data = Get-Content $sseFile -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            $line = ($data -split "`n") |
                    Where-Object { $_ -like 'data:*' -and $_ -match ('"id":' + $id) } |
                    Select-Object -Last 1
        }
        if (-not $line) { throw "No SSE response for id=$id" }
        $line = ($line -replace '^data:\s*','').Trim()
        return ($line | ConvertFrom-Json)
    }
    finally {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        Remove-Item $sseFile -Force -ErrorAction SilentlyContinue
    }
}

switch ($Command) {
    'list' {
        $r = Invoke-Mcp 'tools/list' @{}
        $r.result.tools | ForEach-Object {
            $keys = ($_.inputSchema.properties.PSObject.Properties.Name) -join ', '
            [pscustomobject]@{ name = $_.name; args = $keys }
        } | Format-Table -AutoSize
        break
    }
    'conns' {
        $r = Invoke-Mcp 'tools/call' @{ name = 'list-recent-connections'; arguments = @{} }
        $text = $r.result.content[0].text
        if ($Raw) { $text; break }
        @($text | ConvertFrom-Json)
        break
    }
    'payload' {
        if ($IncrId -lt 0) { throw "payload requires -IncrId <int>" }
        $r = Invoke-Mcp 'tools/call' @{
            name = 'get-connection-payload'
            arguments = @{ incr_id = $IncrId; direction = $Direction; max_bytes = $MaxBytes }
        }
        $text = $r.result.content[0].text
        if ($Raw) { $text } else { $text | ConvertFrom-Json }
        break
    }
}
