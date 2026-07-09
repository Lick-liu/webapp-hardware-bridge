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

    @Test
    public void userServiceInstallerUsesServerLauncherAndCapturedJavaPath() throws Exception {
        File installer = new File("src/linux/scripts/install-user-service.sh");

        assertTrue("Missing installer: " + installer, installer.isFile());

        String script = Files.readString(installer.toPath(), StandardCharsets.UTF_8);

        assertTrue("Installer should be a POSIX shell script", script.startsWith("#!/usr/bin/env sh"));
        assertTrue("Installer should check for the packaged jar", script.contains("$APP_HOME/lib/$APP_NAME.jar"));
        assertTrue("Installer should create a user-level systemd service", script.contains("systemctl --user enable"));
        assertTrue("Installer should restart the user-level systemd service", script.contains("systemctl --user restart"));
        assertTrue("Installer should run the server-only launcher", script.contains("ExecStart=$INSTALL_DIR/bin/$APP_NAME-server"));
        assertTrue("Installer should capture JAVA_HOME", script.contains("Environment=JAVA_HOME=$JAVA_HOME_DIR"));
        assertTrue("Installer should capture the Java binary directory in PATH", script.contains("Environment=PATH=$JAVA_BIN_DIR:/usr/local/bin:/usr/bin:/bin"));
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
