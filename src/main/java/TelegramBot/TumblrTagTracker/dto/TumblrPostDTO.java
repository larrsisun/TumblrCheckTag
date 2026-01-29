package TelegramBot.TumblrTagTracker.dto;

import TelegramBot.TumblrTagTracker.util.HtmlDecoder;
import com.tumblr.jumblr.types.Post;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private String videoUrl;
    private String sourceUrl; // для ссылок
    private String noteCount;

    private static HtmlDecoder htmlDecoder = new HtmlDecoder();

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

        if (postURL != null) {
            stringBuilder.append("\n ;; [открыть пост](").append(postURL).append(")");
        }

        return stringBuilder.toString();
    }

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

        return htmlDecoder.cleanHtml(text);
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
        Pattern pattern = Pattern.compile(
            "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>",
            Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(body);
        
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
