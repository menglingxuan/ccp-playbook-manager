import { get, post } from '../api.js';
import * as ui from '../ui.js';

const jobId = document.body.dataset.jobId;

async function load() {
  try {
    const job = await get(`/api/external-jobs/${encodeURIComponent(jobId)}`);
    ui.byId('jobTitle').textContent = `外部任务 ${job.id.slice(0, 8)}`;
    ui.byId('jobSubtitle').textContent = `${job.productCode} · ${job.caseCount} 条用例 · ${ui.statusLabel(job.status)}`;

    ui.setHtml('jobInfo', `
      <div class="k">任务 ID</div><div class="v">${ui.escapeHtml(job.id)}</div>
      <div class="k">状态</div><div class="v">${ui.statusBadge(job.status)}</div>
      <div class="k">请求 ID</div><div class="v">${ui.escapeHtml(job.requestId)}</div>
      <div class="k">外部任务 ID</div><div class="v">${ui.escapeHtml(job.externalJobId || '—')}</div>
      <div class="k">任务文件夹</div><div class="v">${ui.escapeHtml(job.folderPath)}</div>
      <div class="k">input CSV</div><div class="v">${ui.escapeHtml(job.inputCsvPath)}</div>
      <div class="k">创建时间</div><div class="v">${ui.escapeHtml(job.createdAt)}</div>
      <div class="k">派发时间</div><div class="v">${ui.escapeHtml(job.dispatchedAt || '—')}</div>
      <div class="k">完成时间</div><div class="v">${ui.escapeHtml(job.finishedAt || '—')}</div>
      <div class="k">最近轮询</div><div class="v">${ui.escapeHtml(job.lastPolledAt || '—')}</div>
      <div class="k">说明</div><div class="v">${ui.escapeHtml(job.message || '—')}</div>`);

    ui.byId('jobPayload').textContent = pretty(job.dispatchPayload);
    ui.byId('jobResponse').textContent = pretty(job.lastResponse);

    const outputs = job.outputs || [];
    ui.byId('outputCount').textContent = `${outputs.length} 个文件`;
    const rows = outputs.map((output) => `
      <tr>
        <td class="mono">${ui.escapeHtml(output.fileName)}<div class="mono-sm">${ui.escapeHtml(output.relativePath)}</div></td>
        <td>${ui.statusBadge(output.status)}</td>
        <td class="num">${ui.bytes(output.sizeBytes)}</td>
        <td class="num">${output.caseCount}</td>
        <td class="mono">${ui.escapeHtml(output.discoveredAt)}</td>
        <td class="muted" style="font-size:12px">${ui.escapeHtml(output.message || '')}</td>
        <td><div class="row-actions">
          <a class="tool-btn small" href="/outputs/${encodeURIComponent(output.id)}">产出详情</a>
          <a class="tool-btn small" href="/api/external-jobs/${encodeURIComponent(jobId)}/files/${encodeURIComponent(output.id)}/download">下载</a>
        </div></td>
      </tr>`);
    ui.setHtml('jobOutputRows', rows.length ? rows.join('')
      : '<tr><td colspan="7" class="empty">还没有产出文件，点击「扫描产出」</td></tr>');
  } catch (error) {
    ui.showError(error);
  }
}

function pretty(text) {
  if (!text) {
    return '—';
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch (ignored) {
    return text;
  }
}

async function action(kind) {
  try {
    const job = await post(`/api/external-jobs/${encodeURIComponent(jobId)}/${kind}`, {});
    ui.toast(`状态：${ui.statusLabel(job.status)}`, 'ok');
    load();
  } catch (error) {
    ui.showError(error);
  }
}

async function scan() {
  try {
    const result = await post(`/api/external-jobs/${encodeURIComponent(jobId)}/scan`, {});
    ui.toast(`发现 ${result.discovered} · 导入 ${result.imported} · 跳过 ${result.skipped} · 失败 ${result.failed}`, 'ok');
    if (result.messages && result.messages.length) {
      ui.openModal('扫描结果', `<div class="issues">${result.messages.map((message) => `
        <div class="issue WARN"><span>${ui.escapeHtml(message)}</span></div>`).join('')}</div>`);
    }
    load();
  } catch (error) {
    ui.showError(error);
  }
}

async function stop() {
  if (!window.confirm('停止该外部任务？停止后仍可扫描已生成的产出。')) {
    return;
  }
  try {
    await post(`/api/external-jobs/${encodeURIComponent(jobId)}/stop`, {});
    load();
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await load();
  ui.on('backBtn', 'click', () => { window.location.href = '/external-jobs'; });
  ui.on('refreshStatBtn', 'click', () => action('refresh'));
  ui.on('scanBtn', 'click', scan);
  ui.on('stopBtn', 'click', stop);
}

init();
