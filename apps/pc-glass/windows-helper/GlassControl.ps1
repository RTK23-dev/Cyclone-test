#Requires -Version 5.1
<#
.SYNOPSIS
  Start/stop Cyclone PC Glass without flashing a console window.
#>
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-GlassRoot {
  return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

function Get-GlassLogDir {
  $dir = Join-Path $env:LOCALAPPDATA 'CyclonePcGlass\logs'
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  return $dir
}

function Write-GlassLog([string]$Message) {
  $line = '{0:u} {1}' -f (Get-Date), $Message
  Add-Content -Path (Join-Path (Get-GlassLogDir) 'helper.log') -Value $line -Encoding UTF8
}

function Test-GlassUp([int]$Port = 8000) {
  try {
    $req = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$Port/api/status")
    $req.Timeout = 2000
    $resp = $req.GetResponse()
    $resp.Close()
    return $true
  } catch {
    return $false
  }
}

function Start-GlassHidden {
  param([int]$Port = 8000)
  $root = Get-GlassRoot
  if (Test-GlassUp -Port $Port) {
    Write-GlassLog "Glass already up on :$Port"
    return @{ Ok = $true; Message = "Already running on http://127.0.0.1:$Port" }
  }

  $uvCmd = Get-Command uv -ErrorAction SilentlyContinue
  if (-not $uvCmd) {
    return @{ Ok = $false; Message = 'uv not found on PATH. Install https://docs.astral.sh/uv/' }
  }

  if (-not $env:CYCLONE_DEVICE_GATEWAY_URL) {
    $env:CYCLONE_DEVICE_GATEWAY_URL = 'http://127.0.0.1:8765'
  }
  if (-not $env:CYCLONE_SESSION_ID) {
    $env:CYCLONE_SESSION_ID = 'default-foreground'
  }
  $env:CYCLONE_CONNECTED = '1'

  Write-GlassLog "Starting Glass in $root (port $Port)"

  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $uvCmd.Source
  $psi.Arguments = "run python -m artemis ui --port $Port --no-open"
  $psi.WorkingDirectory = $root
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.EnvironmentVariables['CYCLONE_CONNECTED'] = '1'
  $psi.EnvironmentVariables['CYCLONE_DEVICE_GATEWAY_URL'] = $env:CYCLONE_DEVICE_GATEWAY_URL
  $psi.EnvironmentVariables['CYCLONE_SESSION_ID'] = $env:CYCLONE_SESSION_ID

  $proc = New-Object System.Diagnostics.Process
  $proc.StartInfo = $psi
  $null = $proc.Start()

  $logOut = Join-Path (Get-GlassLogDir) 'glass-stdout.log'
  $logErr = Join-Path (Get-GlassLogDir) 'glass-stderr.log'
  Start-Job -ScriptBlock {
    param($stdout, $stderr, $outPath, $errPath)
    try {
      while ($true) {
        $line = $stdout.ReadLine()
        if ($null -eq $line) { break }
        Add-Content -Path $outPath -Value $line
      }
    } catch {}
    try {
      $err = $stderr.ReadToEnd()
      if ($err) { Add-Content -Path $errPath -Value $err }
    } catch {}
  } -ArgumentList $proc.StandardOutput, $proc.StandardError, $logOut, $logErr | Out-Null

  for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 2
    if (Test-GlassUp -Port $Port) {
      Write-GlassLog "Glass up after $($i*2)s pid=$($proc.Id)"
      return @{ Ok = $true; Message = "Started — http://127.0.0.1:$Port"; Pid = $proc.Id }
    }
    if ($proc.HasExited) {
      Write-GlassLog "Glass process exited early code=$($proc.ExitCode)"
      return @{ Ok = $false; Message = "Process exited early (see $logErr)" }
    }
  }
  return @{ Ok = $false; Message = "Timed out waiting for :$Port (check logs under %LOCALAPPDATA%\CyclonePcGlass\logs)" }
}

function Stop-GlassHidden {
  param([int]$Port = 8000)
  $root = Get-GlassRoot
  $uvCmd = Get-Command uv -ErrorAction SilentlyContinue
  if (-not $uvCmd) {
    return @{ Ok = $false; Message = 'uv not found on PATH' }
  }
  Write-GlassLog "Stopping Glass on :$Port"
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $uvCmd.Source
  $psi.Arguments = "run python -m artemis stop --port $Port"
  $psi.WorkingDirectory = $root
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $proc = [System.Diagnostics.Process]::Start($psi)
  $null = $proc.WaitForExit(60000)
  $out = $proc.StandardOutput.ReadToEnd()
  $err = $proc.StandardError.ReadToEnd()
  if ($out) { Write-GlassLog $out.Trim() }
  if ($err) { Write-GlassLog ("stderr: " + $err.Trim()) }
  Start-Sleep -Seconds 1
  if (Test-GlassUp -Port $Port) {
    return @{ Ok = $false; Message = 'Stop reported done but :8000 still responds' }
  }
  return @{ Ok = $true; Message = 'Stopped' }
}
