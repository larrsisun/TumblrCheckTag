# Lord of Mysteries Reddit Tracker Bot (in process, readme only for now)

A Telegram notification bot that helps the international fan community of
the novel *Lord of the Mysteries* stay updated with new content on r/LordOfTheMysteries.

## Purpose
- **Read-only monitoring** of new posts in r/LordOfTheMysteries
- Sending filtered updates (fan translations, fan art, discussions) to subscribed users via Telegram
- **No posting, commenting, or any write operations** on Reddit
- Helping fans stay updated without constantly checking the subreddit manually

## Technology Stack
- **Java 17** with **Spring Boot**
- **Reddit API** (via JRAW library) - read-only access only
- **Telegram Bot API** (via telegrambots-spring-boot-starter)
- **MySQL** for storing user subscriptions
- **Redis** for caching and rate limiting
- **Docker** containerization (planned)
- **RabbitMQ* (planned)

## Security & API Usage Compliance
- **Reddit API**: Will use proper OAuth2 authentication and respect all rate limits
- **User Data**: Only stores Telegram chat IDs for notifications, no personal data
- **Environment Variables**: All sensitive tokens stored as environment variables (not in code)
- **Rate Limiting**: Will implement 1 request per 2 seconds to respect Reddit's API limits
- **User Agent**: Proper User-Agent header will be set as per Reddit API guidelines

## Planned Features
1. Users can subscribe/unsubscribe via Telegram commands
2. Filter posts by flair (fanart, translation, discussion)
3. Scheduled checks for new content (every 5-10/30 minutes)
4. Smart notifications with post summaries

## Why This Project is API-Friendly
- **Read-only**: This bot will only consume data from Reddit, never post or interact
- **Single subreddit**: Only monitors r/lordofmysteries, not crawling multiple communities
- **Clear benefit**: Helps organize and deliver content to interested fans
- **Open source**: Code is publicly reviewable for transparency

---

## What This Bot Will NOT Do
- Post, comment, vote, or modify any content on Reddit
- Collect personal data from Reddit users
- Spam or send unsolicited messages
- Operate in any subreddit besides r/lordofmysteries

## Links
- **Subreddit**: https://www.reddit.com/r/LordOfTheMysteries
- **Telegram Bot**: @LordOfMysteriesTracker_bot (when deployed)
