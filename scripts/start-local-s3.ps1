# Local-only S3 emulator for the browser image upload flow. Never use these test credentials in production.
$ErrorActionPreference = 'Continue'
$containerName = 'reused-demo-s3'
$image = 'motoserver/moto@sha256:91fd602a21f49cf9eb82fdf474015a3c131d40104c8297ea6a2ca920708ae32c'
$bucket = 'reused-demo-images'
$endpoint = 'http://127.0.0.1:5000'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required.' }
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { throw 'AWS CLI is required for local bucket setup.' }

$existing = docker container inspect $containerName --format '{{.Config.Image}}|{{.State.Running}}' 2>$null
if ($LASTEXITCODE -eq 0) {
    $parts = $existing -split '\|', 2
    if ($parts[0] -ne $image) { throw "Container $containerName exists with another image; leaving it untouched." }
    if ($parts[1] -ne 'true') {
        docker start $containerName | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restart the local S3 emulator.' }
    }
} else {
    docker run -d --name $containerName -p 127.0.0.1:5000:5000 $image | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start the local S3 emulator.' }
}

$oldAccessKey = $env:AWS_ACCESS_KEY_ID
$oldSecretKey = $env:AWS_SECRET_ACCESS_KEY
$oldSessionToken = $env:AWS_SESSION_TOKEN
$oldRegion = $env:AWS_DEFAULT_REGION
$oldMetadataDisabled = $env:AWS_EC2_METADATA_DISABLED
try {
    $env:AWS_ACCESS_KEY_ID = 'test'
    $env:AWS_SECRET_ACCESS_KEY = 'test'
    $env:AWS_SESSION_TOKEN = $null
    $env:AWS_DEFAULT_REGION = 'ap-northeast-2'
    $env:AWS_EC2_METADATA_DISABLED = 'true'
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        aws --endpoint-url=$endpoint s3api list-buckets *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Local S3 did not become ready within 30 seconds.' }

    aws --endpoint-url=$endpoint s3api head-bucket --bucket $bucket *> $null
    if ($LASTEXITCODE -ne 0) {
        aws --endpoint-url=$endpoint s3api create-bucket --bucket $bucket --create-bucket-configuration LocationConstraint=ap-northeast-2 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the local S3 bucket.' }
    }
    $corsPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'local-s3-cors.json')).Path.Replace('\', '/')
    aws --endpoint-url=$endpoint s3api put-bucket-cors --bucket $bucket --cors-configuration "file://$corsPath" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure local S3 CORS.' }
} finally {
    $env:AWS_ACCESS_KEY_ID = $oldAccessKey
    $env:AWS_SECRET_ACCESS_KEY = $oldSecretKey
    $env:AWS_SESSION_TOKEN = $oldSessionToken
    $env:AWS_DEFAULT_REGION = $oldRegion
    $env:AWS_EC2_METADATA_DISABLED = $oldMetadataDisabled
}

Write-Host 'Local S3 is ready for browser image testing.'
Write-Host "IMAGE_S3_BUCKET=$bucket"
Write-Host 'IMAGE_S3_REGION=ap-northeast-2'
Write-Host "IMAGE_S3_ENDPOINT=$endpoint"
Write-Host 'AWS_ACCESS_KEY_ID=test'
Write-Host 'AWS_SECRET_ACCESS_KEY=test'
