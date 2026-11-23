param(
  [Parameter(Mandatory = $true)][string]$Action,   # status | start | stop
  [string]$Ssid = "HiCarlink",
  [string]$Password = "hicarlink123"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
$asTaskAction = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
})[0]

function Await($op, $type) {
  $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
  $task.Wait()
  return $task.Result
}
function AwaitAction($op) {
  $task = $asTaskAction.Invoke($null, @($op))
  $task.Wait()
}

[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType = WindowsRuntime] | Out-Null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration, Windows.Networking.NetworkOperators, ContentType = WindowsRuntime] | Out-Null
[Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType = WindowsRuntime] | Out-Null

function Get-Profile {
  $internet = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
  if ($internet -ne $null) { return $internet }
  $profiles = [Windows.Networking.Connectivity.NetworkInformation]::GetConnectionProfiles()
  foreach ($p in $profiles) { if ($p.IsWlanConnectionProfile) { return $p } }
  foreach ($p in $profiles) { return $p }
  return $null
}

$profile = Get-Profile
if ($profile -eq $null) {
  Write-Output "ERROR:没有可用的网络连接配置（电脑没联网时 Windows 不给开热点）"
  exit 2
}
$mgr = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)

if ($Action -eq "status") {
  "state=" + $mgr.TetheringOperationalState
  exit 0
}

if ($Action -eq "stop") {
  $r = Await ($mgr.StopTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
  "stop=" + $r.Status
  exit 0
}

$cfg = New-Object Windows.Networking.NetworkOperators.NetworkOperatorTetheringAccessPointConfiguration
$cfg.Ssid = $Ssid
$cfg.Passphrase = $Password
AwaitAction ($mgr.ConfigureAccessPointAsync($cfg))
$result = Await ($mgr.StartTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
"start=" + $result.Status

# 等 ICS 虚拟网卡拿到 192.168.137.1
Start-Sleep -Milliseconds 1500
$ip = $null
for ($i = 0; $i -lt 10 -and $ip -eq $null; $i++) {
  $addrs = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like "192.168.137.*" }
  if ($addrs) { $ip = $addrs[0] }
  else { Start-Sleep -Milliseconds 500 }
}
$mac = ""
$adapters = Get-NetAdapter -IncludeHidden -ErrorAction SilentlyContinue | Where-Object { $_.InterfaceDescription -match "Wi-Fi Direct|WLAN" -and $_.Status -eq "Up" }
if ($adapters) { $mac = ($adapters | Select-Object -First 1).MacAddress }

"ssid=" + $Ssid
"password=" + $Password
if ($ip -ne $null) { "ip=" + $ip.IPAddress + " prefix=" + $ip.PrefixLength } else { "ip=" }
"mac=" + $mac
"freq=2437"