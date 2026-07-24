using System.Windows;
using System.Windows.Input;
using MedicalSchoolApp.Windows.Services;

namespace MedicalSchoolApp.Windows.Views;

public partial class PasswordPromptWindow : Window
{
    public bool Verified { get; private set; }

    public PasswordPromptWindow()
    {
        InitializeComponent();
    }

    /// <summary>保護者パスワードの確認ダイアログを表示し、検証に成功したかを返す。</summary>
    public static bool ConfirmParentPassword(string message = "保護者パスワードを入力してください")
    {
        var window = new PasswordPromptWindow();
        window.MessageText.Text = message;
        window.ShowDialog();
        return window.Verified;
    }

    private void PasswordBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) OkButton_Click(sender, e);
    }

    private void OkButton_Click(object sender, RoutedEventArgs e)
    {
        var password = PasswordBox.Password;
        if (PasswordHasher.Verify(password, AppState.Settings.ParentPasswordSalt, AppState.Settings.ParentPasswordHash))
        {
            Verified = true;
            Close();
        }
        else
        {
            ErrorText.Text = "パスワードが違います";
            ErrorText.Visibility = Visibility.Visible;
        }
    }

    private void CancelButton_Click(object sender, RoutedEventArgs e)
    {
        Verified = false;
        Close();
    }
}
