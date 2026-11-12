package git4idea.repo;

import consulo.logging.Logger;
import jakarta.annotation.Nonnull;
import org.ini4j.Ini;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * from kotlin
 */
class GitConfigHelper {
    private static final Logger LOGGER = Logger.getInstance(GitConfigHelper.class);

    @Nonnull
    static Ini loadIniFile(@Nonnull Path configFile) throws IOException {
        Ini ini = new Ini();
        // duplicate keys (e.g. url in [remote])
        ini.getConfig().setMultiOption(true);
        // don't need tree structure: it corrupts url in section name (e.g. [url "http://github.com/"]
        ini.getConfig().setTree(false);
        ini.getConfig().setLowerCaseOption(false);
        try (InputStream stream = Files.newInputStream(configFile)) {
            ini.load(stream);
        }
        catch (IOException e) {
            LOGGER.warn("Couldn't load config file at " + configFile, e);
            throw e;
        }
        return ini;
    }
}
