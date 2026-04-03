# TeamSpeak3 Server Reverse Engineering Notes

Last updated: 2026-04-03  
Target package: `teamspeak3-server_linux_amd64`  
Workspace path: `D:\IdeaProjects\model-response-adapter\TS3AudioBot\teamspeak3-server_linux_amd64`

## 1) Scope and method

This document is a static reverse-analysis baseline for future feature work.

- What is covered:
  - Binary shape and dependency graph
  - Startup/runtime architecture
  - Configuration and protocol surfaces
  - File-transfer and avatar-related behavior
  - DB schema entry points and permission model anchors
  - Practical troubleshooting playbook
- What is not covered yet:
  - Live dynamic tracing on a running Linux host
  - Deep disassembly-level call graph recovery for every code path
  - Full protocol grammar for voice packet internals

Evidence sources used in this package:

- `ts3server`, `libts3_ssh.so`, `libts3db_*.so`, `tsdns/tsdnsserver`
- `doc/server_quickstart.md`
- `doc/webquery.md`
- `doc/serverquery/serverquery.html`
- `serverquerydocs/*.txt`
- `sql/*.sql`
- `CHANGELOG`

Project-side integration mapping:

- `src/main/java/pub/longyi/ts3audiobot/ts3/full/TsFullClient.java`
- `src/main/java/pub/longyi/ts3audiobot/bot/BotInstance.java`

---

## 2) Package anatomy (server side)

Core executables and modules:

- `ts3server` (main daemon, ELF64)
- `libts3_ssh.so` (ServerQuery over SSH support)
- `libts3db_sqlite3.so` (default DB plugin)
- `libts3db_mariadb.so` (MariaDB/MySQL DB plugin)
- `libts3db_postgresql.so` (PostgreSQL DB plugin)
- `tsdns/tsdnsserver` (optional TSDNS resolver service)

Operational docs:

- `doc/server_quickstart.md` (ports, params, db plugins, deploy basics)
- `doc/webquery.md` (HTTP query API semantics)
- `serverquerydocs/*.txt` and `doc/serverquery/serverquery.html` (query command reference)

Data layer assets:

- `sql/create_*/*` (schema bootstrap scripts)
- `sql/defaults.sql` (default properties/permissions)
- `sql/update_*.sql` (schema migrations)

---

## 3) Binary fingerprint and dependency facts

From ELF header/sections of `ts3server`:

- ELF class: 64-bit
- Machine: x86_64 (machine id 62)
- Entry: `0x4230f0`
- Section facts:
  - `.text` present (large, executable)
  - `.rodata` present
  - `.dynsym/.dynstr` present
  - no `.symtab` visible in section list (release-style stripped symbols)

Dynamic dependency facts recovered from ELF dynamic table:

- `ts3server` depends on:
  - `libts3_ssh.so`
  - `libdl.so.2`, `librt.so.1`, `libm.so.6`, `libpthread.so.0`, `libstdc++.so.6`, `libc.so.6`
- runtime search path:
  - `DT_RPATH=$ORIGIN:$ORIGIN/lib/`
- DB plugins:
  - `libts3db_mariadb.so` -> `libmariadb.so.2`
  - `libts3db_postgresql.so` -> `libpq.so.5`
  - `libts3db_sqlite3.so` uses system C/C++ runtime only

Implication:

- Server is modular at least on:
  - Query transport (`libts3_ssh.so`)
  - DB backend plugins

---

## 4) Runtime architecture reconstruction

High-level process model:

1. `ts3server` process boots and loads DB plugin (`dbplugin`).
2. A server instance container starts.
3. One or more virtual servers run inside the same process.
4. Subsystems expose network interfaces:
   - voice
   - file transfer
   - ServerQuery (raw/ssh/http)

Boot scripts:

- `ts3server_startscript.sh`: start/stop/status, daemon mode, PID handling
- `ts3server_minimal_runscript.sh`: direct `exec ./ts3server $@`

License gate:

- Startup requires explicit license acceptance (file/env/param), from `doc/server_quickstart.md`.

---

## 5) Network surfaces and default ports

From `doc/server_quickstart.md` + query docs + binary strings:

- Voice:
  - UDP `9987` default (`default_voice_port`)
- File transfer:
  - TCP `30033` default (`filetransfer_port`)
- ServerQuery raw:
  - TCP `10011` default (`query_port`)
- ServerQuery SSH:
  - TCP `10022` default (`query_ssh_port`)
- WebQuery HTTP:
  - TCP `10080` default (`query_http_port`)

Hidden/less-documented knobs discovered in binary strings:

- `query_https_port`
- `query_https_ip`
- `query_https_certificate_file`
- `query_https_private_key_file`
- `http_file_transfer_settings_file`

[Inference] These indicate an HTTPS-capable query path in this binary family, even if not fully described in the bundled quickstart text.

---

## 6) Query protocol and command surface map

Known query transports:

- raw (telnet-style command stream)
- ssh
- http (WebQuery JSON wrapper)

WebQuery note (`doc/webquery.md`):

- `ft*` command family is not supported via WebQuery.
- File-transfer control is therefore primarily through raw/ssh query API.

Command families (from `serverquerydocs` and `serverquery.html`):

- Instance/host: `hostinfo`, `instanceinfo`, `instanceedit`, `bindinglist`
- Virtual server lifecycle: `serverlist`, `servercreate`, `serverstart`, `serverstop`, `serverdelete`
- Channel/user/group/permission ops
- File transfer: `ftinitupload`, `ftinitdownload`, `ftlist`, `ftstop`, file/dir ops
- Tokens/api keys/login management
- Snapshot/import/export/control

Useful anchor commands for diagnostics:

- `instanceinfo`
- `bindinglist subsystem=filetransfer`
- `serverinfo`
- `ftlist`
- `permissionlist`

---

## 7) File-transfer reverse path (key for avatar and future media ops)

Reconstructed flow (server docs + project client implementation):

1. Query control plane initializes transfer:
   - command: `ftinitupload ... size=... overwrite=... resume=... [proto=0|1]`
2. Server returns:
   - `ftkey`
   - `port`
   - optional `ip`
3. Data plane opens TCP connection to filetransfer endpoint.
4. Client sends transfer key preface and payload bytes.
5. Server responds with transfer status (`error id=... msg=...`) or closes.

Package evidence:

- `serverquerydocs/ftinitupload.txt`
- `serverquerydocs/ftinitdownload.txt`
- `serverquerydocs/ftlist.txt`
- `CHANGELOG` notes around 3.0.13.x:
  - new file transfer implementation
  - stricter source IP acceptance for transfer sockets
  - idle timing constraints after connect

Important behavior constraints from changelog/history:

- Transfer socket may reject non-matching source IP relative to init-query origin.
- Transfer path is timing-sensitive after connect.
- Unix filetransfer depends on `aio*` routines (binary contains explicit warning string).

---

## 8) Avatar-related behavior model

Server-side clues:

- Client property includes avatar flag:
  - `CLIENT_FLAG_AVATAR` in `doc/serverquery/serverquery.html`
- Permission model includes avatar controls:
  - `i_client_max_avatar_filesize`
  - `b_client_avatar_delete_other`
  - related `i_needed_modify_power_*`
- Defaults in `sql/defaults.sql` include:
  - `i_client_max_avatar_filesize = 200000` for default groups

Project-side implementation map:

- Avatar update orchestration:
  - `TsFullClient.updateClientAvatar(...)` at `src/main/java/pub/longyi/ts3audiobot/ts3/full/TsFullClient.java:292`
- Upload init:
  - `uploadAvatarBytes(...)` at `src/main/java/pub/longyi/ts3audiobot/ts3/full/TsFullClient.java:368`
  - uses `ftinitupload` with `proto=1`
- Data upload:
  - `uploadFileTransferPayload(...)` at `src/main/java/pub/longyi/ts3audiobot/ts3/full/TsFullClient.java:1738`
  - waits for ack via `readFileTransferAck(...)` at `src/main/java/pub/longyi/ts3audiobot/ts3/full/TsFullClient.java:1777`
- Avatar compression retry:
  - `compressAvatarBytes(...)` at `src/main/java/pub/longyi/ts3audiobot/ts3/full/TsFullClient.java:449`
- Profile sync caller:
  - `BotInstance.applyClientProfile(...)` around `src/main/java/pub/longyi/ts3audiobot/bot/BotInstance.java:598`

---

## 9) Analysis of the observed `Connection reset` during avatar upload

Observed sequence (from your runtime logs):

1. `avatar upload init ok ... port=30033 bytes=18987`
2. `avatar upload payload sent ...`
3. `avatar upload transport failed ... java.net.SocketException: Connection reset`

Interpretation:

- Failure point is data-plane ack read, not query init.
- Control plane (`ftinitupload`) succeeded.
- Transport was actively reset by peer/network path.

Most likely buckets:

1. Source-IP mismatch policy on transfer socket
   - Server changelog explicitly mentions strict matching behavior in file transfer path.
   - Common trigger: LB/proxy/NAT asymmetry between query and transfer connection.
2. Transfer socket timing/idle constraints
   - Historical note mentions aggressive idle behavior after connect.
3. ftkey validity/race window issues
   - If key/session invalidates quickly, server can close/reset.
4. Middlebox behavior
   - TCP proxy/firewall resets if payload pattern/flow not allowed.

Less likely in this specific case:

- Avatar size limit rejection (size was 18987, below 200000 default).
- Permission denial at `ftinitupload` phase (init was successful).

Secondary issue seen in your logs:

- Compression retry produced `candidate=0` (`avatar compress failed`), indicating local recompression branch failure (image codec/format/logic path), independent from remote reset root cause.

---

## 10) Reverse-derived config checklist for stable operations

Instance-level:

- Keep query and filetransfer endpoints routable and source-consistent.
- Verify:
  - `filetransfer_port`
  - `filetransfer_ip`
  - `query_port` / `query_ssh_port` / `query_http_port`
  - `query_protocols`
- Check bind exposure:
  - `bindinglist subsystem=filetransfer`

Permission-level:

- Ensure bot identity/group has:
  - `i_ft_file_upload_power >= i_ft_needed_file_upload_power`
  - sane quota settings (`i_ft_quota_mb_upload_per_client`)
  - avatar-related modify power if needed

Logging and observability:

- Enable/verify:
  - `virtualserver_log_filetransfer`
  - `logquerycommands` (for deep query trace)

---

## 11) Feature development map (what this reverse baseline enables)

Based on current artifact analysis, future features can be built with these stable control points:

- Channel and group automation:
  - `channel*`, `servergroup*`, `channelgroup*`, `perm*`
- Identity and role workflows:
  - `token*`, `apikey*`, `querylogin*`
- Media/file management:
  - `ft*` command family (raw/ssh query path)
- Server lifecycle/orchestration:
  - `serverstart`, `serverstop`, `servercreate`, snapshots
- Diagnostics dashboard:
  - `hostinfo`, `instanceinfo`, `serverrequestconnectioninfo`, `ftlist`

Recommendation:

- Treat raw/ssh ServerQuery as authoritative control plane.
- Use WebQuery mainly for REST-friendly non-`ft*` operations.

---

## 12) Quick evidence commands (for future debugging sessions)

Inside `teamspeak3-server_linux_amd64`:

```powershell
# list filetransfer bind addresses
# (run in a ServerQuery session)
bindinglist subsystem=filetransfer

# show instance settings (includes filetransfer port)
instanceinfo

# list active transfers
ftlist
```

In project repo:

```powershell
# locate avatar/filetransfer implementation
rg -n "updateClientAvatar|ftinitupload|uploadFileTransferPayload|readFileTransferAck" src/main/java

# locate profile sync and caller context
rg -n "applyClientProfile|profile avatar update failed" src/main/java
```

---

## 13) Current confidence and open items

Confidence:

- High for:
  - subsystem boundaries
  - port/config surfaces
  - query/filetransfer control flow
  - permission/schema anchors
- Medium for:
  - undocumented HTTPS query parameters semantics
  - precise server-side close/reset branch conditions without live tracing

Open items for phase-2 dynamic reverse:

1. Capture server-side logs at filetransfer reset timestamp.
2. Compare source IP seen by query socket vs filetransfer socket.
3. Replay transfer with official TS client and current bot, diff packet timing.
4. Validate whether `proto=0` vs `proto=1` changes reset behavior.
5. Add optional retry with fresh `ftinitupload` on reset before recompress branch.

