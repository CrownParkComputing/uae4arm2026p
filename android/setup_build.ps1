param(
    [switch]$Clean,
    [switch]$Install,
    [switch]$NoDaemon,
    [switch]$SkipDeviceCheck,
    [int]$PreferredJavaMajor = 21
)

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Get-JavaMajorVersion {
    param([string]$JavaExe)

    $oldEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $JavaExe -version 2>&1
        $text = ($lines | Out-String)
        if ([string]::IsNullOrWhiteSpace($text)) { return $null }

        if ($text -match 'version\s+"([0-9]+)(?:\.([0-9]+))?.*"') {
            $major = [int]$Matches[1]
            if ($major -eq 1 -and $Matches[2]) {
                return [int]$Matches[2]
            }
            return $major
        }
    } catch {
        return $null
    } finally {
        $ErrorActionPreference = $oldEap
    }

    return $null
}

function Add-JavaCandidate {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$PathValue
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) { return }
    $trimmed = $PathValue.Trim('"').Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed)) { return }
    if (-not (Test-Path $trimmed)) { return }
    if ($List -notcontains $trimmed) {
        $List.Add($trimmed)
    }
}

function Resolve-JavaHome {
    param([int]$PreferredMajor = 21)

    $candidates = New-Object 'System.Collections.Generic.List[string]'

    Add-JavaCandidate -List $candidates -PathValue $env:JAVA_HOME
    Add-JavaCandidate -List $candidates -PathValue $env:ANDROID_STUDIO_JDK
    Add-JavaCandidate -List $candidates -PathValue 'C:\Program Files\Android\Android Studio\jbr'
    Add-JavaCandidate -List $candidates -PathValue 'C:\Program Files\Android\Android Studio\jre'

    foreach ($jdkRoot in @(
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft',
        'C:\Program Files\Amazon Corretto',
        'C:\Program Files\Zulu'
    )) {
        if (Test-Path $jdkRoot) {
            Get-ChildItem -Path $jdkRoot -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Add-JavaCandidate -List $candidates -PathValue $_.FullName }
        }
    }

    $gradleJdks = Join-Path $env:USERPROFILE '.gradle\jdks'
    if (Test-Path $gradleJdks) {
        Get-ChildItem -Path $gradleJdks -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Add-JavaCandidate -List $candidates -PathValue $_.FullName }
    }

    $whereJava = $null
    try {
        $whereJava = & where.exe java 2>$null
    } catch { }
    if ($whereJava) {
        foreach ($javaPath in $whereJava) {
            if (-not [string]::IsNullOrWhiteSpace($javaPath)) {
                $javaHomeCandidate = Split-Path -Path (Split-Path -Path $javaPath -Parent) -Parent
                Add-JavaCandidate -List $candidates -PathValue $javaHomeCandidate
            }
        }
    }

    $exactHome = $null
    $bestCompatibleHome = $null
    $bestCompatibleMajor = -1
    $detected = New-Object 'System.Collections.Generic.List[string]'

    foreach ($candidateHome in $candidates) {
        $javaExe = Join-Path $candidateHome 'bin\\java.exe'
        if (-not (Test-Path $javaExe)) { continue }

        $major = Get-JavaMajorVersion -JavaExe $javaExe
        if ($null -eq $major) { continue }
        $detected.Add("$major => $candidateHome")

        if ($major -eq $PreferredMajor) {
            $exactHome = $candidateHome
            break
        }

        if ($major -lt $PreferredMajor -and $major -ge 17 -and $major -gt $bestCompatibleMajor) {
            $bestCompatibleMajor = $major
            $bestCompatibleHome = $candidateHome
        }
    }

    if ($exactHome) {
        return @{ Home = $exactHome; Major = $PreferredMajor }
    }

    if ($bestCompatibleHome) {
        return @{ Home = $bestCompatibleHome; Major = $bestCompatibleMajor }
    }

    if ($detected.Count -gt 0) {
        throw "No compatible Java <= $PreferredMajor found. Detected: $($detected -join '; ')"
    }

    throw 'No Java installation found. Install JDK 21 or configure JAVA_HOME.'
}

$java = Resolve-JavaHome -PreferredMajor $PreferredJavaMajor
$env:JAVA_HOME = $java.Home
$env:PATH = "$($env:JAVA_HOME)\bin;$($env:PATH)"

Write-Host "Using JAVA_HOME=$($env:JAVA_HOME) (Java $($java.Major))"

if (-not $SkipDeviceCheck) {
    try {
        Write-Host 'Checking adb devices...'
        & adb devices
    } catch {
        Write-Warning 'adb not available on PATH. Build can continue, install may fail.'
    }
}

$gradleArgs = @('--console=plain', 'assembleDebug', '--stacktrace')
if ($NoDaemon) {
    $gradleArgs = @('--no-daemon') + $gradleArgs
}
if ($Clean) {
    $gradleArgs = @('--console=plain', 'clean', 'assembleDebug', '--stacktrace')
    if ($NoDaemon) {
        $gradleArgs = @('--no-daemon') + $gradleArgs
    }
}

$log = Join-Path $PSScriptRoot 'gradle_setup_build.txt'
Remove-Item -Path $log -ErrorAction SilentlyContinue

$gradleBat = Join-Path $PSScriptRoot 'gradlew.bat'
$oldEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $gradleBat @gradleArgs *>&1 | Out-File -FilePath $log -Encoding utf8
$ErrorActionPreference = $oldEap

if ($LASTEXITCODE -ne 0) {
    Write-Error "Gradle build failed (exit $LASTEXITCODE). See: $log"
    exit $LASTEXITCODE
}

Write-Host "Build succeeded. Log: $log"

if (-not $Install) {
    exit 0
}

$apk = Get-ChildItem -Path (Join-Path $PSScriptRoot 'build\outputs\apk\debug') -Filter '*.apk' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $apk) {
    Write-Error 'No debug APK found after build.'
    exit 1
}

Write-Host "Installing APK: $($apk.FullName)"
& adb install -r "$($apk.FullName)"
$installExit = $LASTEXITCODE
if ($installExit -ne 0) {
    Write-Error "adb install failed (exit $installExit)."
    exit $installExit
}

Write-Host 'Install succeeded.'
exit 0
