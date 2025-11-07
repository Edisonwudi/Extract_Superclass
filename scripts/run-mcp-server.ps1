Param(
    [string]$JarPath,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ToolArgs
)

$scriptPath = $MyInvocation.MyCommand.Path
if (-not $scriptPath -or $scriptPath.Trim() -eq '') {
    Write-Error 'Unable to resolve script location (MyInvocation.MyCommand.Path is empty).'
    exit 1
}

$scriptDir = Split-Path -Parent $scriptPath
$projectDir = Split-Path -Parent $scriptDir

if (-not $JarPath -or $JarPath.Trim() -eq '') {
    $JarPath = Join-Path $projectDir 'target\extractsuperclass-mcp-server.jar'
}

$javaHomeCandidates = @(
    $env:EXTRACT_SUPERCLASS_JAVA_HOME,
    $env:JAVA_HOME_21,
    $env:JAVA_HOME
) | Where-Object { $_ -and $_.Trim() -ne '' } | Select-Object -Unique
Write-Verbose ("JAVA home candidates: " + ($javaHomeCandidates -join ', '))

$javaExe = $null
$firstError = $null
foreach ($javaHomePath in $javaHomeCandidates) {
    Write-Verbose "Checking Java home: $javaHomePath"
    $candidate = Join-Path $javaHomePath 'bin\java.exe'
    if (-not (Test-Path -Path $candidate -PathType Leaf)) {
        if (-not $firstError) {
            $firstError = "java.exe not found at $candidate"
        }
        continue
    }

    $versionOutput = & $candidate -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        if (-not $firstError) {
            $firstError = "java -version failed for $candidate"
        }
        continue
    }

    Write-Verbose ("java -version output:`n" + ($versionOutput -join "`n"))
    $firstLine = $versionOutput | Select-Object -First 1
    if ($firstLine -and $firstLine -match '"(?<major>\d+)\.') {
        $major = [int]$matches.major
        if ($major -eq 21) {
            $javaExe = $candidate
            break
        }
    }
}

if (-not $javaExe) {
    if ($firstError) {
        Write-Warning $firstError
    }
    Write-Error 'No Java 21 runtime detected. Set EXTRACT_SUPERCLASS_JAVA_HOME (or JAVA_HOME_21) to a JDK 21 installation.'
    exit 1
}

if (-not (Test-Path -Path $JarPath -PathType Leaf)) {
    Write-Error "Unable to find MCP server JAR at '$JarPath'. Build the project with 'mvn package'."
    exit 1
}

& $javaExe @('-jar', $JarPath) @ToolArgs
exit $LASTEXITCODE
