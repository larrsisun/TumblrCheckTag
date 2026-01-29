package TelegramBot.TumblrTagTracker.util;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Решение для неправильного декодинга постов

@Component
public class HtmlDecoder {

    private static final Map<String, String> HTML_ENTITIES = new HashMap<>();
    private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&(#?[a-zA-Z0-9]+);");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    static {
        // Основные HTML entities
        HTML_ENTITIES.put("nbsp", " ");
        HTML_ENTITIES.put("amp", "&");
        HTML_ENTITIES.put("lt", "<");
        HTML_ENTITIES.put("gt", ">");
        HTML_ENTITIES.put("quot", "\"");
        HTML_ENTITIES.put("apos", "'");
        HTML_ENTITIES.put("#39", "'");

        // Кавычки
        HTML_ENTITIES.put("ldquo", "\"");  // левая двойная кавычка
        HTML_ENTITIES.put("rdquo", "\"");  // правая двойная кавычка
        HTML_ENTITIES.put("lsquo", "'");   // левая одинарная кавычка
        HTML_ENTITIES.put("rsquo", "'");   // правая одинарная кавычка
        HTML_ENTITIES.put("#8216", "'");   // левая одинарная
        HTML_ENTITIES.put("#8217", "'");   // правая одинарная
        HTML_ENTITIES.put("#8220", "\"");  // левая двойная
        HTML_ENTITIES.put("#8221", "\"");  // правая двойная

        // Тире и дефисы
        HTML_ENTITIES.put("ndash", "–");   // короткое тире
        HTML_ENTITIES.put("mdash", "—");   // длинное тире
        HTML_ENTITIES.put("#8211", "–");
        HTML_ENTITIES.put("#8212", "—");
        HTML_ENTITIES.put("minus", "−");

        // Другие популярные
        HTML_ENTITIES.put("copy", "©");
        HTML_ENTITIES.put("reg", "®");
        HTML_ENTITIES.put("trade", "™");
        HTML_ENTITIES.put("hellip", "…");
        HTML_ENTITIES.put("#8230", "…");
        HTML_ENTITIES.put("bull", "•");
        HTML_ENTITIES.put("middot", "·");

        // Математические
        HTML_ENTITIES.put("times", "×");
        HTML_ENTITIES.put("divide", "÷");
        HTML_ENTITIES.put("plusmn", "±");

        // Валюты
        HTML_ENTITIES.put("euro", "€");
        HTML_ENTITIES.put("pound", "£");
        HTML_ENTITIES.put("yen", "¥");
        HTML_ENTITIES.put("cent", "¢");
    }

    public String cleanHtml(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }

        // 1. Удаляем HTML теги
        String text = HTML_TAG_PATTERN.matcher(html).replaceAll("");

        // 2. Декодируем HTML entities
        text = decodeHtmlEntities(text);

        // 3. Убираем лишние пробелы и переносы
        text = text.replaceAll("\\s+", " ").trim();

        return text;
    }


    public String decodeHtmlEntities(String text) {
        if (text == null || !text.contains("&")) {
            return text;
        }

        Matcher matcher = HTML_ENTITY_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String entity = matcher.group(1);
            String replacement = decodeEntity(entity);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }


    private String decodeEntity(String entity) {
        String decoded = HTML_ENTITIES.get(entity);
        if (decoded != null) {
            return decoded;
        }

        // Обработка числовых кодов, так как числовые html-entities начинаются с #
        if (entity.startsWith("#")) {
            try {
                int codePoint;
                if (entity.length() > 2 && (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')) {
                    // Hex (16-ричный) код: &#xABCD;
                    codePoint = Integer.parseInt(entity.substring(2), 16);
                } else {
                    // Decimal (10-ричный) код: &#1234;
                    codePoint = Integer.parseInt(entity.substring(1));
                }
                // Не просто (char)codePoint, потому что некоторые символы (например, эмодзи) имеют code point > 0xFFFF,
                // А они кодируются двумя char (суррогатная пара)
                return new String(Character.toChars(codePoint));

            } catch (IllegalArgumentException e) {
                // Если не удалось декодировать, возвращаем как есть
                return "&" + entity + ";";
            }
        }
        // Если entity не распознан, возвращаем как есть
        return "&" + entity + ";";
    }
}
