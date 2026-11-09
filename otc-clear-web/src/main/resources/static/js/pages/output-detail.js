import { del, get, post, query } from '../api.js';
import * as ui from '../ui.js';
import { openCaseDrawer } from './case-drawer.js';

const outputId = document.body.dataset.outputId;
const state = { output: null, page: 1, size: 50, q: '', hasFormula: false, caseIds: [] };

function debounce(fn, delay = 350) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

async function loadOutput() {
  try {
    const output = await get(`/api/outputs/${encodeURIComponent(outputId)}`);
    state.output = output;
    ui.byId('outputTitle').textContent = output.fileName;
    ui.byId('outputSubtitle').textContent = `${output.status} · ${ui.bytes(output.sizeBytes)} · ${output.caseCount} 条用例`;
    ui.setHtml('outputInfo', `
      <div class="k">产出 ID</div><div class="v">${ui.escapeHtml(output.id)}</div>
      <div class="k">来源任务</div><div class="v">${ui.escapeHtml(output.jobId)}</div>
      <div class="k">相对路径</div><div class="v">${ui.escapeHtml(output.relativePath)}</div>
      <div class="k">SHA-256</div><div class="v">${ui.escapeHtml(output.sha256)}</div>
      <div class="k">批次</div><div class="v">${output.importedBatchId
        ? `<a href="/batches/${encodeURIComponent(output.importedBatchId)}">${ui.escapeHtml(output.importedBatchId.slice(0, 8))}</a>`
        : '—'}</div>
      <div class="k">发现时间</div><div class="v">${ui.escapeHtml(output.discoveredAt)}</div>
      <div class="k">导入时间</div><div class="v">${ui.escapeHtml(output.importedAt || '—')}</div>
      <div class="k">说明</div><div class="v">${ui.escapeHtml(output.message || '—')}</div>`);
  } catch (error) {
    ui.showError(error);
  }
}

async function loadCases() {
  try {
    const result = await get(`/api/outputs/${encodeURIComponent(outputId)}/cases` + query({
      q: state.q, hasFormula: state.hasFormula ? true : null, page: state.page, size: state.size,
    }));
    state.caseIds = result.items.map((item) => item.id);
    const rows = result.items.map((item) => {
      const preview = item.preview.map((field) => `
        <div><span class="muted">${ui.escapeHtml(field.header)}</span>
        <span class="mono" style="margin-left:4px">${field.value === '' ? '—' : ui.escapeHtml(field.value)}</span></div>`).join('');
      return `
        <tr>
          <td class="num">${item.rowNo}</td>
          <td class="mono">${ui.escapeHtml(item.testCaseId || '—')}</td>
          <td class="mono">${ui.escapeHtml(item.keyValue || '—')}</td>
          <td class="mono" style="font-size:11px">${preview}</td>
          <td class="num">${item.formulaCount}</td>
          <td><button class="tool-btn small" data-open="${ui.escapeHtml(item.id)}">纵向查看</button></td>
        </tr>`;
    });
    ui.setHtml('outputCaseRows', rows.length ? rows.join('')
      : '<tr><td colspan="6" class="empty">没有解析到用例</td></tr>');
    ui.byId('outputCaseRows').querySelectorAll('button[data-open]').forEach((button) => {
      button.addEventListener('click', () => {
        const index = state.caseIds.indexOf(button.dataset.open);
        openCaseDrawer(state.caseIds, index < 0 ? 0 : index, { onChanged: loadCases });
      });
    });
    ui.renderPager('outputCasePager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      loadCases();
    });
  } catch (error) {
    ui.showError(error);
  }
}

function openClonePanel() {
  const ids = state.caseIds;
  if (!ids.length) {
    ui.toast('当前页没有可用例', 'error');
    return;
  }
  window.location.href = state.output.importedBatchId
    ? `/batches/${encodeURIComponent(state.output.importedBatchId)}` : '/outputs';
}

async function openExportPanel() {
  if (!state.output || !state.output.importedBatchId) {
    ui.toast('该产出没有关联批次，无法导出', 'error');
    return;
  }
  try {
    const precheck = await post('/api/exports/precheck', { batchId: state.output.importedBatchId, caseIds: [], options: null });
    if (!precheck.valid) {
      ui.toast('预检未通过：' + (precheck.issues[0]?.message || ''), 'error');
      return;
    }
    const result = await post('/api/exports', { batchId: state.output.importedBatchId, caseIds: [], options: null });
    window.location.href = `/api/exports/${encodeURIComponent(result.exportId)}/download`;
  } catch (error) {
    ui.showError(error);
  }
}

async function removeOutput() {
  if (!window.confirm('删除产出会同时删除其导入的批次与用例，确定继续？')) {
    return;
  }
  try {
    await del(`/api/outputs/${encodeURIComponent(outputId)}`);
    window.location.href = '/outputs';
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await loadOutput();
  await loadCases();
  ui.on('outputCaseSearch', 'input', debounce((event) => {
    state.q = event.target.value;
    state.page = 1;
    loadCases();
  }));
  ui.on('onlyFormula', 'change', (event) => {
    state.hasFormula = event.target.checked;
    state.page = 1;
    loadCases();
  });
  ui.on('refreshCasesBtn', 'click', loadCases);
  ui.on('backBtn', 'click', () => { window.location.href = '/outputs'; });
  ui.on('reCloneBtn', 'click', openClonePanel);
  ui.on('reExportBtn', 'click', openExportPanel);
  ui.on('deleteOutputBtn', 'click', removeOutput);
}

init();
