$ErrorActionPreference = 'Stop'
$source = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Protocol.cs') -Raw
$tests = @"
namespace BLE_tcp_driver {
    public static class ProtocolChecks {
        public static int Run() {
            int count = 0;
            foreach (var value in new byte[][] { null, new byte[0], new byte[] {170}, new byte[] {170,187} }) {
                if (ProtocolHelper.IsClaudeStatusUpload(value)) throw new System.Exception("short command");
                count++;
            }
            var frame = ProtocolHelper.BuildPacket(PacketType.SelectDevice, new byte[] {1,2,3});
            if (frame[0] != 6 || frame[1] != 3 || frame[2] != 0 || frame[5] != 3) throw new System.Exception("frame");
            count++;
            bool rejected = false;
            try { ProtocolHelper.BuildPacket(PacketType.DeviceListResp, new byte[65536]); }
            catch (System.ArgumentOutOfRangeException) { rejected = true; }
            if (!rejected) throw new System.Exception("oversize payload");
            count++;
            var status = ProtocolHelper.BuildBleStatusPacket(new BleStatusInfo());
            if (status[0] != 130 || status.Length != 7 || status[3] != 0 || status[6] != 0) throw new System.Exception("empty status");
            count++;
            var info = ProtocolHelper.BuildDeviceInfoPacket(new DeviceStatusInfo { BatteryLevel = 78, SignalStrength = 5 });
            if (info[0] != 131 || info[1] != 8 || info[3] != 78 || info[4] != 5) throw new System.Exception("battery frame");
            return count + 1;
        }
    }
}
"@
Add-Type -TypeDefinition ($source + [Environment]::NewLine + $tests) -IgnoreWarnings -WarningAction SilentlyContinue
$passed = [BLE_tcp_driver.ProtocolChecks]::Run()
Write-Output "$passed protocol checks passed"
