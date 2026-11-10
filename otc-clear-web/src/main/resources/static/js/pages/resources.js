import { get } from '../api.js';
import * as ui from '../ui.js';

/* Resource statistics: file areas, database rows and JVM state of the bound tenant. */

function percent(part, whole) {
  if (!whole || whole <= 0) {
    return 0;
  }
  return Math.min(100, Math.round((part / whole) * 1000) / 10);
}

function bar(part, whole, tone = '') {
  const value = percent(part, whole);
  return `<div class="res-bar ${tone}"><span style="width:${value}%"></span></div>
    <span class="res-bar-text">${value}%</span>`;
}

function duration(seconds) {
  const total = Math.max(0, Math.floor(seconds));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days > 0) {
    return `${days} 天 ${hours} 小时`;
  }
  if (hours > 0) {
    return `${hours} 小时 ${minutes} 分`;
  }
  return `${minutes} 分 ${total % 60} 秒`;
}

function card(title, value, hint, extra = '') {
  return `
    <div class="res-card">
      <div class="res-card-title">${ui.escapeHtml(title)}</div>
      <div class="res-card-value">${value}</div>
      ${extra}
      <div class="res-card-hint">${hint}</div>
    </div>`;
}

function cardBar(part, whole, tone = '') {
  return `<div class="res-cell-bar">${bar(part, whole, tone)}</div>`;
}

function renderCards(stats) {
  const diskUsed = Math.max(0, stats.diskTotalBytes - stats.diskFreeBytes);
  const heapPercent = percent(stats.runtime.heapUsedBytes, stats.runtime.heapMaxBytes);
  const cards = [
    card('数据目录占用', ui.bytes(stats.totalBytes), `${stats.totalFiles} 个文件 · ${ui.escapeHtml(stats.tenantId)}`),
  ];
  if (stats.metrics.disk) {
    cards.push(card('磁盘可用', ui.bytes(stats.diskFreeBytes),
      `已用 ${ui.bytes(diskUsed)} / 总量 ${ui.bytes(stats.diskTotalBytes)}`,
      cardBar(diskUsed, stats.diskTotalBytes, percent(diskUsed, stats.diskTotalBytes) > 90 ? 'danger' : 'warn')));
  }
  cards.push(
    card('数据库文件', ui.bytes(stats.database.fileBytes),
      `${stats.database.files} 个文件 · ${ui.escapeHtml(stats.database.display)}`),
    card('数据库总行数', stats.database.totalRows.toLocaleString('zh-CN'),
      `${stats.database.tables.length} 张业务表（当前租户）`));
  if (stats.metrics.heap) {
    cards.push(card('堆内存', `${ui.bytes(stats.runtime.heapUsedBytes)} / ${ui.bytes(stats.runtime.heapMaxBytes)}`,
      `线程 ${stats.runtime.threads} · 运行 ${duration(stats.runtime.uptimeSeconds)}`,
      cardBar(stats.runtime.heapUsedBytes, stats.runtime.heapMaxBytes, heapPercent > 85 ? 'danger' : '')));
  }
  ui.setHtml('resCards', cards.join(''));
  ui.setHtml('resGenerated', `生成于 ${ui.escapeHtml(stats.generatedAt)}`);
  ui.setHtml('resTenantPath', ui.escapeHtml(stats.tenantPath));
}

function renderAreas(stats) {
  const rows = stats.areas.map((area) => `
    <tr>
      <td>${ui.escapeHtml(area.name)}<div class="mono-sm">${ui.escapeHtml(area.path)}</div></td>
      <td class="num">${area.files}</td>
      <td class="num">${ui.bytes(area.bytes)}</td>
      <td class="res-cell-bar">${bar(area.bytes, stats.totalBytes)}</td>
      <td class="mono-sm">${area.newestText ? ui.escapeHtml(area.newestText) : '—'}</td>
      <td class="muted res-note">${ui.escapeHtml(area.note)}</td>
    </tr>`);
  ui.setHtml('resAreaRows', rows.length ? rows.join('')
    : '<tr><td colspan="6" class="empty">还没有任何文件</td></tr>');
}

function renderDatabase(stats) {
  const database = stats.database;
  ui.setHtml('resDatabaseInfo',
    `${ui.escapeHtml(database.display)} · ${ui.escapeHtml(database.url)} · ${ui.bytes(database.fileBytes)}`);
  const maxRows = Math.max(1, ...database.tables.map((table) => table.rows));
  const rows = database.tables.map((table) => `
    <tr>
      <td>${ui.escapeHtml(table.name)}<div class="mono-sm">${ui.escapeHtml(table.table)}</div></td>
      <td class="num">${table.rows.toLocaleString('zh-CN')}</td>
      <td class="res-cell-bar">${bar(table.rows, maxRows)}</td>
      <td class="muted res-note">${ui.escapeHtml(table.note || '')}</td>
    </tr>`);
  ui.setHtml('resTableRows', rows.length ? rows.join('')
    : '<tr><td colspan="4" class="empty">没有可统计的数据表</td></tr>');
}

function renderRuntime(stats) {
  const runtime = stats.runtime;
  const load = runtime.systemLoad < 0 ? '不可用' : runtime.systemLoad.toFixed(2);
  const rows = [];
  if (stats.metrics.heap) {
    rows.push(['堆内存', `${ui.bytes(runtime.heapUsedBytes)} / ${ui.bytes(runtime.heapMaxBytes)}`],
      ['非堆内存', ui.bytes(runtime.nonHeapUsedBytes)]);
  }
  rows.push(['线程数', `${runtime.threads}`], ['运行时长', duration(runtime.uptimeSeconds)],
    ['处理器', `${runtime.processors}`], ['系统负载', load]);
  ui.setHtml('resRuntime', rows.map(([key, value]) => `
    <div class="k">${ui.escapeHtml(key)}</div><div class="v">${value}</div>`).join(''));
}

async function load() {
  try {
    const stats = await get('/api/resources/stats');
    renderCards(stats);
    renderAreas(stats);
    renderDatabase(stats);
    renderRuntime(stats);
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await load();
  ui.enableColumnResize(ui.byId('resAreaTable'), 'resources-areas');
  ui.enableColumnResize(ui.byId('resTableTable'), 'resources-tables');
  ui.on('resReload', 'click', () => {
    load();
    ui.toast('已刷新资源统计', 'ok');
  });
}

init();
