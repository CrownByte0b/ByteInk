$ErrorActionPreference = 'Stop'

# Hosted Windows images can leave this account-onboarding window above the pen fixture.
# Dismiss only that window in the job's session; keep the pen test's occlusion checks active.
$desktopSession = [System.Diagnostics.Process]::GetCurrentProcess().SessionId
$prompts = @(Get-Process -Name WWAHost -ErrorAction SilentlyContinue | Where-Object {
    $_.SessionId -eq $desktopSession -and $_.MainWindowTitle -eq 'Microsoft account'
})
foreach ($prompt in $prompts) {
    Write-Host "Dismissing runner Microsoft account window (WWAHost PID $($prompt.Id))."
    $null = $prompt.CloseMainWindow()
    if (-not $prompt.WaitForExit(3000)) {
        try {
            Stop-Process -InputObject $prompt -Force
        } catch {
            if (-not $prompt.HasExited) { throw }
        }
        if (-not $prompt.WaitForExit(3000)) {
            throw 'The runner Microsoft account window did not close.'
        }
    }
}
