package tigerworkshop.webapphardwarebridge;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class LinuxDistributionLayoutTest {
    @Test
    public void linuxLaunchersRunFromApplicationHome() throws Exception {
        assertLauncherChangesToAppHome(new File("src/linux/bin/webapp-hardware-bridge"));
        assertLauncherChangesToAppHome(new File("src/linux/bin/webapp-hardware-bridge-server"));
    }

    private void assertLauncherChangesToAppHome(File launcher) throws Exception {
        assertTrue("Missing launcher: " + launcher, launcher.isFile());

        String script = Files.readString(launcher.toPath(), StandardCharsets.UTF_8);

        assertTrue("Launcher should be a POSIX shell script", script.startsWith("#!/usr/bin/env sh"));
        assertTrue("Launcher should locate APP_HOME", script.contains("APP_HOME="));
        assertTrue("Launcher should run from APP_HOME so web/ can be served", script.contains("cd \"$APP_HOME\""));
        assertTrue("Launcher should use the packaged lib directory", script.contains("$APP_HOME/lib/*"));
    }
}
