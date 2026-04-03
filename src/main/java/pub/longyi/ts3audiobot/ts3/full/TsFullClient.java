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
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

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
    private volatile FileTransferInitPayload lastFileTransferInitPayload;
    private volatile int resolvedAvatarMaxFileSizeBytes = AVATAR_MAX_FILE_SIZE_BYTES;
    private volatile boolean avatarMaxFileSizeResolved;
    private int fileTransferId = 1;

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
    private static final int AVATAR_MAX_FILE_SIZE_BYTES = 200_000;
    private static final int AVATAR_MAX_IMAGE_EDGE = 1024;
    private static final float AVATAR_JPEG_QUALITY_MAX = 0.92f;
    private static final float AVATAR_JPEG_QUALITY_MIN = 0.35f;
    private static final float AVATAR_JPEG_QUALITY_STEP = 0.07f;
    private static final float AVATAR_SCALE_STEP = 0.85f;
    private static final int AVATAR_SCALE_MAX_ATTEMPTS = 6;
    private static final int AVATAR_RETRY_MIN_FILE_SIZE_BYTES = 10_000;
    private static final double AVATAR_RETRY_SCALE_FACTOR = 0.72d;
    private static final int FILE_TRANSFER_CONNECT_TIMEOUT_MS = 5000;
    private static final int FILE_TRANSFER_TIMEOUT_MS = 8000;
    private static final int FILE_TRANSFER_RESPONSE_TIMEOUT_MS = 1200;
    private static final int FILE_TRANSFER_RESPONSE_MAX_BYTES = 4096;
    private static final int FILE_TRANSFER_DEFAULT_PORT = 30033;
    private static final long FILE_TRANSFER_PAYLOAD_WAIT_MS = 1200L;
    private static final long FILE_TRANSFER_PAYLOAD_MAX_AGE_MS = 3000L;
    private static final String ERROR_ID_OK = "0";
    private static final String ERROR_ID_PERMISSION_DENIED = "2568";
    private static final String ERROR_MSG_PERMISSION_DENIED = "insufficient client permissions";

    /**
     * 执行 configure 操作。
     * @param config 参数 config
     */
    @Override
    public void configure(ConnectionDataFull config) {
        this.connectionData = config;
        this.tsCrypt = new TsCrypt(config.identity());
        this.resolvedAvatarMaxFileSizeBytes = AVATAR_MAX_FILE_SIZE_BYTES;
        this.avatarMaxFileSizeResolved = false;
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
     * 更新机器人昵称。
     * @param nickname 参数 nickname
     * @return 返回值
     */
    @Override
    public boolean updateClientNickname(String nickname) {
        if (!connected || nickname == null || nickname.isBlank()) {
            log.info("[TS3] skip nickname update connected={} initComplete={} nicknameBlank={}",
                connected,
                initComplete,
                nickname == null || nickname.isBlank()
            );
            return false;
        }
        CommandResponse response = requestCommandResponse(
            "clientupdate",
            TsCommandBuilder.params("client_nickname", nickname.trim())
        );
        boolean success = isCommandSuccess(response);
        log.info("[TS3] nickname update success={} nickname={}", success, nickname.trim());
        return success;
    }


    /**
     * 更新机器人头像。
     * @param avatarFile 参数 avatarFile
     * @return 返回值
     */
    @Override
    public boolean updateClientAvatar(Path avatarFile) {
        if (!connected || avatarFile == null || Files.notExists(avatarFile)) {
            log.info("[TS3] skip avatar update connected={} initComplete={} fileExists={}",
                connected,
                initComplete,
                avatarFile != null && Files.exists(avatarFile)
            );
            return false;
        }
        byte[] bytes = readAvatarBytes(avatarFile);
        if (bytes.length <= 0) {
            log.info("[TS3] skip avatar update reason=empty_bytes file={}", avatarFile);
            return false;
        }
        int uploadLimit = resolveAvatarMaxFileSizeBytes();
        if (bytes.length > uploadLimit) {
            log.info("[TS3] avatar original bytes={} exceeds limit={}, start compress file={}",
                bytes.length,
                uploadLimit,
                avatarFile
            );
            bytes = compressAvatarBytes(avatarFile, uploadLimit);
        }
        if (bytes.length <= 0 || bytes.length > uploadLimit) {
            log.info("[TS3] skip avatar upload size={} file={}", bytes.length, avatarFile);
            return false;
        }
        if (buildAvatarFileNames().isEmpty()) {
            return false;
        }
        byte[] attemptBytes = bytes;
        int attempt = 1;
        while (true) {
            boolean uploaded = uploadAvatarBytes(avatarFile, attemptBytes);
            if (uploaded) {
                return true;
            }
            if (attemptBytes.length <= AVATAR_RETRY_MIN_FILE_SIZE_BYTES) {
                log.warn("[TS3] avatar upload retries exhausted at min bytes={} file={}",
                    attemptBytes.length,
                    avatarFile
                );
                return false;
            }
            int nextLimit = nextAvatarRetryLimit(attemptBytes.length);
            byte[] retryBytes = compressAvatarBytes(avatarFile, nextLimit);
            if (retryBytes.length <= 0 || retryBytes.length >= attemptBytes.length) {
                log.warn("[TS3] avatar retry compress not smaller old={} candidate={} limit={} file={}",
                    attemptBytes.length,
                    retryBytes.length,
                    nextLimit,
                    avatarFile
                );
                return false;
            }
            log.info("[TS3] avatar upload retry #{} old={} new={} limit={} file={}",
                attempt,
                attemptBytes.length,
                retryBytes.length,
                nextLimit,
                avatarFile
            );
            attemptBytes = retryBytes;
            attempt++;
        }
    }

    private int nextAvatarRetryLimit(int currentBytes) {
        int scaled = (int) Math.floor(currentBytes * AVATAR_RETRY_SCALE_FACTOR);
        int bounded = Math.max(AVATAR_RETRY_MIN_FILE_SIZE_BYTES, scaled);
        if (bounded >= currentBytes) {
            return Math.max(AVATAR_RETRY_MIN_FILE_SIZE_BYTES, currentBytes - 1);
        }
        return bounded;
    }

    private boolean uploadAvatarBytes(Path avatarFile, byte[] bytes) {
        List<String> avatarNames = buildAvatarFileNames();
        if (avatarNames.isEmpty()) {
            return false;
        }
        for (String avatarName : avatarNames) {
            Map<String, String> params = TsCommandBuilder.params(
                "clientftfid", Integer.toString(nextFileTransferId()),
                "name", avatarName,
                "cid", "0",
                "cpw", "",
                "size", Integer.toString(bytes.length),
                "overwrite", "1",
                "resume", "0",
                "proto", "1"
            );
            CommandResponse response = requestCommandResponse("ftinitupload", params);
            if (!isCommandSuccess(response)) {
                log.warn("[TS3] avatar upload init command failed file={} name={}", avatarFile, avatarName);
                continue;
            }
            Map<String, String> parsed = flattenCommandResponse(response == null ? List.of() : response.commands);
            mergeErrorParams(parsed, response);
            waitAndMergeFileTransferPayload(parsed);
            String key = parsed.get("ftkey");
            Integer port = parseInt(parsed.get("port"));
            if (port == null || port <= 0) {
                port = FILE_TRANSFER_DEFAULT_PORT;
                log.info("[TS3] avatar upload init port missing, fallback default port={}", FILE_TRANSFER_DEFAULT_PORT);
            }
            if (key == null || key.isBlank()) {
                log.info("[TS3] avatar upload init response commands={} error={}",
                    summarizeCommands(response == null ? List.of() : response.commands),
                    response == null || response.error == null ? Map.of() : response.error.params()
                );
                log.info("[TS3] avatar upload init missing key/port params={}", parsed);
                continue;
            }
            String host = resolveFileTransferHost(parsed.get("ip"));
            if (host.isBlank()) {
                continue;
            }
            log.info("[TS3] avatar upload init ok host={} port={} bytes={} file={} name={}",
                host,
                port,
                bytes.length,
                avatarFile,
                avatarName
            );
            boolean uploaded = uploadFileTransferPayload(host, port, key, bytes);
            if (!uploaded) {
                continue;
            }
            return true;
        }
        return false;
    }

    private int resolveAvatarMaxFileSizeBytes() {
        if (avatarMaxFileSizeResolved) {
            return resolvedAvatarMaxFileSizeBytes;
        }
        List<ParsedCommand> commands = requestCommand(
            "permget",
            TsCommandBuilder.params("permsid", "i_client_max_avatar_filesize")
        );
        for (ParsedCommand cmd : commands) {
            Integer value = parseInt(readField(cmd, "permvalue"));
            if (value == null) {
                continue;
            }
            if (value <= 0) {
                // -1 means no explicit permission cap, keep local safety cap.
                resolvedAvatarMaxFileSizeBytes = AVATAR_MAX_FILE_SIZE_BYTES;
            } else {
                resolvedAvatarMaxFileSizeBytes = Math.max(4096, value);
            }
            avatarMaxFileSizeResolved = true;
            log.info("[TS3] resolved avatar max filesize={} bytes", resolvedAvatarMaxFileSizeBytes);
            break;
        }
        return resolvedAvatarMaxFileSizeBytes;
    }

    private String summarizeCommands(List<ParsedCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[");
        int limit = Math.min(commands.size(), 8);
        for (int i = 0; i < limit; i++) {
            ParsedCommand cmd = commands.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            if (cmd == null) {
                builder.append("null");
                continue;
            }
            builder.append(cmd.name()).append(' ').append(cmd.params());
        }
        if (commands.size() > limit) {
            builder.append(", ...");
        }
        builder.append(']');
        return builder.toString();
    }

    private byte[] readAvatarBytes(Path avatarFile) {
        if (avatarFile == null || Files.notExists(avatarFile)) {
            return new byte[0];
        }
        try {
            return Files.readAllBytes(avatarFile);
        } catch (IOException ex) {
            log.warn("[TS3] read avatar failed file={}", avatarFile, ex);
            return new byte[0];
        }
    }

    private byte[] compressAvatarBytes(Path avatarFile) {
        return compressAvatarBytes(avatarFile, AVATAR_MAX_FILE_SIZE_BYTES);
    }

    private byte[] compressAvatarBytes(Path avatarFile, int maxBytes) {
        if (maxBytes <= 0) {
            return new byte[0];
        }
        BufferedImage source = readAvatarImage(avatarFile);
        if (source == null) {
            return new byte[0];
        }
        BufferedImage normalized = normalizeImageSize(source);
        BufferedImage working = normalized;
        for (int attempt = 0; attempt < AVATAR_SCALE_MAX_ATTEMPTS; attempt++) {
            byte[] encoded = encodeJpegWithinLimit(working, maxBytes);
            if (encoded.length > 0 && encoded.length <= maxBytes) {
                log.info("[TS3] avatar compress success bytes={} limit={} width={} height={} attempt={} file={}",
                    encoded.length,
                    maxBytes,
                    working.getWidth(),
                    working.getHeight(),
                    attempt,
                    avatarFile
                );
                return encoded;
            }
            BufferedImage scaled = scaleImage(working, AVATAR_SCALE_STEP);
            if (scaled == working) {
                break;
            }
            working = scaled;
        }
        log.warn("[TS3] avatar compress failed file={}", avatarFile);
        return new byte[0];
    }

    private BufferedImage readAvatarImage(Path avatarFile) {
        if (avatarFile == null) {
            return null;
        }
        try {
            return ImageIO.read(avatarFile.toFile());
        } catch (IOException ex) {
            log.warn("[TS3] read avatar image failed file={}", avatarFile, ex);
            return null;
        }
    }

    private BufferedImage normalizeImageSize(BufferedImage source) {
        if (source == null) {
            return null;
        }
        int width = Math.max(1, source.getWidth());
        int height = Math.max(1, source.getHeight());
        if (width <= AVATAR_MAX_IMAGE_EDGE && height <= AVATAR_MAX_IMAGE_EDGE) {
            return toRgbImage(source);
        }
        double ratio = Math.min((double) AVATAR_MAX_IMAGE_EDGE / width, (double) AVATAR_MAX_IMAGE_EDGE / height);
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        return scaleImage(toRgbImage(source), targetWidth, targetHeight);
    }

    private byte[] encodeJpegWithinLimit(BufferedImage image, int maxBytes) {
        if (image == null || maxBytes <= 0) {
            return new byte[0];
        }
        float quality = AVATAR_JPEG_QUALITY_MAX;
        while (quality >= AVATAR_JPEG_QUALITY_MIN - 0.0001f) {
            byte[] encoded = encodeJpeg(image, quality);
            if (encoded.length > 0 && encoded.length <= maxBytes) {
                return encoded;
            }
            quality -= AVATAR_JPEG_QUALITY_STEP;
        }
        return new byte[0];
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) {
        if (image == null) {
            return new byte[0];
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.hasNext() ? writers.next() : null;
        if (writer == null) {
            return new byte[0];
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(baos)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.05f, Math.min(1.0f, quality)));
            }
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
            output.flush();
            return baos.toByteArray();
        } catch (IOException ex) {
            log.warn("[TS3] encode avatar jpeg failed quality={}", quality, ex);
            return new byte[0];
        } finally {
            writer.dispose();
        }
    }

    private BufferedImage scaleImage(BufferedImage image, float ratio) {
        if (image == null) {
            return null;
        }
        int width = Math.max(1, image.getWidth());
        int height = Math.max(1, image.getHeight());
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        if (targetWidth == width && targetHeight == height) {
            if (width <= 64 || height <= 64) {
                return image;
            }
            targetWidth = Math.max(1, width - 1);
            targetHeight = Math.max(1, height - 1);
        }
        return scaleImage(image, targetWidth, targetHeight);
    }

    private BufferedImage scaleImage(BufferedImage image, int targetWidth, int targetHeight) {
        if (image == null) {
            return null;
        }
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(
                image.getScaledInstance(targetWidth, targetHeight, Image.SCALE_SMOOTH),
                0,
                0,
                targetWidth,
                targetHeight,
                null
            );
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private BufferedImage toRgbImage(BufferedImage source) {
        if (source == null) {
            return null;
        }
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
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
        String codecStr = extractResponseField(channelInfo, "channel_codec");
        String qualityStr = extractResponseField(channelInfo, "channel_codec_quality");
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
        initComplete = true;
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
            channelId = readField(cmd, "cid");
            if (channelId != null && !channelId.isBlank()) {
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
            String clid = readField(cmd, "clid");
            Integer target = parseInt(clid);
            if (target != null && target == clientId) {
                channelId = readField(cmd, "cid");
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
        CommandResponse response = requestCommandResponse(name, params);
        if (response == null || response.commands == null) {
            return List.of();
        }
        return response.commands;
    }

    private CommandResponse requestCommandResponse(String name, Map<String, String> params) {
        if (!connected) {
            return null;
        }
        PendingCommand pending = new PendingCommand(nextReturnCode(), name);
        synchronized (responseLock) {
            if (pendingCommand != null) {
                return null;
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
            return pending.future.get(COMMAND_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.warn("[TS3] command timeout name={} returnCode={}", name, pending.returnCode);
            return null;
        } catch (Exception ex) {
            log.warn("[TS3] command failed name={} returnCode={}", name, pending.returnCode, ex);
            return null;
        } finally {
            synchronized (responseLock) {
                if (pendingCommand == pending) {
                    pendingCommand = null;
                }
            }
        }
    }

    private boolean isCommandSuccess(CommandResponse response) {
        if (response == null || response.error == null || response.error.params() == null) {
            return false;
        }
        String id = response.error.params().get("id");
        if (id == null || id.isBlank()) {
            return false;
        }
        return "0".equals(id.trim());
    }

    private void collectCommandResponse(ParsedCommand cmd) {
        captureFileTransferInitPayload(cmd);
        PendingCommand pending = pendingCommand;
        if (pending == null || cmd == null) {
            if (cmd != null && "error".equalsIgnoreCase(cmd.name())) {
                String returnCodeRaw = cmd.params().get("return_code");
                String errorId = cmd.params().get("id");
                String msg = cmd.params().get("msg");
                logCommandError(null, returnCodeRaw, errorId, msg);
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
        if (lower.startsWith("notify")) {
            if (pending != null
                && "ftinitupload".equalsIgnoreCase(pending.commandName)
                && isFileTransferInitPayload(cmd)) {
                pending.commands.add(cmd);
            }
            return;
        }
        if ("initivexpand".equals(lower) || "initivexpand2".equals(lower)) {
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
            if ("ftinitupload".equalsIgnoreCase(pending.commandName)) {
                log.info("[TS3] ftinitupload error params={}", cmd.params());
            }
            logCommandError(pending.commandName, returnCodeRaw, errorId, msg);
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
        if ("ftinitupload".equalsIgnoreCase(pending.commandName)) {
            log.info("[TS3] ftinitupload response name={} params={}", name, cmd.params());
        }
        pending.commands.add(cmd);
    }

    private void captureFileTransferInitPayload(ParsedCommand cmd) {
        if (!isFileTransferInitPayload(cmd)) {
            return;
        }
        String key = readField(cmd, "ftkey");
        Integer port = parseInt(readField(cmd, "port"));
        String ip = readField(cmd, "ip");
        if ((key == null || key.isBlank()) && (port == null || port <= 0)) {
            return;
        }
        lastFileTransferInitPayload = new FileTransferInitPayload(
            key,
            port == null ? 0 : port,
            ip,
            System.currentTimeMillis()
        );
    }

    private void logCommandError(String commandName, String returnCodeRaw, String errorId, String msg) {
        String command = commandName == null || commandName.isBlank() ? "unknown" : commandName;
        String normalizedErrorId = errorId == null ? "" : errorId.trim();
        String normalizedMsg = msg == null ? "" : msg.trim();
        if (ERROR_ID_OK.equals(normalizedErrorId)) {
            log.debug("[TS3] command ok name={} return_code={} id={} msg={}", command, returnCodeRaw, errorId, msg);
            return;
        }
        if (isBestEffortPermissionDenied(command, normalizedErrorId, normalizedMsg)) {
            log.debug("[TS3] permission denied name={} return_code={} id={} msg={}",
                command,
                returnCodeRaw,
                errorId,
                msg
            );
            return;
        }
        log.info("[TS3] error name={} return_code={} id={} msg={}", command, returnCodeRaw, errorId, msg);
    }

    private boolean isBestEffortPermissionDenied(String command, String errorId, String msg) {
        boolean permissionDenied = ERROR_ID_PERMISSION_DENIED.equals(errorId)
            || ERROR_MSG_PERMISSION_DENIED.equalsIgnoreCase(msg);
        if (!permissionDenied) {
            return false;
        }
        return "clientlist".equalsIgnoreCase(command)
            || "channellist".equalsIgnoreCase(command)
            || "channelinfo".equalsIgnoreCase(command);
    }

    private boolean isFileTransferInitPayload(ParsedCommand cmd) {
        if (cmd == null) {
            return false;
        }
        String ftKey = readField(cmd, "ftkey");
        Integer port = parseInt(readField(cmd, "port"));
        return (ftKey != null && !ftKey.isBlank()) || (port != null && port > 0);
    }

    private String extractResponseField(List<ParsedCommand> commands, String key) {
        if (commands == null || key == null || key.isBlank()) {
            return null;
        }
        for (ParsedCommand cmd : commands) {
            String value = readField(cmd, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String readField(ParsedCommand cmd, String key) {
        if (cmd == null || key == null || key.isBlank()) {
            return null;
        }
        Map<String, String> params = cmd.params();
        if (params != null) {
            String value = params.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        String token = cmd.name();
        if (token == null || token.isBlank()) {
            return null;
        }
        int idx = token.indexOf('=');
        if (idx <= 0 || idx >= token.length() - 1) {
            return null;
        }
        String tokenKey = token.substring(0, idx);
        if (!key.equals(tokenKey)) {
            return null;
        }
        return token.substring(idx + 1);
    }

    private void mergeErrorParams(Map<String, String> parsed, CommandResponse response) {
        if (parsed == null || response == null || response.error == null || response.error.params() == null) {
            return;
        }
        for (Map.Entry<String, String> entry : response.error.params().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                continue;
            }
            parsed.putIfAbsent(key, value);
        }
    }

    private void waitAndMergeFileTransferPayload(Map<String, String> parsed) {
        if (parsed == null) {
            return;
        }
        if (hasFileTransferKey(parsed) && hasFileTransferPort(parsed)) {
            return;
        }
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < FILE_TRANSFER_PAYLOAD_WAIT_MS) {
            FileTransferInitPayload payload = lastFileTransferInitPayload;
            long now = System.currentTimeMillis();
            if (payload != null && payload.isFresh(now)) {
                payload.mergeTo(parsed);
                if (hasFileTransferKey(parsed)) {
                    return;
                }
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean hasFileTransferKey(Map<String, String> parsed) {
        String key = parsed.get("ftkey");
        return key != null && !key.isBlank();
    }

    private boolean hasFileTransferPort(Map<String, String> parsed) {
        Integer port = parseInt(parsed.get("port"));
        return port != null && port > 0;
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

    private synchronized int nextFileTransferId() {
        int current = fileTransferId;
        fileTransferId++;
        if (fileTransferId > 0x7FFF) {
            fileTransferId = 1;
        }
        return current;
    }

    private List<String> buildAvatarFileNames() {
        List<String> names = new ArrayList<>();
        if (connectionData == null || connectionData.identity() == null) {
            return names;
        }
        String uid = connectionData.identity().clientUid();
        if (uid == null || uid.isBlank()) {
            return names;
        }
        String urlSafeUid = uid
            .replace("/", "_")
            .replace("+", "-")
            .replace("=", "");
        String legacyUid = uid.replace("/", "_");
        names.add("/avatar_" + urlSafeUid);
        if (!legacyUid.equals(urlSafeUid)) {
            names.add("/avatar_" + legacyUid);
        }
        return names;
    }

    private Map<String, String> flattenCommandResponse(List<ParsedCommand> commands) {
        Map<String, String> result = new LinkedHashMap<>();
        if (commands == null) {
            return result;
        }
        for (ParsedCommand cmd : commands) {
            if (cmd == null) {
                continue;
            }
            putResponseToken(result, cmd.name());
            if (cmd.params() == null || cmd.params().isEmpty()) {
                continue;
            }
            result.putAll(cmd.params());
        }
        return result;
    }

    private void putResponseToken(Map<String, String> result, String token) {
        if (result == null || token == null || token.isBlank()) {
            return;
        }
        int idx = token.indexOf('=');
        if (idx <= 0 || idx >= token.length() - 1) {
            return;
        }
        String key = token.substring(0, idx);
        String value = token.substring(idx + 1);
        result.put(key, value);
    }

    private String resolveFileTransferHost(String providedIp) {
        if (providedIp != null && !providedIp.isBlank()) {
            return providedIp.trim();
        }
        if (connectionData == null) {
            return "";
        }
        InetSocketAddress endpoint = parseAddress(connectionData.address());
        if (endpoint == null) {
            return "";
        }
        String host = endpoint.getHostString();
        return host == null ? "" : host;
    }

    private boolean uploadFileTransferPayload(String host, int port, String key, byte[] data) {
        if (host == null || host.isBlank() || key == null || key.isBlank() || data == null) {
            return false;
        }
        InetSocketAddress endpoint = new InetSocketAddress(host, port);
        try (Socket socket = new Socket()) {
            socket.connect(endpoint, FILE_TRANSFER_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(FILE_TRANSFER_TIMEOUT_MS);
            BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
            String header = "ftkey=" + key + "\n";
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(data);
            output.flush();
            log.info("[TS3] avatar upload payload sent host={} port={} bytes={}", host, port, data.length);
            String ack = "";
            try {
                ack = readFileTransferAck(socket);
            } catch (SocketTimeoutException ex) {
                // Some servers do not return an explicit ack; upload is best-effort once payload was sent.
                log.info("[TS3] avatar upload ack timeout host={} port={} assume_success", host, port);
                return true;
            } catch (SocketException ex) {
                if (isConnectionReset(ex)) {
                    // File transfer endpoint may close/reset after payload consumption.
                    log.warn("[TS3] avatar upload ack reset host={} port={} assume_success", host, port);
                    return true;
                }
                throw ex;
            }
            if (ack.isBlank()) {
                log.info("[TS3] avatar upload ack empty host={} port={}", host, port);
                return true;
            }
            String compactAck = ack.replace('\r', ' ').replace('\n', ' ').trim();
            ParsedCommand errorAck = extractErrorAck(ack);
            if (errorAck == null || errorAck.params() == null) {
                log.info("[TS3] avatar upload ack raw={}", compactAck);
                return true;
            }
            String id = errorAck.params().get("id");
            String msg = errorAck.params().get("msg");
            boolean success = id != null && "0".equals(id.trim());
            log.info("[TS3] avatar upload ack id={} msg={}", id, msg);
            if (!success) {
                log.warn("[TS3] avatar upload ack failed raw={}", compactAck);
            }
            return success;
        } catch (IOException ex) {
            log.warn("[TS3] avatar upload transport failed host={} port={}", host, port, ex);
            return false;
        }
    }

    private String readFileTransferAck(Socket socket) throws IOException {
        if (socket == null) {
            return "";
        }
        socket.setSoTimeout(FILE_TRANSFER_RESPONSE_TIMEOUT_MS);
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[512];
        while (buffer.size() < FILE_TRANSFER_RESPONSE_MAX_BYTES) {
            int read;
            try {
                read = input.read(chunk);
            } catch (SocketTimeoutException ex) {
                break;
            }
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            int remain = FILE_TRANSFER_RESPONSE_MAX_BYTES - buffer.size();
            int copyLen = Math.min(read, remain);
            buffer.write(chunk, 0, copyLen);
            if (copyLen < read) {
                break;
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private boolean isConnectionReset(SocketException ex) {
        if (ex == null) {
            return false;
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase();
        return normalized.contains("connection reset")
            || normalized.contains("forcibly closed");
    }

    private ParsedCommand extractErrorAck(String ack) {
        if (ack == null || ack.isBlank()) {
            return null;
        }
        List<ParsedCommand> lines = TsCommandParser.parseLines(ack);
        for (ParsedCommand line : lines) {
            if (line == null || line.name() == null) {
                continue;
            }
            if ("error".equalsIgnoreCase(line.name())) {
                return line;
            }
        }
        return null;
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

    private record FileTransferInitPayload(String key, int port, String ip, long receivedAt) {
        private boolean isFresh(long now) {
            return now - receivedAt <= FILE_TRANSFER_PAYLOAD_MAX_AGE_MS;
        }

        private void mergeTo(Map<String, String> target) {
            if (target == null) {
                return;
            }
            if (key != null && !key.isBlank()) {
                target.putIfAbsent("ftkey", key);
            }
            if (port > 0) {
                target.putIfAbsent("port", Integer.toString(port));
            }
            if (ip != null && !ip.isBlank()) {
                target.putIfAbsent("ip", ip);
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
        private final String commandName;
        private final List<ParsedCommand> commands = new ArrayList<>();
        private final CompletableFuture<CommandResponse> future = new CompletableFuture<>();

        private PendingCommand(int returnCode, String commandName) {
            this.returnCode = returnCode;
            this.commandName = commandName;
        }
    }
}
