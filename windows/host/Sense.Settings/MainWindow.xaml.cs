using System.Windows;
using Sense.Settings.ViewModels;

namespace Sense.Settings;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        DataContext = new MainViewModel();
    }
}
