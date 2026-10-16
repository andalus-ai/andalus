$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BundleRoot = (Resolve-Path (Join-Path $ScriptDir "..\..\..")).Path
$AndalusHome = if ($env:ANDALUS_HOME) { $env:ANDALUS_HOME } else { "$env:USERPROFILE\.andalus" }
$BundledAndalusHome = Join-Path $BundleRoot ".andalus"

if ((Test-Path $BundledAndalusHome) -and ($BundledAndalusHome -ne $AndalusHome)) {
    New-Item -ItemType Directory -Force -Path $AndalusHome | Out-Null
    Copy-Item -Path (Join-Path $BundledAndalusHome "*") -Destination $AndalusHome -Recurse -Force
}

$AndalusConfigDir = if ($env:ANDALUS_CONFIG_DIR) { $env:ANDALUS_CONFIG_DIR } else { Join-Path $AndalusHome "config" }
$AndalusLogDir = if ($env:ANDALUS_LOG_DIR) { $env:ANDALUS_LOG_DIR } else { Join-Path $AndalusHome "logs" }
$AndalusServerLogDir = if ($env:ANDALUS_SERVER_LOG_DIR) { $env:ANDALUS_SERVER_LOG_DIR } else { Join-Path $AndalusLogDir "server" }
$AndalusLogFilePath = if ($env:ANDALUS_LOG_FILE_PATH) { $env:ANDALUS_LOG_FILE_PATH } else { Join-Path $AndalusServerLogDir "server.log" }
$AndalusPluginsDir = if ($env:ANDALUS_PLUGINS_DIR) { $env:ANDALUS_PLUGINS_DIR } else { Join-Path $AndalusHome "plugins" }
$AndalusSecretsDir = if ($env:ANDALUS_SECRETS_DIR) { $env:ANDALUS_SECRETS_DIR } else { Join-Path $AndalusHome "secrets" }
$AndalusModelsDir = if ($env:ANDALUS_MODELS_DIR) { $env:ANDALUS_MODELS_DIR } else { Join-Path $AndalusHome "models" }
$AndalusMcpDir = if ($env:ANDALUS_MCP_DIR) { $env:ANDALUS_MCP_DIR } else { Join-Path $AndalusHome "mcp" }
$AndalusRunDir = if ($env:ANDALUS_RUN_DIR) { $env:ANDALUS_RUN_DIR } else { Join-Path $AndalusHome "run" }
$AndalusVectorDir = if ($env:ANDALUS_VECTOR_DIR) { $env:ANDALUS_VECTOR_DIR } else { Join-Path $AndalusHome "vector" }
$AqliHome = if ($env:ANDALUS_AQLI_HOME) { $env:ANDALUS_AQLI_HOME } else { Join-Path $AndalusHome "aqli" }
$legacyAqliHome = if ($env:AQLI_HOME) { $env:AQLI_HOME } else { "$env:USERPROFILE\.aqli" }

try {
    New-Item -ItemType Directory -Force -Path $AqliHome | Out-Null
    $ResolvedAqliHome = $AqliHome
} catch {
    New-Item -ItemType Directory -Force -Path $legacyAqliHome | Out-Null
    $ResolvedAqliHome = $legacyAqliHome
}

$AndalusAqliModelsDir = if ($env:ANDALUS_AQLI_MODELS_DIR) { $env:ANDALUS_AQLI_MODELS_DIR } else { Join-Path $ResolvedAqliHome "models" }
$AndalusAqliStorageDir = if ($env:ANDALUS_AQLI_STORAGE_DIR) { $env:ANDALUS_AQLI_STORAGE_DIR } else { Join-Path $ResolvedAqliHome "storage" }

@(
    $AndalusConfigDir,
    $AndalusServerLogDir,
    $AndalusPluginsDir,
    $AndalusSecretsDir,
    $AndalusModelsDir,
    $AndalusMcpDir,
    $AndalusRunDir,
    $AndalusVectorDir,
    $AndalusAqliModelsDir,
    $AndalusAqliStorageDir
) | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }

$AndalusExecutable = if ($env:ANDALUS_EXECUTABLE) { $env:ANDALUS_EXECUTABLE } else { Join-Path $AndalusHome "bin\andalus.exe" }
if (-not (Test-Path $AndalusExecutable)) {
    throw "Andalus executable not found: $AndalusExecutable"
}

$ServiceName = if ($env:ANDALUS_SERVICE_NAME) { $env:ANDALUS_SERVICE_NAME } else { "Andalus" }
$envArgs = @(
    "ANDALUS_HOME=$AndalusHome",
    "ANDALUS_CONFIG_DIR=$AndalusConfigDir",
    "ANDALUS_LOG_DIR=$AndalusLogDir",
    "ANDALUS_SERVER_LOG_DIR=$AndalusServerLogDir",
    "ANDALUS_LOG_FILE_PATH=$AndalusLogFilePath",
    "ANDALUS_PLUGINS_DIR=$AndalusPluginsDir",
    "ANDALUS_SECRETS_DIR=$AndalusSecretsDir",
    "ANDALUS_MODELS_DIR=$AndalusModelsDir",
    "ANDALUS_MCP_DIR=$AndalusMcpDir",
    "ANDALUS_RUN_DIR=$AndalusRunDir",
    "ANDALUS_VECTOR_DIR=$AndalusVectorDir",
    "ANDALUS_AQLI_HOME=$AqliHome",
    "AQLI_HOME=$ResolvedAqliHome",
    "ANDALUS_AQLI_MODELS_DIR=$AndalusAqliModelsDir",
    "ANDALUS_AQLI_STORAGE_DIR=$AndalusAqliStorageDir"
)

if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    Stop-Service -Name $ServiceName -ErrorAction SilentlyContinue
    sc.exe delete $ServiceName | Out-Null
}

New-Service -Name $ServiceName -BinaryPathName "`"$AndalusExecutable`"" -DisplayName "Andalus" -StartupType Automatic
Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName" -Name Environment -Type MultiString -Value $envArgs
Start-Service -Name $ServiceName
Write-Host "Installed Andalus Windows service '$ServiceName'"
