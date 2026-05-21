# Web Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a static web dashboard for the Binance futures analyzer with left-sidebar navigation, overview cards, and per-analyzer detail pages with parameter forms and result tables.

**Architecture:** Single-page static app (HTML + Vanilla JS + CSS) served from Spring Boot's `src/main/resources/static/`. Left sidebar lists analyzers, right panel shows either overview cards or analyzer detail with parameter form and results table. Short-term analyzer auto-polls every 30s.

**Tech Stack:** HTML5, Vanilla JavaScript (ES6+), CSS3, Fetch API

---

## File Structure

```
src/main/resources/static/
├── index.html      — page shell: header, sidebar, content area
├── css/
│   └── style.css   — all styles (layout, components, theme)
└── js/
    └── app.js      — all logic (API calls, rendering, state, polling)
```

---

## Task 1: HTML Page Shell

**Files:**
- Create: `src/main/resources/static/index.html`

- [ ] **Step 1: Create index.html with layout structure**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>币安合约分析系统</title>
    <link rel="stylesheet" href="css/style.css">
</head>
<body>
    <header class="header">
        <h1 class="header-title">币安合约分析系统</h1>
        <div class="header-status">
            <span id="health-indicator" class="status-dot offline"></span>
            <span id="health-text">检测中...</span>
            <span class="divider">|</span>
            <span id="symbol-count">合约: --</span>
        </div>
    </header>
    <div class="layout">
        <aside class="sidebar" id="sidebar">
            <nav class="sidebar-nav">
                <a class="nav-item active" data-view="overview">
                    总览
                </a>
            </nav>
            <div class="sidebar-footer">
                <button id="btn-refresh-symbols" class="btn-small">刷新合约列表</button>
            </div>
        </aside>
        <main class="content" id="content">
        </main>
    </div>
    <script src="js/app.js"></script>
</body>
</html>
```

- [ ] **Step 2: Verify file is served**

Run: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/index.html`
Expected: `200` (requires app running with `mvn spring-boot:run`)

---

## Task 2: CSS Styles — Layout and Theme

**Files:**
- Create: `src/main/resources/static/css/style.css`

- [ ] **Step 1: Create style.css with reset, layout, and header styles**

```css
* { margin: 0; padding: 0; box-sizing: border-box; }

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background: #f5f5f5;
    color: #333;
    height: 100vh;
    display: flex;
    flex-direction: column;
}

.header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 24px;
    height: 56px;
    background: #fff;
    border-bottom: 1px solid #e8e8e8;
    flex-shrink: 0;
}

.header-title { font-size: 18px; font-weight: 600; }
.header-status { display: flex; align-items: center; gap: 8px; font-size: 14px; color: #666; }

.status-dot { width: 8px; height: 8px; border-radius: 50%; }
.status-dot.online { background: #52c41a; }
.status-dot.offline { background: #ff4d4f; }

.divider { color: #d9d9d9; }

.layout { display: flex; flex: 1; overflow: hidden; }

.sidebar {
    width: 220px;
    background: #fff;
    border-right: 1px solid #e8e8e8;
    display: flex;
    flex-direction: column;
    overflow-y: auto;
    flex-shrink: 0;
}

.sidebar-nav { flex: 1; padding: 12px 0; }

.nav-item {
    display: flex; align-items: center; justify-content: space-between;
    padding: 10px 20px; cursor: pointer; color: #333;
    text-decoration: none; font-size: 14px; transition: background 0.2s;
}
.nav-item:hover { background: #f0f5ff; }
.nav-item.active { background: #e6f7ff; color: #1890ff; font-weight: 500; }

.badge {
    background: #1890ff; color: #fff; font-size: 12px;
    padding: 1px 6px; border-radius: 10px; min-width: 20px; text-align: center;
}

.sidebar-footer { padding: 12px 16px; border-top: 1px solid #e8e8e8; }
.content { flex: 1; padding: 24px; overflow-y: auto; }
```

- [ ] **Step 2: Append component styles (cards, forms, tables, buttons)**

Append to `style.css`:

```css
.btn-small {
    padding: 6px 12px; font-size: 13px; border: 1px solid #d9d9d9;
    border-radius: 4px; background: #fff; cursor: pointer; width: 100%;
}
.btn-small:hover { border-color: #1890ff; color: #1890ff; }

.btn-primary {
    padding: 8px 20px; font-size: 14px; border: none; border-radius: 4px;
    background: #1890ff; color: #fff; cursor: pointer;
}
.btn-primary:hover { background: #40a9ff; }
.btn-primary:disabled { background: #d9d9d9; cursor: not-allowed; }

.btn-default {
    padding: 8px 20px; font-size: 14px; border: 1px solid #d9d9d9;
    border-radius: 4px; background: #fff; cursor: pointer;
}
.btn-default:hover { border-color: #1890ff; color: #1890ff; }

.overview-grid {
    display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px;
}

.card {
    background: #fff; border-radius: 4px; padding: 16px;
    border: 1px solid #e8e8e8; cursor: pointer; transition: box-shadow 0.2s;
}
.card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.1); }

.card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.card-title { font-size: 15px; font-weight: 500; }
.card-meta { font-size: 12px; color: #999; }
.card-coins { font-size: 13px; color: #666; margin-top: 8px; }

.detail-section { background: #fff; border-radius: 4px; padding: 20px; border: 1px solid #e8e8e8; margin-bottom: 16px; }

.params-form { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
.form-group { display: flex; flex-direction: column; gap: 4px; }
.form-group label { font-size: 13px; color: #666; }
.form-group input { padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 4px; font-size: 14px; }
.form-group input:focus { outline: none; border-color: #1890ff; }
.form-group .param-hint { font-size: 11px; color: #999; }

.form-actions { display: flex; gap: 8px; margin-top: 16px; }

.result-table { width: 100%; border-collapse: collapse; font-size: 14px; }
.result-table th { text-align: left; padding: 10px 12px; background: #fafafa; border-bottom: 1px solid #e8e8e8; font-weight: 500; }
.result-table td { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; }
.result-table tr:hover td { background: #f5f5f5; }

.empty-state { text-align: center; padding: 40px; color: #999; font-size: 14px; }
.loading { text-align: center; padding: 40px; color: #999; }
.error-msg { color: #ff4d4f; font-size: 14px; padding: 12px; background: #fff2f0; border-radius: 4px; }
.poll-toggle { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #666; margin-bottom: 12px; }
```

- [ ] **Step 3: Verify styles load**

Open `http://localhost:8080` in browser, confirm layout renders (header + sidebar + content area).

---

## Task 3: JavaScript — API Layer and State

**Files:**
- Create: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Create app.js with API module and app state**

```javascript
const API_BASE = '/api/analysis';

const api = {
    async health() {
        const res = await fetch('/api/health', { signal: AbortSignal.timeout(5000) });
        return res.ok;
    },
    async list() {
        const res = await fetch(`${API_BASE}/list`);
        return res.json();
    },
    async latest() {
        const res = await fetch(`${API_BASE}/latest`);
        return res.json();
    },
    async latestByName(name) {
        const res = await fetch(`${API_BASE}/latest/${encodeURIComponent(name)}`);
        if (!res.ok) return null;
        return res.json();
    },
    async params(name) {
        const res = await fetch(`${API_BASE}/params/${encodeURIComponent(name)}`);
        return res.json();
    },
    async sync(name, params) {
        const res = await fetch(`${API_BASE}/sync/${encodeURIComponent(name)}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(params),
            signal: AbortSignal.timeout(30000)
        });
        if (res.status === 429) throw new Error('rate_limited');
        if (!res.ok) throw new Error('request_failed');
        return res.json();
    },
    async symbols() {
        const res = await fetch(`${API_BASE}/symbols`);
        return res.json();
    },
    async refreshSymbols() {
        const res = await fetch(`${API_BASE}/symbols/refresh`, { method: 'POST' });
        return res.json();
    }
};

const state = {
    analyzers: [],
    latestReports: {},
    currentView: 'overview',
    currentAnalyzer: null,
    pollTimer: null,
    pollEnabled: true
};
```

- [ ] **Step 2: Commit initial structure**

```bash
git add src/main/resources/static/
git commit -m "feat: add web dashboard skeleton (HTML + CSS + JS API layer)"
```

---

## Task 4: JavaScript — Initialization and Sidebar Rendering

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Add init function and sidebar rendering**

Append to `app.js`:

```javascript
function renderSidebar() {
    const nav = document.querySelector('.sidebar-nav');
    let html = '<a class="nav-item active" data-view="overview">总览</a>';
    for (const name of state.analyzers) {
        const report = state.latestReports[name];
        const count = report ? report.matchedCount : 0;
        const badgeHtml = count > 0 ? `<span class="badge">${count}</span>` : '';
        html += `<a class="nav-item" data-view="detail" data-name="${name}">
            <span>${name}</span>${badgeHtml}
        </a>`;
    }
    nav.innerHTML = html;

    nav.querySelectorAll('.nav-item').forEach(item => {
        item.addEventListener('click', () => {
            nav.querySelectorAll('.nav-item').forEach(i => i.classList.remove('active'));
            item.classList.add('active');
            const view = item.dataset.view;
            if (view === 'overview') {
                state.currentView = 'overview';
                state.currentAnalyzer = null;
                renderOverview();
            } else {
                state.currentView = 'detail';
                state.currentAnalyzer = item.dataset.name;
                renderDetail(item.dataset.name);
            }
        });
    });
}

async function checkHealth() {
    try {
        const ok = await api.health();
        document.getElementById('health-indicator').className = 'status-dot ' + (ok ? 'online' : 'offline');
        document.getElementById('health-text').textContent = ok ? '服务正常' : '服务离线';
    } catch {
        document.getElementById('health-indicator').className = 'status-dot offline';
        document.getElementById('health-text').textContent = '服务离线';
    }
}

async function loadSymbolCount() {
    try {
        const symbols = await api.symbols();
        document.getElementById('symbol-count').textContent = `合约: ${symbols.length}`;
    } catch { /* ignore */ }
}

async function init() {
    await checkHealth();
    await loadSymbolCount();
    try {
        state.analyzers = await api.list();
        state.latestReports = await api.latest();
    } catch (e) {
        document.getElementById('content').innerHTML = '<div class="error-msg">加载失败，请确认后端服务是否运行</div>';
        return;
    }
    renderSidebar();
    renderOverview();
    startPolling();

    document.getElementById('btn-refresh-symbols').addEventListener('click', async (e) => {
        e.target.disabled = true;
        e.target.textContent = '刷新中...';
        try {
            await api.refreshSymbols();
            await loadSymbolCount();
        } catch { /* ignore */ }
        e.target.disabled = false;
        e.target.textContent = '刷新合约列表';
    });
}

document.addEventListener('DOMContentLoaded', init);
```

---

## Task 5: JavaScript — Overview Page Rendering

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Add renderOverview function**

Append to `app.js`:

```javascript
function renderOverview() {
    const content = document.getElementById('content');
    const reports = state.latestReports;
    const names = state.analyzers;

    if (names.length === 0) {
        content.innerHTML = '<div class="empty-state">暂无分析器数据</div>';
        return;
    }

    let html = '<div class="overview-grid">';
    for (const name of names) {
        const report = reports[name];
        if (!report) {
            html += `<div class="card" data-name="${name}">
                <div class="card-header"><span class="card-title">${name}</span></div>
                <div class="card-meta">暂无结果</div>
            </div>`;
            continue;
        }
        const time = report.analysisTime || '--';
        const count = report.matchedCount || 0;
        const coins = (report.coins || []).slice(0, 3).map(c => c.symbol).join(', ');
        html += `<div class="card" data-name="${name}">
            <div class="card-header">
                <span class="card-title">${name}</span>
                <span class="badge">${count}</span>
            </div>
            <div class="card-meta">${time}</div>
            <div class="card-coins">${coins || '无匹配币种'}</div>
        </div>`;
    }
    html += '</div>';
    content.innerHTML = html;

    content.querySelectorAll('.card').forEach(card => {
        card.addEventListener('click', () => {
            const name = card.dataset.name;
            state.currentView = 'detail';
            state.currentAnalyzer = name;
            document.querySelectorAll('.nav-item').forEach(i => {
                i.classList.toggle('active', i.dataset.name === name);
            });
            renderDetail(name);
        });
    });
}
```

- [ ] **Step 2: Verify overview renders**

Open `http://localhost:8080`, confirm overview cards appear with analyzer names and latest results.

---

## Task 6: JavaScript — Detail Page with Parameter Form

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Add renderDetail function**

Append to `app.js`:

```javascript
async function renderDetail(name) {
    const content = document.getElementById('content');
    content.innerHTML = '<div class="loading">加载中...</div>';

    let paramSpec, report;
    try {
        [paramSpec, report] = await Promise.all([
            api.params(name),
            api.latestByName(name)
        ]);
    } catch {
        content.innerHTML = '<div class="error-msg">加载失败</div>';
        return;
    }

    const isShortTerm = name === '短期急涨';
    let html = '';

    if (isShortTerm) {
        html += `<div class="poll-toggle">
            <input type="checkbox" id="poll-checkbox" ${state.pollEnabled ? 'checked' : ''}>
            <label for="poll-checkbox">自动刷新 (每30秒)</label>
        </div>`;
    }

    html += `<div class="detail-section">
        <h3 style="margin-bottom:4px">${paramSpec.name}</h3>
        <p style="font-size:13px;color:#666;margin-bottom:16px">${paramSpec.description || ''}</p>
        <div class="params-form" id="params-form">`;

    const params = paramSpec.params || {};
    for (const [key, spec] of Object.entries(params)) {
        html += `<div class="form-group">
            <label>${key}</label>
            <input type="${spec.type === 'string' ? 'text' : 'number'}"
                   name="${key}" value="${spec.default}"
                   step="${spec.type === 'double' ? '0.1' : '1'}"
                   placeholder="${spec.default}">
            <span class="param-hint">${spec.description} (默认: ${spec.default})</span>
        </div>`;
    }

    html += `</div>
        <div class="form-actions">
            <button class="btn-primary" id="btn-execute">执行分析</button>
            <button class="btn-default" id="btn-reset">重置为默认</button>
        </div>
    </div>`;

    html += '<div class="detail-section" id="result-section">';
    html += renderResultTable(report);
    html += '</div>';

    content.innerHTML = html;
    bindDetailEvents(name, paramSpec);
}
```

---

## Task 7: JavaScript — Result Table and Event Binding

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Add renderResultTable function**

Append to `app.js`:

```javascript
function renderResultTable(report) {
    if (!report) return '<div class="empty-state">暂无分析结果，请点击"执行分析"</div>';
    if (!report.coins || report.coins.length === 0) {
        return `<p style="font-size:13px;color:#666;margin-bottom:8px">
            分析时间: ${report.analysisTime} | 扫描: ${report.totalAnalyzed} 个币种
        </p><div class="empty-state">无符合条件的币种</div>`;
    }

    let html = `<p style="font-size:13px;color:#666;margin-bottom:8px">
        分析时间: ${report.analysisTime} | 匹配: ${report.matchedCount}/${report.totalAnalyzed}
    </p>`;
    html += '<table class="result-table"><thead><tr>';
    html += '<th>币种</th><th>当前价格</th><th>涨跌幅</th>';

    const hasScore = report.coins.some(c => c.score != null);
    if (hasScore) html += '<th>评分</th>';
    html += '<th>详情</th></tr></thead><tbody>';

    const sorted = [...report.coins].sort((a, b) => {
        if (hasScore) return (b.score || 0) - (a.score || 0);
        return b.changePercent - a.changePercent;
    });

    for (const coin of sorted) {
        const color = coin.changePercent >= 0 ? '#52c41a' : '#ff4d4f';
        html += `<tr>
            <td><strong>${coin.symbol}</strong></td>
            <td>${coin.currentPrice}</td>
            <td style="color:${color}">${coin.changePercent >= 0 ? '+' : ''}${coin.changePercent.toFixed(2)}%</td>`;
        if (hasScore) html += `<td>${coin.score ?? '--'}</td>`;
        html += `<td style="font-size:12px;color:#666;max-width:300px">${coin.detail || ''}</td>`;
        html += '</tr>';
    }
    html += '</tbody></table>';
    return html;
}
```

- [ ] **Step 2: Add bindDetailEvents function**

Append to `app.js`:

```javascript
function bindDetailEvents(name, paramSpec) {
    const btnExecute = document.getElementById('btn-execute');
    const btnReset = document.getElementById('btn-reset');
    const pollCheckbox = document.getElementById('poll-checkbox');

    btnExecute.addEventListener('click', async () => {
        const form = document.getElementById('params-form');
        const inputs = form.querySelectorAll('input');
        const params = {};
        inputs.forEach(input => {
            const val = input.value.trim();
            if (val === '') return;
            const spec = (paramSpec.params || {})[input.name];
            if (spec && spec.type === 'int') params[input.name] = parseInt(val);
            else if (spec && spec.type === 'double') params[input.name] = parseFloat(val);
            else params[input.name] = val;
        });

        btnExecute.disabled = true;
        btnExecute.textContent = '执行中...';
        const resultSection = document.getElementById('result-section');
        resultSection.innerHTML = '<div class="loading">分析中，请稍候...</div>';

        try {
            const report = await api.sync(name, params);
            state.latestReports[name] = report;
            resultSection.innerHTML = renderResultTable(report);
            renderSidebar();
            highlightNav(name);
        } catch (e) {
            if (e.message === 'rate_limited') {
                resultSection.innerHTML = '<div class="error-msg">请求过于频繁，请10秒后重试</div>';
            } else {
                resultSection.innerHTML = '<div class="error-msg">执行失败，请重试</div>';
            }
        }

        setTimeout(() => {
            btnExecute.disabled = false;
            btnExecute.textContent = '执行分析';
        }, 10000);
    });

    btnReset.addEventListener('click', () => {
        const form = document.getElementById('params-form');
        form.querySelectorAll('input').forEach(input => {
            input.value = input.placeholder;
        });
    });

    if (pollCheckbox) {
        pollCheckbox.addEventListener('change', (e) => {
            state.pollEnabled = e.target.checked;
            if (state.pollEnabled) startPolling();
            else stopPolling();
        });
    }
}

function highlightNav(name) {
    document.querySelectorAll('.nav-item').forEach(i => {
        i.classList.toggle('active', i.dataset.name === name);
    });
}
```

- [ ] **Step 3: Commit detail page logic**

```bash
git add src/main/resources/static/js/app.js
git commit -m "feat: add overview and detail page rendering with parameter forms"
```

---

## Task 8: JavaScript — Polling for Short-Term Analyzer

**Files:**
- Modify: `src/main/resources/static/js/app.js`

- [ ] **Step 1: Add polling functions**

Append to `app.js`:

```javascript
function startPolling() {
    stopPolling();
    if (!state.pollEnabled) return;
    state.pollTimer = setInterval(async () => {
        try {
            const report = await api.latestByName('短期急涨');
            if (report) {
                state.latestReports['短期急涨'] = report;
                renderSidebar();
                if (state.currentView === 'overview') renderOverview();
                if (state.currentView === 'detail' && state.currentAnalyzer === '短期急涨') {
                    const section = document.getElementById('result-section');
                    if (section) section.innerHTML = renderResultTable(report);
                }
                highlightNav(state.currentAnalyzer);
            }
        } catch { /* ignore polling errors */ }
    }, 30000);
}

function stopPolling() {
    if (state.pollTimer) {
        clearInterval(state.pollTimer);
        state.pollTimer = null;
    }
}
```

- [ ] **Step 2: Final commit**

```bash
git add src/main/resources/static/js/app.js
git commit -m "feat: add auto-polling for short-term analyzer (30s interval)"
```

---

## Task 9: CORS Configuration (if needed)

**Files:**
- Modify: `src/main/java/com/ba/analyzer/config/` (only if CORS issues arise)

Since frontend is served from the same origin as the API, CORS should not be needed. Skip this task unless browser console shows CORS errors during testing.

---

## Task 10: Integration Test

- [ ] **Step 1: Start the application**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

- [ ] **Step 2: Verify in browser**

Open `http://localhost:8080` and verify:
1. Header shows "服务正常" with green dot
2. Sidebar shows all analyzers from the list API
3. Overview page shows cards (may show "暂无结果" if no analyses have run)
4. Click an analyzer → parameter form appears with correct fields and defaults
5. Click "执行分析" → results appear in table (or "无符合条件的币种")
6. Rate limit works — button disabled for 10 seconds after execution
7. "短期急涨" detail page shows auto-refresh toggle

- [ ] **Step 3: Final commit with all files**

```bash
git add src/main/resources/static/
git commit -m "feat: complete web dashboard for Binance futures analyzer"
```
