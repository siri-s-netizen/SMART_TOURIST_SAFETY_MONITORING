<#
.SYNOPSIS
    Run the whole ML pipeline: preprocess -> eda -> train -> evaluate -> har_eda
    -> history -> tests. Network-dependent stages are opt-in.

.DESCRIPTION
    One command for the reproducible data-analysis / anomaly-detection module.
    Real GeoLife is used when present at
    ml/data/geolife/Geolife Trajectories 1.3/Data, otherwise the SYNTHETIC
    fixture is used and every stage prints a SYNTHETIC warning banner.

    The default run is OFFLINE-ONLY. The two stages that need the network
    (osm_eda.py = Overpass API, weather_eda.py = Open-Meteo / OpenWeatherMap)
    only run with -Network. The two slow supervised models - the transport-mode
    classifier and the future-event risk model - are gated behind -Transport and
    -FutureRisk respectively.

.PARAMETER Fast
    Use --summary-scope sample (fast). Default is 'all' (full 18,670-trajectory
    summary; slower).

.PARAMETER Synthetic
    Force the SYNTHETIC fixture regardless of whether real data is present.

.PARAMETER Network
    Also run the network stages: osm_eda.py and weather_eda.py.

.PARAMETER Transport
    Also run transport_mode.py (the supervised transport-mode model; reads every
    labelled user's .plt files and takes several minutes).

.PARAMETER FutureRisk
    Also run future_risk/train.py: the supervised *future-event* model that
    predicts a hard fall impact within the next second, trained on UniMiB SHAR's
    real recorded falls. Needs the archive unpacked under ml/data/unimib (see
    ml/data/README.md section 6) and takes a few minutes; it also refreshes the
    model asset the Android app ships and the reference cases its parity test
    reads, so re-run it whenever the feature definitions or the label geometry
    change.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1
    powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1 -Fast
    powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1 -Network
    powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1 -Transport
    powershell -NoProfile -ExecutionPolicy Bypass -File ml\run_all.ps1 -FutureRisk
#>
param(
    [switch]$Fast,
    [switch]$Synthetic,
    [switch]$Network,
    [switch]$Transport,
    [switch]$FutureRisk
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$sync = if ($Synthetic) { @("--synthetic") } else { @() }
$scope = if ($Fast) { "sample" } else { "all" }

function Invoke-Step {
    param([string]$Name, [string[]]$StepArgs)
    Write-Host ""
    Write-Host ("=" * 74)
    Write-Host ("== {0}: python {1}" -f $Name, ($StepArgs -join " "))
    Write-Host ("=" * 74)
    & python @StepArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Host ("!! {0} FAILED with exit code {1}" -f $Name, $LASTEXITCODE)
        exit $LASTEXITCODE
    }
}

Write-Host "Python: $((python --version) 2>&1)"

# --- core offline pipeline (unchanged) -------------------------------------- #
Invoke-Step "preprocess" (@("preprocess.py", "--summary-scope", $scope, "--sample-trajectories", "1200") + $sync)
Invoke-Step "eda"        (@("eda.py") + $sync)
Invoke-Step "train"      (@("train.py") + $sync)
Invoke-Step "evaluate"   (@("evaluate.py") + $sync)

# --- additional offline analyses (fast enough for the default run) ---------- #
Invoke-Step "har_eda"    @("har_eda.py")
Invoke-Step "history"    @("history.py")

# --- slow supervised model (opt-in) ----------------------------------------- #
if ($Transport) {
    Invoke-Step "transport_mode" (@("transport_mode.py") + $sync)
} else {
    Write-Host "skipping transport_mode.py (pass -Transport to run it)"
}

# --- supervised future-event model (opt-in) --------------------------------- #
# Produces the model the Android app runs on-device, so this stage also rewrites
# app/src/main/assets/future_risk_model.json and the reference cases its Kotlin
# parity test reads. `future_risk/tune.py` is deliberately NOT run here: it is a
# 65-config search and its output is already recorded in
# ml/outputs/future_risk/tuning.json and folded into future_risk/config.py.
if ($FutureRisk) {
    Invoke-Step "future_risk" @("future_risk/train.py")
} else {
    Write-Host "skipping future_risk/train.py (pass -FutureRisk to run it)"
}

# --- network stages (opt-in) ------------------------------------------------ #
if ($Network) {
    Invoke-Step "osm_eda"     @("osm_eda.py")
    Invoke-Step "weather_eda" @("weather_eda.py")
} else {
    Write-Host "skipping osm_eda.py and weather_eda.py (pass -Network to run them)"
}

Invoke-Step "pytest"     @("-m", "pytest", "tests", "future_risk/tests", "-q")

Write-Host ""
Write-Host ("=" * 74)
Write-Host "All pipeline stages completed successfully."
Write-Host ("=" * 74)
