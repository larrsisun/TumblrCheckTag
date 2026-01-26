package TelegramBot.TumblrTagTracker.dto;

import com.tumblr.jumblr.types.Post;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
public class TumblrPostDTO {
    private String id;
    private String blogName;
    private String postURL;
    private String summary;
    private String body;
    private List<String> tags;
    private Long timestamp;
    private Post.PostType type; // text, photo, quote, link, chat, audio, video, answer
    private String photoUrl; // для фото постов
    private String sourceUrl; // для ссылок

    public String getFormattedMessage() {
        StringBuilder stringBuilder = new StringBuilder();

        // Описание поста (заголовок или текст)
        String description = getCleanText();
        if (description != null && !description.trim().isEmpty()) {
            // Ограничиваем длину описания
            if (description.length() > 500) {
                description = description.substring(0, 497) + "...";
            }
            stringBuilder.append(escapeMarkdown(description.trim()));
            stringBuilder.append("\n\n");
        }

        // Теги (опционально, можно убрать если не нужны)
        if (tags != null && !tags.isEmpty() && tags.size() <= 5) {
            stringBuilder.append("🏷 ");
            stringBuilder.append(String.join(", ", tags));
            stringBuilder.append("\n");
        }

        // Ссылка на пост
        if (postURL != null) {
            stringBuilder.append("\n[📎 Открыть пост](").append(postURL).append(")");
        }

        return stringBuilder.toString();
    }

    /**
     * Извлекает чистый текст из HTML, убирая все теги
     */
    public String getCleanText() {
        String text = null;
        
        if (summary != null && !summary.trim().isEmpty()) {
            text = summary;
        } else if (body != null && !body.trim().isEmpty()) {
            text = body;
        }
        
        if (text == null) {
            return null;
        }
        
        // Убираем HTML теги
        text = stripHtmlTags(text);
        
        // Убираем лишние пробелы и переносы строк
        text = text.replaceAll("\\s+", " ").trim();
        
        return text;
    }

    /**
     * Удаляет HTML теги из текста
     */
    private String stripHtmlTags(String html) {
        if (html == null) {
            return null;
        }
        
        // Удаляем все HTML теги
        String text = html.replaceAll("<[^>]+>", "");
        
        // Декодируем HTML entities
        text = text.replace("&nbsp;", " ")
                   .replace("&amp;", "&")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&apos;", "'");
        
        return text;
    }

    private String escapeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        // Экранируем специальные символы Markdown для Telegram
        return text.replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace(">", "\\>")
                .replace("#", "\\#")
                .replace("+", "\\+")
                .replace("-", "\\-")
                .replace("=", "\\=")
                .replace("|", "\\|")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace(".", "\\.")
                .replace("!", "\\!");
    }
    
    /**
     * Извлекает URL первого изображения из HTML body (для TEXT постов с изображениями)
     */
    public String extractImageUrlFromBody() {
        if (body == null || body.isEmpty()) {
            return null;
        }
        
        // Ищем img теги
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher matcher = pattern.matcher(body);
        
        if (matcher.find()) {
            String imageUrl = matcher.group(1);
            // Проверяем, что это действительно URL изображения
            if (imageUrl != null && (imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                return imageUrl;
            }
        }
        
        return null;
    }

}
