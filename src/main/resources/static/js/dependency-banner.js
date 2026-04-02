(function (window, $) {
    'use strict';
    if (!$) {
        return;
    }

    function formatBytes(value) {
        const num = Number(value || 0);
        if (!Number.isFinite(num) || num <= 0) {
            return '0 B';
        }
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        let idx = 0;
        let size = num;
        while (size >= 1024 && idx < units.length - 1) {
            size /= 1024;
            idx += 1;
        }
        const fixed = size >= 10 || idx === 0 ? 0 : 1;
        return `${size.toFixed(fixed)} ${units[idx]}`;
    }

    function formatEta(seconds) {
        if (!Number.isFinite(seconds) || seconds < 0) {
            return '--';
        }
        const s = Math.floor(seconds);
        if (s < 60) {
            return `${s}s`;
        }
        const m = Math.floor(s / 60);
        const remain = s % 60;
        return `${m}m ${remain}s`;
    }

    function renderItem(item) {
        const downloading = Boolean(item.downloading);
        const installed = Boolean(item.installed);
        const canDownload = Boolean(item.canDownload);
        const restartRequired = Boolean(item.restartRequired);
        const progress = Number(item.progressPercent);
        const hasProgress = Number.isFinite(progress);
        const downloaded = formatBytes(item.downloadedBytes);
        const total = Number(item.totalBytes) > 0 ? formatBytes(item.totalBytes) : '--';
        const speed = Number(item.speedBytesPerSecond) > 0 ? `${formatBytes(item.speedBytesPerSecond)}/s` : '--';
        const eta = formatEta(Number(item.etaSeconds));
        const statusText = installed
            ? '已就绪'
            : downloading
                ? '下载中'
                : restartRequired
                    ? '需重启生效'
                    : '缺失';
        const statusClass = installed ? 'text-bg-success' : (downloading ? 'text-bg-warning' : 'text-bg-danger');
        const progressBar = downloading ? `
            <div class="progress mt-2" style="height: 8px;">
                <div class="progress-bar progress-bar-striped progress-bar-animated" role="progressbar" style="width: ${hasProgress ? Math.max(1, Math.min(progress, 100)) : 15}%"></div>
            </div>
            <div class="small text-muted mt-1">已下载 ${downloaded} / ${total} · 速度 ${speed} · 剩余 ${eta}</div>
        ` : '';
        const action = (!installed && !downloading && canDownload)
            ? `<button type="button" class="btn btn-sm btn-warning dep-download-btn" data-tool="${item.id}">下载</button>`
            : '';
        const configured = item.configured ? `<div class="small text-muted mt-1">当前配置：${$('<div/>').text(item.configured).html()}</div>` : '';
        return `
            <div class="border rounded p-3 mb-2">
                <div class="d-flex align-items-center justify-content-between gap-2">
                    <div class="fw-semibold">${$('<div/>').text(item.name || item.id || '依赖').html()}</div>
                    <span class="badge ${statusClass}">${statusText}</span>
                </div>
                <div class="small mt-1">${$('<div/>').text(item.message || '').html()}</div>
                ${progressBar}
                ${configured}
                <div class="mt-2">${action}</div>
            </div>
        `;
    }

    function needsAttention(item) {
        if (!item) {
            return false;
        }
        return !item.installed || item.downloading || item.restartRequired;
    }

    function mount(selector) {
        const $root = $(selector);
        if (!$root.length) {
            return;
        }
        let timer = null;
        let pollingMs = 8000;
        let loading = false;

        function schedule(ms) {
            if (timer) {
                clearTimeout(timer);
            }
            timer = setTimeout(fetchStatus, ms);
        }

        function setLoading(on) {
            loading = on;
            $root.find('.dep-download-btn').prop('disabled', on);
        }

        function render(snapshot) {
            const items = snapshot && Array.isArray(snapshot.items) ? snapshot.items : [];
            const attentionItems = items.filter(needsAttention);
            if (!attentionItems.length) {
                $root.addClass('d-none').empty();
                pollingMs = 12000;
                return;
            }
            const hasDownloading = attentionItems.some((item) => item.downloading);
            pollingMs = hasDownloading ? 1000 : 5000;
            const listHtml = attentionItems.map(renderItem).join('');
            const restartHint = attentionItems.some((item) => item.restartRequired)
                ? '<div class="alert alert-warning py-2 mb-2">依赖已下载，但当前进程可能仍在使用旧配置，建议重启服务。</div>'
                : '';
            $root
                .removeClass('d-none')
                .html(`
                    <div class="alert alert-warning shadow-sm mb-3">
                        <div class="d-flex align-items-center justify-content-between mb-2">
                            <strong>运行依赖缺失</strong>
                            <button type="button" class="btn btn-sm btn-outline-light dep-refresh-btn">刷新</button>
                        </div>
                        <div class="small mb-2">检测到部分工具不可用，可直接下载并安装到当前项目目录。</div>
                        ${restartHint}
                        ${listHtml}
                    </div>
                `);
            bindActions();
        }

        function startDownload(toolId) {
            if (!toolId) {
                return;
            }
            setLoading(true);
            $.post(`/internal/dependencies/${encodeURIComponent(toolId)}/download`)
                .always(() => {
                    setLoading(false);
                    fetchStatus();
                });
        }

        function bindActions() {
            $root.find('.dep-download-btn').off('click').on('click', function () {
                startDownload($(this).data('tool'));
            });
            $root.find('.dep-refresh-btn').off('click').on('click', function () {
                fetchStatus();
            });
        }

        function fetchStatus() {
            if (loading) {
                schedule(pollingMs);
                return;
            }
            $.getJSON('/internal/dependencies')
                .done((data) => render(data))
                .fail(() => {
                    $root
                        .removeClass('d-none')
                        .html('<div class="alert alert-danger mb-3">依赖状态获取失败，请稍后重试。</div>');
                    pollingMs = 10000;
                })
                .always(() => schedule(pollingMs));
        }

        fetchStatus();
    }

    window.TS3DependencyBanner = { mount };
}(window, window.jQuery));
