/* =========================================================================
   netty-server 控制台 - 公共 JS
   所有页面共享的 Toast / 日志工具
   ========================================================================= */

/* ============ Toast ============ */
function showToast(message, type) {
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        container.className = 'toast-container';
        document.body.appendChild(container);
    }
    const toast = document.createElement('div');
    toast.className = 'toast ' + (type === 'error' ? 'error' : 'success');
    const icon = type === 'error' ? 'bi-x-circle-fill' : 'bi-check-circle-fill';
    toast.innerHTML = '<i class="bi ' + icon + '"></i><span></span>';
    toast.querySelector('span').textContent = message;
    container.appendChild(toast);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(20px)';
        toast.style.transition = 'all .2s';
        setTimeout(() => toast.remove(), 200);
    }, 2800);
}

/* ============ 操作日志 ============ */
function logOp(message, type) {
    const logBox = document.getElementById('ops-log');
    if (!logBox) return;
    const placeholder = logBox.querySelector('.log-empty');
    if (placeholder) logBox.innerHTML = '';
    const time = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    const line = document.createElement('div');
    line.className = 'log-line';
    const timeSpan = document.createElement('span');
    timeSpan.className = 'log-time';
    timeSpan.textContent = '[' + time + ']';
    const msgSpan = document.createElement('span');
    msgSpan.className = 'log-msg ' + (type || 'info');
    msgSpan.textContent = message;
    line.appendChild(timeSpan);
    line.appendChild(msgSpan);
    logBox.prepend(line);
}

function clearOpsLog() {
    const logBox = document.getElementById('ops-log');
    if (!logBox) return;
    logBox.innerHTML = '<div class="log-line"><span class="log-time">--</span>' +
                      '<span class="log-msg info">暂无操作记录</span></div>';
}

/* ============ REST 调用封装（所有页面通用） ============ */
async function callApi(method, url, successMsg, opts) {
    opts = opts || {};
    try {
        const resp = await fetch(url, { method: method, ...(opts.fetchOpts || {}) });
        const data = await resp.json();
        if (data.success === false) {
            const reason = data.reason || '操作失败';
            showToast(reason, 'error');
            logOp(method + ' ' + url + ' → 失败: ' + reason, 'error');
            return data;
        }
        const msg = successMsg || (data.message || '操作成功');
        showToast(msg, 'success');
        logOp(method + ' ' + url + ' → ' + msg, 'success');
        if (opts.onSuccess) opts.onSuccess(data);
        else if (opts.reload !== false) setTimeout(() => location.reload(), 800);
        return data;
    } catch (e) {
        showToast('请求异常: ' + e.message, 'error');
        logOp(method + ' ' + url + ' → 异常: ' + e.message, 'error');
    }
}
