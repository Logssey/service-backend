param(
    [int]$SmtpPort = 1026,
    [int]$UiPort = 8026,
    [string]$BinaryPath
)

$ErrorActionPreference = 'Stop'
$version = 'v1.31.3'
$expectedZipHash = '863E9502D4E0F14A78C0F91C5091797B1C7B7B7E3FC7E5EAB62E5770CE44B76E'
$expectedBinaryHash = 'EE0B025BC9F61E6856D6032128408EE6FE1627F510C118A4FA0FAA7BFDB7CD33'

if (-not $BinaryPath) {
    $installDir = Join-Path $env:LOCALAPPDATA "ReusedLocal\Mailpit\$version"
    $BinaryPath = Join-Path $installDir 'mailpit.exe'
    if (-not (Test-Path -LiteralPath $BinaryPath)) {
        New-Item -ItemType Directory -Path $installDir -Force | Out-Null
        $archive = Join-Path $installDir 'mailpit-windows-amd64.zip'
        if (-not (Test-Path -LiteralPath $archive)) {
            $url = "https://github.com/axllent/mailpit/releases/download/$version/mailpit-windows-amd64.zip"
            Invoke-WebRequest -Uri $url -OutFile $archive
        }
        $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
        if ($actualHash -ne $expectedZipHash) {
            throw 'Mailpit archive hash does not match the pinned official release. Remove only that archive after investigation.'
        }
        Expand-Archive -LiteralPath $archive -DestinationPath $installDir -Force
    }
}

if (-not (Test-Path -LiteralPath $BinaryPath -PathType Leaf)) {
    throw "Mailpit executable not found: $BinaryPath"
}
if ((Get-FileHash -LiteralPath $BinaryPath -Algorithm SHA256).Hash -ne $expectedBinaryHash) {
    throw 'Mailpit executable hash does not match the pinned official release.'
}

foreach ($port in @($SmtpPort, $UiPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Port $port is already in use; existing services were not changed."
    }
}

$process = Start-Process -FilePath $BinaryPath -ArgumentList @(
    "--smtp=127.0.0.1:$SmtpPort",
    "--listen=127.0.0.1:$UiPort",
    '--disable-version-check'
) -PassThru -WindowStyle Hidden

for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 250
    $process.Refresh()
    if ($process.HasExited) {
        throw "Mailpit exited during startup with code $($process.ExitCode)."
    }
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect('127.0.0.1', $SmtpPort)
        $client.ReceiveTimeout = 1000
        $buffer = [byte[]]::new(128)
        $count = $client.GetStream().Read($buffer, 0, $buffer.Length)
        $banner = [System.Text.Encoding]::ASCII.GetString($buffer, 0, $count)
        if ($banner.StartsWith('220 ')) {
            Write-Output "Mailpit PID=$($process.Id), SMTP=127.0.0.1:$SmtpPort, UI=http://127.0.0.1:$UiPort"
            exit 0
        }
    }
    catch [System.Net.Sockets.SocketException] {
        # The process may not have opened the port yet.
    }
    finally {
        $client.Dispose()
    }
}

throw 'Mailpit did not provide an SMTP greeting within five seconds.'
