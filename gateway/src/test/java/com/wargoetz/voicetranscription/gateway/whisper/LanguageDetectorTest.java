package com.wargoetz.voicetranscription.gateway.whisper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

    @Test
    void english() {
        assertEquals("en", LanguageDetector.detect("The pump on line two is leaking and the valve is stuck.", "de"));
    }

    @Test
    void german() {
        assertEquals("de", LanguageDetector.detect("Die Pumpe auf der Linie zwei ist nicht dicht und das Ventil klemmt.", "en"));
    }

    @Test
    void spanish() {
        assertEquals("es", LanguageDetector.detect("La bomba de la línea dos no funciona y el operador está en el turno.", "en"));
    }

    @Test
    void scripts() {
        assertEquals("ja", LanguageDetector.detect("ポンプが故障しています", "en"));
        assertEquals("ru", LanguageDetector.detect("Насос на второй линии протекает", "en"));
        assertEquals("ko", LanguageDetector.detect("펌프가 고장났습니다", "en"));
    }

    @Test
    void ambiguousFallsBack() {
        assertEquals("fr", LanguageDetector.detect("OK", "fr"));
        assertEquals("en", LanguageDetector.detect("", "en"));
        assertEquals("en", LanguageDetector.detect(null, "en"));
    }
}
