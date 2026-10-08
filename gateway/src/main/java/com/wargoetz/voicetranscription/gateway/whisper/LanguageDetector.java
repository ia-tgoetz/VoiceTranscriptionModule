package com.wargoetz.voicetranscription.gateway.whisper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Labels an auto-detected transcript with an ISO-639-1 code for the MongoDB {@code language} field.
 *
 * <p>Why not ask Whisper? whisper.cpp does detect the language when {@code language = "auto"}, but the
 * whisper-jni 1.7.1 binding exposes no {@code whisper_full_lang_id()} accessor. The only other way out is the
 * native log line ("auto-detected language: xx"), and whisper-jni's log proxy calls {@code FindClass} from
 * whatever thread logs - which, under Ignition's per-module classloader, can resolve to null on a native
 * worker thread and crash the gateway JVM. So Whisper decodes in the right language (that is what matters
 * for the transcript) and this class only labels the result: Unicode script first, then stop-word
 * frequency for Latin-script languages. Short or ambiguous text falls back to the configured default.</p>
 */
public final class LanguageDetector {
    private static final Map<String, Set<String>> STOPWORDS = new LinkedHashMap<>();

    static {
        STOPWORDS.put("en", Set.of("the", "and", "is", "to", "of", "a", "in", "that", "it", "for", "on",
            "with", "this", "was", "are", "be", "we", "you", "have", "not", "over", "check", "please"));
        STOPWORDS.put("de", Set.of("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "mit",
            "den", "auf", "ich", "wir", "sie", "es", "von", "bitte", "auch", "dem"));
        STOPWORDS.put("fr", Set.of("le", "la", "les", "et", "est", "un", "une", "des", "du", "pas", "que",
            "pour", "dans", "nous", "vous", "il", "sur", "avec", "ce", "au"));
        STOPWORDS.put("es", Set.of("el", "la", "los", "las", "y", "es", "un", "una", "que", "de", "en",
            "no", "por", "para", "con", "del", "se", "lo", "al", "está"));
        STOPWORDS.put("it", Set.of("il", "lo", "la", "gli", "le", "e", "è", "un", "una", "che", "di",
            "non", "per", "con", "del", "della", "sono", "nel", "anche", "questo"));
        STOPWORDS.put("pt", Set.of("o", "a", "os", "as", "e", "é", "um", "uma", "que", "de", "não", "para",
            "com", "do", "da", "em", "no", "na", "por", "está"));
        STOPWORDS.put("nl", Set.of("de", "het", "een", "en", "is", "niet", "van", "op", "dat", "met", "voor",
            "zijn", "ik", "we", "je", "ook", "maar", "er", "aan", "dit"));
        STOPWORDS.put("sv", Set.of("och", "är", "det", "att", "en", "ett", "som", "på", "inte", "med", "för",
            "jag", "vi", "av", "till", "den", "har", "om", "men", "så"));
        STOPWORDS.put("pl", Set.of("i", "jest", "nie", "się", "na", "to", "że", "w", "z", "do", "jak", "co",
            "ale", "tak", "od", "po", "przez", "dla", "być", "już"));
        STOPWORDS.put("tr", Set.of("ve", "bir", "bu", "da", "de", "için", "ile", "değil", "çok", "ne", "var",
            "olarak", "daha", "gibi", "ama", "sonra", "şu", "ben", "biz", "mi"));
    }

    private LanguageDetector() {
    }

    /**
     * @param text     transcript produced with {@code language = "auto"}
     * @param fallback language to report when the text gives no clear signal
     */
    public static String detect(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        String byScript = detectByScript(text);
        if (byScript != null) {
            return byScript;
        }
        Map<String, Integer> scores = new HashMap<>();
        int words = 0;
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{L}']+")) {
            if (raw.isEmpty()) {
                continue;
            }
            words++;
            for (Map.Entry<String, Set<String>> e : STOPWORDS.entrySet()) {
                if (e.getValue().contains(raw)) {
                    scores.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        String best = null;
        int bestScore = 0;
        int second = 0;
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            if (e.getValue() > bestScore) {
                second = bestScore;
                bestScore = e.getValue();
                best = e.getKey();
            } else if (e.getValue() > second) {
                second = e.getValue();
            }
        }
        // Require a clear winner; otherwise trust the administrator's default.
        if (best == null || bestScore < 2 || bestScore == second || words < 3) {
            return fallback;
        }
        return best;
    }

    private static String detectByScript(String text) {
        int letters = 0;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            String lang = switch (script) {
                case HIRAGANA, KATAKANA -> "ja";
                case HAN -> "zh";
                case HANGUL -> "ko";
                case CYRILLIC -> "ru";
                case ARABIC -> "ar";
                case HEBREW -> "he";
                case GREEK -> "el";
                case DEVANAGARI -> "hi";
                case THAI -> "th";
                default -> null;
            };
            if (lang != null) {
                counts.merge(lang, 1, Integer::sum);
            }
        }
        if (letters == 0 || counts.isEmpty()) {
            return null;
        }
        // Japanese mixes Han with kana; any kana means Japanese.
        if (counts.containsKey("ja")) {
            return "ja";
        }
        Map.Entry<String, Integer> top = counts.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElseThrow();
        return top.getValue() * 2 >= letters ? top.getKey() : null;
    }
}
