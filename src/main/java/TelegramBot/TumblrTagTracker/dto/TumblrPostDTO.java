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
    private Post.PostType type; // text, photo, quote, link, video, answer
    private String photoUrl; // для фото постов
    private String videoUrl;
    private String sourceUrl; // для ссылок
    private String noteCount;
    private String question; // для постов типа answer
    private String answer; // для постов типа answer

    private static HtmlDecoder htmlDecoder = new HtmlDecoder();

    public String getFormattedMessage() {
        StringBuilder message = new StringBuilder();

        // специальная обработка для постов типа answer
        if (type == Post.PostType.ANSWER && question != null && !question.trim().isEmpty()) {
            message.append("> *Вопрос:*\n");
            String cleanQuestion = htmlDecoder.cleanHtml(question);
            if (cleanQuestion != null && !cleanQuestion.isEmpty()) {
                if (cleanQuestion.length() > 400) {
                    cleanQuestion = cleanQuestion.substring(0, 397) + "...";
                }
                message.append(escapeMarkdown(cleanQuestion.trim()));
            }
            message.append("\n\n");

            if (answer != null && !answer.trim().isEmpty()) {
                message.append("> *Ответ:*\n");
                String cleanAnswer = htmlDecoder.cleanHtml(answer);
                if (cleanAnswer != null && !cleanAnswer.isEmpty()) {
                    if (cleanAnswer.length() > 400) {
                        cleanAnswer = cleanAnswer.substring(0, 397) + "...";
                    }
                    message.append(escapeMarkdown(cleanAnswer.trim()));
                }
                message.append("\n\n");
            }
            // Ссылка на пост
            if (postURL != null) {
                message.append("\n;; [Открыть пост](").append(postURL).append(")");
            }

            return message.toString();
        }

        // обычная обработка
        String description = getCleanText();
        if (description != null && !description.trim().isEmpty()) {
            // Ограничиваем длину описания
            if (description.length() > 500) {
                description = description.substring(0, 497) + "...";
            }
            message.append(escapeMarkdown(description.trim()));
            message.append("\n\n");
        }

        // Ссылка на пост
        if (postURL != null) {
            message.append("\n;; [Открыть пост](").append(postURL).append(")");
        }

        return message.toString();
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
}
