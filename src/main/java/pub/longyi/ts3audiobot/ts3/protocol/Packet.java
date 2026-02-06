package pub.longyi.ts3audiobot.ts3.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Packet 相关功能。
 */


/**
 * Packet 相关功能。
 *
 * <p>职责：负责 Packet 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class Packet {
    private final PacketDirection direction;

    private byte packetTypeFlagged;
    private int packetId;
    private int generationId;
    private int clientId;

    private byte[] data;
    private byte[] raw;

    /**
     * 创建 Packet 实例。
     * @param direction 参数 direction
     * @param type 参数 type
     * @param packetId 参数 packetId
     * @param generationId 参数 generationId
     * @param data 参数 data
     */
    public Packet(PacketDirection direction, PacketType type, int packetId, int generationId, byte[] data) {
        this.direction = direction;
        this.packetTypeFlagged = (byte) (type.value() & 0x0F);
        this.packetId = packetId;
        this.generationId = generationId;
        this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }


    /**
     * 执行 fromRaw 操作。
     * @param direction 参数 direction
     * @param raw 参数 raw
     * @return 返回值
     */
    public static Packet fromRaw(PacketDirection direction, byte[] raw) {
        if (raw == null || raw.length < ProtocolConst.MAC_LEN + direction.headerLength()) {
            return null;
        }
        Packet packet = new Packet(direction, PacketType.COMMAND, 0, 0, new byte[0]);
        packet.raw = Arrays.copyOf(raw, raw.length);
        packet.parseHeader();
        int dataOffset = ProtocolConst.MAC_LEN + direction.headerLength();
        packet.data = Arrays.copyOfRange(raw, dataOffset, raw.length);
        return packet;
    }


    /**
     * 执行 getDirection 操作。
     * @return 返回值
     */
    public PacketDirection getDirection() {
        return direction;
    }


    /**
     * 执行 getPacketType 操作。
     * @return 返回值
     */
    public PacketType getPacketType() {
        return PacketType.fromValue(packetTypeFlagged & 0x0F);
    }


    /**
     * 执行 setPacketType 操作。
     * @param type 参数 type
     */
    public void setPacketType(PacketType type) {
        packetTypeFlagged = (byte) ((packetTypeFlagged & 0xF0) | (type.value() & 0x0F));
    }


    /**
     * 执行 getPacketTypeFlagged 操作。
     * @return 返回值
     */
    public byte getPacketTypeFlagged() {
        return packetTypeFlagged;
    }


    /**
     * 执行 setFlag 操作。
     * @param flag 参数 flag
     * @param enabled 参数 enabled
     */
    public void setFlag(PacketFlags flag, boolean enabled) {
        if (enabled) {
            packetTypeFlagged = (byte) (packetTypeFlagged | flag.mask());
        } else {
            packetTypeFlagged = (byte) (packetTypeFlagged & ~flag.mask());
        }
    }


    /**
     * 执行 hasFlag 操作。
     * @param flag 参数 flag
     * @return 返回值
     */
    public boolean hasFlag(PacketFlags flag) {
        return (packetTypeFlagged & flag.mask()) != 0;
    }


    /**
     * 执行 getPacketId 操作。
     * @return 返回值
     */
    public int getPacketId() {
        return packetId;
    }


    /**
     * 执行 setPacketId 操作。
     * @param packetId 参数 packetId
     */
    public void setPacketId(int packetId) {
        this.packetId = packetId;
    }


    /**
     * 执行 getGenerationId 操作。
     * @return 返回值
     */
    public int getGenerationId() {
        return generationId;
    }


    /**
     * 执行 setGenerationId 操作。
     * @param generationId 参数 generationId
     */
    public void setGenerationId(int generationId) {
        this.generationId = generationId;
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
     * 执行 getData 操作。
     * @return 返回值
     */
    public byte[] getData() {
        return data;
    }


    /**
     * 执行 setData 操作。
     * @param data 参数 data
     */
    public void setData(byte[] data) {
        this.data = data == null ? new byte[0] : Arrays.copyOf(data, data.length);
    }


    /**
     * 执行 toRaw 操作。
     * @return 返回值
     */
    public byte[] toRaw() {
        byte[] header = buildHeader();
        int totalLen = ProtocolConst.MAC_LEN + header.length + data.length;
        byte[] out = new byte[totalLen];
        System.arraycopy(header, 0, out, ProtocolConst.MAC_LEN, header.length);
        System.arraycopy(data, 0, out, ProtocolConst.MAC_LEN + header.length, data.length);
        raw = Arrays.copyOf(out, out.length);
        return raw;
    }


    /**
     * 执行 getRaw 操作。
     * @return 返回值
     */
    public byte[] getRaw() {
        return raw;
    }


    /**
     * 执行 setRaw 操作。
     * @param raw 参数 raw
     */
    public void setRaw(byte[] raw) {
        this.raw = raw;
    }


    /**
     * 执行 buildHeader 操作。
     * @return 返回值
     */
    public byte[] buildHeader() {
        byte[] header = new byte[direction.headerLength()];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        buffer.putShort((short) packetId);
        if (direction == PacketDirection.C2S) {
            buffer.putShort((short) clientId);
        }
        buffer.put(packetTypeFlagged);
        return header;
    }

    private void parseHeader() {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        buffer.position(ProtocolConst.MAC_LEN);
        packetId = Short.toUnsignedInt(buffer.getShort());
        if (direction == PacketDirection.C2S) {
            clientId = Short.toUnsignedInt(buffer.getShort());
        }
        packetTypeFlagged = buffer.get();
    }
}
