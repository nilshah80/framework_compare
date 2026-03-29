using System.Text.Json;
using Microsoft.AspNetCore.Http;

namespace controller_api;

public static class Helpers
{
    private static readonly HashSet<string> RedactedHeaderNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "Authorization", "Cookie", "Set-Cookie", "X-Api-Key", "X-Auth-Token"
    };

    public static void LogEntry(string level, string msg, object fields)
    {
        var entry = new Dictionary<string, object>
        {
            ["time"] = DateTime.UtcNow.ToString("O"),
            ["level"] = level,
            ["msg"] = msg
        };
        foreach (var prop in fields.GetType().GetProperties())
            entry[prop.Name] = prop.GetValue(fields) ?? "";
        Console.WriteLine(JsonSerializer.Serialize(entry));
    }

    public static Dictionary<string, string> RedactHeaders(IHeaderDictionary headers)
    {
        var result = new Dictionary<string, string>();
        foreach (var h in headers)
            result[h.Key] = RedactedHeaderNames.Contains(h.Key) ? "[REDACTED]" : h.Value.ToString();
        return result;
    }
}
