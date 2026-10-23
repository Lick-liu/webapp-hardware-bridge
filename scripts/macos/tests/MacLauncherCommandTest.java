package cn.teahouses.printhelper;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class MacLauncherCommandTest {
    private static final String KEY = "tea.print.macPos58RasterQueue";
    private static final String TEAR = "tea.print.macPos58ManualTear";

    @Test public void absentPropertyPreservesDefaultCommand() {
        String previous = System.getProperty(KEY), tear = System.getProperty(TEAR);
        try {
            System.clearProperty(TEAR);
            System.clearProperty(KEY);
            assertEquals(List.of("java", "-cp", "libraries", "tigerworkshop.webapphardwarebridge.GUI"),
                    MacLauncher.childCommand("java", "libraries", false));
        } finally { restore(KEY, previous); restore(TEAR, tear); }
    }

    @Test public void forwardsSingleOptInToActualChild() {
        String previous = System.getProperty(KEY), tear = System.getProperty(TEAR);
        try {
            System.clearProperty(TEAR);
            System.setProperty(KEY, "Xprinter_USB_Printer_Port");
            assertEquals(List.of("java", "-D" + KEY + "=Xprinter_USB_Printer_Port", "-cp",
                    "libraries with spaces", "tigerworkshop.webapphardwarebridge.Server"),
                    MacLauncher.childCommand("java", "libraries with spaces", true));
        } finally { restore(KEY, previous); restore(TEAR, tear); }
    }

    @Test public void doesNotForwardUnrelatedProperties() {
        String previous = System.getProperty(KEY), tear = System.getProperty(TEAR);
        String other = System.getProperty("tea.print.unrelated");
        try {
            System.clearProperty(TEAR);
            System.setProperty(KEY, " ");
            System.setProperty("tea.print.unrelated", "must-not-forward");
            assertEquals(4, MacLauncher.childCommand("java", "libraries", false).size());
        } finally { restore(KEY, previous); restore("tea.print.unrelated", other); restore(TEAR, tear); }
    }

    @Test public void manualTearCompanionRequiresQueueOptIn() {
        String previous = System.getProperty(KEY), tear = System.getProperty(TEAR);
        try {
            System.clearProperty(TEAR);
            System.setProperty(TEAR, "true");
            System.clearProperty(KEY);
            assertEquals(4, MacLauncher.childCommand("java", "libraries", false).size());
            System.setProperty(KEY, "Xprinter_USB_Printer_Port");
            assertEquals(List.of("java", "-D" + KEY + "=Xprinter_USB_Printer_Port", "-D" + TEAR + "=true",
                    "-cp", "libraries", "tigerworkshop.webapphardwarebridge.GUI"),
                    MacLauncher.childCommand("java", "libraries", false));
        } finally { restore(KEY, previous); restore(TEAR, tear); }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
