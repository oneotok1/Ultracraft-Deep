# Ultracraft: one click starts ULTRAKILL (hidden, with the UltraBridge plugin) and Minecraft, which becomes V1 as
# soon as ULTRAKILL is ready. Closing Minecraft closes the ULTRAKILL it started. -World picks another save (tests).
param([string]$World = 'Ultracraft')
$ErrorActionPreference = 'Stop'
$root   = Split-Path -Parent $MyInvocation.MyCommand.Path
$fabric = Join-Path $root 'fabric'
$jdk    = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$log    = Join-Path $root 'launcher.log'
function Log([string]$m) { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $m" | Add-Content -Path $log }

try {
    # 1. ULTRAKILL with -ultracraft: it hides its window, loads the Sandbox and waits for Minecraft
    $startedUk = $false
    if (-not (Get-Process ULTRAKILL -ErrorAction SilentlyContinue)) {
        $steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamExe
        if (-not $steam) { $steam = 'C:\Program Files (x86)\Steam\steam.exe' }
        Log "starting ULTRAKILL via $steam"
        Start-Process -FilePath $steam -ArgumentList '-applaunch 1229490 -ultracraft -screen-fullscreen 0 -screen-width 1280 -screen-height 720'
        $startedUk = $true
    }

    # 2. Minecraft (Fabric + the ultracraft mod) straight into the Ultracraft world. Java is started directly with
    #    the launch files Loom wrote on the last build, which skips Gradle's start-up; Gradle is the fallback.
    $cfg    = Join-Path $fabric '.gradle\loom-cache\launch.cfg'
    $cpFile = Join-Path $fabric 'build\loom-cache\argFiles\runClient'
    if ((Test-Path $cfg) -and (Test-Path $cpFile)) {
        $mcArgs = @(
            "`"-Dfabric.dli.config=$cfg`"",
            '-Dfabric.dli.env=client',
            '-Dultracraft.noLaunch=true',
            '-Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient',
            "`"@$cpFile`"",
            'net.fabricmc.devlaunchinjector.Main',
            '--username', 'V1', '--quickPlaySingleplayer', $World
        ) -join ' '
        Log 'starting Minecraft'
        $mc = Start-Process -FilePath (Join-Path $jdk 'bin\javaw.exe') -ArgumentList $mcArgs -WorkingDirectory (Join-Path $fabric 'run') -PassThru
        $mc.WaitForExit()
        Log "Minecraft exited ($($mc.ExitCode))"
    } else {
        Log 'no Loom launch files yet: starting Minecraft through Gradle'
        $env:JAVA_HOME = $jdk
        Push-Location $fabric
        & .\gradlew.bat runClient
        Pop-Location
    }

    # 3. Minecraft closed: close the ULTRAKILL we started
    if ($startedUk) {
        $uk = Get-Process ULTRAKILL -ErrorAction SilentlyContinue
        if ($uk) {
            Log 'closing ULTRAKILL'
            [void]$uk.CloseMainWindow()
            if (-not $uk.WaitForExit(8000)) { $uk | Stop-Process -Force }
        }
    }
} catch {
    Log "error: $_"
    Add-Type -AssemblyName System.Windows.Forms
    [void][System.Windows.Forms.MessageBox]::Show("Ultracraft couldn't start:`n$_`n`nSee $log", 'Ultracraft')
}
