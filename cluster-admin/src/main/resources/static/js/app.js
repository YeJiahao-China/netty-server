/* =========================================================================
   netty-server 控制台 - 公共 JS
   所有页面共享的 Toast / 日志工具
   ========================================================================= */

/* ============ Toast ============ */
function showToast(message, type, durationMs, onClick) {
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        container.className = 'toast-container';
        document.body.appendChild(container);
    }
    const toast = document.createElement('div');
    toast.className = 'toast ' + (type === 'error' ? 'error' : type === 'warning' ? 'warn' : 'success');
    const icon = type === 'error' ? 'bi-x-circle-fill'
               : type === 'warning' ? 'bi-exclamation-triangle-fill'
               : 'bi-check-circle-fill';
    toast.innerHTML = '<i class="bi ' + icon + '"></i><span></span>';
    toast.querySelector('span').textContent = message;
    container.appendChild(toast);
    // 点击关闭 toast；传入 onClick 时（如卸载后点击立即刷新列表）关闭后执行回调
    toast.addEventListener('click', () => {
        toast.remove();
        if (onClick) onClick();
    });
    // 警示类信息通常较长（如卸载"无节点可达/部分节点未完成"的详情），延长展示时间；
    // durationMs 可显式指定（如节点级成败明细等信息量大的提示）
    const duration = durationMs || (type === 'warning' ? 6000 : 2800);
    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(20px)';
        toast.style.transition = 'all .2s';
        setTimeout(() => toast.remove(), 200);
    }, duration);
}

/* ============ 操作日志 ============ */

/* ============ 通用确认弹窗 ============ */
function showConfirm(message, onConfirm, opts) {
    opts = opts || {};
    var modal = document.getElementById('confirm-modal');
    if (!modal) return;
    var titleEl = document.getElementById('confirm-title');
    var msgEl = document.getElementById('confirm-message');
    var iconEl = document.getElementById('confirm-icon');
    var okBtn = document.getElementById('confirm-ok-btn');

    titleEl.textContent = opts.title || '确认操作';
    msgEl.textContent = message;

    // 图标样式
    var iconClass = opts.icon || 'bi-exclamation-triangle';
    var iconColor = opts.iconColor || 'var(--warning)';
    iconEl.className = 'bi ' + iconClass;
    iconEl.style.color = iconColor;

    // 确认按钮样式
    okBtn.className = 'btn btn-sm ' + (opts.btnClass || 'btn-primary');
    okBtn.textContent = opts.btnText || '确定';

    // 重新绑定事件（先克隆移除旧监听）
    var newBtn = okBtn.cloneNode(true);
    okBtn.parentNode.replaceChild(newBtn, okBtn);
    newBtn.addEventListener('click', function () {
        hideConfirm();
        if (onConfirm) onConfirm();
    });

    modal.classList.remove('hidden');

    // 点击遮罩关闭
    modal.onclick = function (e) {
        if (e.target === modal) hideConfirm();
    };
}

function hideConfirm() {
    var modal = document.getElementById('confirm-modal');
    if (modal) {
        modal.classList.add('hidden');
        modal.onclick = null;
    }
}

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
        if (data.warning) {
            // 目标态类操作（unload/purge 等）可能"成功但带警示"（无节点可达、部分节点运行时未完成），
            // 警示内容必须呈现给管理员（例：无节点执行运行时卸载时不应立即删除 nginx 转发配置）
            showToast(data.warning, 'warning');
            logOp(method + ' ' + url + ' → 警示: ' + data.warning, 'warn');
        }
        if (opts.onSuccess) opts.onSuccess(data);
        else if (opts.reload !== false) setTimeout(() => location.reload(), 800);
        return data;
    } catch (e) {
        showToast('请求异常: ' + e.message, 'error');
        logOp(method + ' ' + url + ' → 异常: ' + e.message, 'error');
    }
}
