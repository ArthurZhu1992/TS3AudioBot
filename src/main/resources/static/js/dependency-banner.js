(function (window, $) {
    'use strict';
    if (!$) {
        return;
    }

    function escapeHtml(value) {
        return $('<div/>').text(value == null ? '' : String(value)).html();
    }

    function normalizeDownloadUrl(value) {
        if (!value) {
            return '';
        }
        const url = String(value).trim();
        if (!url) {
            return '';
        }
        return /^https?:\/\//i.test(url) ? url : '';
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
            return `${s}秒`;
        }
        const m = Math.floor(s / 60);
        const remain = s % 60;
        return `${m}分 ${remain}秒`;
    }

    function renderItem(item) {
        const downloading = Boolean(item.downloading);
        const installed = Boolean(item.installed);
        const canDownload = Boolean(item.canDownload);
        const progress = Number(item.progressPercent);
        const hasProgress = Number.isFinite(progress);
        const downloaded = formatBytes(item.downloadedBytes);
        const total = Number(item.totalBytes) > 0 ? formatBytes(item.totalBytes) : '--';
        const speed = Number(item.speedBytesPerSecond) > 0 ? `${formatBytes(item.speedBytesPerSecond)}/s` : '--';
        const eta = formatEta(Number(item.etaSeconds));
        const statusText = installed ? '已就绪' : (downloading ? '下载中' : '缺失');
        const statusClass = installed ? 'text-bg-success' : (downloading ? 'text-bg-warning' : 'text-bg-danger');
        const progressBar = downloading ? `
            <div class="progress mt-2" style="height: 8px;">
                <div class="progress-bar progress-bar-striped progress-bar-animated" role="progressbar" style="width: ${hasProgress ? Math.max(1, Math.min(progress, 100)) : 15}%"></div>
            </div>
            <div class="small text-muted mt-1">已下载 ${downloaded} / ${total} · 速度 ${speed} · 剩余 ${eta}</div>
        ` : '';
        const action = (!installed && !downloading && canDownload)
            ? `<button type="button" class="btn btn-sm btn-warning dep-download-btn" data-tool="${escapeHtml(item.id)}">下载</button>`
            : '';
        const configured = item.configured
            ? `<div class="small text-muted mt-1">当前配置：${escapeHtml(item.configured)}</div>`
            : '';
        const targetPath = item.targetPath
            ? `<div class="small text-muted mt-1">目标位置：<code>${escapeHtml(item.targetPath)}</code></div>`
            : '';
        const downloadUrl = normalizeDownloadUrl(item.downloadUrl);
        const manualDownload = downloadUrl
            ? `<div class="small text-muted mt-1">下载地址：<a href="${escapeHtml(downloadUrl)}" target="_blank" rel="noopener noreferrer">${escapeHtml(downloadUrl)}</a></div>
               <div class="small text-muted">若自动下载较慢，可手动下载后放到上面的目标位置。</div>`
            : '';
        return `
            <div class="border rounded p-3 mb-2">
                <div class="d-flex align-items-center justify-content-between gap-2">
                    <div class="fw-semibold">${escapeHtml(item.name || item.id || '依赖')}</div>
                    <span class="badge ${statusClass}">${statusText}</span>
                </div>
                <div class="small mt-1">${escapeHtml(item.message || '')}</div>
                ${progressBar}
                ${configured}
                ${targetPath}
                ${manualDownload}
                <div class="mt-2">${action}</div>
            </div>
        `;
    }

    function needsAttention(item) {
        if (!item) {
            return false;
        }
        return !item.installed || item.downloading;
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
                if (timer) {
                    clearTimeout(timer);
                    timer = null;
                }
                return false;
            }
            const hasDownloading = attentionItems.some((entry) => entry.downloading);
            pollingMs = hasDownloading ? 1000 : 5000;
            const listHtml = attentionItems.map(renderItem).join('');
            $root
                .removeClass('d-none')
                .html(`
                    <div class="alert alert-warning shadow-sm mb-3">
                        <div class="d-flex align-items-center justify-content-between mb-2">
                            <strong>运行依赖缺失</strong>
                            <button type="button" class="btn btn-sm btn-outline-light dep-refresh-btn">刷新</button>
                        </div>
                        <div class="small mb-2">检测到依赖不可用。下载或手动复制到目标位置后会自动恢复。</div>
                        ${listHtml}
                    </div>
                `);
            bindActions();
            return true;
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
                .done((data) => {
                    const keepPolling = render(data);
                    if (keepPolling) {
                        schedule(pollingMs);
                    }
                })
                .fail(() => {
                    $root
                        .removeClass('d-none')
                        .html('<div class="alert alert-danger mb-3">依赖状态获取失败，请稍后重试。</div>');
                    pollingMs = 10000;
                    schedule(pollingMs);
                });
        }

        fetchStatus();
    }

    window.TS3DependencyBanner = { mount };
}(window, window.jQuery));
