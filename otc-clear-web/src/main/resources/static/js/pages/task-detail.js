import { del, get } from '../api.js';
import * as ui from '../ui.js';

const taskId = document.body.dataset.taskId;

async function load() {
  try {
    const detail = await get(`/api/tasks/${encodeURIComponent(taskId)}`);
    const task = detail.task;
    ui.byId('taskTitle').textContent = `任务 ${task.id.slice(0, 8)}`;
    ui.byId('taskSubtitle').textContent = `${task.sourceName} · ${task.kind === 'FILE_UPLOAD' ? '文件导入' : '外部产出'}`;

    const summary = [
      { label: '状态', value: ui.statusLabel(task.status) },
      { label: 'Sheet 数', value: task.sheetCount },
      { label: '批次', value: task.batchCount },
      { label: '有效行', value: task.totalRows },
      { label: '无效行', value: task.invalidRows },
      { label: '耗时(s)', value: task.durationSeconds || 0 },
    ];
    ui.setHtml('taskSummary', summary.map((item) => `
      <div class="card"><div class="card-label">${item.label}</div>
      <div class="card-value">${ui.escapeHtml(item.value)}</div></div>`).join(''));

    ui.setHtml('taskInfo', `
      <div class="k">任务 ID</div><div class="v">${ui.escapeHtml(task.id)}</div>
      <div class="k">状态</div><div class="v">${ui.statusBadge(task.status)}</div>
      <div class="k">源文件</div><div class="v">${ui.escapeHtml(task.sourceName)}</div>
      <div class="k">SHA-256</div><div class="v">${ui.escapeHtml(task.sourceSha256 || '—')}</div>
      <div class="k">创建时间</div><div class="v">${ui.escapeHtml(task.createdAt)}</div>
      <div class="k">完成时间</div><div class="v">${ui.escapeHtml(task.finishedAt || '—')}</div>
      <div class="k">说明</div><div class="v">${ui.escapeHtml(task.message || '—')}</div>`);

    const rows = detail.batches.map((batch) => `
      <tr>
        <td class="mono"><a href="/batches/${encodeURIComponent(batch.id)}">${batch.id.slice(0, 8)}</a></td>
        <td>${ui.escapeHtml(batch.productCode)}</td>
        <td>${ui.escapeHtml(batch.sheetName || '—')}</td>
        <td class="num">${batch.headerRow}</td>
        <td class="mono">${batch.tradeStartColumn} – ${batch.tradeEndColumn}</td>
        <td class="num">${batch.totalRows}</td>
        <td class="num">${batch.invalidRows}</td>
        <td class="mono">${ui.escapeHtml(batch.createdAt)}</td>
        <td><a class="tool-btn small" href="/batches/${encodeURIComponent(batch.id)}">打开批次</a></td>
      </tr>`);
    ui.setHtml('batchRows', rows.length ? rows.join('')
      : '<tr><td colspan="9" class="empty">没有成功导入的批次</td></tr>');

    const issues = detail.issues || [];
    ui.byId('issueCount').textContent = issues.length ? `${issues.length} 条` : '';
    ui.setHtml('taskIssues', issues.length ? issues.map((issue) => `
      <div class="issue ${ui.escapeHtml(issue.level)}">
        <span>${ui.escapeHtml(issue.message)}</span>
        <code>${ui.escapeHtml(issue.code)}${issue.row ? ` · 行 ${issue.row}` : ''}${issue.column ? ` · 列 ${issue.column}` : ''}</code>
      </div>`).join('') : '<div class="muted">没有记录到问题。</div>');
  } catch (error) {
    ui.showError(error);
  }
}

async function removeTask() {
  if (!window.confirm('删除任务会级联软删其批次与用例，确定继续？')) {
    return;
  }
  try {
    await del(`/api/tasks/${encodeURIComponent(taskId)}`);
    window.location.href = '/';
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await load();
  ui.enableColumnResize(document.querySelector('#batchRows').closest('table'), 'task-batches');
  ui.on('backBtn', 'click', () => { window.location.href = '/'; });
  ui.on('deleteTaskBtn', 'click', removeTask);
}

init();
