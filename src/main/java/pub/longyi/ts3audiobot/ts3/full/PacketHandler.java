package pub.longyi.ts3audiobot.ts3.full;

import lombok.extern.slf4j.Slf4j;
import pub.longyi.ts3audiobot.ts3.protocol.Packet;
import pub.longyi.ts3audiobot.ts3.protocol.PacketDirection;
import pub.longyi.ts3audiobot.ts3.protocol.PacketFlags;
import pub.longyi.ts3audiobot.ts3.protocol.PacketKind;
import pub.longyi.ts3audiobot.ts3.protocol.PacketStatistics;
import pub.longyi.ts3audiobot.ts3.protocol.PacketType;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PacketHandler 相关功能。
 */


/**
 * PacketHandler 相关功能。
 *
 * <p>职责：负责 PacketHandler 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class PacketHandler {
    private static final long PACKET_TIMEOUT_MS = 30_000L;
    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final long PING_INTERVAL_MS = 1000L;

    private volatile boolean connected;
    private InetSocketAddress remote;
    private int clientId;
    private DatagramSocket socket;
    private Thread receiverThread;
    private TsCrypt tsCrypt;
    private final Map<PacketType, Integer> incomingNextIds = new EnumMap<>(PacketType.class);
    private final Map<PacketType, Integer> incomingGenerations = new EnumMap<>(PacketType.class);
    private final Map<Integer, ResendEntry> resendCommand = new ConcurrentHashMap<>();
    private final Map<Integer, ResendEntry> resendCommandLow = new ConcurrentHashMap<>();
    private volatile ResendEntry init1Entry;
    private final Object sendLock = new Object();
    private ScheduledExecutorService scheduler;
    private int pingId;
    private int pingGeneration;
    private final EnumMap<PacketKind, PacketStatistics> stats = new EnumMap<>(PacketKind.class);
    private final Map<Integer, Long> pingSentTimes = new ConcurrentHashMap<>();
    private final Deque<Double> pingSamples = new ArrayDeque<>();
    private final Object pingLock = new Object();

    private Consumer<Packet> packetEvent;
    private Consumer<String> stopEvent;

    /**
     * 创建 PacketHandler 实例。
     */
    public PacketHandler() {
        for (PacketKind kind : PacketKind.values()) {
            stats.put(kind, new PacketStatistics());
        }
    }


    /**
     * 执行 connect 操作。
     * @param address 参数 address
     * @param tsCrypt 参数 tsCrypt
     * @return 返回值
     */
    public boolean connect(InetSocketAddress address, TsCrypt tsCrypt) {
        this.remote = address;
        this.tsCrypt = tsCrypt;
        log.info("[TS3] PacketHandler connect {}", address);
        try {
            socket = new DatagramSocket();
            socket.connect(address);
            connected = true;
            startScheduler();
            startReceiver();
            return true;
        } catch (SocketException ex) {
            log.error("[TS3] PacketHandler socket error", ex);
            connected = false;
            return false;
        }
    }


    /**
     * 执行 stop 操作。
     */
    public void stop() {
        if (!connected) {
            return;
        }
        connected = false;
        stopScheduler();
        if (socket != null) {
            socket.close();
        }
        if (stopEvent != null) {
            stopEvent.accept("stopped");
        }
    }


    /**
     * 执行 isConnected 操作。
     * @return 返回值
     */
    public boolean isConnected() {
        return connected;
    }


    /**
     * 执行 setPacketEvent 操作。
     * @param packetEvent 参数 packetEvent
     */
    public void setPacketEvent(Consumer<Packet> packetEvent) {
        this.packetEvent = packetEvent;
    }


    /**
     * 执行 setStopEvent 操作。
     * @param stopEvent 参数 stopEvent
     */
    public void setStopEvent(Consumer<String> stopEvent) {
        this.stopEvent = stopEvent;
    }


    /**
     * 执行 getClientId 操作。
     * @return 返回值
     */
    public int getClientId() {
        return clientId;
    }


    /**
     * 执行 setClientId 操作。
     * @param clientId 参数 clientId
     */
    public void setClientId(int clientId) {
        this.clientId = clientId;
    }


    /**
     * 执行 markInitComplete 操作。
     */
    public void markInitComplete() {
        init1Entry = null;
    }


    /**
     * 执行 ackCommand 操作。
     * @param packetId 参数 packetId
     */
    public void ackCommand(int packetId) {
        if (packetId < 0) {
            return;
        }
        resendCommand.remove(packetId);
    }


    /**
     * 执行 addOutgoingPacket 操作。
     * @param packet 参数 packet
     * @return 返回值
     */
    public boolean addOutgoingPacket(Packet packet) {
        if (!connected) {
            return false;
        }
        if (tsCrypt != null) {
            tsCrypt.encrypt(packet);
        } else {
            packet.toRaw();
        }
        byte[] raw = packet.getRaw();
        if (raw == null) {
            return false;
        }
        recordOutgoing(packet, raw.length);
        if (!sendRaw(raw)) {
            return false;
        }
        trackResend(packet, raw);
        return true;
    }

    private void startReceiver() {
        receiverThread = new Thread(this::receiveLoop, "ts3-packet-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[8192];
        while (connected && socket != null && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException ex) {
                if (connected) {
                    log.error("[TS3] receive failed", ex);
                }
                break;
            }
            byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
            Packet tsPacket = Packet.fromRaw(PacketDirection.S2C, data);
            if (tsPacket == null) {
                continue;
            }
            PacketType type = tsPacket.getPacketType();
            IncomingInfo info = resolveIncoming(type, tsPacket.getPacketId());
            tsPacket.setGenerationId(info.generationId);
            if (tsCrypt != null && !tsCrypt.decrypt(tsPacket)) {
                log.debug("[TS3] decrypt failed");
                continue;
            }
            recordIncoming(tsPacket, data.length);
            if (info.inWindow && type != PacketType.INIT1) {
                int nextId = (tsPacket.getPacketId() + 1) & 0xFFFF;
                int nextGen = info.generationId + (tsPacket.getPacketId() == 0xFFFF ? 1 : 0);
                incomingNextIds.put(type, nextId);
                incomingGenerations.put(type, nextGen);
            }
            if (tsPacket.getPacketType() == PacketType.ACK || tsPacket.getPacketType() == PacketType.ACK_LOW) {
                handleAck(tsPacket);
                continue;
            }
            if (packetEvent != null) {
                packetEvent.accept(tsPacket);
            }
        }
    }

    private IncomingInfo resolveIncoming(PacketType type, int packetId) {
        if (type == PacketType.INIT1) {
            return new IncomingInfo(0, true);
        }
        int curNext = incomingNextIds.getOrDefault(type, 0);
        int gen = incomingGenerations.getOrDefault(type, 0);
        int limit = (curNext + 0x8000) & 0xFFFF;
        boolean nextGen = (curNext + 0x8000) > 0xFFFF;
        boolean inWindow = (!nextGen && packetId >= curNext && packetId < limit)
            || (nextGen && (packetId >= curNext || packetId < limit));
        int packetGen;
        if (inWindow) {
            packetGen = (nextGen && packetId < limit) ? gen + 1 : gen;
        } else if (packetId < curNext) {
            packetGen = gen;
        } else {
            packetGen = Math.max(0, gen - 1);
        }
        return new IncomingInfo(packetGen, inWindow);
    }

    private void trackResend(Packet packet, byte[] raw) {
        PacketType type = packet.getPacketType();
        long now = System.currentTimeMillis();
        if (type == PacketType.COMMAND) {
            resendCommand.put(packet.getPacketId(), new ResendEntry(raw, now, now, packet.getPacketId(), type));
        } else if (type == PacketType.COMMAND_LOW) {
            resendCommandLow.put(packet.getPacketId(), new ResendEntry(raw, now, now, packet.getPacketId(), type));
        } else if (type == PacketType.INIT1) {
            init1Entry = new ResendEntry(raw, now, now, packet.getPacketId(), type);
        }
    }

    private void handleAck(Packet packet) {
        byte[] data = packet.getData();
        if (data == null || data.length < 2) {
            return;
        }
        int acked = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (packet.getPacketType() == PacketType.ACK) {
            resendCommand.remove(acked);
        } else {
            resendCommandLow.remove(acked);
        }
    }

    private boolean sendRaw(byte[] raw) {
        if (socket == null || !connected) {
            return false;
        }
        DatagramPacket datagram = new DatagramPacket(raw, raw.length, remote);
        try {
            synchronized (sendLock) {
                socket.send(datagram);
            }
            return true;
        } catch (IOException ex) {
            log.error("[TS3] send failed", ex);
            return false;
        }
    }

    private void startScheduler() {
        stopScheduler();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ts3-packet-timer");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::resendLoop, RETRY_INTERVAL_MS, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::sendPing, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void resendLoop() {
        long now = System.currentTimeMillis();
        resendMap(now, resendCommand);
        resendMap(now, resendCommandLow);
        ResendEntry init = init1Entry;
        if (init != null) {
            if (now - init.firstSent > PACKET_TIMEOUT_MS) {
                log.warn("[TS3] init1 timeout");
                stop();
                return;
            }
            if (now - init.lastSent >= RETRY_INTERVAL_MS) {
                if (sendRaw(init.raw)) {
                    init.lastSent = now;
                }
            }
        }
    }

    private void resendMap(long now, Map<Integer, ResendEntry> map) {
        for (ResendEntry entry : map.values()) {
            if (now - entry.firstSent > PACKET_TIMEOUT_MS) {
                log.warn("[TS3] packet timeout type={} id={}", entry.packetType, entry.packetId);
                stop();
                return;
            }
            if (now - entry.lastSent >= RETRY_INTERVAL_MS) {
                if (sendRaw(entry.raw)) {
                    entry.lastSent = now;
                }
            }
        }
    }

    private void sendPing() {
        if (!connected) {
            return;
        }
        int id = pingId & 0xFFFF;
        int gen = pingGeneration;
        pingId++;
        if (pingId > 0xFFFF) {
            pingId = 0;
            pingGeneration++;
        }
        Packet ping = new Packet(PacketDirection.C2S, PacketType.PING, id, gen, new byte[0]);
        ping.setClientId(clientId);
        ping.setFlag(PacketFlags.UNENCRYPTED, true);
        pingSentTimes.put(id, System.nanoTime());
        addOutgoingPacket(ping);
    }


    /**
     * 执行 getStats 操作。
     * @param kind 参数 kind
     * @return 返回值
     */
    public PacketStatistics getStats(PacketKind kind) {
        return stats.get(kind);
    }


    /**
     * 执行 getPingStats 操作。
     * @return 返回值
     */
    public PingStats getPingStats() {
        synchronized (pingLock) {
            if (pingSamples.isEmpty()) {
                return new PingStats(PACKET_TIMEOUT_MS / 1000.0, 0.0);
            }
            double avg = 0.0;
            for (double sample : pingSamples) {
                avg += sample;
            }
            avg /= pingSamples.size();
            double variance = 0.0;
            for (double sample : pingSamples) {
                double diff = sample - avg;
                variance += diff * diff;
            }
            double deviation = pingSamples.size() > 1 ? Math.sqrt(variance / (pingSamples.size() - 1)) : 0.0;
            return new PingStats(avg, deviation);
        }
    }

    private void recordOutgoing(Packet packet, int size) {
        PacketKind kind = kindOf(packet.getPacketType());
        PacketStatistics stat = stats.get(kind);
        if (stat != null) {
            stat.processOutgoing(size);
        }
    }

    private void recordIncoming(Packet packet, int size) {
        PacketKind kind = kindOf(packet.getPacketType());
        PacketStatistics stat = stats.get(kind);
        if (stat != null) {
            stat.processIncoming(size);
        }
        if (packet.getPacketType() == PacketType.PONG) {
            handlePong(packet);
        }
    }

    private void handlePong(Packet packet) {
        byte[] data = packet.getData();
        if (data == null || data.length < 2) {
            return;
        }
        int acked = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        Long sentAt = pingSentTimes.remove(acked);
        if (sentAt == null) {
            return;
        }
        double rtt = (System.nanoTime() - sentAt) / 1_000_000_000.0;
        synchronized (pingLock) {
            pingSamples.addFirst(rtt);
            while (pingSamples.size() > 5) {
                pingSamples.removeLast();
            }
        }
    }

    private static PacketKind kindOf(PacketType type) {
        if (type == null) {
            return PacketKind.CONTROL;
        }
        return switch (type) {
            case VOICE, VOICE_WHISPER -> PacketKind.SPEECH;
            case PING, PONG -> PacketKind.KEEPALIVE;
            default -> PacketKind.CONTROL;
        };
    }

    private static final class ResendEntry {
        private final byte[] raw;
        private final long firstSent;
        private volatile long lastSent;
        private final int packetId;
        private final PacketType packetType;

        private ResendEntry(byte[] raw, long firstSent, long lastSent, int packetId, PacketType packetType) {
            this.raw = raw;
            this.firstSent = firstSent;
            this.lastSent = lastSent;
            this.packetId = packetId;
            this.packetType = packetType;
        }
    }

    private record IncomingInfo(int generationId, boolean inWindow) {
    }

    public record PingStats(double pingSeconds, double deviationSeconds) {
    }
}
