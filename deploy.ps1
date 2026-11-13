# build + deploy Ultracraft (dll + jar), print hashes
# Any failed step stops the whole script: a build that fails but still copies and exits 0
# is how a stale dll reached the game once already.
$root = 'C:\Minecraft\Ultracraft-mod\work\uc-publish-v1.0.12'
$ver  = '1.1.3'
$game = 'C:\SteamLibrary\steamapps\common\ULTRAKILL'

# An inherited PATHEXT without .EXE makes every bare command name unresolvable here, so restore
# the standard list before resolving anything (dotnet, cmd and gradle all depend on it).
$env:PATHEXT = '.COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC'

# pwsh may not have dotnet on PATH even where bash does, so resolve it explicitly rather than
# letting the build fail for a reason that has nothing to do with the code.
$dotnet = (Get-Command dotnet -ErrorAction SilentlyContinue).Source
if (-not $dotnet -and (Test-Path 'C:\Program Files\dotnet\dotnet.exe')) { $dotnet = 'C:\Program Files\dotnet\dotnet.exe' }
if (-not $dotnet) { Write-Host 'FAILED: dotnet not found (PATH and C:\Program Files\dotnet both miss it)' -ForegroundColor Red; exit 1 }

function Stop-Here([string]$Name, $Out) {
    Write-Host "FAILED: $Name" -ForegroundColor Red
    $Out | Select-Object -Last 40 | ForEach-Object { Write-Host "  $_" }
    exit 1
}

# $LASTEXITCODE only survives in the scope that ran the native command, so each step is checked
# right here with no helper scriptblock in between.
Push-Location "$root\ultrabridge"
$out = & $dotnet build -c Release --no-restore -p:GameDir=$game 2>&1 | ForEach-Object { "$_" }
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Stop-Here 'dotnet build' $out }
$out | Select-String -Pattern 'error|错误' | ForEach-Object { Write-Host "  $($_.Line)" }
Write-Host 'ok: dotnet build' -ForegroundColor Green

Push-Location "$root\fabric"
$out = & cmd /c ".\gradlew.bat clean" 2>&1 | ForEach-Object { "$_" }
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Stop-Here 'gradle clean' $out }
Write-Host 'ok: gradle clean' -ForegroundColor Green

Push-Location "$root\fabric"
$out = & cmd /c ".\gradlew.bat build" 2>&1 | ForEach-Object { "$_" }
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Stop-Here 'gradle build' $out }
Write-Host 'ok: gradle build' -ForegroundColor Green

$jar = "$root\fabric\build\1.21.11\libs\ultracraft-$ver+1.21.11.jar"
$dll = "$root\ultrabridge\bin\Release\net472\UltraBridge.dll"
$mj  = "C:\Minecraft\.minecraft\versions\Ultracraft\mods\ultracraft-$ver+1.21.11.jar"
$md  = "$game\BepInEx\plugins\UltraBridge\UltraBridge.dll"

foreach ($f in @($jar, $dll)) {
    if (-not (Test-Path $f)) { Write-Host "FAILED: missing build output $f" -ForegroundColor Red; exit 1 }
}
Copy-Item $jar $mj -Force
Copy-Item $dll $md -Force

foreach ($f in @($jar, $dll, $mj, $md)) {
    "{0}  {1}  {2}" -f [System.IO.Path]::GetFileName($f), (Get-Item $f).Length, (Get-FileHash $f -Algorithm SHA256).Hash.Substring(0, 8)
}

# The jar embeds the dll, and a jar built before the dll was replaced silently ships the old one.
$z = [System.IO.Compression.ZipFile]::OpenRead($mj)
try {
    $e = $z.Entries | Where-Object { $_.FullName -eq 'ultrakill/plugin/UltraBridge.dll' }
    if (-not $e) { Write-Host 'FAILED: jar has no embedded UltraBridge.dll' -ForegroundColor Red; exit 1 }
    $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
} finally { $z.Dispose() }
$sha = [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash($ms.ToArray())) -replace '-', ''
"embedded dll {0}  {1}" -f $ms.Length, $sha.Substring(0, 8)

$built = (Get-FileHash $dll -Algorithm SHA256).Hash
if ($sha -ne $built) {
    Write-Host 'FAILED: the jar embeds a different dll than the one just built' -ForegroundColor Red
    Write-Host "  built    $($built.Substring(0,16))"
    Write-Host "  embedded $($sha.Substring(0,16))"
    Write-Host '  gradle did not pick up the new dll - delete fabric/build and rebuild.' -ForegroundColor Yellow
    exit 1
}
Write-Host 'ok: jar embeds the dll just built' -ForegroundColor Green

'mods dir:'
Get-ChildItem 'C:\Minecraft\.minecraft\versions\Ultracraft\mods\*.jar' | ForEach-Object { $_.Name }
