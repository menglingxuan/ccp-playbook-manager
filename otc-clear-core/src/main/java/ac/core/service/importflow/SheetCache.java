package ac.core.service.importflow;

import ac.domain.table.RawTable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Small LRU cache of parsed sheets so the debounced preview does not re-read the workbook. */
@Component
public class SheetCache {

    private static final int CAPACITY = 4;

    private final Map<String, RawTable> cache = new LinkedHashMap<>(CAPACITY, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RawTable> eldest) {
            return size() > CAPACITY;
        }
    };

    public synchronized RawTable get(String key, Supplier<RawTable> loader) {
        RawTable cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        RawTable loaded = loader.get();
        cache.put(key, loaded);
        return loaded;
    }

    public synchronized void evict(String uploadId) {
        cache.keySet().removeIf(key -> key.startsWith(uploadId + "#"));
    }
}
