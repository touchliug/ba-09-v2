/* ===== API Layer ===== */
const API_BASE = '/api/analysis';

const api = {
    async health() {
        const ctrl = new AbortController();
        const tid = setTimeout(() => ctrl.abort(), 5000);
        try {
            const res = await fetch('/api/health', { signal: ctrl.signal });
            return res.ok;
        } catch {
            return false;
        } finally {
            clearTimeout(tid);
        }
    },

    async list() {
        const res = await fetch(`${API_BASE}/list`);
        if (!res.ok) throw new Error('list failed');
        return res.json();
    },

    async latest() {
        const res = await fetch(`${API_BASE}/latest`);
        if (!res.ok) throw new Error('latest failed');
        return res.json();
    },

    async latestByName(name) {
        try {
            const res = await fetch(`${API_BASE}/latest/${encodeURIComponent(name)}`);
            if (!res.ok) return null;
            return res.json();
        } catch {
            return null;
        }
    },

    async params(name) {
        const res = await fetch(`${API_BASE}/params/${encodeURIComponent(name)}`);
        if (!res.ok) throw new Error('params failed');
        return res.json();
    },

    async sync(name, params) {
        const ctrl = new AbortController();
        const tid = setTimeout(() => ctrl.abort(), 30000);
        try {
            const res = await fetch(`${API_BASE}/sync/${encodeURIComponent(name)}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(params),
                signal: ctrl.signal
            });
            if (res.status === 429) throw new Error('rate_limited');
            if (!res.ok) throw new Error(`sync failed: ${res.status}`);
            return res.json();
        } finally {
            clearTimeout(tid);
        }
    },

    async symbols() {
        const res = await fetch(`${API_BASE}/symbols`);
        if (!res.ok) throw new Error('symbols failed');
        return res.json();
    },

    async refreshSymbols() {
        const res = await fetch(`${API_BASE}/symbols/refresh`, { method: 'POST' });
        if (!res.ok) throw new Error('refresh failed');
        return res.json();
    }
};

/* ===== State ===== */
const state = {
    analyzers: [],
    latestReports: {},
    currentView: 'overview',
    currentAnalyzer: null,
    pollTimer: null,
    pollEnabled: true
};

/* ===== Sidebar ===== */
function renderSidebar() {
    const nav = document.querySelector('.sidebar-nav');
    let html = `<button class="nav-item${state.currentView === 'overview' ? ' active' : ''}" data-view="overview">总览</button>`;
    for (const name of state.analyzers) {
        const report = state.latestReports[name];
        const count = report ? report.matchedCount : 0;
        const badgeClass = count === 0 ? 'nav-badge zero' : 'nav-badge';
        const isActive = state.currentView === 'detail' && state.currentAnalyzer === name;
        html += `<button class="nav-item${isActive ? ' active' : ''}" data-view="detail" data-name="${escHtml(name)}">
            <span>${escHtml(name)}</span>
            <span class="${badgeClass}">${count}</span>
        </button>`;
    }
    nav.innerHTML = html;
    nav.querySelectorAll('.nav-item').forEach(el => {
        el.addEventListener('click', () => {
            const view = el.dataset.view;
            if (view === 'overview') {
                state.currentView = 'overview';
                state.currentAnalyzer = null;
                renderSidebar();
                renderOverview();
            } else {
                const name = el.dataset.name;
                state.currentView = 'detail';
                state.currentAnalyzer = name;
                renderSidebar();
                renderDetail(name);
            }
        });
    });
}

/* ===== Health Check ===== */
async function checkHealth() {
    const dot = document.getElementById('health-indicator');
    const txt = document.getElementById('health-text');
    const ok = await api.health();
    if (ok) {
        dot.className = 'status-dot online';
        txt.textContent = '在线';
    } else {
        dot.className = 'status-dot offline';
        txt.textContent = '离线';
    }
}

/* ===== Symbol Count ===== */
async function loadSymbolCount() {
    try {
        const data = await api.symbols();
        const count = Array.isArray(data) ? data.length : (data.count || '--');
        document.getElementById('symbol-count').textContent = `合约: ${count}`;
    } catch {
        document.getElementById('symbol-count').textContent = '合约: --';
    }
}

/* ===== Helpers ===== */
function escHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatTime(t) {
    if (!t) return '--';
    try { return new Date(t).toLocaleString('zh-CN'); } catch { return t; }
}

/* ===== Init ===== */
async function init() {
    checkHealth();
    loadSymbolCount();

    try {
        const [analyzerList, latestMap] = await Promise.all([api.list(), api.latest()]);
        state.analyzers = analyzerList || [];
        state.latestReports = latestMap || {};
    } catch (e) {
        console.error('Init load failed', e);
        state.analyzers = [];
        state.latestReports = {};
    }

    renderSidebar();
    renderOverview();
    startPolling();

    document.getElementById('btn-refresh-symbols').addEventListener('click', async () => {
        const btn = document.getElementById('btn-refresh-symbols');
        btn.disabled = true;
        btn.textContent = '刷新中...';
        try {
            await api.refreshSymbols();
            await loadSymbolCount();
        } catch (e) {
            console.error('Refresh symbols failed', e);
        } finally {
            btn.disabled = false;
            btn.textContent = '刷新合约列表';
        }
    });
}

/* ===== Overview ===== */
function renderOverview() {
    const content = document.getElementById('content');
    if (state.analyzers.length === 0) {
        content.innerHTML = `<div class="empty-state">暂无分析器数据</div>`;
        return;
    }
    let cards = '';
    for (const name of state.analyzers) {
        const report = state.latestReports[name];
        const count = report ? report.matchedCount : 0;
        const badgeClass = count === 0 ? 'card-badge zero' : 'card-badge';
        const time = report ? formatTime(report.analysisTime) : '--';
        let coinsHtml = '';
        if (report && report.coins && report.coins.length > 0) {
            const show = report.coins.slice(0, 3);
            for (const c of show) coinsHtml += `<span class="coin-tag">${escHtml(c.symbol)}</span>`;
            if (report.coins.length > 3) {
                coinsHtml += `<span class="coin-tag-more">+${report.coins.length - 3}</span>`;
            }
        } else {
            coinsHtml = `<span style="color:#aaa;font-size:12px;">无符合条件</span>`;
        }
        cards += `
        <div class="card" data-name="${escHtml(name)}">
            <div class="card-header">
                <span class="card-title">${escHtml(name)}</span>
                <span class="${badgeClass}">${count}</span>
            </div>
            <div class="card-meta">更新: ${escHtml(time)}</div>
            <div class="card-coins">${coinsHtml}</div>
        </div>`;
    }
    content.innerHTML = `<div class="page-title">分析器总览</div><div class="overview-grid">${cards}</div>`;
    content.querySelectorAll('.card').forEach(card => {
        card.addEventListener('click', () => {
            const name = card.dataset.name;
            state.currentView = 'detail';
            state.currentAnalyzer = name;
            renderSidebar();
            renderDetail(name);
        });
    });
}

/* ===== Detail View ===== */
async function renderDetail(name) {
    const content = document.getElementById('content');
    content.innerHTML = `<div class="loading">加载中...</div>`;

    let paramSpec = null;
    let report = null;
    try {
        [paramSpec, report] = await Promise.all([api.params(name), api.latestByName(name)]);
    } catch (e) {
        content.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
        return;
    }

    const isShortTerm = name === '短期急涨';
    const pollChecked = state.pollEnabled ? 'checked' : '';
    const pollSection = isShortTerm ? `
        <label class="poll-toggle">
            <input type="checkbox" id="poll-checkbox" ${pollChecked}>
            自动刷新 (30s)
        </label>` : '';

    let paramsHtml = '';
    if (paramSpec && paramSpec.params) {
        for (const [key, spec] of Object.entries(paramSpec.params)) {
            const inputType = (spec.type === 'int' || spec.type === 'integer' || spec.type === 'double' || spec.type === 'float' || spec.type === 'number') ? 'number' : 'text';
            const step = (spec.type === 'double' || spec.type === 'float') ? 'step="any"' : '';
            paramsHtml += `
            <div class="form-group">
                <label for="param-${escHtml(key)}">${escHtml(key)}</label>
                <input type="${inputType}" id="param-${escHtml(key)}" data-key="${escHtml(key)}"
                    data-type="${escHtml(spec.type || 'string')}"
                    value="${escHtml(String(spec.default ?? ''))}"
                    placeholder="${escHtml(String(spec.default ?? ''))}" ${step}>
                <span class="form-hint">${escHtml(spec.description || '')}</span>
            </div>`;
        }
    }

    const desc = paramSpec ? escHtml(paramSpec.description || '') : '';

    content.innerHTML = `
        <div class="page-title">${escHtml(name)}</div>
        <div class="detail-section">
            <div class="detail-title">参数配置${desc ? ' — ' + desc : ''}</div>
            <div class="param-form" id="param-form">${paramsHtml || '<span style="color:#aaa">无可配置参数</span>'}</div>
            <div class="detail-actions">
                <button class="btn-primary" id="btn-execute">执行分析</button>
                <button class="btn-default" id="btn-reset">重置参数</button>
                ${pollSection}
            </div>
            <div id="execute-error"></div>
        </div>
        <div class="detail-section" id="result-section">
            <div class="detail-title">分析结果</div>
            <div id="result-content"></div>
        </div>`;

    renderResultTable(report);
    bindDetailEvents(name, paramSpec);
}

/* ===== Result Table ===== */
function renderResultTable(report) {
    const container = document.getElementById('result-content');
    if (!container) return;
    if (!report || !report.coins || report.coins.length === 0) {
        container.innerHTML = `<div class="empty-state">暂无符合条件的交易对</div>`;
        return;
    }

    const hasScore = report.coins.some(c => c.score != null && c.score !== undefined);
    const sorted = [...report.coins].sort((a, b) => {
        if (hasScore) return (b.score || 0) - (a.score || 0);
        return (b.changePercent || 0) - (a.changePercent || 0);
    });

    let rows = '';
    for (const coin of sorted) {
        const pct = coin.changePercent != null ? coin.changePercent : null;
        let pctHtml = '<span class="neutral">--</span>';
        if (pct != null) {
            const cls = pct > 0 ? 'positive' : pct < 0 ? 'negative' : 'neutral';
            const sign = pct > 0 ? '+' : '';
            pctHtml = `<span class="${cls}">${sign}${pct.toFixed(2)}%</span>`;
        }
        const scoreCell = hasScore ? `<td>${coin.score != null ? escHtml(String(coin.score)) : '--'}</td>` : '';
        rows += `<tr>
            <td><strong>${escHtml(coin.symbol)}</strong></td>
            <td>${coin.currentPrice != null ? escHtml(String(coin.currentPrice)) : '--'}</td>
            <td>${pctHtml}</td>
            ${scoreCell}
            <td style="max-width:300px;word-break:break-word">${escHtml(coin.detail || '')}</td>
        </tr>`;
    }

    const scoreHeader = hasScore ? '<th>评分</th>' : '';
    container.innerHTML = `
        <div class="result-summary">
            分析时间: ${escHtml(formatTime(report.analysisTime))} &nbsp;|&nbsp;
            共分析: ${escHtml(String(report.totalAnalyzed || '--'))} &nbsp;|&nbsp;
            符合条件: <strong>${escHtml(String(report.matchedCount || 0))}</strong>
        </div>
        <div class="result-table-wrap">
            <table class="result-table">
                <thead><tr>
                    <th>币种</th><th>当前价格</th><th>涨跌幅</th>${scoreHeader}<th>详情</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
}

/* ===== Bind Detail Events ===== */
function bindDetailEvents(name, paramSpec) {
    const btnExecute = document.getElementById('btn-execute');
    const btnReset = document.getElementById('btn-reset');
    const errDiv = document.getElementById('execute-error');

    if (btnExecute) {
        btnExecute.addEventListener('click', async () => {
            errDiv.innerHTML = '';
            const params = {};
            if (paramSpec && paramSpec.params) {
                document.querySelectorAll('#param-form input[data-key]').forEach(input => {
                    const key = input.dataset.key;
                    const type = input.dataset.type;
                    const val = input.value.trim();
                    if (val === '') return;
                    if (type === 'integer' || type === 'int') {
                        params[key] = parseInt(val, 10);
                    } else if (type === 'number' || type === 'double' || type === 'float') {
                        params[key] = parseFloat(val);
                    } else if (type === 'boolean') {
                        params[key] = val === 'true';
                    } else {
                        params[key] = val;
                    }
                });
            }
            btnExecute.disabled = true;
            btnExecute.textContent = '执行中...';
            let rateLimited = false;
            try {
                const report = await api.sync(name, params);
                state.latestReports[name] = report;
                renderSidebar();
                renderResultTable(report);
                btnExecute.disabled = false;
                btnExecute.textContent = '执行分析';
            } catch (e) {
                rateLimited = e.message === 'rate_limited';
                if (rateLimited) {
                    errDiv.innerHTML = `<div class="error-msg">请求过于频繁，请稍后再试 (10秒冷却)</div>`;
                } else {
                    errDiv.innerHTML = `<div class="error-msg">执行失败: ${escHtml(String(e))}</div>`;
                }
                setTimeout(() => {
                    if (btnExecute) {
                        btnExecute.disabled = false;
                        btnExecute.textContent = '执行分析';
                    }
                }, 10000);
            }
        });
    }

    if (btnReset && paramSpec && paramSpec.params) {
        btnReset.addEventListener('click', () => {
            document.querySelectorAll('#param-form input[data-key]').forEach(input => {
                const key = input.dataset.key;
                const spec = paramSpec.params[key];
                if (spec) input.value = String(spec.default ?? '');
            });
        });
    }

    const pollCheckbox = document.getElementById('poll-checkbox');
    if (pollCheckbox) {
        pollCheckbox.addEventListener('change', () => {
            state.pollEnabled = pollCheckbox.checked;
            if (state.pollEnabled) {
                startPolling();
            } else {
                stopPolling();
            }
        });
    }
}

/* ===== Polling ===== */
function startPolling() {
    stopPolling();
    state.pollTimer = setInterval(async () => {
        if (!state.pollEnabled) return;
        try {
            const report = await api.latestByName('短期急涨');
            if (report) {
                state.latestReports['短期急涨'] = report;
                renderSidebar();
                if (state.currentView === 'detail' && state.currentAnalyzer === '短期急涨') {
                    renderResultTable(report);
                }
                if (state.currentView === 'overview') {
                    renderOverview();
                }
            }
        } catch (e) {
            console.warn('Poll failed', e);
        }
    }, 30000);
}

function stopPolling() {
    if (state.pollTimer) {
        clearInterval(state.pollTimer);
        state.pollTimer = null;
    }
}

/* ===== Bootstrap ===== */
document.addEventListener('DOMContentLoaded', init);

