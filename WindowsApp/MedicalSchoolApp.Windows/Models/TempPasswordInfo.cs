namespace MedicalSchoolApp.Windows.Models;

public class TempPasswordInfo
{
    public string CodeHash { get; set; } = "";
    public string Salt { get; set; } = "";
    public int ExtendMinutes { get; set; } = 15;
    public DateTime ExpiresAt { get; set; }
    public bool Used { get; set; }
}
