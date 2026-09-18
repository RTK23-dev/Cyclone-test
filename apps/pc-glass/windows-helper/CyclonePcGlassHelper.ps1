#Requires -Version 5.1
<#
.SYNOPSIS
  Small Windows UI to start/stop Cyclone PC Glass without command-prompt windows.
#>
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'GlassControl.ps1')

[System.Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object System.Windows.Forms.Form
$form.Text = 'Cyclone PC Glass'
$form.Size = New-Object System.Drawing.Size(420, 220)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedSingle'
$form.MaximizeBox = $false
$form.Font = New-Object System.Drawing.Font('Segoe UI', 10)

$lbl = New-Object System.Windows.Forms.Label
$lbl.AutoSize = $false
$lbl.Location = New-Object System.Drawing.Point(16, 16)
$lbl.Size = New-Object System.Drawing.Size(370, 48)
$lbl.Text = 'Checking…'

$statusDot = New-Object System.Windows.Forms.Label
$statusDot.Location = New-Object System.Drawing.Point(16, 70)
$statusDot.Size = New-Object System.Drawing.Size(370, 24)
$statusDot.Text = ''

$btnStart = New-Object System.Windows.Forms.Button
$btnStart.Text = 'Start Glass'
$btnStart.Location = New-Object System.Drawing.Point(16, 110)
$btnStart.Size = New-Object System.Drawing.Size(110, 36)

$btnStop = New-Object System.Windows.Forms.Button
$btnStop.Text = 'Stop Glass'
$btnStop.Location = New-Object System.Drawing.Point(136, 110)
$btnStop.Size = New-Object System.Drawing.Size(110, 36)

$btnOpen = New-Object System.Windows.Forms.Button
$btnOpen.Text = 'Open UI'
$btnOpen.Location = New-Object System.Drawing.Point(256, 110)
$btnOpen.Size = New-Object System.Drawing.Size(110, 36)

$lnk = New-Object System.Windows.Forms.LinkLabel
$lnk.Text = 'Logs folder'
$lnk.Location = New-Object System.Drawing.Point(16, 158)
$lnk.AutoSize = $true
$lnk.add_Click({ Start-Process explorer.exe (Get-GlassLogDir) })

function Refresh-Status {
  if (Test-GlassUp) {
    $lbl.Text = 'Cyclone PC Glass is running'
    $statusDot.Text = 'http://127.0.0.1:8000'
    $statusDot.ForeColor = [System.Drawing.Color]::ForestGreen
    $btnStart.Enabled = $false
    $btnStop.Enabled = $true
    $btnOpen.Enabled = $true
  } else {
    $lbl.Text = 'Cyclone PC Glass is stopped'
    $statusDot.Text = 'Mode A defaults: CYCLONE_CONNECTED=1 · session default-foreground'
    $statusDot.ForeColor = [System.Drawing.Color]::DimGray
    $btnStart.Enabled = $true
    $btnStop.Enabled = $false
    $btnOpen.Enabled = $false
  }
}

$btnStart.add_Click({
  $btnStart.Enabled = $false
  $lbl.Text = 'Starting (no console)…'
  $form.Refresh()
  $r = Start-GlassHidden
  [System.Windows.Forms.MessageBox]::Show($r.Message, 'Cyclone PC Glass') | Out-Null
  Refresh-Status
})

$btnStop.add_Click({
  $btnStop.Enabled = $false
  $lbl.Text = 'Stopping…'
  $form.Refresh()
  $r = Stop-GlassHidden
  [System.Windows.Forms.MessageBox]::Show($r.Message, 'Cyclone PC Glass') | Out-Null
  Refresh-Status
})

$btnOpen.add_Click({
  Start-Process 'http://127.0.0.1:8000'
})

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 4000
$timer.add_Tick({ Refresh-Status })
$timer.Start()

$form.Controls.AddRange(@($lbl, $statusDot, $btnStart, $btnStop, $btnOpen, $lnk))
Refresh-Status
[void]$form.ShowDialog()
