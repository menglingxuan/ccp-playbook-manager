package ac.web.api;

import ac.web.i18n.MessageCatalog;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the active locale's message bundle to the browser scripts. */
@RestController
@RequestMapping("/api/i18n")
public class I18nController {

    private final MessageCatalog catalog;

    public I18nController(MessageCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<Bundle> bundle(Locale locale) {
        Bundle bundle = new Bundle(locale.toLanguageTag(), catalog.messages(locale));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bundle);
    }

    /**
     * Message bundle payload.
     *
     * @param locale   resolved locale tag, for example {@code zh-HK}
     * @param messages flat key to text map
     */
    public record Bundle(String locale, Map<String, String> messages) {
    }
}
