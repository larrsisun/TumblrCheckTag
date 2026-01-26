package TelegramBot.TumblrTagTracker.dto;

import com.tumblr.jumblr.types.Post;

import java.util.List;

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

        if (tags != null && !tags.isEmpty()) {
            stringBuilder.append("Теги: ").append(String.join(", ", tags)).append("\n");
        }

        // Заголовок или саммари
        String title = summary != null && !summary.isEmpty() ? summary :
                (body != null && body.length() > 100 ? body.substring(0, 100) + "..." : body);

        if (title != null && !title.isEmpty()) {
            stringBuilder.append("*").append(escapeMarkdown(title)).append("*\n");
        }

        // Ссылка на пост
        if (postURL != null) {
            stringBuilder.append("\n[Ссылка на пост](").append(postURL).append(")");
        }

        // Ссылка на источник (для фото/ссылок)
        if (sourceUrl != null && !sourceUrl.equals(postURL) && !sourceUrl.contains("tumblr.com")) {
            stringBuilder.append(" | [Ссылка на контент](").append(sourceUrl).append(")");
        }

        return stringBuilder.toString();
    }

    private String escapeMarkdown(String text) {
        // Экранируем специальные символы MarkdownV2 для Telegram
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

    public String getBlogName() {
        return blogName;
    }

    public void setBlogName(String blogName) {
        this.blogName = blogName;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getPostURL() {
        return postURL;
    }

    public void setPostURL(String postURL) {
        this.postURL = postURL;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public Post.PostType getType() {
        return type;
    }

    public void setType(Post.PostType type) {
        this.type = type;
    }
}
