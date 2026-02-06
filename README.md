# TS3AudioBot
<div align="right">
  <a href="README.en.md"><kbd>English</kbd></a>
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
#### 1) 环境依赖
- Java 21
- FFmpeg（放入系统 PATH，或放在程序同目录的 `ffmpeg/` 内）
- yt-dlp（放入系统 PATH，或在配置里指定）

可选：自动下载 FFmpeg / yt-dlp（下载到项目目录的 `ffmpeg/` 与 `yt-dlp/`）

Windows PowerShell：
```powershell
.\scripts\setup-tools.ps1
```

Linux / macOS：
```bash
./scripts/setup-tools.sh
```

下载完成后建议：
- 将 `ffmpeg/` 与 `yt-dlp/` 目录放在 **jar 同目录**（或保持在项目根目录并从此处运行）
- 也可将可执行文件加入系统 PATH

#### 2) 准备外置配置
将 `ts3Audio-config.toml` 放到 **jar 同目录**，按需修改：
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
```
说明：
- 所有字段都可选，空值或非法值会自动回退到内置默认
- `db_path` 相对路径以配置文件所在目录为基准

#### 3) 构建
```bash
./gradlew bootJar
```
Windows：
```bat
gradlew.bat bootJar
```

#### 4) 运行
```bash
java -jar build/libs/TS3AudioBot-0.0.1-SNAPSHOT.jar
```

#### 5) 首次登录
启动后访问控制台首页，首次会跳转到 `/setup` 创建管理员账号，完成后即可登录使用。

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

### 数据与存储
- 机器人/管理员配置：`data/ts3audiobot.db`
- 播放队列：`data/queues.json`

### 常见问题
- **FFmpeg 找不到**：确认系统 PATH，或将可执行文件放入 `ffmpeg/`
- **yt-dlp 不可用**：安装并加入 PATH，或在配置文件中指定绝对路径

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
