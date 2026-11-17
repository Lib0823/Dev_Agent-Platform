#Requires -Version 7.0
<#
.SYNOPSIS
  Installs a portable preview runtime (JDK 21 + Maven + Tomcat 10.1) under <repo>\tools
  and (re)creates the Tomcat CATALINA_BASE instance used to preview the `shop` WAR.

.DESCRIPTION
  Idempotent: downloads are skipped when the target directory already exists; the
  CATALINA_BASE config (tools\tomcat-preview\conf, bin\setenv.bat) is re-created on
  every run. Nothing outside <repo>\tools is touched (no PATH / registry changes).

  Layout produced:
    tools\dl\              downloaded archives (kept for re-runs)
    tools\jdk-21\          Temurin JDK 21 (JAVA_HOME)
    tools\maven\           Apache Maven 3.9.x
    tools\tomcat\          Apache Tomcat 10.1.x (CATALINA_HOME)
    tools\tomcat-preview\  CATALINA_BASE: conf\ logs\ temp\ webapps\ work\ bin\setenv.bat

.EXAMPLE
  pwsh -File scripts\setup-preview.ps1
  pwsh -File scripts\setup-preview.ps1 -HttpPort 8790 -ShutdownPort 8795 -TomcatVersion 10.1.61
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),   # repo root (parent of scripts\)
    [int]$HttpPort = 8790,
    [int]$ShutdownPort = 8795,
    [string]$MavenVersion = '3.9.16',
    [string]$TomcatVersion = '10.1.61',
    [string]$JavaOpts = '-Xmx1g -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Tools  = Join-Path $Root 'tools'
$Dl     = Join-Path $Tools 'dl'
$JdkDir = Join-Path $Tools 'jdk-21'
$MvnDir = Join-Path $Tools 'maven'
$CatHome = Join-Path $Tools 'tomcat'
$CatBase = Join-Path $Tools 'tomcat-preview'

New-Item -ItemType Directory -Force $Tools, $Dl | Out-Null

function Get-File([string]$Url, [string]$Dest) {
    if (Test-Path $Dest) { Write-Host "  [skip] $(Split-Path -Leaf $Dest) already downloaded"; return }
    Write-Host "  [get ] $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing
}

function Assert-Hash([string]$File, [string]$Expected, [string]$Algo) {
    $actual = (Get-FileHash -Path $File -Algorithm $Algo).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        Remove-Item $File -Force
        throw "Checksum mismatch for $File ($Algo). Deleted; re-run."
    }
    Write-Host "  [ok  ] $Algo verified: $(Split-Path -Leaf $File)"
}

# Extract a zip whose single top-level folder becomes $Target.
function Expand-Single([string]$Zip, [string]$Target) {
    $tmp = Join-Path $Tools ('extract-' + [IO.Path]::GetRandomFileName())
    Expand-Archive -Path $Zip -DestinationPath $tmp -Force
    $top = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $top) { throw "Unexpected archive layout: $Zip" }
    Move-Item $top.FullName $Target
    Remove-Item $tmp -Recurse -Force
}

# ---------------------------------------------------------------- JDK 21 (Temurin)
Write-Host "== JDK 21 -> $JdkDir"
if (Test-Path (Join-Path $JdkDir 'bin\java.exe')) {
    Write-Host "  [skip] present"
} else {
    $api = 'https://api.adoptium.net/v3/assets/latest/21/hotspot?os=windows&architecture=x64&image_type=jdk&vendor=eclipse'
    $asset = (Invoke-RestMethod -Uri $api -UseBasicParsing) | Where-Object { $_.binary.package.name -like '*.zip' } | Select-Object -First 1
    if (-not $asset) { throw "Adoptium API returned no JDK 21 zip asset" }
    Write-Host "  release: $($asset.release_name)"
    $zip = Join-Path $Dl $asset.binary.package.name
    Get-File $asset.binary.package.link $zip
    Assert-Hash $zip $asset.binary.package.checksum 'SHA256'
    Expand-Single $zip $JdkDir
}

# ---------------------------------------------------------------- Maven
Write-Host "== Maven $MavenVersion -> $MvnDir"
if (Test-Path (Join-Path $MvnDir 'bin\mvn.cmd')) {
    Write-Host "  [skip] present"
} else {
    $name = "apache-maven-$MavenVersion-bin.zip"
    $base = "https://dlcdn.apache.org/maven/maven-3/$MavenVersion/binaries/$name"
    $zip = Join-Path $Dl $name
    Get-File $base $zip
    $sha = ((Invoke-WebRequest -Uri "$base.sha512" -UseBasicParsing).Content.Trim() -split '\s+')[0]
    Assert-Hash $zip $sha 'SHA512'
    Expand-Single $zip $MvnDir
}

# ---------------------------------------------------------------- Tomcat (CATALINA_HOME)
Write-Host "== Tomcat $TomcatVersion -> $CatHome"
if (Test-Path (Join-Path $CatHome 'bin\catalina.bat')) {
    Write-Host "  [skip] present"
} else {
    $name = "apache-tomcat-$TomcatVersion-windows-x64.zip"
    $major = $TomcatVersion.Split('.')[0]
    $base = "https://dlcdn.apache.org/tomcat/tomcat-$major/v$TomcatVersion/bin/$name"
    $zip = Join-Path $Dl $name
    Get-File $base $zip
    $sha = ((Invoke-WebRequest -Uri "$base.sha512" -UseBasicParsing).Content.Trim() -split '\s+')[0]
    Assert-Hash $zip $sha 'SHA512'
    Expand-Single $zip $CatHome
}

# ---------------------------------------------------------------- Ant (JspC = Ant Task; scripts/bin/jspcheck needs ant.jar)
$AntDir = Join-Path $Tools 'ant'
$AntVersion = '1.10.15'
Write-Host "== Ant $AntVersion (jar only, for jspcheck) -> $AntDir"
if (Test-Path (Join-Path $AntDir 'ant.jar')) {
    Write-Host "  [skip] present"
} else {
    New-Item -ItemType Directory -Force $AntDir | Out-Null
    $base = "https://repo1.maven.org/maven2/org/apache/ant/ant/$AntVersion/ant-$AntVersion.jar"
    $jar = Join-Path $AntDir 'ant.jar'
    Get-File $base $jar
    $sha = ((Invoke-WebRequest -Uri "$base.sha1" -UseBasicParsing).Content.Trim() -split '\s+')[0]
    Assert-Hash $jar $sha 'SHA1'
}

# ---------------------------------------------------------------- CATALINA_BASE instance
Write-Host "== CATALINA_BASE -> $CatBase (http $HttpPort, shutdown $ShutdownPort)"
foreach ($d in 'logs', 'temp', 'webapps', 'work', 'bin') {
    New-Item -ItemType Directory -Force (Join-Path $CatBase $d) | Out-Null
}
$confDst = Join-Path $CatBase 'conf'
if (Test-Path $confDst) { Remove-Item $confDst -Recurse -Force }
Copy-Item (Join-Path $CatHome 'conf') $confDst -Recurse

$serverXml = Join-Path $confDst 'server.xml'
$xml = Get-Content $serverXml -Raw -Encoding UTF8
$xml = $xml -replace '<Server port="8005" shutdown="SHUTDOWN">', "<Server port=`"$ShutdownPort`" shutdown=`"SHUTDOWN`">"
$xml = $xml -replace '(?s)<Connector port="8080" protocol="HTTP/1.1"\s+connectionTimeout="20000"\s+redirectPort="8443"\s+maxParameterCount="1000"\s+/>',
    "<Connector port=`"$HttpPort`" protocol=`"HTTP/1.1`"`r`n               address=`"127.0.0.1`"`r`n               connectionTimeout=`"20000`"`r`n               URIEncoding=`"UTF-8`"`r`n               maxParameterCount=`"1000`"`r`n               />"
# AJP connector: stock 10.1 server.xml ships it commented out. If a template ever has it
# active (not preceded by an open comment), comment it out.
$noComments = $xml -replace '(?s)<!--.*?-->', ''
if ($noComments -match '<Connector protocol="AJP/1.3"') {
    $xml = $xml -replace '(?s)(<Connector protocol="AJP/1.3".*?/>)', '<!-- $1 -->'
}
if ($xml -notmatch "port=`"$HttpPort`"" -or $xml -notmatch "port=`"$ShutdownPort`"") {
    throw "server.xml patch failed (template changed?) - inspect $serverXml"
}
[IO.File]::WriteAllText($serverXml, $xml, [Text.UTF8Encoding]::new($false))

$setenv = @"
@echo off
REM Generated by scripts\setup-preview.ps1 - JVM options for the preview instance.
set "JAVA_OPTS=%JAVA_OPTS% $JavaOpts"
set "CATALINA_OPTS=%CATALINA_OPTS% -Dpreview.instance=shop"
"@
[IO.File]::WriteAllText((Join-Path $CatBase 'bin\setenv.bat'), $setenv, [Text.ASCIIEncoding]::new())

# ---------------------------------------------------------------- summary
$env:JAVA_HOME = $JdkDir
Write-Host ""
Write-Host "JAVA_HOME     = $JdkDir"
Write-Host "MAVEN_HOME    = $MvnDir"
Write-Host "CATALINA_HOME = $CatHome"
Write-Host "CATALINA_BASE = $CatBase"
& (Join-Path $JdkDir 'bin\java.exe') -version 2>&1 | Select-Object -First 1
& (Join-Path $MvnDir 'bin\mvn.cmd') -v 2>&1 | Select-Object -First 1
Select-String -Path (Join-Path $CatHome 'RELEASE-NOTES') -Pattern 'Apache Tomcat Version' | Select-Object -First 1 | ForEach-Object { $_.Line.Trim() }
Write-Host "done."
