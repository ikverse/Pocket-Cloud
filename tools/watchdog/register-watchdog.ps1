# Creates (or replaces) the Task Scheduler job that runs pocket-watchdog.ps1 every 5 minutes for the current
# user, hidden, whether or not the laptop is on battery. Run once:
#     powershell -ExecutionPolicy Bypass -File register-watchdog.ps1
# Remove with:  Unregister-ScheduledTask -TaskName "Pocket Cloud watchdog" -Confirm:$false
$script = Join-Path $PSScriptRoot "pocket-watchdog.ps1"
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$script`""
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).Date -RepetitionInterval (New-TimeSpan -Minutes 5) -RepetitionDuration (New-TimeSpan -Days 3650)
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 2) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName "Pocket Cloud watchdog" -Action $action -Trigger $trigger -Settings $settings -Description "Shows a notification when the Pocket Cloud phone has been unreachable for 10 minutes." -Force | Out-Null
Write-Host "Registered 'Pocket Cloud watchdog': every 5 minutes, runs $script"
