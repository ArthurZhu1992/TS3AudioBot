package pub.longyi.ts3audiobot.ts3.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PacketStatistics 相关功能。
 */


/**
 * PacketStatistics 相关功能。
 *
 * <p>职责：负责 PacketStatistics 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class PacketStatistics {
    private final List<DataPoint> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<DataPoint> received = Collections.synchronizedList(new ArrayList<>());

    private int sentBytes;
    private int sentPackets;
    private int receivedBytes;
    private int receivedPackets;

    /**
     * 执行 processOutgoing 操作。
     * @param size 参数 size
     */
    public void processOutgoing(int size) {
        sentBytes += size;
        sentPackets++;
        sent.add(new DataPoint(System.nanoTime(), size));
        prune(sent);
    }


    /**
     * 执行 processIncoming 操作。
     * @param size 参数 size
     */
    public void processIncoming(int size) {
        receivedBytes += size;
        receivedPackets++;
        received.add(new DataPoint(System.nanoTime(), size));
        prune(received);
    }


    /**
     * 执行 getSentPackets 操作。
     * @return 返回值
     */
    public int getSentPackets() {
        return sentPackets;
    }


    /**
     * 执行 getSentBytes 操作。
     * @return 返回值
     */
    public int getSentBytes() {
        return sentBytes;
    }


    /**
     * 执行 getReceivedPackets 操作。
     * @return 返回值
     */
    public int getReceivedPackets() {
        return receivedPackets;
    }


    /**
     * 执行 getReceivedBytes 操作。
     * @return 返回值
     */
    public int getReceivedBytes() {
        return receivedBytes;
    }


    /**
     * 执行 getSentBytesLastSecond 操作。
     * @return 返回值
     */
    public int getSentBytesLastSecond() {
        return sumSince(sent, 1_000_000_000L);
    }


    /**
     * 执行 getSentBytesLastMinute 操作。
     * @return 返回值
     */
    public int getSentBytesLastMinute() {
        return sumSince(sent, 60_000_000_000L);
    }


    /**
     * 执行 getReceivedBytesLastSecond 操作。
     * @return 返回值
     */
    public int getReceivedBytesLastSecond() {
        return sumSince(received, 1_000_000_000L);
    }


    /**
     * 执行 getReceivedBytesLastMinute 操作。
     * @return 返回值
     */
    public int getReceivedBytesLastMinute() {
        return sumSince(received, 60_000_000_000L);
    }

    private static int sumSince(List<DataPoint> points, long windowNanos) {
        long now = System.nanoTime() - windowNanos;
        synchronized (points) {
            int total = 0;
            for (DataPoint point : points) {
                if (point.time >= now) {
                    total += point.size;
                }
            }
            return total;
        }
    }

    private static void prune(List<DataPoint> points) {
        long cutoff = System.nanoTime() - 60_000_000_000L;
        points.removeIf(point -> point.time < cutoff);
    }


    /**
     * DataPoint 相关功能。
     *
     * <p>职责：负责 DataPoint 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    private static final class DataPoint {
        private final long time;
        private final int size;

        private DataPoint(long time, int size) {
            this.time = time;
            this.size = size;
        }
    }
}
