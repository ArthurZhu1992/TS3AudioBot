const STORAGE_KEY = 'ts3ab.selectedBotId';
    const PLAYLIST_KEY_PREFIX = 'ts3ab.playlist.';
    const params = new URLSearchParams(window.location.search);
    const $botSelect = $('#botSelect');
    const $playlistSelect = $('#playlistSelect');
    const $alert = $('#pageAlert');

    function showAlert(message) {
        if (!message) return;
        $alert.text(message).removeClass('d-none');
    }

    function clearAlert() {
        $alert.addClass('d-none').text('');
    }

    function getBotId() {
        return new URLSearchParams(window.location.search).get('botId');
    }

    function getPlaylistId() {
        const value = $playlistSelect.val();
        return value && value.length ? value : 'default';
    }

    function persistPlaylist(botId, playlistId) {
        if (!botId || !playlistId) return;
        localStorage.setItem(PLAYLIST_KEY_PREFIX + botId, playlistId);
    }

    function switchPlaylist() {
        clearAlert();
        const botId = getBotId();
        const playlistId = getPlaylistId();
        if (!botId) {
            showAlert('请选择机器人');
            return;
        }
        persistPlaylist(botId, playlistId);
        $.ajax({
            url: `/internal/queue/${botId}/playlists/${encodeURIComponent(playlistId)}/activate`,
            method: 'POST'
        }).done(() => {
            window.location.href = `/queue?botId=${encodeURIComponent(botId)}&playlistId=${encodeURIComponent(playlistId)}`;
        }).fail(() => showAlert('切换失败，请稍后重试'));
    }

    function activatePlaylist() {
        clearAlert();
        const botId = getBotId();
        const playlistId = getPlaylistId();
        if (!botId || !playlistId) {
            showAlert('请选择机器人和播放列表');
            return;
        }
        persistPlaylist(botId, playlistId);
        $.ajax({
            url: `/internal/queue/${botId}/playlists/${encodeURIComponent(playlistId)}/activate`,
            method: 'POST'
        }).done(() => window.location.reload())
            .fail(() => showAlert('设置播放列表失败'));
    }

    function createPlaylist() {
        clearAlert();
        const botId = getBotId();
        const name = $('#playlistName').val().trim();
        if (!botId || !name) {
            showAlert('请输入播放列表名称');
            return;
        }
        $.ajax({
            url: `/internal/queue/${botId}/playlists`,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ name })
        }).done(() => {
            persistPlaylist(botId, name);
            window.location.href = `/queue?botId=${encodeURIComponent(botId)}&playlistId=${encodeURIComponent(name)}`;
        }).fail(() => showAlert('创建失败'));
    }

    function renamePlaylist() {
        clearAlert();
        const botId = getBotId();
        const oldName = getPlaylistId();
        const name = $('#renameName').val().trim();
        if (!botId || !oldName || !name) {
            showAlert('请输入新名称');
            return;
        }
        if (oldName === 'default') {
            showAlert('默认播放列表不允许重命名');
            return;
        }
        $.ajax({
            url: `/internal/queue/${botId}/playlists/${encodeURIComponent(oldName)}/rename`,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ name })
        }).done(() => {
            persistPlaylist(botId, name);
            window.location.href = `/queue?botId=${encodeURIComponent(botId)}&playlistId=${encodeURIComponent(name)}`;
        }).fail(() => showAlert('重命名失败'));
    }

    function deletePlaylist() {
        clearAlert();
        const botId = getBotId();
        const playlistId = getPlaylistId();
        if (!botId || !playlistId) {
            showAlert('请选择播放列表');
            return;
        }
        if (playlistId === 'default') {
            showAlert('默认播放列表不可删除');
            return;
        }
        $.ajax({
            url: `/internal/queue/${botId}/playlists/${encodeURIComponent(playlistId)}`,
            method: 'DELETE'
        }).done(() => {
            localStorage.removeItem(PLAYLIST_KEY_PREFIX + botId);
            window.location.href = `/queue?botId=${encodeURIComponent(botId)}`;
        }).fail(() => showAlert('删除失败'));
    }

    function addToQueue() {
        clearAlert();
        const botId = getBotId();
        const playlistId = getPlaylistId();
        const query = $('#songQuery').val().trim();
        const addedBy = $('#songAddedBy').val().trim() || 'web';
        if (!botId || !query) {
            showAlert('请输入歌曲链接或关键词');
            return;
        }
        persistPlaylist(botId, playlistId);
        $.ajax({
            url: `/internal/queue/${botId}/${encodeURIComponent(playlistId)}/add`,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ query, addedBy })
        }).done(() => window.location.reload())
            .fail(() => showAlert('添加失败'));
    }

    $(function () {
        const botId = params.get('botId');
        const playlistId = params.get('playlistId');

        if (!botId) {
            const stored = localStorage.getItem(STORAGE_KEY);
            if (stored) {
                const storedPlaylist = localStorage.getItem(PLAYLIST_KEY_PREFIX + stored);
                const target = storedPlaylist
                    ? `/queue?botId=${encodeURIComponent(stored)}&playlistId=${encodeURIComponent(storedPlaylist)}`
                    : `/queue?botId=${encodeURIComponent(stored)}`;
                window.location.href = target;
                return;
            }
        } else {
            localStorage.setItem(STORAGE_KEY, botId);
        }

        if (botId && !playlistId) {
            const storedPlaylist = localStorage.getItem(PLAYLIST_KEY_PREFIX + botId);
            if (storedPlaylist) {
                window.location.href = `/queue?botId=${encodeURIComponent(botId)}&playlistId=${encodeURIComponent(storedPlaylist)}`;
                return;
            }
        }

        $botSelect.on('change', () => {
            const selected = $botSelect.val();
            if (!selected) return;
            localStorage.setItem(STORAGE_KEY, selected);
            const storedPlaylist = localStorage.getItem(PLAYLIST_KEY_PREFIX + selected);
            const target = storedPlaylist
                ? `/queue?botId=${encodeURIComponent(selected)}&playlistId=${encodeURIComponent(storedPlaylist)}`
                : `/queue?botId=${encodeURIComponent(selected)}`;
            window.location.href = target;
        });

        $('#switchPlaylistBtn').on('click', switchPlaylist);
        $('#activatePlaylistBtn').on('click', activatePlaylist);
        $('#deletePlaylistBtn').on('click', deletePlaylist);
        $('#createPlaylistBtn').on('click', createPlaylist);
        $('#renamePlaylistBtn').on('click', renamePlaylist);
        $('#addSongBtn').on('click', addToQueue);
    });
