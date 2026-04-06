# TS3AudioBot
<div align="right">
  <a href="README.md"><kbd>中文</kbd></a>
</div>

TS3AudioBot is a TeamSpeak 3 audio bot with a built-in web console. It supports multi-bot management, playback queues, and common resolvers.

> This project is under active development. Star/Fork to follow and contribute.

### Features
- Web console: bot management, player, queue
- Run multiple bots concurrently
- SQLite persistence for bots/admin/queues
- External config file to override paths and tools

### Quick Start
#### 1) Requirements
- Java 21
- FFmpeg (in PATH or placed under `ffmpeg/` next to the jar)
- yt-dlp (in PATH or configured explicitly)

Optional: auto-download FFmpeg / yt-dlp (saved under `ffmpeg/` and `yt-dlp/`)

Windows PowerShell:
```powershell
.\scripts\setup-tools.ps1
```

Linux / macOS:
```bash
./scripts/setup-tools.sh
```

After download:
- Keep `ffmpeg/` and `yt-dlp/` **next to the jar** (or run the app from this project root)
- Or add the executables to PATH

#### 2) External Config
Configuration is now unified into two layers:
- `application.yml` (built-in defaults + env var overrides)
- `ts3Audio-config.toml` (deployment-side editable settings)

Place `ts3Audio-config.toml` **next to the jar** and edit as needed:
```toml
[configs]
db_path = "data/ts3audiobot.db"
bots_path = "bots"

[web]
port = 58913
hosts = ["*"]

[web.api]
enabled = false

[web.interface]
enabled = true

[tools]
ffmpeg_path = "ffmpeg"

[resolvers.external]
yt = "yt-dlp"
ytmusic = "yt-dlp"
netease = "netease-cloud-music"
qq = "qqmusic"

[media]
cache_enabled = true
audio_cache_enabled = true
max_size_gb = 20
cache_ttl_hours = 720

[media.image]
enabled = true
# mode options:
# - "direct": source URL only (no local proxy)
# - "hybrid": source URL first, auto fallback to local proxy if image load fails
# - "proxy": always use local proxy and resized cover
mode = "hybrid"
thumb_size = 120
cover_size = 360
```
Notes:
- All keys are optional; invalid values fall back to built-in defaults
- Relative `db_path` resolves from the config file directory

#### 3) Build
```bash
./gradlew bootJar
```
Windows:
```bat
gradlew.bat bootJar
```

#### 4) Run
```bash
java -jar build/libs/TS3AudioBot-0.0.1-SNAPSHOT.jar
```

#### 5) First Login
Open the web console. On first run you will be redirected to `/setup` to create an admin account.

### Usage
1. Go to **Bot Management**
2. Create a bot with address, channel, nickname, etc.
3. Changes take effect immediately (no restart)
4. Manage playlists in **Player** and **Queue** pages

### Config Keys (Minimal)
- `configs.db_path`: SQLite file path (default `data/ts3audiobot.db`)
- `configs.bots_path`: reserved for bot config extension
- `web.port`: web port (default `58913`)
- `web.hosts`: allowed hosts (reserved for future use)
- `web.api.enabled`: internal API toggle
- `web.interface.enabled`: web UI toggle
- `tools.ffmpeg_path`: FFmpeg path (`ffmpeg` or `auto` tries auto resolution)
- `resolvers.external.*`: resolver command paths
- `media.cache_enabled`: global media cache switch (cover + audio)
- `media.audio_cache_enabled`: enable persisted audio cache (disable to keep only cover cache)
- `media.max_size_gb`: media cache capacity limit in GB (LRU cleanup for unreferenced files)
- `media.cache_ttl_hours`: media cache TTL in hours
- `media.image.enabled`: image strategy switch
- `media.image.mode`: `direct` / `hybrid` / `proxy`
- `media.image.thumb_size`: proxy thumbnail max edge (px)
- `media.image.cover_size`: proxy cover max edge (px)

### Data Storage
- Bots/Admin: `data/ts3audiobot.db`
- Queues: `data/queues.json`

### Troubleshooting
- **FFmpeg not found**: ensure it is in PATH or under `ffmpeg/`
- **yt-dlp not found**: install and add to PATH or set absolute path

### Compliance Notice (Important)
- Use this project only with audio content you are legally authorized to access.
- Enabling `media.audio_cache_enabled=true` persists playback audio on local disk.
- You are solely responsible for complying with applicable laws and platform terms of service.

### Contribution & Standards
- Contributions are welcome!
- Please read and follow the coding standard: `doc/编码规范.en.md`

### Contribution Flow (PR / Commit / Test)
1. **Branch naming**: `feature/xxx`, `fix/xxx`, `refactor/xxx` (short topic)
2. **Commit message**: `type(scope): subject`  
   - Example: `feat(web): support custom port`  
   - Types: `feat` / `fix` / `refactor` / `chore` / `docs` / `test`
3. **PR description**:
   - Background & goal (why)
   - Key changes (what)
   - Impact scope (affected modules)
   - Risks & rollback (if any)
4. **Testing**:
   - Update/add tests for core logic changes
   - At minimum ensure `./gradlew test` passes
   - If no tests, state the reason and verification steps

### Advanced: Override paths
You can override config/db paths via:
- Environment: `TS3AB_CONFIG` / `TS3AB_DB`
- JVM args: `-Dts3ab.config=...` / `-Dts3ab.db=...`

### Acknowledgements
- This project references [Manevolent/ts3j](https://github.com/Manevolent/ts3j). Special thanks.

### License
This project is licensed under the **Apache License 2.0**. You must keep copyright
notices and the license text when using or redistributing.
