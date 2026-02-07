package pub.longyi.ts3audiobot.ts3.full;

import lombok.extern.slf4j.Slf4j;
import pub.longyi.ts3audiobot.ts3.Ts3VoiceClient;
import pub.longyi.ts3audiobot.ts3.command.ParsedCommand;
import pub.longyi.ts3audiobot.ts3.command.TsCommandBuilder;
import pub.longyi.ts3audiobot.ts3.command.TsCommandParser;
import pub.longyi.ts3audiobot.ts3.protocol.Packet;
import pub.longyi.ts3audiobot.ts3.protocol.PacketDirection;
import pub.longyi.ts3audiobot.ts3.protocol.PacketFlags;
import pub.longyi.ts3audiobot.ts3.protocol.PacketKind;
import pub.longyi.ts3audiobot.ts3.protocol.PacketStatistics;
import pub.longyi.ts3audiobot.ts3.protocol.PacketType;
import pub.longyi.ts3audiobot.ts3.util.QuickLZ;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 TsFullClient 相关功能。
 */


/**
 * TsFullClient 相关功能。
 *
 * <p>职责：负责 TsFullClient 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class TsFullClient implements Ts3VoiceClient {
    private final PacketHandler packetHandler = new PacketHandler();
    private final int[] packetCounters = new int[PacketType.values().length];
    private final int[] generationCounters = new int[PacketType.values().length];
    private volatile boolean connected;
    private ConnectionDataFull connectionData;
    private TsCrypt tsCrypt;
    private volatile boolean initComplete;
    private volatile byte voiceCodec = 0x05;
    private volatile int lastClientInitPacketId = -1;
    private final CommandQueue commandQueue = new CommandQueue();
    private final CommandQueue commandLowQueue = new CommandQueue();
    private volatile long lastConnectionInfoSentAt;
    private volatile boolean voiceSessionActive;
    private volatile int voiceFlaggedRemaining;
    private volatile byte voiceSessionId = 1;
    private volatile String currentChannelId;
    private volatile boolean channelListRequested;
    private final Object responseLock = new Object();
    private volatile PendingCommand pendingCommand;
    private int returnCodeCounter = 1;
    private volatile Consumer<ParsedCommand> errorListener;
    private volatile Runnable loginListener;
    private volatile Consumer<String> stopListener;

    private static final int MAX_PACKET_SIZE = 500;
    private static final int MAX_COMMAND_FRAGMENT = 40960;
    private static final int MAX_QUEUE_LEN = 200;
    private static final int MAX_DECOMPRESSED_SIZE = 1024 * 1024;
    private static final int VOICE_FLAGGED_PACKETS = 5;
    private static final byte VOICE_SESSION_MAX = 7;
    private static final long COMMAND_RESPONSE_TIMEOUT_MS = 5000L;
    private static final int CHANNEL_LIST_RETRY_MAX = 3;
    private static final long CHANNEL_LIST_RETRY_DELAY_MS = 1000L;
    private static final String CHANNEL_ID_PREFIX = "cid=";
    private static final String CHANNEL_ID_MARKER = "#";
    private static final String CHANNEL_PATH_SEPARATOR = "/";
    private static final String CHANNEL_ROOT_PARENT = "0";
    private static final String CHANNEL_ID_PATTERN = "\\d+";

    /**
     * 执行 configure 操作。
     * @param config 参数 config
     */
    @Override
    public void configure(ConnectionDataFull config) {
        this.connectionData = config;
        this.tsCrypt = new TsCrypt(config.identity());
    }


    /**
     * 执行 connect 操作。
     * @param address 参数 address
     * @param channel 参数 channel
     */
    @Override
    public void connect(String address, String channel) {
        if (connectionData == null) {
            throw new IllegalStateException("ConnectionData not configured");
        }
        resetCounters();
        InetSocketAddress endpoint = parseAddress(address);
        tsCrypt.setClientIp(resolveClientIp(endpoint));
        log.info("[TS3] identity uid={} nickname={}",
            connectionData.identity().clientUid(),
            connectionData.username()
        );
        log.info("[TS3] full client connecting to {} channel={}", endpoint, channel);

        packetHandler.setPacketEvent(this::onPacket);
        packetHandler.setStopEvent(reason -> {
            connected = false;
            log.info("[TS3] connection closed: {}", reason);
            Consumer<String> listener = stopListener;
            if (listener != null) {
                listener.accept(reason);
            }
        });

        if (packetHandler.connect(endpoint, tsCrypt)) {
            connected = false;
            log.info("[TS3] init1 handshake start");
            sendInit1(tsCrypt.processInit1(PacketDirection.C2S, null));
        }
    }


    /**
     * 执行 disconnect 操作。
     */
    @Override
    public void disconnect() {
        log.info("[TS3] full client disconnect requested");
        if (connected) {
            try {
                sendCommand("clientdisconnect reasonid=8 reasonmsg=leaving");
            } catch (Exception ex) {
                log.debug("[TS3] clientdisconnect failed", ex);
            }
        }
        resetVoiceSession();
        packetHandler.stop();
        connected = false;
    }


    /**
     * 执行 setErrorListener 操作。
     * @param listener 参数 listener
     */
    public void setErrorListener(Consumer<ParsedCommand> listener) {
        this.errorListener = listener;
    }


    /**
     * 执行 setLoginListener 操作。
     * @param listener 参数 listener
     */
    public void setLoginListener(Runnable listener) {
        this.loginListener = listener;
    }


    /**
     * 执行 setStopListener 操作。
     * @param listener 参数 listener
     */
    public void setStopListener(Consumer<String> listener) {
        this.stopListener = listener;
    }


    /**
     * 执行 isConnected 操作。
     * @return 返回值
     */
    @Override
    public boolean isConnected() {
        return connected;
    }


    /**
     * 执行 sendOpusFrame 操作。
     * @param data 参数 data
     * @param length 参数 length
     */
    @Override
    public void sendOpusFrame(byte[] data, int length) {
        if (!connected) {
            return;
        }
        if (data == null || length < 0) {
            return;
        }
        if (length == 0) {
            sendVoicePacket(new byte[0], 0, false);
            resetVoiceSession();
            return;
        }
        if (!voiceSessionActive) {
            voiceSessionActive = true;
            voiceFlaggedRemaining = VOICE_FLAGGED_PACKETS;
        }
        boolean flagSession = voiceFlaggedRemaining > 0;
        if (flagSession) {
            voiceFlaggedRemaining--;
        }
        sendVoicePacket(data, length, flagSession);
    }


    /**
     * 执行 fetchChannelCodecInfo 操作。
     * @return 返回值
     */
    public ChannelCodecInfo fetchChannelCodecInfo() {
        if (!connected) {
            log.info("[TS3] channelinfo skipped: not connected");
            return null;
        }
        String channelId = currentChannelId;
        if (channelId == null || channelId.isBlank()) {
            channelId = resolveChannelIdFromClientInfo();
        }
        if (channelId == null || channelId.isBlank()) {
            channelId = resolveChannelIdFromClientList();
        }
        if (channelId == null || channelId.isBlank()) {
            log.warn("[TS3] channelinfo skipped: channelId not resolved");
            return null;
        }
        log.info("[TS3] channelinfo cid={}", channelId);
        List<ParsedCommand> channelInfo = requestCommand(
            "channelinfo",
            TsCommandBuilder.params("cid", channelId)
        );
        if (channelInfo.isEmpty()) {
            log.warn("[TS3] channelinfo empty cid={}", channelId);
        }
        String codecStr = null;
        String qualityStr = null;
        for (ParsedCommand cmd : channelInfo) {
            if ("channelinfo".equalsIgnoreCase(cmd.name())) {
                codecStr = cmd.params().get("channel_codec");
                qualityStr = cmd.params().get("channel_codec_quality");
                log.info("[TS3] channelinfo params={}", cmd.params());
                break;
            }
        }
        Integer codec = parseInt(codecStr);
        Integer quality = parseInt(qualityStr);
        if (codec == null || quality == null) {
            log.warn("[TS3] channelinfo missing codec/quality codec={} quality={}", codecStr, qualityStr);
            return null;
        }
        applyChannelCodec(codec);
        return new ChannelCodecInfo(codec, quality);
    }

    private void sendVoicePacket(byte[] data, int length, boolean flagSession) {
        if (!connected) {
            return;
        }
        PacketCounter counter = nextPacket(PacketType.VOICE);
        int packetId = counter.id;
        int extra = flagSession ? 1 : 0;
        byte[] voiceData = new byte[length + 3 + extra];
        voiceData[0] = (byte) ((packetId >> 8) & 0xFF);
        voiceData[1] = (byte) (packetId & 0xFF);
        voiceData[2] = voiceCodec;
        System.arraycopy(data, 0, voiceData, 3, length);
        if (flagSession) {
            voiceData[3 + length] = voiceSessionId;
        }

        Packet voicePacket = new Packet(PacketDirection.C2S, PacketType.VOICE, packetId, counter.generation, voiceData);
        voicePacket.setClientId(packetHandler.getClientId());
        if (flagSession) {
            voicePacket.setFlag(PacketFlags.COMPRESSED, true);
        }
        packetHandler.addOutgoingPacket(voicePacket);
    }

    private void resetVoiceSession() {
        voiceSessionActive = false;
        voiceFlaggedRemaining = VOICE_FLAGGED_PACKETS;
        voiceSessionId++;
        if (voiceSessionId > VOICE_SESSION_MAX) {
            voiceSessionId = 1;
        }
    }

    private void onPacket(Packet packet) {
        if (packet.getPacketType() == PacketType.INIT1) {
            log.debug("[TS3] recv init1 len={}", packet.getData() == null ? 0 : packet.getData().length);
            try {
                byte[] response = tsCrypt.processInit1(PacketDirection.S2C, packet.getData());
                if (response.length > 0) {
                    sendInit1(response);
                } else if (!initComplete) {
                    initComplete = true;
                    log.info("[TS3] init1 handshake completed");
                }
            } catch (Exception ex) {
                log.error("[TS3] init1 failed", ex);
            }
            return;
        }
        if (packet.getPacketType() == PacketType.COMMAND || packet.getPacketType() == PacketType.COMMAND_LOW) {
            sendAck(packet);
            List<byte[]> payloads = assembleCommand(packet);
            for (byte[] payload : payloads) {
                if (payload != null && payload.length > 0) {
                    handleCommandPayload(payload);
                }
            }
            return;
        }
        if (packet.getPacketType() == PacketType.PING) {
            sendPong(packet);
            return;
        }
    }

    private void sendInit1(byte[] payload) {
        PacketCounter counter = nextPacket(PacketType.INIT1);
        log.debug("[TS3] send init1 id={} gen={} len={}",
            counter.id,
            counter.generation,
            payload == null ? 0 : payload.length
        );
        Packet initPacket = new Packet(PacketDirection.C2S, PacketType.INIT1, counter.id, counter.generation, payload);
        initPacket.setClientId(packetHandler.getClientId());
        packetHandler.addOutgoingPacket(initPacket);
    }

    private void sendAck(Packet packet) {
        PacketType ackType = packet.getPacketType() == PacketType.COMMAND ? PacketType.ACK : PacketType.ACK_LOW;
        byte[] ackData = new byte[2];
        ackData[0] = (byte) ((packet.getPacketId() >> 8) & 0xFF);
        ackData[1] = (byte) (packet.getPacketId() & 0xFF);
        PacketCounter counter = nextPacket(ackType);
        Packet ack = new Packet(PacketDirection.C2S, ackType, counter.id, counter.generation, ackData);
        ack.setClientId(packetHandler.getClientId());
        if (ackType == PacketType.ACK_LOW) {
            ack.setFlag(PacketFlags.UNENCRYPTED, true);
        }
        packetHandler.addOutgoingPacket(ack);
    }

    private void sendPong(Packet ping) {
        byte[] pongData = new byte[2];
        pongData[0] = (byte) ((ping.getPacketId() >> 8) & 0xFF);
        pongData[1] = (byte) (ping.getPacketId() & 0xFF);
        PacketCounter counter = nextPacket(PacketType.PONG);
        Packet pong = new Packet(PacketDirection.C2S, PacketType.PONG, counter.id, counter.generation, pongData);
        pong.setClientId(packetHandler.getClientId());
        pong.setFlag(PacketFlags.UNENCRYPTED, true);
        packetHandler.addOutgoingPacket(pong);
    }

    private void sendClientInit() {
        if (connectionData == null) {
            return;
        }
        log.info("[TS3] send clientinit nickname={} channel={} serverPwd={} "
                + "channelPwd={} clientVersion={} platform={} signLen={}",
            connectionData.username(),
            connectionData.defaultChannel(),
            connectionData.serverPassword().hashedPassword().isEmpty() ? "no" : "yes",
            connectionData.defaultChannelPassword().hashedPassword().isEmpty() ? "no" : "yes",
            connectionData.versionSign().rawVersion(),
            connectionData.versionSign().platform(),
            connectionData.versionSign().sign() == null ? 0 : connectionData.versionSign().sign().length()
        );
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_nickname", connectionData.username());
        params.put("client_version", connectionData.versionSign().rawVersion());
        params.put("client_platform", connectionData.versionSign().platform());
        params.put("client_input_hardware", "1");
        params.put("client_output_hardware", "1");
        params.put("client_default_channel", safe(connectionData.defaultChannel()));
        params.put("client_default_channel_password", safePassword(connectionData.defaultChannelPassword()));
        params.put("client_server_password", safePassword(connectionData.serverPassword()));
        params.put("client_nickname_phonetic", safe(connectionData.nicknamePhonetic()));
        params.put("client_meta_data", "");
        params.put("client_default_token", safe(connectionData.defaultToken()));
        params.put("client_version_sign", connectionData.versionSign().sign());
        params.put("client_key_offset", Long.toString(connectionData.identity().validKeyOffset()));
        params.put("hwid", safeOrDefault(connectionData.hwid(), "+LyYqbDqOvEEpN5pdAbF8/v5kZ0="));
        String command = TsCommandBuilder.build("clientinit", params);
        log.info("[TS3] clientinit raw={}", command);
        lastClientInitPacketId = sendCommand(command);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeOrDefault(String value, String fallback) {
        String resolved = value == null ? "" : value;
        return resolved.isBlank() ? fallback : resolved;
    }

    private String safePassword(Password password) {
        if (password == null) {
            return "";
        }
        String hashed = password.hashedPassword();
        return hashed == null ? "" : hashed;
    }

    private int sendCommand(String command) {
        byte[] data = command.getBytes(StandardCharsets.UTF_8);
        String name = command;
        int space = command.indexOf(' ');
        if (space > 0) {
            name = command.substring(0, space);
        }
        return sendCommandPayload(name, data);
    }

    private void handleCommandPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        String message = new String(payload, StandardCharsets.UTF_8);
        for (ParsedCommand cmd : TsCommandParser.parseLines(message)) {
            String name = cmd.name();
            if ("initivexpand".equalsIgnoreCase(name)) {
                handleInitIvExpand(cmd);
            } else if ("initivexpand2".equalsIgnoreCase(name)) {
                handleInitIvExpand2(cmd);
            } else if ("initserver".equalsIgnoreCase(name)) {
                handleInitServer(cmd);
            } else if ("notifyconnectioninforequest".equalsIgnoreCase(name)) {
                handleConnectionInfoRequest();
            } else if ("notifycliententerview".equalsIgnoreCase(name)) {
                handleClientEnterView(cmd);
            } else if ("notifyclientleftview".equalsIgnoreCase(name)) {
                handleClientLeftView(cmd);
            } else if ("notifyclientmoved".equalsIgnoreCase(name)) {
                handleClientMoved(cmd);
            }
            collectCommandResponse(cmd);
        }
    }

    private void handleInitIvExpand(ParsedCommand cmd) {
        log.info("[TS3] recv initivexpand");
        String alpha = cmd.params().get("alpha");
        String beta = cmd.params().get("beta");
        String omega = cmd.params().get("omega");
        String result = tsCrypt.cryptoInit(alpha, beta, omega);
        if (result != null) {
            log.error("[TS3] crypto init failed: {}", result);
            return;
        }
        packetHandler.markInitComplete();
        sendClientInit();
    }

    private void handleInitIvExpand2(ParsedCommand cmd) {
        log.info("[TS3] recv initivexpand2");
        String license = cmd.params().get("license");
        if (license == null) {
            license = cmd.params().get("l");
        }
        String omega = cmd.params().get("omega");
        String proof = cmd.params().get("proof");
        String beta = cmd.params().get("beta");
        if (license == null || omega == null || proof == null || beta == null) {
            log.error("[TS3] initivexpand2 missing fields {}", cmd.params().keySet());
            return;
        }
        if (connectionData == null) {
            log.error("[TS3] initivexpand2 without connection data");
            return;
        }

        TsCrypt.TempKey tmpKey = TsCrypt.generateTemporaryKey();
        String ek = Base64.getEncoder().encodeToString(tmpKey.publicKey());
        byte[] betaBytes;
        try {
            betaBytes = Base64.getDecoder().decode(beta);
        } catch (IllegalArgumentException ex) {
            log.error("[TS3] initivexpand2 beta invalid", ex);
            return;
        }
        if (betaBytes.length != 54) {
            log.error("[TS3] initivexpand2 beta invalid length {}", betaBytes.length);
            return;
        }
        byte[] toSign = new byte[86];
        System.arraycopy(tmpKey.publicKey(), 0, toSign, 0, 32);
        System.arraycopy(betaBytes, 0, toSign, 32, 54);
        byte[] sign = TsCrypt.sign(connectionData.identity().privateKey(), toSign);
        String proofOut = Base64.getEncoder().encodeToString(sign);
        sendClientEk(ek, proofOut);

        String result = tsCrypt.cryptoInit2(license, omega, proof, beta, tmpKey.privateKey());
        if (result != null) {
            log.error("[TS3] crypto init2 failed: {}", result);
            return;
        }
        packetHandler.markInitComplete();
        sendClientInit();
    }

    private void handleInitServer(ParsedCommand cmd) {
        log.info("[TS3] recv initserver");
        if (lastClientInitPacketId >= 0) {
            packetHandler.ackCommand(lastClientInitPacketId);
        }
        if (cmd.params() != null && !cmd.params().isEmpty()) {
            log.info("[TS3] initserver params={}", cmd.params());
            String cid = cmd.params().get("cid");
            if (cid != null && !cid.isBlank()) {
                currentChannelId = cid;
                log.info("[TS3] initserver cid={}", cid);
            }
        }
        String clid = cmd.params().get("aclid");
        if (clid == null) {
            clid = cmd.params().get("client_id");
        }
        if (clid != null) {
            try {
                packetHandler.setClientId(Integer.parseInt(clid));
                log.info("[TS3] connected with client id {}", clid);
            } catch (NumberFormatException ex) {
                log.warn("[TS3] invalid client id {}", clid);
            }
        }
        updateCodec(cmd);
        connected = true;
        log.info("[TS3] login complete");
        Runnable listener = loginListener;
        if (listener != null) {
            listener.run();
        }
        scheduleChannelListPrint();
    }

    private void handleClientEnterView(ParsedCommand cmd) {
        String clid = cmd.params().get("clid");
        String cid = cmd.params().get("cid");
        if (clid == null || cid == null) {
            return;
        }
        int clientId = packetHandler.getClientId();
        if (clientId <= 0) {
            return;
        }
        Integer target = parseInt(clid);
        if (target != null && target == clientId) {
            currentChannelId = cid;
            log.info("[TS3] client entered channel cid={}", cid);
        }
    }

    private void handleClientLeftView(ParsedCommand cmd) {
        String clid = cmd.params().get("clid");
        if (clid == null) {
            return;
        }
        int clientId = packetHandler.getClientId();
        if (clientId <= 0) {
            return;
        }
        Integer target = parseInt(clid);
        if (target != null && target == clientId) {
            currentChannelId = null;
            log.info("[TS3] client left channel");
        }
    }

    private void handleClientMoved(ParsedCommand cmd) {
        String clid = cmd.params().get("clid");
        String cid = cmd.params().get("cid");
        if (clid == null || cid == null) {
            return;
        }
        int clientId = packetHandler.getClientId();
        if (clientId <= 0) {
            return;
        }
        Integer target = parseInt(clid);
        if (target != null && target == clientId) {
            currentChannelId = cid;
            log.info("[TS3] client moved channel cid={}", cid);
        }
    }

    private void scheduleChannelListPrint() {
        if (channelListRequested) {
            return;
        }
        channelListRequested = true;
        Thread worker = new Thread(this::printChannelListWithRetry, "ts3-channel-list");
        worker.setDaemon(true);
        worker.start();
    }

    private void printChannelListWithRetry() {
        for (int attempt = 1; attempt <= CHANNEL_LIST_RETRY_MAX; attempt++) {
            if (!connected) {
                log.info("[TS3] channellist skipped: not connected");
                return;
            }
            List<ParsedCommand> commands = requestCommand("channellist", Map.of());
            List<ChannelSnapshot> channels = parseChannelSnapshots(commands);
            if (!channels.isEmpty()) {
                log.info("[TS3] channel list entries={}", channels.size());
                for (ChannelSnapshot channel : channels) {
                    log.info("[TS3] channel params={}", channel.params);
                    log.info(
                        "[TS3] channel cid={} pid={} order={} name={}",
                        channel.cid,
                        channel.parentCid,
                        channel.order,
                        channel.name
                    );
                }
                attemptJoinConfiguredChannel(channels);
                return;
            }
            if (attempt < CHANNEL_LIST_RETRY_MAX) {
                try {
                    Thread.sleep(CHANNEL_LIST_RETRY_DELAY_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("[TS3] channellist empty");
    }

    private List<ChannelSnapshot> parseChannelSnapshots(List<ParsedCommand> commands) {
        List<ChannelSnapshot> channels = new ArrayList<>();
        if (commands == null || commands.isEmpty()) {
            return channels;
        }
        for (ParsedCommand cmd : commands) {
            if (!isChannelEntry(cmd)) {
                continue;
            }
            Map<String, String> params = cmd.params();
            String cid = params.get("cid");
            if (cid == null && cmd.name() != null && cmd.name().startsWith("cid=")) {
                cid = cmd.name().substring(4);
            }
            if (cid == null || cid.isBlank()) {
                continue;
            }
            String parentCid = params.get("cpid");
            if (parentCid == null || parentCid.isBlank()) {
                parentCid = "0";
            }
            String name = params.get("channel_name");
            if (name == null) {
                name = "";
            }
            String order = params.get("channel_order");
            channels.add(new ChannelSnapshot(cid, parentCid, order, name, params));
        }
        return channels;
    }

    private void attemptJoinConfiguredChannel(List<ChannelSnapshot> channels) {
        ConnectionDataFull data = connectionData;
        if (data == null) {
            return;
        }
        String target = data.defaultChannel();
        if (target == null || target.isBlank()) {
            return;
        }
        String targetCid = resolveChannelId(channels, target);
        if (targetCid == null || targetCid.isBlank()) {
            log.warn("[TS3] target channel not found: {}", target);
            return;
        }
        String currentCid = currentChannelId;
        if (currentCid != null && currentCid.equals(targetCid)) {
            return;
        }
        int clientId = packetHandler.getClientId();
        if (clientId <= 0) {
            log.warn("[TS3] clientmove skipped: clientId not ready");
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("clid", Integer.toString(clientId));
        params.put("cid", targetCid);
        String cpw = data.defaultChannelPassword().hashedPassword();
        if (cpw != null && !cpw.isBlank()) {
            params.put("cpw", cpw);
        }
        log.info("[TS3] request move to channel cid={} name={}", targetCid, target);
        requestCommand("clientmove", params);
    }

    private String resolveChannelId(List<ChannelSnapshot> channels, String target) {
        if (target == null) {
            return null;
        }
        String trimmed = target.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        if (trimmed.startsWith(CHANNEL_ID_PREFIX)) {
            return trimmed.substring(CHANNEL_ID_PREFIX.length());
        }
        if (trimmed.startsWith(CHANNEL_ID_MARKER)) {
            return trimmed.substring(CHANNEL_ID_MARKER.length());
        }
        if (trimmed.contains(CHANNEL_PATH_SEPARATOR)) {
            Map<String, List<ChannelSnapshot>> byParent = groupByParent(channels);
            return resolveChannelPath(byParent, trimmed);
        }
        for (ChannelSnapshot channel : channels) {
            if (trimmed.equals(channel.name)) {
                return channel.cid;
            }
        }
        if (trimmed.matches(CHANNEL_ID_PATTERN)) {
            return trimmed;
        }
        return null;
    }

    private Map<String, List<ChannelSnapshot>> groupByParent(List<ChannelSnapshot> channels) {
        Map<String, List<ChannelSnapshot>> byParent = new LinkedHashMap<>();
        for (ChannelSnapshot channel : channels) {
            byParent.computeIfAbsent(channel.parentCid, key -> new ArrayList<>()).add(channel);
        }
        return byParent;
    }

    private String resolveChannelPath(Map<String, List<ChannelSnapshot>> byParent, String path) {
        String parent = CHANNEL_ROOT_PARENT;
        String[] parts = path.split(CHANNEL_PATH_SEPARATOR);
        for (String part : parts) {
            String name = part.trim();
            if (name.isBlank()) {
                continue;
            }
            List<ChannelSnapshot> children = byParent.get(parent);
            if (children == null || children.isEmpty()) {
                return null;
            }
            ChannelSnapshot match = findChildByName(children, name, parent);
            if (match == null) {
                return null;
            }
            parent = match.cid;
        }
        return parent;
    }

    private ChannelSnapshot findChildByName(
        List<ChannelSnapshot> children,
        String name,
        String parent
    ) {
        ChannelSnapshot match = null;
        for (ChannelSnapshot child : children) {
            if (!name.equals(child.name)) {
                continue;
            }
            if (match != null) {
                log.warn("[TS3] duplicate channel name under parent {}: {}", parent, name);
                return match;
            }
            match = child;
        }
        return match;
    }

    private boolean isChannelEntry(ParsedCommand cmd) {
        if (cmd == null) {
            return false;
        }
        String name = cmd.name();
        if ("channellist".equalsIgnoreCase(name)) {
            return true;
        }
        Map<String, String> params = cmd.params();
        if (params == null || params.isEmpty()) {
            return false;
        }
        return params.containsKey("channel_name");
    }


    /**
     * ChannelSnapshot 相关功能。
     *
     * <p>职责：负责 ChannelSnapshot 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    private static final class ChannelSnapshot {
        private final String cid;
        private final String parentCid;
        private final String order;
        private final String name;
        private final Map<String, String> params;

        private ChannelSnapshot(String cid, String parentCid, String order, String name, Map<String, String> params) {
            this.cid = cid;
            this.parentCid = parentCid;
            this.order = order;
            this.name = name;
            this.params = params;
        }
    }

    private void handleConnectionInfoRequest() {
        long now = System.currentTimeMillis();
        if (now - lastConnectionInfoSentAt < 1000L) {
            return;
        }
        lastConnectionInfoSentAt = now;
        PacketHandler.PingStats ping = packetHandler.getPingStats();
        double pingMs = Math.round(ping.pingSeconds() * 1000.0);
        double devMs = Math.round(ping.deviationSeconds() * 1000.0 * 1000.0) / 1000.0;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("connection_ping", Long.toString((long) pingMs));
        params.put("connection_ping_deviation", Double.toString(devMs));

        for (PacketKind kind : PacketKind.values()) {
            PacketStatistics stats = packetHandler.getStats(kind);
            String name = kind.label();
            params.put("connection_packets_sent_" + name, Integer.toString(stats.getSentPackets()));
            params.put("connection_packets_received_" + name, Integer.toString(stats.getReceivedPackets()));
            params.put("connection_bytes_sent_" + name, Integer.toString(stats.getSentBytes()));
            params.put("connection_bytes_received_" + name, Integer.toString(stats.getReceivedBytes()));
            params.put("connection_server2client_packetloss_" + name, "0");
            params.put(
                "connection_bandwidth_sent_last_second_" + name,
                Integer.toString(stats.getSentBytesLastSecond())
            );
            params.put(
                "connection_bandwidth_sent_last_minute_" + name,
                Integer.toString(stats.getSentBytesLastMinute())
            );
            params.put(
                "connection_bandwidth_received_last_second_" + name,
                Integer.toString(stats.getReceivedBytesLastSecond())
            );
            params.put(
                "connection_bandwidth_received_last_minute_" + name,
                Integer.toString(stats.getReceivedBytesLastMinute())
            );
        }
        params.put("connection_server2client_packetloss_total", "0");

        String command = TsCommandBuilder.build("setconnectioninfo", params);
        sendCommand(command);
    }

    private void updateCodec(ParsedCommand cmd) {
        String codecStr = cmd.params().get("virtualserver_codec");
        if (codecStr == null) {
            codecStr = cmd.params().get("virtualserver_default_channel_codec");
        }
        if (codecStr == null) {
            return;
        }
        try {
            int codec = Integer.parseInt(codecStr);
            if (codec == 4 || codec == 5) {
                voiceCodec = (byte) codec;
                log.info("[TS3] server codec {}", codec);
            } else {
                log.warn("[TS3] unsupported server codec {}, forcing opus music", codec);
                voiceCodec = 0x05;
            }
        } catch (NumberFormatException ex) {
            log.warn("[TS3] invalid codec value {}", codecStr);
        }
    }

    private void applyChannelCodec(int codec) {
        if (codec == 4 || codec == 5) {
            voiceCodec = (byte) codec;
        }
    }

    private String resolveChannelIdFromClientInfo() {
        int clientId = packetHandler.getClientId();
        if (clientId <= 0) {
            log.info("[TS3] clientinfo skipped: clientId not ready");
            return null;
        }
        List<ParsedCommand> clientInfo = requestCommand(
            "clientinfo",
            TsCommandBuilder.params("clid", Integer.toString(clientId))
        );
        if (clientInfo.isEmpty()) {
            log.warn("[TS3] clientinfo empty, cannot resolve channel");
        }
        String channelId = null;
        for (ParsedCommand cmd : clientInfo) {
            if ("clientinfo".equalsIgnoreCase(cmd.name())) {
                channelId = cmd.params().get("cid");
                break;
            }
        }
        if (channelId == null || channelId.isBlank()) {
            log.warn("[TS3] clientinfo missing cid");
        } else {
            log.info("[TS3] clientinfo cid={}", channelId);
        }
        return channelId;
    }

    private String resolveChannelIdFromClientList() {
        int clientId = packetHandler.getClientId();
        if (clientId <= 0) {
            log.info("[TS3] clientlist skipped: clientId not ready");
            return null;
        }
        List<ParsedCommand> clientList = requestCommand("clientlist", Map.of());
        if (clientList.isEmpty()) {
            log.warn("[TS3] clientlist empty");
        }
        String channelId = null;
        for (ParsedCommand cmd : clientList) {
            if (!"clientlist".equalsIgnoreCase(cmd.name())) {
                continue;
            }
            String clid = cmd.params().get("clid");
            Integer target = parseInt(clid);
            if (target != null && target == clientId) {
                channelId = cmd.params().get("cid");
                break;
            }
        }
        if (channelId == null || channelId.isBlank()) {
            log.warn("[TS3] clientlist missing cid");
        } else {
            log.info("[TS3] clientlist cid={}", channelId);
        }
        return channelId;
    }

    private void sendClientEk(String ek, String proof) {
        String command = TsCommandBuilder.build("clientek", TsCommandBuilder.params(
            "ek", ek,
            "proof", proof
        ));
        sendCommand(command);
    }

    private synchronized PacketCounter nextPacket(PacketType type) {
        if (type == PacketType.INIT1) {
            return new PacketCounter(101, 0);
        }
        int idx = type.ordinal();
        int id = packetCounters[idx] & 0xFFFF;
        int generation = generationCounters[idx];
        int next = id + 1;
        if (next > 0xFFFF) {
            next = 0;
            generationCounters[idx] = generation + 1;
        }
        packetCounters[idx] = next;
        return new PacketCounter(id, generation);
    }

    private void resetCounters() {
        for (int i = 0; i < packetCounters.length; i++) {
            packetCounters[i] = 0;
            generationCounters[i] = 0;
        }
        int commandIdx = PacketType.COMMAND.ordinal();
        packetCounters[commandIdx] = 1;
        lastClientInitPacketId = -1;
        initComplete = false;
        commandQueue.reset();
        commandLowQueue.reset();
        lastConnectionInfoSentAt = 0L;
        channelListRequested = false;
    }

    private List<ParsedCommand> requestCommand(String name, Map<String, String> params) {
        if (!connected) {
            return List.of();
        }
        PendingCommand pending = new PendingCommand(nextReturnCode());
        synchronized (responseLock) {
            if (pendingCommand != null) {
                return List.of();
            }
            pendingCommand = pending;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (params != null) {
            merged.putAll(params);
        }
        merged.put("return_code", Integer.toString(pending.returnCode));
        String command = TsCommandBuilder.build(name, merged);
        sendCommand(command);
        try {
            CommandResponse response = pending.future.get(COMMAND_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return response.commands;
        } catch (TimeoutException ex) {
            log.warn("[TS3] command timeout name={} returnCode={}", name, pending.returnCode);
            return List.of();
        } catch (Exception ex) {
            log.warn("[TS3] command failed name={} returnCode={}", name, pending.returnCode, ex);
            return List.of();
        } finally {
            synchronized (responseLock) {
                if (pendingCommand == pending) {
                    pendingCommand = null;
                }
            }
        }
    }

    private void collectCommandResponse(ParsedCommand cmd) {
        PendingCommand pending = pendingCommand;
        if (pending == null || cmd == null) {
            if (cmd != null && "error".equalsIgnoreCase(cmd.name())) {
                String returnCodeRaw = cmd.params().get("return_code");
                String errorId = cmd.params().get("id");
                String msg = cmd.params().get("msg");
                log.info("[TS3] error return_code={} id={} msg={}", returnCodeRaw, errorId, msg);
                Consumer<ParsedCommand> listener = errorListener;
                if (listener != null) {
                    listener.accept(cmd);
                }
            }
            return;
        }
        String name = cmd.name();
        if (name == null) {
            return;
        }
        String lower = name.toLowerCase();
        if (lower.startsWith("notify") || "initivexpand".equals(lower) || "initivexpand2".equals(lower)) {
            return;
        }
        if ("initserver".equalsIgnoreCase(name)) {
            return;
        }
        if ("clientinfo".equalsIgnoreCase(name) || "channelinfo".equalsIgnoreCase(name)) {
            log.info("[TS3] recv {} params={}", name, cmd.params());
        }
        if ("error".equalsIgnoreCase(name)) {
            String returnCodeRaw = cmd.params().get("return_code");
            Integer code = parseInt(returnCodeRaw);
            String errorId = cmd.params().get("id");
            String msg = cmd.params().get("msg");
            log.info("[TS3] error return_code={} id={} msg={}", returnCodeRaw, errorId, msg);
            Consumer<ParsedCommand> listener = errorListener;
            if (listener != null) {
                listener.accept(cmd);
            }
            if (code == null || code == pending.returnCode) {
                pending.future.complete(new CommandResponse(pending.returnCode, List.copyOf(pending.commands), cmd));
                synchronized (responseLock) {
                    if (pendingCommand == pending) {
                        pendingCommand = null;
                    }
                }
            }
            return;
        }
        pending.commands.add(cmd);
    }

    private int nextReturnCode() {
        int current = returnCodeCounter;
        returnCodeCounter++;
        if (returnCodeCounter > 0x7FFF) {
            returnCodeCounter = 1;
        }
        return current;
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<byte[]> assembleCommand(Packet packet) {
        CommandQueue queue = packet.getPacketType() == PacketType.COMMAND ? commandQueue : commandLowQueue;
        return queue.accept(packet);
    }

    private int sendCommandPayload(String name, byte[] data) {
        int headerLen = PacketDirection.C2S.headerLength();
        int maxPayload = MAX_PACKET_SIZE - headerLen;
        if (maxPayload <= 0) {
            return -1;
        }

        byte[] payload = data;
        boolean compressed = false;
        if (payload.length > maxPayload) {
            try {
                byte[] compressedData = QuickLZ.compress(payload, 1);
                if (compressedData.length < payload.length) {
                    payload = compressedData;
                    compressed = true;
                }
            } catch (RuntimeException ex) {
                log.warn("[TS3] command compress failed, send uncompressed name={}", name, ex);
            }
        }

        if (payload.length <= maxPayload) {
            PacketCounter counter = nextPacket(PacketType.COMMAND);
            log.info("[TS3] send command {} id={} gen={}", name, counter.id, counter.generation);
            Packet cmdPacket = new Packet(
                PacketDirection.C2S,
                PacketType.COMMAND,
                counter.id,
                counter.generation,
                payload
            );
            cmdPacket.setClientId(packetHandler.getClientId());
            cmdPacket.setFlag(PacketFlags.NEW_PROTOCOL, true);
            if (compressed) {
                cmdPacket.setFlag(PacketFlags.COMPRESSED, true);
            }
            packetHandler.addOutgoingPacket(cmdPacket);
            return counter.id;
        }

        int offset = 0;
        int lastId = -1;
        int remaining = payload.length;
        int fragmentIndex = 0;
        int totalFragments = (payload.length + maxPayload - 1) / maxPayload;
        while (remaining > 0) {
            int len = Math.min(maxPayload, remaining);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, offset, chunk, 0, len);
            PacketCounter counter = nextPacket(PacketType.COMMAND);
            if (fragmentIndex == 0) {
                log.info("[TS3] send command {} id={} gen={} fragments={}",
                    name,
                    counter.id,
                    counter.generation,
                    totalFragments
                );
            } else {
                log.info("[TS3] send command-fragment {} id={} gen={}", name, counter.id, counter.generation);
            }

            Packet cmdPacket = new Packet(
                PacketDirection.C2S,
                PacketType.COMMAND,
                counter.id,
                counter.generation,
                chunk
            );
            cmdPacket.setClientId(packetHandler.getClientId());
            cmdPacket.setFlag(PacketFlags.NEW_PROTOCOL, true);
            boolean isFirst = fragmentIndex == 0;
            boolean isLast = fragmentIndex == totalFragments - 1;
            if (isFirst || isLast) {
                cmdPacket.setFlag(PacketFlags.FRAGMENTED, true);
            }
            if (compressed && isFirst) {
                cmdPacket.setFlag(PacketFlags.COMPRESSED, true);
            }
            packetHandler.addOutgoingPacket(cmdPacket);
            lastId = counter.id;

            offset += len;
            remaining -= len;
            fragmentIndex++;
        }
        return lastId;
    }


    /**
     * CommandQueue 相关功能。
     *
     * <p>职责：负责 CommandQueue 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    private final class CommandQueue {
        private int expectedPacketId = 0;
        private final List<Packet> backlog = new ArrayList<>();
        private ByteArrayOutputStream fragmentBuffer;
        private boolean fragmentCompressed;

        private List<byte[]> accept(Packet packet) {
            List<byte[]> out = new ArrayList<>();
            if (packet.getPacketId() == expectedPacketId) {
                processPacket(packet, out);
                Packet next;
                while ((next = pollNext()) != null) {
                    processPacket(next, out);
                }
                return out;
            }
            if (inQueueWindow(packet.getPacketId())) {
                enqueue(packet);
            }
            return out;
        }

        private void processPacket(Packet packet, List<byte[]> out) {
            byte[] assembled = handleFragment(packet);
            if (assembled != null && assembled.length > 0) {
                out.add(assembled);
            }
            expectedPacketId = (expectedPacketId + 1) & 0xFFFF;
        }

        private void enqueue(Packet packet) {
            if (backlog.size() >= MAX_QUEUE_LEN) {
                backlog.clear();
            }
            backlog.add(packet);
        }

        private Packet pollNext() {
            for (int i = 0; i < backlog.size(); i++) {
                Packet candidate = backlog.get(i);
                if (candidate.getPacketId() == expectedPacketId) {
                    backlog.remove(i);
                    return candidate;
                }
            }
            return null;
        }

        private boolean inQueueWindow(int packetId) {
            int limit = (expectedPacketId + MAX_QUEUE_LEN) & 0xFFFF;
            boolean wrap = (expectedPacketId + MAX_QUEUE_LEN) > 0xFFFF;
            if (!wrap) {
                return packetId >= expectedPacketId && packetId < limit;
            }
            return packetId >= expectedPacketId || packetId < limit;
        }

        private byte[] handleFragment(Packet packet) {
            boolean fragmented = packet.hasFlag(PacketFlags.FRAGMENTED);
            boolean packetCompressed = packet.hasFlag(PacketFlags.COMPRESSED);

            if (fragmentBuffer == null) {
                if (!fragmented) {
                    return decompressIfNeeded(packet.getData(), packetCompressed);
                }
                startFragment(packet.getData(), packetCompressed);
                return null;
            }

            appendFragment(packet.getData());
            if (fragmented) {
                byte[] out = fragmentBuffer.toByteArray();
                boolean wasCompressed = fragmentCompressed;
                resetFragment();
                return decompressIfNeeded(out, wasCompressed);
            }
            return null;
        }

        private void startFragment(byte[] data, boolean compressed) {
            fragmentBuffer = new ByteArrayOutputStream(Math.min(MAX_COMMAND_FRAGMENT, Math.max(64, data.length)));
            fragmentCompressed = compressed;
            appendFragment(data);
        }

        private void appendFragment(byte[] data) {
            if (fragmentBuffer == null) {
                return;
            }
            if (fragmentBuffer.size() + data.length > MAX_COMMAND_FRAGMENT) {
                resetFragment();
                return;
            }
            fragmentBuffer.write(data, 0, data.length);
        }

        private void resetFragment() {
            fragmentBuffer = null;
            fragmentCompressed = false;
        }

        private void reset() {
            expectedPacketId = 0;
            backlog.clear();
            resetFragment();
        }

        private byte[] decompressIfNeeded(byte[] data, boolean compressed) {
            if (!compressed) {
                return data;
            }
            try {
                return QuickLZ.decompress(data, MAX_DECOMPRESSED_SIZE);
            } catch (IOException ex) {
                log.warn("[TS3] command decompress failed size={}", data == null ? 0 : data.length, ex);
                return null;
            }
        }
    }

    private record PacketCounter(int id, int generation) {
    }

    private InetSocketAddress parseAddress(String address) {
        if (address == null || address.isBlank()) {
            return new InetSocketAddress("127.0.0.1", 9987);
        }
        String host = address;
        int port = 9987;
        if (address.contains(":")) {
            String[] parts = address.split(":", 2);
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ex) {
                port = 9987;
            }
        }
        return new InetSocketAddress(host, port);
    }

    private String resolveClientIp(InetSocketAddress endpoint) {
        if (endpoint == null) {
            return "";
        }
        InetAddress address = endpoint.getAddress();
        if (address == null) {
            return "";
        }
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()) {
            return "";
        }
        return address.getHostAddress();
    }


    /**
     * 执行 ChannelCodecInfo 操作。
     * @param codec 参数 codec
     * @param quality 参数 quality
     * @return 返回值
     */
    public record ChannelCodecInfo(int codec, int quality) {
    }

    private record CommandResponse(int returnCode, List<ParsedCommand> commands, ParsedCommand error) {
    }


    /**
     * PendingCommand 相关功能。
     *
     * <p>职责：负责 PendingCommand 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    private static final class PendingCommand {
        private final int returnCode;
        private final List<ParsedCommand> commands = new ArrayList<>();
        private final CompletableFuture<CommandResponse> future = new CompletableFuture<>();

        private PendingCommand(int returnCode) {
            this.returnCode = returnCode;
        }
    }
}
