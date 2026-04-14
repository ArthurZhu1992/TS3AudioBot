const $form = $('#botForm');
    const form = $form[0];
    const $formTitle = $('#formTitle');
    const $formAlert = $('#formAlert');
    const $submitBtn = $('#submitBtn');
    const $deleteTargetName = $('#deleteTargetName');
    const STATUS_LABELS = {
        ONLINE: '在线',
        OFFLINE: '离线',
        CONNECTING: '连接中',
        ERROR: '异常'
    };
    const runtimeStates = new Map();
    const runtimePendingActions = new Map();
    const RUNTIME_POLL_INTERVAL_MS = 3000;
    let deleteTarget = '';
    let deleteModal = null;
    let runtimePollTimer = null;
    let runtimePolling = false;

    function showAlert(message) {
        if (!message) return;
        $formAlert.text(message).removeClass('d-none');
    }

    function clearAlert() {
        $formAlert.addClass('d-none').text('');
    }

    function normalizeRuntimeState(rawState, online) {
        const value = String(rawState || '').trim().toUpperCase();
        if (value === 'ONLINE') return 'ONLINE';
        if (value === 'OFFLINE' || value === 'STOPPED') return 'OFFLINE';
        if (value === 'CONNECTING' || value === 'STARTING') return 'CONNECTING';
        if (value === 'ERROR' || value === 'FAILED') return 'ERROR';
        if (value === 'RUNNING') {
            return online ? 'ONLINE' : 'CONNECTING';
        }
        if (value.includes('ONLINE') || value.includes('CONNECTED')) return 'ONLINE';
        if (value.includes('START') || value.includes('CONNECT')) return 'CONNECTING';
        if (value.includes('ERROR') || value.includes('FAIL')) return 'ERROR';
        return online ? 'ONLINE' : 'OFFLINE';
    }

    function resolveBadgeClass(state) {
        if (state === 'ONLINE') return 'text-bg-success';
        if (state === 'CONNECTING') return 'text-bg-warning';
        if (state === 'ERROR') return 'text-bg-danger';
        return 'text-bg-secondary';
    }

    function resolveStatusLabel(state) {
        return STATUS_LABELS[state] || STATUS_LABELS.OFFLINE;
    }

    function applyStatusBadge($badge, state) {
        const normalized = normalizeRuntimeState(state, state === 'ONLINE');
        $badge
            .removeClass('text-bg-secondary text-bg-success text-bg-warning text-bg-danger')
            .addClass(resolveBadgeClass(normalized))
            .attr('data-status', normalized)
            .text(resolveStatusLabel(normalized));
    }

    function applyStatusBadges() {
        $('.status-badge').each(function () {
            const status = String($(this).attr('data-status') || '');
            applyStatusBadge($(this), status);
        });
    }

    function bootstrapRuntimeStatesFromDom() {
        $('.bot-row').each(function () {
            const $row = $(this);
            const name = String($row.attr('data-name') || '').trim();
            if (!name) {
                return;
            }
            const rawStatus = String($row.find('.status-badge').attr('data-status') || '');
            const state = normalizeRuntimeState(rawStatus, rawStatus.toUpperCase() === 'ONLINE');
            const item = { name, state, online: state === 'ONLINE' };
            runtimeStates.set(name, item);
            renderRuntimeState(item);
        });
    }

    function findBotRow(botName) {
        return $('.bot-row').filter(function () {
            return String($(this).attr('data-name') || '') === botName;
        });
    }

    function renderConnectButton($button, state) {
        const pendingAction = runtimePendingActions.get(state.name) || '';
        $button
            .removeClass('btn-outline-success btn-outline-warning btn-outline-secondary')
            .prop('disabled', false);

        if (pendingAction === 'connect') {
            $button.addClass('btn-outline-secondary').prop('disabled', true).text('连接中...');
            return;
        }
        if (pendingAction === 'disconnect') {
            $button.addClass('btn-outline-secondary').prop('disabled', true).text('断开中...');
            return;
        }
        if (state.state === 'CONNECTING') {
            $button.addClass('btn-outline-secondary').prop('disabled', true).text('连接中...');
            return;
        }
        if (state.state === 'ONLINE') {
            $button.addClass('btn-outline-warning').text('断开');
            return;
        }
        $button.addClass('btn-outline-success').text('连接');
    }

    function renderRuntimeState(state) {
        if (!state || !state.name) {
            return;
        }
        const $row = findBotRow(state.name);
        if (!$row.length) {
            return;
        }
        const $badge = $row.find('.status-badge');
        const $button = $row.find('.toggle-connect-btn');
        if ($badge.length) {
            applyStatusBadge($badge, state.state);
        }
        if ($button.length) {
            renderConnectButton($button, state);
        }
    }

    function normalizeRuntimePayload(item) {
        const name = String(item?.name || '').trim();
        const online = item?.online === true;
        const normalizedState = normalizeRuntimeState(item?.state || item?.lifecycleStatus, online);
        return {
            name,
            online: normalizedState === 'ONLINE',
            state: normalizedState
        };
    }

    function updateRuntimeState(item) {
        const normalized = normalizeRuntimePayload(item);
        if (!normalized.name) {
            return;
        }
        runtimeStates.set(normalized.name, normalized);
        renderRuntimeState(normalized);
    }

    function refreshRuntimeStates() {
        if (runtimePolling) {
            return;
        }
        // 轮询防重入，避免慢请求叠加导致状态闪烁。
        runtimePolling = true;
        $.getJSON('/internal/admin/bots/runtime')
            .done((items) => {
                if (!Array.isArray(items)) {
                    return;
                }
                items.forEach(updateRuntimeState);
            })
            .always(() => {
                runtimePolling = false;
            });
    }

    function toggleBotConnection(button) {
        const $button = $(button);
        const botName = String($button.attr('data-name') || '').trim();
        if (!botName || runtimePendingActions.has(botName)) {
            return;
        }
        const current = runtimeStates.get(botName);
        const action = current && current.state === 'ONLINE' ? 'disconnect' : 'connect';
        // 先把按钮置为处理中，防止用户连续点击触发重复请求。
        runtimePendingActions.set(botName, action);
        renderRuntimeState(current || { name: botName, state: 'OFFLINE', online: false });

        $.ajax({
            url: `/internal/admin/bots/${encodeURIComponent(botName)}/${action}`,
            method: 'POST'
        }).done((data) => {
            if (data && typeof data === 'object') {
                updateRuntimeState(data);
            } else {
                refreshRuntimeStates();
            }
        }).fail((xhr) => {
            const text = xhr?.responseText || `机器人${action === 'connect' ? '连接' : '断开'}失败`;
            showAlert(text);
        }).always(() => {
            runtimePendingActions.delete(botName);
            const latest = runtimeStates.get(botName);
            renderRuntimeState(latest || { name: botName, state: 'OFFLINE', online: false });
            refreshRuntimeStates();
        });
    }

    function focusForm() {
        const offset = $('#botFormCard').offset();
        if (offset) {
            $('html, body').animate({ scrollTop: offset.top - 16 }, 250);
        }
    }

    function editBot(btn) {
        const $btn = $(btn);
        clearAlert();
        $formTitle.text('编辑机器人');
        form.originalName.value = $btn.data('name') || '';
        form.name.value = $btn.data('name') || '';
        form.run.value = $btn.data('run') === true || $btn.data('run') === 'true' ? 'true' : 'false';
        form.connectAddress.value = $btn.data('address') || '';
        form.channel.value = $btn.data('channel') || '';
        form.nickname.value = $btn.data('nickname') || '';
        form.serverPassword.value = $btn.data('serverPassword') || '';
        form.channelPassword.value = $btn.data('channelPassword') || '';
        form.identity.value = $btn.data('identity') || '';
        form.identityKeyOffset.value = $btn.data('identityOffset') || '8';
        form.clientVersion.value = $btn.data('clientVersion') || '';
        form.clientPlatform.value = $btn.data('clientPlatform') || '';
        form.clientVersionSign.value = $btn.data('clientSign') || '';
        form.clientHwid.value = $btn.data('clientHwid') || '';
        form.clientNicknamePhonetic.value = $btn.data('clientPhonetic') || '';
        form.clientDefaultToken.value = $btn.data('clientToken') || '';
        focusForm();
    }

    function resetForm() {
        clearAlert();
        $formTitle.text('新增机器人');
        form.reset();
        form.originalName.value = '';
    }

    function submitBot(event) {
        event.preventDefault();
        clearAlert();

        const payload = {
            name: form.name.value,
            run: form.run.value === 'true',
            connectAddress: form.connectAddress.value,
            channel: form.channel.value,
            nickname: form.nickname.value,
            serverPassword: form.serverPassword.value,
            channelPassword: form.channelPassword.value,
            identity: form.identity.value,
            identityKeyOffset: Number(form.identityKeyOffset.value || 8),
            clientVersion: form.clientVersion.value,
            clientPlatform: form.clientPlatform.value,
            clientVersionSign: form.clientVersionSign.value,
            clientHwid: form.clientHwid.value,
            clientNicknamePhonetic: form.clientNicknamePhonetic.value,
            clientDefaultToken: form.clientDefaultToken.value
        };
        const original = form.originalName.value;
        const url = original
            ? `/internal/admin/bots/${encodeURIComponent(original)}`
            : '/internal/admin/bots';
        const method = original ? 'PUT' : 'POST';

        $submitBtn.prop('disabled', true).text('保存中...');
        $.ajax({
            url,
            method,
            contentType: 'application/json',
            data: JSON.stringify(payload)
        }).done(() => window.location.reload())
            .fail((xhr) => {
                const msg = xhr.responseJSON?.message || xhr.responseText || '保存失败，请稍后重试。';
                showAlert(msg);
            })
            .always(() => {
                $submitBtn.prop('disabled', false).text('保存');
            });
        return false;
    }

    function requestDelete(name) {
        deleteTarget = name || '';
        $deleteTargetName.text(deleteTarget || '-');
        if (deleteModal) {
            deleteModal.show();
        }
    }

    function confirmDelete() {
        if (!deleteTarget) {
            return;
        }
        $.ajax({
            url: `/internal/admin/bots/${encodeURIComponent(deleteTarget)}`,
            method: 'DELETE'
        }).done(() => window.location.reload());
    }

    $(function () {
        if (window.TS3DependencyBanner) {
            window.TS3DependencyBanner.mount('#dependency-banner');
        }
        deleteModal = new bootstrap.Modal($('#deleteModal')[0]);
        $('[data-bs-toggle="tooltip"]').each(function () {
            new bootstrap.Tooltip(this);
        });
        applyStatusBadges();
        bootstrapRuntimeStatesFromDom();
        $('.toggle-connect-btn').on('click', function () { toggleBotConnection(this); });
        runtimePollTimer = window.setInterval(refreshRuntimeStates, RUNTIME_POLL_INTERVAL_MS);
        refreshRuntimeStates();

        $('.edit-btn').on('click', function () { editBot(this); });
        $('.delete-btn').on('click', function () { requestDelete($(this).data('name')); });
        $('#confirmDeleteBtn').on('click', confirmDelete);
        $('#resetBtn').on('click', resetForm);
        $('#newBotBtn').on('click', function () { resetForm(); focusForm(); });
        $form.on('submit', submitBot);

        $(window).on('beforeunload', function () {
            if (runtimePollTimer) {
                window.clearInterval(runtimePollTimer);
            }
        });
    });
