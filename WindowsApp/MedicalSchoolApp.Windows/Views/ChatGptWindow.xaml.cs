using System.Windows;

namespace MedicalSchoolApp.Windows.Views;

public partial class ChatGptWindow : Window
{
    private static readonly string[] AllowedHosts = { "chatgpt.com", "chat.openai.com" };

    public ChatGptWindow()
    {
        InitializeComponent();
        Closing += (_, e) =>
        {
            e.Cancel = true;
            Hide();
        };
        Loaded += async (_, _) => await InitializeWebViewAsync();
    }

    private async System.Threading.Tasks.Task InitializeWebViewAsync()
    {
        await WebView.EnsureCoreWebView2Async();

        WebView.CoreWebView2.NavigationStarting += (_, e) =>
        {
            if (!IsAllowedHost(e.Uri))
            {
                e.Cancel = true;
            }
        };

        // 新規ウィンドウ(window.open等)は許可しない
        WebView.CoreWebView2.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
        };
    }

    private static bool IsAllowedHost(string url)
    {
        try
        {
            var host = new Uri(url).Host.ToLowerInvariant();
            return AllowedHosts.Any(allowed => host == allowed || host.EndsWith("." + allowed));
        }
        catch
        {
            return false;
        }
    }
}
