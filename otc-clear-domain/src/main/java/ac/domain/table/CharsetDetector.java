package ac.domain.table;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Picks a usable charset for CSV files: BOM first, then strict UTF-8, then GB18030. */
public final class CharsetDetector {

    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final Charset FALLBACK = Charset.forName("windows-1252");
    private static final int PROBE_LIMIT = 1 << 20;

    private CharsetDetector() {
    }

    public static Charset detect(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
                return StandardCharsets.UTF_8;
            }
            if (startsWith(bytes, 0xFF, 0xFE)) {
                return StandardCharsets.UTF_16LE;
            }
            if (startsWith(bytes, 0xFE, 0xFF)) {
                return StandardCharsets.UTF_16BE;
            }
            int length = Math.min(bytes.length, PROBE_LIMIT);
            if (canDecode(bytes, length, StandardCharsets.UTF_8)) {
                return StandardCharsets.UTF_8;
            }
            if (canDecode(bytes, length, GB18030)) {
                return GB18030;
            }
            return FALLBACK;
        } catch (IOException e) {
            throw new TableReadException("Cannot inspect CSV charset: " + e.getMessage(), e);
        }
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean canDecode(byte[] bytes, int length, Charset charset) {
        try {
            charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, length));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
