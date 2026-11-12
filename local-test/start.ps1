# AgentCraft: server Minecraft di prova sul tuo PC (Windows). Avviato da start.bat.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-Location -LiteralPath $PSScriptRoot

$PurpurBuild = 2633
$PurpurMd5 = '3f3c933f2d518af98571c7461fa42278'
$PurpurUrl = "https://api.purpurmc.org/v2/purpur/26.2/$PurpurBuild/download"
$Ram = if ($env:RAM) { $env:RAM } else { '2G' }

function Stop-WithMessage([string]$Text) {
    Write-Host $Text -ForegroundColor Yellow
    Read-Host 'Premi Invio per chiudere'
    exit 1
}

# 1. Java 25
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Stop-WithMessage "Java non trovato. Installa Java 25 (Temurin, file .msi): https://adoptium.net/temurin/releases/?version=25"
}
$versionText = (& cmd /c 'java -version 2>&1') | Out-String
if ($versionText -notmatch 'version "(\d+)') { Stop-WithMessage "Non riesco a leggere la versione di Java:`n$versionText" }
if ([int]$Matches[1] -lt 25) {
    Stop-WithMessage "Serve Java 25 o piu' recente (trovato: $($Matches[1])). Installa Temurin 25: https://adoptium.net/temurin/releases/?version=25"
}

# 2. Purpur 26.2 (scaricato una volta e verificato)
function Get-Md5([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm MD5).Hash.ToLowerInvariant() }
if (-not (Test-Path purpur.jar) -or (Get-Md5 'purpur.jar') -ne $PurpurMd5) {
    Write-Host "Scarico Purpur 26.2 build $PurpurBuild..."
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $PurpurUrl -OutFile 'purpur.jar.part' -UseBasicParsing
    if ((Get-Md5 'purpur.jar.part') -ne $PurpurMd5) {
        Remove-Item 'purpur.jar.part'
        Stop-WithMessage "Il file scaricato non corrisponde all'impronta ufficiale: interrotto."
    }
    Move-Item -Force 'purpur.jar.part' 'purpur.jar'
}

# 3. EULA di Minecraft: la accetti tu
if (-not ((Test-Path eula.txt) -and (Select-String -Path eula.txt -Pattern 'eula=true' -Quiet))) {
    Write-Host "Per avviare un server Minecraft devi accettare l'EULA: https://aka.ms/MinecraftEULA"
    $answer = Read-Host 'La accetti? [s/N]'
    if ($answer -match '^(s|si|y)') { Set-Content -Path eula.txt -Value 'eula=true' -Encoding ascii }
    else { Stop-WithMessage 'Senza EULA il server non parte.' }
}

# 4. Impostazioni di un mondo di prova (solo la prima volta)
if (-not (Test-Path server.properties)) {
    @(
        'motd=AgentCraft - server di prova',
        'online-mode=true',
        'server-port=25565',
        'level-name=mondo-prova',
        'level-type=minecraft\:flat',
        'generate-structures=false',
        'spawn-protection=0',
        'difficulty=peaceful',
        'view-distance=8',
        'simulation-distance=6'
    ) | Set-Content -Path server.properties -Encoding ascii
}

Write-Host ''
Write-Host "Avvio il server. Quando vedi 'Done', entra in Minecraft (26.2) su: localhost" -ForegroundColor Green
Write-Host 'Per darti i permessi scrivi qui:  op <il tuo nome>      Per fermarlo:  stop' -ForegroundColor Green
Write-Host ''
while ($true) {
    & java "-Xms$Ram" "-Xmx$Ram" -jar purpur.jar --nogui
    # AgentCraft leaves this file when it restarts the server to install an updated plugin
    if (-not (Test-Path '.agentcraft-restart')) { break }
    Remove-Item '.agentcraft-restart' -Force
    Write-Host 'Riavvio richiesto da AgentCraft: riparto...' -ForegroundColor Yellow
}
Read-Host 'Server fermo. Premi Invio per chiudere'
