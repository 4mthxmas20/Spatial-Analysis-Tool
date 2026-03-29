package hkspatialanalysis;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point for the HK Spatial Analysis System.
 * Sets the system look-and-feel and launches the main window.
 */
public class Main {

    public static void main(String[] args) {
        // Use the system look-and-feel for a native appearance
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // fall back to default L&F
        }

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
