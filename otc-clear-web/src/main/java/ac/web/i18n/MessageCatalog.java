package ac.web.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Exposes the message bundle of the active locale to the browser.
 *
 * <p>The key set is read once from the default bundle so the JSON payload always matches
 * {@code messages.properties}; missing translations fall back to the key itself.
 */
@Component
public class MessageCatalog {

    private static final String BASE_BUNDLE = "i18n/messages.properties";

    private final MessageSource messageSource;
    private final List<String> keys;

    public MessageCatalog(MessageSource messageSource) {
        this.messageSource = messageSource;
        this.keys = loadKeys();
    }

    /** Flat key to text map for the given locale. */
    public Map<String, String> messages(Locale locale) {
        Map<String, String> messages = new LinkedHashMap<>();
        for (String key : keys) {
            messages.put(key, messageSource.getMessage(key, null, key, locale));
        }
        return messages;
    }

    public List<String> keys() {
        return keys;
    }

    private static List<String> loadKeys() {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(BASE_BUNDLE).getInputStream();
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + BASE_BUNDLE, e);
        }
        return properties.stringPropertyNames().stream().sorted().toList();
    }
}
