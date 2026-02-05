# Tumblr Tag Tracker Bot

A Telegram bot that monitors Tumblr posts by tags and sends notifications to subscribers when popular posts are found.

## Features

- **Tag-based Monitoring**: Track Tumblr posts by custom tags which are chosen by a user via Telegram
- **Smart Filtering**: Posts are filtered based on note count and age before delivery
- **Rate Limiting**: Built-in rate limiting to respect Tumblr API constraints
- **Duplicate Prevention**: Redis-based caching ensures users don't receive the same post twice
- **Multi-user Support**: Each user can subscribe to their own set of tags
- **Scheduled Checks**: Automatic periodic checking for new posts
- **Post Tracking**: Monitors post metrics over time and sends delayed posts when they meet thresholds
- **Rich Media Support**: Handles text, photo, video, quote, link, and answer posts
- **Circuit Breaker**: Resilience4j integration for fault tolerance
- **Asynchronous Processing**: Parallel post delivery to multiple users

## Architecture

### Technology Stack

- **Backend**: Spring Boot 4.0.2
- **Language**: Java 21
- **Database**: MySQL (for persistent storage)
- **Cache**: Redis (for duplicate prevention)
- **Telegram API**: Telegrambots 6.9.7.1
- **Tumblr API**: Jumblr 0.0.13
- **Build Tool**: Maven
- **Containerization**: Docker

### Key Components

```
┌─────────────────┐
│  Telegram Bot   │
│   (TumblrBot)   │
└────────┬────────┘
         │
         ├─────────────────────────────────────┐
         │                                     │
┌────────▼────────┐                  ┌────────▼────────┐
│   Schedulers    │                  │    Commands     │
│ (Check Posts)   │                  │  (/subscribe)   │
└────────┬────────┘                  └────────┬────────┘
         │                                     │
         ├─────────────────────────────────────┤
         │                                     │
┌────────▼────────┐      ┌──────────▼─────────┐
│ TumblrService   │      │ SubscriptionService│
│ (Fetch Posts)   │      │  (Manage Users)    │
└────────┬────────┘      └──────────┬─────────┘
         │                           │
┌────────▼────────┐      ┌──────────▼─────────┐
│ PostTracking    │      │    MySQL DB        │
│   Service       │      │  (Subscriptions)   │
└────────┬────────┘      └────────────────────┘
         │
┌────────▼────────┐
│  Redis Cache    │
│ (Deduplication) │
└─────────────────┘
```

## Prerequisites

- Java 21 or higher
- Maven 3.9+
- MySQL 8.0+
- Redis 6.0+
- Docker & Docker Compose (optional, for containerized deployment)
- Telegram Bot Token (from [@BotFather](https://t.me/botfather))
- Tumblr API Keys (from [Tumblr API Console](https://www.tumblr.com/oauth/apps))

## Usage

### Getting Started

1. **Start a conversation** with your bot on Telegram
2. **Subscribe** to start receiving posts: `/subscribe`
3. **Add tags** you want to track: `/tag add "lord of the mysteries" dispatch`
4. **Wait** for the bot to check for new posts (every 10 minutes)

### Tag Management

Tags can be:
- Single words: `fanart`, `photography`
- Multi-word phrases: `"lord of the mysteries"`, `"cell of empireo"`

Maximum limits:
- Tag length: 150 characters
- Tags per user: 100
- Valid characters: letters, numbers, spaces, hyphens

## Commands

| Command | Description | Example |
|---------|-------------|---------|
| `/start` | Start the bot and see available commands | `/start` |
| `/subscribe` | Subscribe to post notifications | `/subscribe` |
| `/unsubscribe` | Unsubscribe from notifications | `/unsubscribe` |
| `/tag` | Show current tags and instructions | `/tag` |
| `/tag add <tags>` | Add tags to track | `/tag add "lord of the mysteries" ersatz` |
| `/tag remove <tags>` | Remove specific tags | `/tag remove "lord of the mysteries"` |
| `/tag clear` | Clear all tags | `/tag clear` |
| `/tag list` | List current tags | `/tag list` |
| `/help` | Show help message | `/help` |

### Command Examples

```bash
# Add single-word tags
/tag add fanart photography

# Add multi-word tags (use quotes)
/tag add "lord of the mysteries" "original character"

# Remove specific tags
/tag remove fanart

# Clear all tags at once
/tag clear
```

## Development

### Project Structure

```
src/
├── main/
│   ├── java/TelegramBot/TumblrTagTracker/
│   │   ├── bot/
│   │   │   ├── commands/           # Bot commands
│   │   │   └── TumblrBot.java     # Main bot class
│   │   ├── configs/                # Configuration classes
│   │   ├── dto/                    # Data transfer objects
│   │   ├── models/                 # JPA entities
│   │   ├── repositories/           # Data repositories
│   │   ├── schedulers/             # Scheduled tasks
│   │   ├── services/               # Business logic
│   │   └── util/                   # Utility classes
│   └── resources/
│       └── application.yml         # Application configuration
└── test/                           # Test files
```

### Key Services

#### TumblrService
Handles all interactions with Tumblr API:
- Fetches posts by tags
- Rate limiting (20 requests per minute)
- Circuit breaker for fault tolerance

#### PostTrackingService
Manages post lifecycle:
- Tracks posts over time
- Applies filters (minimum notes, age)
- Marks posts as sent

#### UserPostTrackingService
Per-user post delivery tracking:
- Prevents duplicate deliveries
- Tracks which tags matched for each user
- Maintains delivery history

#### NotificationService
Sends posts to users:
- Handles different post types (text, photo, video)
- Formats messages with Markdown
- Retry logic and fallbacks

---

**Note**: This bot respects Tumblr's API rate limits (20 requests per minute) and implements proper caching to minimize API calls. 
