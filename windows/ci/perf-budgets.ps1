# Measures the published Windows app and fails past a ceiling:
#   - first frame: median time from process start to the window's first frame, over several
#     launches after one warm-up
#   - memory: median working set five seconds after the first frame
# Each launch runs `CodexMeter.exe --perf-budget FILE`, which starts in demo mode, writes both
# numbers to FILE and quits. Prints a Markdown table to the log, and to the job summary in Actions.
#
#   pwsh windows/ci/perf-budgets.ps1 path/to/CodexMeter.exe
#
# Ceilings are set from measured CI runs (windows-2025 x64) with headroom for runner noise. Raise
# one only with a reason in the PR that needs it; lower them as the app gets leaner.
param([Parameter(Mandatory)][string]$App)
$ErrorActionPreference = 'Stop'

# First measured 2026-11-17: 7 first frames between 783 and 971 ms (median 810 ms), working set
# 156.4-159 MB. With ReadyToRun (2026-11-19): 588-647 ms (median 610 ms), 150.9-151.8 MB. The
# ceilings leave about 55% and 25% headroom over the worst launch.
$launches = if ($env:LAUNCHES) { [int]$env:LAUNCHES } else { 7 }
$maxFirstFrameMs = if ($env:MAX_FIRST_FRAME_MS) { [double]$env:MAX_FIRST_FRAME_MS } else { 1000 }
$maxWorkingSetMb = if ($env:MAX_WORKING_SET_MB) { [double]$env:MAX_WORKING_SET_MB } else { 190 }

$report = Join-Path ([IO.Path]::GetTempPath()) 'codexmeter-perf.txt'
function Measure-Launch {
    Remove-Item $report -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath $App -ArgumentList '--perf-budget', "`"$report`"" -PassThru
    if (-not $p.WaitForExit(60000)) { $p.Kill(); throw 'The app did not quit within 60 s' }
    if (-not (Test-Path $report)) { throw 'The app wrote no performance report' }
    $values = @{}
    foreach ($line in Get-Content $report) {
        $key, $value = $line -split '=', 2
        $values[$key] = [double]::Parse($value, [Globalization.CultureInfo]::InvariantCulture)
    }
    $values
}
function Get-Median([double[]]$values) { ($values | Sort-Object)[[int][Math]::Floor(($values.Count - 1) / 2)] }

$null = Measure-Launch # warm-up: the first launch also fills the file cache and font caches
$frames = @(); $memory = @()
for ($i = 0; $i -lt $launches; $i++) {
    $run = Measure-Launch
    $frames += $run['first_frame_ms']
    $memory += $run['working_set_mb']
}
$frameMedian = Get-Median $frames
$memoryMedian = Get-Median $memory

$failed = $false
function Format-Row($name, $value, $ceiling, $unit) {
    $verdict = 'pass'
    if ($value -gt $ceiling) { $verdict = '**over**'; $script:failed = $true }
    "| $name | $value $unit | $ceiling $unit | $verdict |"
}
$lines = @(
    '### Windows performance budgets', '',
    '| Budget | Measured | Ceiling | Result |', '|---|---:|---:|---|',
    (Format-Row "First frame (median of $launches)" $frameMedian $maxFirstFrameMs 'ms'),
    (Format-Row 'Memory after launch (working set)' $memoryMedian $maxWorkingSetMb 'MB'), '',
    "First frames: $(($frames | Sort-Object) -join ' ') ms; working sets: $(($memory | Sort-Object) -join ' ') MB"
)
$lines | Write-Output
if ($env:GITHUB_STEP_SUMMARY) { $lines | Add-Content -Path $env:GITHUB_STEP_SUMMARY }

if ($failed) {
    Write-Output '::error::A performance budget was exceeded. See the table in the job summary.'
    exit 1
}
