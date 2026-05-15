# TS3AudioBot
<div align="right">
  <a href="README.en.md">English</a>
</div>

## 中文

TS3AudioBot 是一个带 Web 控制台的 TeamSpeak 3 音频机器人项目，支持多机器人管理、播放队列与常用解析器集成。

> 本项目正在持续完善中，欢迎 Star/Fork 关注与参与开发。

### 功能概览
- Web 控制台：机器人管理、播放中心、队列管理
- 多机器人并行运行
- SQLite 持久化：机器人配置、管理员账号、播放队列
- 外置配置文件：可选覆盖路径与工具配置

### 快速开始

#### 下载发布包

从 [Releases](https://github.com/ArthurZhu1992/TS3AudioBot/releases) 下载对应平台的 ZIP 包，
每个包内含：JAR + FFmpeg + yt-dlp + 配置文件模板 + 启动脚本，解压即用。

| 平台 | 包名 |
|------|------|
| Linux x86_64 | `TS3AudioBot-*-linux-x64.zip` |
| Linux ARM64 | `TS3AudioBot-*-linux-arm64.zip` |
| Windows x86_64 | `TS3AudioBot-*-win-x64.zip` |
| macOS (Intel + Apple Silicon) | `TS3AudioBot-*-macos.zip` |

```bash
# 解压
unzip TS3AudioBot-*-linux-x64.zip -d ts3audiobot
cd ts3audiobot

# 启动（自动检测 Java + 使用自带 ffmpeg/yt-dlp）
./start.sh
```

Windows 解压后双击 `start.bat` 即可。

#### 手动运行

```bash
java -jar TS3AudioBot-*.jar
```

#### Docker 运行

```bash
docker run ghcr.io/arthurzhu1992/ts3audiobot:latest
```

#### 首次登录
启动后访问控制台首页（默认 http://localhost:58913），首次会跳转到 `/setup` 创建管理员账号，完成后即可登录使用。

### 从源码构建

```bash
./gradlew bootJar
```
Windows：
```bat
gradlew.bat bootJar
```

构建产物在 `build/libs/TS3AudioBot-*.jar`。

### 使用说明
1. 登录后进入 **机器人管理** 页面
2. 新建机器人，填写地址、频道、昵称等信息
3. 保存后可立即运行（无需重启）
4. 在 **播放中心** 与 **队列** 页面管理播放列表

### 配置项说明（精简版）
- `configs.db_path`：SQLite 数据库文件路径（默认 `data/ts3audiobot.db`）
- `configs.bots_path`：保留字段（用于机器人配置路径扩展）
- `web.port`：Web 端口（默认 `58913`）
- `web.hosts`：允许访问的 Host 列表（当前为预留）
- `web.api.enabled`：内部 API 开关
- `web.interface.enabled`：Web UI 开关
- `tools.ffmpeg_path`：FFmpeg 路径，`ffmpeg` 或 `auto` 会尝试自动解析
- `resolvers.external.*`：外部解析器命令路径
- `media.cache_enabled`：媒体缓存总开关（封面 + 音频）
- `media.audio_cache_enabled`：音频落盘缓存开关（关闭后仅缓存封面）
- `media.max_size_gb`：媒体缓存容量上限（GB，超限按最近最少使用清理未引用文件）
- `media.cache_ttl_hours`：媒体缓存过期时间（小时）
- `media.image.enabled`：图片策略开关
- `media.image.mode`：`direct` / `hybrid` / `proxy`
- `media.image.thumb_size`：缩略图代理最大边（px）
- `media.image.cover_size`：封面图代理最大边（px）

### 数据与存储
- 机器人/管理员配置：`data/ts3audiobot.db`
- 播放队列：`data/queues.json`

### 常见问题
- **FFmpeg 找不到**：确认系统 PATH，或将可执行文件放入 `ffmpeg/`
- **yt-dlp 不可用**：安装并加入 PATH，或在配置文件中指定绝对路径

### 合规说明（重要）
- 本项目仅用于你有合法授权的音频内容（例如自有内容、已获许可内容、平台允许的个人使用场景）。
- 启用 `media.audio_cache_enabled=true` 会将播放音频缓存到本地，请自行确认符合你所在地区法律法规及目标平台服务条款。
- 你应对自身使用行为与缓存内容承担全部责任；项目维护者不对违规使用导致的任何后果负责。

### 开发规范与协作
- 欢迎参与开发与改进！
- 请先阅读并遵守编码规范：`doc/编码规范.md`

### 贡献流程（PR / Commit / Test）
1. **分支命名**：`feature/xxx`、`fix/xxx`、`refactor/xxx`（含简短主题）
2. **提交信息**：推荐 `type(scope): subject`  
   - 例：`feat(web): 支持自定义端口`  
   - type 建议：`feat` / `fix` / `refactor` / `chore` / `docs` / `test`
3. **PR 说明**：
   - 背景与目标（为什么改）
   - 主要变更（做了什么）
   - 影响范围（可能影响到的模块）
   - 风险与回滚（如有）
4. **测试要求**：
   - 变更核心逻辑必须补充/更新测试
   - 至少保证能通过 `./gradlew test`
   - 若无测试，说明原因与验证方式

### 高级：自定义配置路径
可通过以下方式指定配置文件或数据库位置：
- 环境变量：`TS3AB_CONFIG` / `TS3AB_DB`
- JVM 参数：`-Dts3ab.config=...` / `-Dts3ab.db=...`

### 致谢
- 本项目参考了 [Manevolent/ts3j](https://github.com/Manevolent/ts3j)，特别感谢。

### 许可证
本项目采用 **Apache License 2.0**。使用、修改与分发时需保留版权声明与许可证文本。
