using System.Security.Cryptography;
using System.Text;

namespace MedicalSchoolApp.Windows.Services;

/// <summary>Salt付きSHA-256でパスワードを保存する（Android版と同方式）。</summary>
public static class PasswordHasher
{
    public static string GenerateSalt()
    {
        var bytes = RandomNumberGenerator.GetBytes(16);
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    public static string Hash(string password, string salt)
    {
        using var sha256 = SHA256.Create();
        var combined = Encoding.UTF8.GetBytes(salt + password);
        var digest = sha256.ComputeHash(combined);
        return Convert.ToHexString(digest).ToLowerInvariant();
    }

    public static bool Verify(string password, string salt, string expectedHash)
    {
        return Hash(password, salt) == expectedHash;
    }

    /// <summary>一時パスワード用の6桁コードを生成する。</summary>
    public static string GenerateTempCode()
    {
        var n = RandomNumberGenerator.GetInt32(0, 1_000_000);
        return n.ToString("D6");
    }
}
