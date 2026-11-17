/* 监控面板前端逻辑。
 *
 * 设计取舍：不用任何框架，直接用 fetch + 2 秒轮询。
 * 为什么不用 WebSocket？因为指标是"快照"而不是"事件流"，
 * 2 秒一次的全量快照实现简单而且断线自愈，丢了也就少一个点。
 * 轮询期间如果上一次请求还没回来，就跳过这一次，避免堆积。
 */

const POLL_MS = 2000;
const MAX_POINTS = 60;

let trendChart = null;
let pieChart = null;
let polling = false;
let lastConfig = null;

/* ---------------- 工具 ---------------- */

const $ = (id) => document.getElementById(id);

function fmtNum(n) {
  if (n === null || n === undefined || Number.isNaN(n)) return '--';
  if (Math.abs(n) >= 1e8) return (n / 1e8).toFixed(2) + ' 亿';
  if (Math.abs(n) >= 1e4) return (n / 1e4).toFixed(1) + ' 万';
  return n.toLocaleString('zh-CN');
}

function fmtMs(v) {
  if (v === null || v === undefined) return '--';
  return v < 1 ? (v * 1000).toFixed(0) + ' μs' : v.toFixed(2) + ' ms';
}

function fmtTime(ms) {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
}

async function api(path, options) {
  const res = await fetch(path, options);
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || res.statusText);
  }
  return res.json();
}

/* ---------------- 图表 ---------------- */

const CHART_FONT = { family: '"Segoe UI","Microsoft YaHei",sans-serif', size: 11 };
const GRID = 'rgba(139,148,158,.14)';
const LABEL = '#8b949e';

function initCharts() {
  Chart.defaults.color = LABEL;
  Chart.defaults.font.family = CHART_FONT.family;
  Chart.defaults.font.size = CHART_FONT.size;

  trendChart = new Chart($('chart-trend'), {
    type: 'line',
    data: {
      labels: [],
      datasets: [
        {
          label: '吞吐 (req/s)',
          data: [],
          borderColor: '#3fb950',
          backgroundColor: 'rgba(63,185,80,.10)',
          borderWidth: 2,
          fill: true,
          tension: .3,
          pointRadius: 0,
          yAxisID: 'y',
        },
        {
          label: 'P99 (ms)',
          data: [],
          borderColor: '#f85149',
          borderWidth: 1.5,
          borderDash: [5, 4],
          fill: false,
          tension: .3,
          pointRadius: 0,
          yAxisID: 'y1',
        },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        legend: { position: 'top', align: 'end', labels: { boxWidth: 10, boxHeight: 10, padding: 12 } },
        tooltip: { backgroundColor: '#161b22', borderColor: '#2a323d', borderWidth: 1 },
      },
      scales: {
        x: { grid: { color: GRID }, ticks: { maxTicksLimit: 8 } },
        y: {
          position: 'left',
          grid: { color: GRID },
          title: { display: true, text: 'req/s', color: LABEL },
        },
        y1: {
          position: 'right',
          grid: { drawOnChartArea: false },
          title: { display: true, text: 'ms', color: LABEL },
        },
      },
    },
  });

  pieChart = new Chart($('chart-pie'), {
    type: 'doughnut',
    data: {
      labels: ['成功', '售罄', '重复', '限流', '拒绝'],
      datasets: [{
        data: [0, 0, 0, 0, 0],
        backgroundColor: ['#3fb950', '#8b949e', '#58a6ff', '#d29922', '#f85149'],
        borderColor: '#1a2029',
        borderWidth: 2,
      }],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      cutout: '62%',
      plugins: {
        legend: { position: 'right', labels: { boxWidth: 10, boxHeight: 10, padding: 10 } },
        tooltip: {
          backgroundColor: '#161b22',
          borderColor: '#2a323d',
          borderWidth: 1,
          callbacks: {
            label: (ctx) => {
              const total = ctx.dataset.data.reduce((a, b) => a + b, 0) || 1;
              const pct = (ctx.parsed / total * 100).toFixed(1);
              return ` ${ctx.label}: ${fmtNum(ctx.parsed)} (${pct}%)`;
            },
          },
        },
      },
    },
  });
}

function pushTrend(m) {
  const label = fmtTime(Date.now());
  trendChart.data.labels.push(label);
  trendChart.data.datasets[0].data.push(Math.round(m.tps));
  trendChart.data.datasets[1].data.push(m.p99);
  if (trendChart.data.labels.length > MAX_POINTS) {
    trendChart.data.labels.shift();
    trendChart.data.datasets.forEach((d) => d.data.shift());
  }
  trendChart.update('none');
}

/* ---------------- 渲染 ---------------- */

function renderMetrics(m) {
  $('k-stock').textContent = fmtNum(m.stockRemaining);
  $('k-stock-sub').textContent = `${m.stockName} · 已售 ${fmtNum(m.stockDeducted)} / ${fmtNum(m.stockCapacity)}`;

  $('k-success').textContent = fmtNum(m.success);
  $('k-success-sub').textContent = `总请求 ${fmtNum(m.total)}`;

  $('k-tps').textContent = fmtNum(Math.round(m.tps));
  $('k-p99').textContent = fmtMs(m.p99);
  $('k-p99-sub').textContent = `P50 ${fmtMs(m.p50)} · P95 ${fmtMs(m.p95)} · Max ${fmtMs(m.max)}`;

  $('k-qdepth').textContent = fmtNum(m.queueDepth);
  $('k-qdepth-sub').textContent = `${m.queueName} · 峰值 ${fmtNum(m.queuePeak)} · 容量 ${fmtNum(m.queueCapacity)}`;

  const oversold = Math.max(0, m.stockDeducted - m.stockCapacity);
  const bad = $('k-bad');
  bad.textContent = `${oversold} / ${fmtNum(m.duplicate)}`;
  bad.style.color = oversold > 0 ? 'var(--danger)' : 'var(--text)';

  const uptime = Math.floor(m.uptimeMs / 1000);
  $('uptime').textContent = `运行 ${Math.floor(uptime / 60)}分${uptime % 60}秒`;

  // 请求分布饼图
  pieChart.data.datasets[0].data = [m.success, m.soldOut, m.duplicate, m.rateLimited, m.rejected];
  pieChart.update('none');

  // 负载进度
  const pct = m.loadTarget > 0 ? Math.min(100, m.loadSent / m.loadTarget * 100) : 0;
  $('load-bar').style.width = pct + '%';
  $('load-text').textContent = m.loading
    ? `${fmtNum(m.loadSent)} / ${fmtNum(m.loadTarget)} (${pct.toFixed(1)}%)`
    : (m.loadTarget > 0 ? `已完成 ${fmtNum(m.loadSent)} / ${fmtNum(m.loadTarget)}` : '未开始');

  $('btn-start').disabled = m.loading;
  $('btn-stop').disabled = !m.loading;
}

function renderDescribe(rows) {
  $('describe').innerHTML = rows
    .map(([k, v]) => `<span>${k}: <b>${v}</b></span>`)
    .join('');
}

async function renderOrders() {
  try {
    const data = await api('/api/orders?limit=40');
    const rec = data.reconcile;
    const box = $('reconcile');

    if (!rec) {
      box.innerHTML = `<div class="rec-item"><div class="k">对账</div><div class="v rec-bad">不可用</div></div>`;
      return;
    }
    const cls = rec.consistent ? 'rec-ok' : 'rec-bad';
    const mark = rec.consistent ? '✓' : '✗';
    box.innerHTML = `
      <div class="rec-item"><div class="k">已占用库存</div><div class="v">${fmtNum(rec.sold)}</div></div>
      <div class="rec-item"><div class="k">剩余库存</div><div class="v">${fmtNum(rec.remaining)}</div></div>
      <div class="rec-item"><div class="k">初始库存</div><div class="v">${fmtNum(rec.initial)}</div></div>
      <div class="rec-item"><div class="k">已关闭 / 死信</div><div class="v">${fmtNum(rec.closed)} / ${fmtNum(rec.dead)}</div></div>
      <div class="rec-item"><div class="k">一致性</div><div class="v ${cls}">${mark} ${rec.consistent ? '通过' : '异常'}</div></div>
    `;

    const body = $('orders-body');
    if (!data.rows || data.rows.length === 0) {
      body.innerHTML = `<tr><td colspan="4" class="empty">暂无订单</td></tr>`;
      return;
    }
    body.innerHTML = data.rows.map((o) => `
      <tr>
        <td>${o.orderId}</td>
        <td>${o.userId}</td>
        <td><span class="st st-${o.status}">${statusText(o.status)}</span></td>
        <td>${fmtTime(o.createdMillis)}</td>
      </tr>
    `).join('');
  } catch (e) {
    $('reconcile').innerHTML = `<div class="rec-item"><div class="k">对账</div><div class="v rec-bad">读取失败</div></div>`;
  }
}

function statusText(s) {
  return { CREATED: '待支付', PAID: '已支付', CLOSED: '已关闭', DEAD: '死信' }[s] || s;
}

function renderBadSub(m) {
  const oversold = Math.max(0, m.stockDeducted - m.stockCapacity);
  $('k-bad-sub').textContent = oversold === 0 && m.duplicate === 0
    ? '无超卖，无重复'
    : '检测到异常，见对账';
}

/* ---------------- 轮询 ---------------- */

async function poll() {
  if (polling) return;
  polling = true;
  try {
    const m = await api('/api/metrics');
    $('conn').textContent = '已连接';
    $('conn').className = 'pill pill-ok';
    renderMetrics(m);
    renderBadSub(m);
    pushTrend(m);
    $('foot-cfg').textContent = `${m.stockName} / ${m.queueName}`;
  } catch (e) {
    $('conn').textContent = '连接断开';
    $('conn').className = 'pill pill-bad';
  } finally {
    polling = false;
  }
}

async function loadConfig() {
  const c = await api('/api/config');
  lastConfig = c;
  const f = $('cfg-form');
  const set = (name, value) => {
    const el = f.elements[name];
    if (el) el.value = value;
  };
  set('stock', c.stock);
  set('queue', c.queue);
  set('queueCapacity', c.queueCapacity);
  set('workers', c.workers);
  set('dedup', c.dedup);
  set('limiter', c.limiter);
  set('limitQps', c.limitQps);
  set('inventory', c.inventory);
  set('store', c.store);
  renderDescribe(c.describe || []);
}

/* ---------------- 事件绑定 ---------------- */

function bindEvents() {
  $('cfg-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target;
    const body = {
      stock: f.elements.stock.value,
      queue: f.elements.queue.value,
      queueCapacity: Number(f.elements.queueCapacity.value),
      workers: Number(f.elements.workers.value),
      dedup: f.elements.dedup.value,
      limiter: f.elements.limiter.value,
      limitQps: Number(f.elements.limitQps.value),
      inventory: Number(f.elements.inventory.value),
      store: f.elements.store.value,
    };
    const btn = f.querySelector('button[type="submit"]');
    btn.disabled = true;
    btn.textContent = '应用中…';
    try {
      const c = await api('/api/config/apply', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      renderDescribe(c.describe || []);
      // 换配置后历史数据没意义了，清空图表
      trendChart.data.labels = [];
      trendChart.data.datasets.forEach((d) => { d.data = []; });
      trendChart.update('none');
      await poll();
    } catch (err) {
      alert('应用失败: ' + err.message);
    } finally {
      btn.disabled = false;
      btn.textContent = '应用配置并重置';
    }
  });

  $('btn-start').addEventListener('click', async () => {
    const f = $('load-form');
    const body = {
      concurrency: Number(f.elements.concurrency.value),
      total: Number(f.elements.total.value),
      sameUser: f.elements.sameUser.checked,
      reuseRequestId: f.elements.reuseRequestId.checked,
    };
    try {
      await api('/api/load/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
    } catch (err) {
      alert('启动失败: ' + err.message);
    }
  });

  $('btn-stop').addEventListener('click', async () => {
    try {
      await api('/api/load/stop', { method: 'POST' });
    } catch (err) {
      // 停止失败不影响后续轮询
    }
  });
}

/* ---------------- 启动 ---------------- */

document.addEventListener('DOMContentLoaded', async () => {
  initCharts();
  bindEvents();
  try {
    await loadConfig();
  } catch (e) {
    console.error('加载配置失败', e);
  }
  poll();
  setInterval(poll, POLL_MS);
  setInterval(renderOrders, 3000);
  renderOrders();
});
