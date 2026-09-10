using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace BLE_tcp_driver
{
    class AppConfig
    {
        public string BleName { get; set; } = "";
        public string BleMac { get; set; } = "";
        public string ServerIP { get; set; } = "127.0.0.1";
        public int ServerPort { get; set; } = 9000;
        public bool StartMinimized { get; set; } = false;

        public bool HasSavedDevice => !string.IsNullOrEmpty(BleName) && !string.IsNullOrEmpty(BleMac);

        private static readonly string ConfigDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "AhaKey Studio",
            "ble-driver");

        private static readonly string ConfigPath = Path.Combine(
            ConfigDirectory,
            "config_server.json");

        public static AppConfig Load()
        {
            AppConfig stable = TryLoad(ConfigPath);
            if (stable != null && stable.HasSavedDevice)
                return stable;

            AppConfig fallback = stable;
            foreach (string legacyPath in LegacyConfigPaths())
            {
                AppConfig legacy = TryLoad(legacyPath);
                if (legacy == null)
                    continue;
                if (fallback == null)
                    fallback = legacy;
                if (legacy.HasSavedDevice)
                {
                    legacy.Save();
                    Console.WriteLine("已迁移旧版 BLE 设备配置到稳定的用户目录。");
                    return legacy;
                }
            }

            if (fallback != null)
            {
                fallback.Save();
                return fallback;
            }
            return CreateDefault();
        }

        private static AppConfig TryLoad(string path)
        {
            if (string.IsNullOrEmpty(path) || !File.Exists(path))
                return null;
            try
            {
                string json = File.ReadAllText(path, Encoding.UTF8);
                var config = new AppConfig();
                config.BleName = JsonExtractString(json, "BleName");
                config.BleMac = JsonExtractString(json, "BleMac");
                // The bridge is a local control surface, never a LAN service.
                config.ServerIP = "127.0.0.1";
                config.ServerPort = JsonExtractInt(json, "ServerPort", 9000);
                config.StartMinimized = JsonExtractBool(json, "StartMinimized", false);
                return config;
            }
            catch (Exception ex)
            {
                Console.WriteLine("配置文件读取失败: " + ex.Message);
                return null;
            }
        }

        private static IEnumerable<string> LegacyConfigPaths()
        {
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string executableDirectory = Path.GetDirectoryName(Application.ExecutablePath);
            string localAppData = Environment.GetFolderPath(
                Environment.SpecialFolder.LocalApplicationData);
            string[] candidates =
            {
                Path.Combine(executableDirectory ?? "", "config_server.json"),
                Path.Combine(localAppData, "Programs", "AhaKey Studio", "config_server.json"),
                Path.Combine(localAppData, "Programs", "AhaKeyStudio", "config_server.json")
            };
            foreach (string candidate in candidates)
            {
                string fullPath;
                try
                {
                    fullPath = Path.GetFullPath(candidate);
                }
                catch
                {
                    continue;
                }
                if (!string.Equals(fullPath, ConfigPath, StringComparison.OrdinalIgnoreCase)
                    && seen.Add(fullPath))
                    yield return fullPath;
            }
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(ConfigDirectory);
                var sb = new StringBuilder();
                sb.AppendLine("{");
                sb.AppendLine($"  \"BleName\": \"{JsonEscape(BleName)}\",");
                sb.AppendLine($"  \"BleMac\": \"{JsonEscape(BleMac)}\",");
                sb.AppendLine($"  \"ServerIP\": \"{JsonEscape(ServerIP)}\",");
                sb.AppendLine($"  \"ServerPort\": {ServerPort},");
                sb.AppendLine($"  \"StartMinimized\": {(StartMinimized ? "true" : "false")}");
                sb.AppendLine("}");
                File.WriteAllText(ConfigPath, sb.ToString(), Encoding.UTF8);
            }
            catch (Exception ex)
            {
                Console.WriteLine("配置文件保存失败: " + ex.Message);
            }
        }

        private static AppConfig CreateDefault()
        {
            var config = new AppConfig();
            config.Save();
            return config;
        }

        #region JSON工具方法 (无外部依赖)

        private static string JsonExtractString(string json, string key)
        {
            var match = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            return match.Success ? JsonUnescape(match.Groups[1].Value) : "";
        }

        private static int JsonExtractInt(string json, string key, int defaultValue)
        {
            var match = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*(\\d+)");
            return match.Success && int.TryParse(match.Groups[1].Value, out int val) ? val : defaultValue;
        }

        private static bool JsonExtractBool(string json, string key, bool defaultValue)
        {
            var match = Regex.Match(json, "\"" + Regex.Escape(key) + "\"\\s*:\\s*(true|false)");
            return match.Success ? match.Groups[1].Value == "true" : defaultValue;
        }

        /// <summary>
        /// JSON字符串转义 (非ASCII字符使用\uXXXX编码)
        /// </summary>
        private static string JsonEscape(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            var sb = new StringBuilder(s.Length);
            foreach (char c in s)
            {
                switch (c)
                {
                    case '"': sb.Append("\\\""); break;
                    case '\\': sb.Append("\\\\"); break;
                    case '\b': sb.Append("\\b"); break;
                    case '\f': sb.Append("\\f"); break;
                    case '\n': sb.Append("\\n"); break;
                    case '\r': sb.Append("\\r"); break;
                    case '\t': sb.Append("\\t"); break;
                    default:
                        if (c < 0x20 || c > 0x7E)
                            sb.AppendFormat("\\u{0:X4}", (int)c);
                        else
                            sb.Append(c);
                        break;
                }
            }
            return sb.ToString();
        }

        /// <summary>
        /// JSON字符串反转义
        /// </summary>
        private static string JsonUnescape(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            var sb = new StringBuilder(s.Length);
            for (int i = 0; i < s.Length; i++)
            {
                if (s[i] == '\\' && i + 1 < s.Length)
                {
                    char next = s[++i];
                    switch (next)
                    {
                        case '"': sb.Append('"'); break;
                        case '\\': sb.Append('\\'); break;
                        case '/': sb.Append('/'); break;
                        case 'b': sb.Append('\b'); break;
                        case 'f': sb.Append('\f'); break;
                        case 'n': sb.Append('\n'); break;
                        case 'r': sb.Append('\r'); break;
                        case 't': sb.Append('\t'); break;
                        case 'u':
                            if (i + 4 < s.Length)
                            {
                                sb.Append((char)Convert.ToInt32(s.Substring(i + 1, 4), 16));
                                i += 4;
                            }
                            break;
                        default: sb.Append('\\').Append(next); break;
                    }
                }
                else
                {
                    sb.Append(s[i]);
                }
            }
            return sb.ToString();
        }

        #endregion
    }
}
