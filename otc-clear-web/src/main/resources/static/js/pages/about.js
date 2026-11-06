import { get, query } from '../api.js';
import * as ui from '../ui.js';

const state = { page: 1, size: 25 };

async function loadContext() {
  try {
    const ctx = await get('/api/context');
    ui.setHtml('aboutInfo', `
      <div class="k">模式</div><div class="v">${ctx.mode === 'TENANT' ? '多租户（实例绑定单一租户）' : '单机'}</div>
      <div class="k">租户</div><div class="v">${ui.escapeHtml(ctx.tenantId)}</div>
      <div class="k">数据目录</div><div class="v">${ui.escapeHtml(ctx.dataRoot)}</div>
      <div class="k">版本</div><div class="v">${ui.escapeHtml(ctx.version)}</div>
      <div class="k">外部工具</div><div class="v">${ctx.externalEnabled ? '已启用' : '未启用'}</div>
      <div class="k">上传上限</div><div class="v">${ui.bytes(ctx.limits.maxFileSizeBytes)}</div>
      <div class="k">克隆上限</div><div class="v">${ctx.limits.maxCloneCount} 份 / 次</div>
      <div class="k">批量删除上限</div><div class="v">${ctx.limits.maxBulkDelete} 条</div>
      <div class="k">单 sheet 行上限</div><div class="v">${ctx.limits.maxRowsPerSheet}</div>`);
  } catch (error) {
    ui.showError(error);
  }
}

async function loadAudit() {
  try {
    const result = await get('/api/audit' + query({ page: state.page, size: state.size }));
    const rows = result.items.map((row) => `
      <tr>
        <td class="mono">${ui.escapeHtml(row.createdAt)}</td>
        <td>${ui.escapeHtml(row.entityType)}</td>
        <td>${ui.escapeHtml(row.action)}</td>
        <td class="mono">${ui.escapeHtml(row.entityId ? row.entityId.slice(0, 8) : '—')}</td>
        <td class="mono" style="font-size:11px;max-width:420px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${ui.escapeHtml(row.detail || '')}</td>
      </tr>`);
    ui.setHtml('auditRows', rows.length ? rows.join('')
      : '<tr><td colspan="5" class="empty">还没有审计记录</td></tr>');
    ui.renderPager('auditPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      loadAudit();
    });
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await loadContext();
  await loadAudit();
}

init();
