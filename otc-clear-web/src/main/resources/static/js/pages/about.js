import { get } from '../api.js';
import * as ui from '../ui.js';

/* About page: runtime context only; the audit trail lives in its own panel. */

async function loadContext() {
  try {
    const ctx = await get('/api/context');
    const profiles = (ctx.profiles || []).join(', ') || '—';
    const auditRow = ctx.auditEnabled
      ? '<div class="k">审计入口</div><div class="v">已启用</div>' : '';
    ui.setHtml('aboutInfo', `
      <div class="k">模式</div><div class="v">${ctx.mode === 'TENANT' ? '多租户（实例绑定单一租户）' : '单机'}</div>
      <div class="k">租户</div><div class="v">${ui.escapeHtml(ctx.tenantId)}</div>
      <div class="k">数据目录</div><div class="v">${ui.escapeHtml(ctx.dataRoot)}</div>
      <div class="k">版本</div><div class="v">${ui.escapeHtml(ctx.version)}</div>
      <div class="k">数据库</div><div class="v">${ui.escapeHtml(ctx.database || '—')}</div>
      <div class="k">配置档</div><div class="v">${ui.escapeHtml(profiles)}</div>
      ${auditRow}
      <div class="k">上传上限</div><div class="v">${ui.bytes(ctx.limits.maxFileSizeBytes)}</div>
      <div class="k">克隆上限</div><div class="v">${ctx.limits.maxCloneCount} 份 / 次</div>
      <div class="k">批量删除上限</div><div class="v">${ctx.limits.maxBulkDelete} 条 / 次</div>
      <div class="k">单 sheet 行上限</div><div class="v">${ctx.limits.maxRowsPerSheet}</div>`);
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await loadContext();
}

init();
