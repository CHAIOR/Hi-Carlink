Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]
function Await($op, $type) {
  $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
  $task.Wait()
  return $task.Result
}
[Windows.Devices.Bluetooth.BluetoothAdapter, Windows.Devices.Bluetooth, ContentType = WindowsRuntime] | Out-Null
$adapter = Await ([Windows.Devices.Bluetooth.BluetoothAdapter]::GetDefaultAsync()) ([Windows.Devices.Bluetooth.BluetoothAdapter])
if ($adapter -eq $null) {
  Write-Output "ERROR:蓝牙没打开或这台电脑没有蓝牙"
  exit 2
}
$hex = $adapter.BluetoothAddress.ToString("X12")
# 转成 AA:BB:CC:DD:EE:FF（高字节在前）
$parts = @()
for ($i = 0; $i -lt 6; $i++) { $parts += $hex.Substring($i * 2, 2) }
Write-Output ("bt=" + ($parts -join ":"))