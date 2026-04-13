package pub.longyi.ts3audiobot.shuffle;

import java.util.ArrayList;
import java.util.List;

/**
 * 随机播放会话状态。
 *
 * <p>该类仅包含与随机序列本身相关的数据，不依赖业务实体，方便后续迁移复用。</p>
 */
final class ShuffleSessionState {
    private List<String> orderItemIds = new ArrayList<>();
    private int cursor = -1;
    private String playlistFingerprint = "";
    private boolean dirty = true;
    private long updatedAt = System.currentTimeMillis();

    List<String> orderItemIds() {
        return orderItemIds;
    }

    void setOrderItemIds(List<String> orderItemIds) {
        this.orderItemIds = orderItemIds == null ? new ArrayList<>() : new ArrayList<>(orderItemIds);
    }

    int cursor() {
        return cursor;
    }

    void setCursor(int cursor) {
        this.cursor = Math.max(-1, cursor);
    }

    String playlistFingerprint() {
        return playlistFingerprint == null ? "" : playlistFingerprint;
    }

    void setPlaylistFingerprint(String playlistFingerprint) {
        this.playlistFingerprint = playlistFingerprint == null ? "" : playlistFingerprint;
    }

    boolean dirty() {
        return dirty;
    }

    void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    long updatedAt() {
        return updatedAt;
    }

    void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
