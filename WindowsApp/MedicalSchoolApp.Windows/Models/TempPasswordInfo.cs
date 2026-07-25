namespace MedicalSchoolApp.Windows.Models;

public class TempPasswordInfo
{
    public string CodeDisplay { get; set; } = "";
    public string CodeHash { get; set; } = "";
    public string Salt { get; set; } = "";
    public int ExtendMinutes { get; set; } = 15;
    public DateTime CreatedAt { get; set; } = DateTime.Now;
    public DateTime ExpiresAt { get; set; } = DateTime.MaxValue;
    public bool Used { get; set; }
}
