package tigerworkshop.webapphardwarebridge;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReleaseVersionContractTest {
    @Test
    public void webUiLoadsAndDisplaysRuntimeVersionWithoutBlockingConfiguration() throws Exception {
        String html = read("src/main/resources/web/index.html");

        assertTrue("Web UI should expose a stable version marker",
                html.contains("data-test=\"app-version\""));
        assertTrue("Web UI should render the runtime version",
                html.contains("v{{ version.version }}"));
        assertTrue("Web UI should load the runtime version endpoint",
                html.contains("axios.get('/system/version.json')"));
        assertTrue("Version lookup failure must not block printer configuration",
                html.contains("this.loadVersion().catch(() => null)"));
    }

    @Test
    public void releaseVersionMetadataStaysAligned() throws Exception {
        String buildGradle = read("build.gradle");
        Matcher gradleVersion = Pattern.compile("(?m)^version\\s+'([^']+)'\\s*$").matcher(buildGradle);
        assertTrue("Gradle project version is missing", gradleVersion.find());
        assertEquals(Constants.VERSION, gradleVersion.group(1));

        String installer = read("install.nsi");
        assertTrue("NSIS application version must match the runtime version",
                installer.contains("!define APP_VERSION \"" + Constants.VERSION + "\""));
        assertTrue("Windows file metadata must contain the product version",
                installer.contains("VIProductVersion \"${APP_VERSION}.0\""));
        assertTrue("Installed Apps should display the application version",
                installer.contains("\"DisplayVersion\" \"${APP_VERSION}\""));
        assertTrue("Release builds should accept an explicit bundled JRE directory",
                installer.contains("!define BUNDLED_JRE_DIR"));
        assertTrue("Installer must package the selected bundled JRE directory",
                installer.contains("File /r \"${BUNDLED_JRE_DIR}\""));

        String changelog = read("CHANGELOG.md");
        assertTrue("Changelog must include the released version",
                changelog.contains("## " + Constants.VERSION));
    }

    private String read(String relativePath) throws Exception {
        File file = new File(relativePath);
        assertTrue("Missing release contract file: " + file, file.isFile());
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }
}
