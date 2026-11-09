import { del, get, post, query } from '../api.js';
import * as ui from '../ui.js';
import { openCaseDrawer } from './case-drawer.js';

const batchId = document.body.dataset.batchId;
const state = {
  batch: null,
  page: 1,
  size: 50,
  q: '',
  hasFormula: false,
  selected: new Set(),
  caseIds: [],
};

function debounce(fn, delay = 350) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function renderSummary(batch) {
  const cards = [
    { label: '产品', value: batch.productCode },
    { label: 'Sheet', value: batch.sheetName || '—' },
    { label: '用例数', value: batch.activeCases },
    { label: '无效行', value: batch.invalidRows, tone: batch.invalidRows ? 'bad' : '' },
    { label: '表头行', value: batch.headerRow },
    { label: '交易列', value: `${batch.tradeStartColumn} – ${batch.tradeEndColumn}` },
  ];
  ui.setHtml('batchSummary', cards.map((card) => `
    <div class="card"><div class="card-label">${card.label}</div>
    <div class="card-value ${card.tone || ''}">${ui.escapeHtml(card.value)}</div></div>`).join(''));
}

async function loadBatch() {
  try {
    const batch = await get(`/api/batches/${encodeURIComponent(batchId)}`);
    state.batch = batch;
    ui.byId('batchTitle').textContent = `${batch.productCode} · ${batch.sheetName || '批次 ' + batch.id.slice(0, 8)}`;
    ui.byId('batchSubtitle').textContent =
      `${batch.sourceFilename} · 创建于 ${batch.createdAt} · 模式 ${batch.kind}`;
    renderSummary(batch);
  } catch (error) {
    ui.showError(error);
  }
}

async function loadCases() {
  try {
    const result = await get(`/api/batches/${encodeURIComponent(batchId)}/cases` + query({
      q: state.q, hasFormula: state.hasFormula ? true : null, page: state.page, size: state.size,
    }));
    state.caseIds = result.items.map((item) => item.id);
    const rows = result.items.map((item) => {
      const preview = item.preview.map((field) => `
        <div><span class="muted">${ui.escapeHtml(field.header)}</span>
        <span class="mono" style="margin-left:4px">${field.value === '' ? '—' : ui.escapeHtml(field.value)}</span>
        ${field.formula ? '<span class="formula-flag" title="含公式">ƒ</span>' : ''}</div>`).join('');
      return `
        <tr data-id="${ui.escapeHtml(item.id)}" class="${state.selected.has(item.id) ? 'selected' : ''}">
          <td class="checkbox-cell"><input type="checkbox" data-select="${ui.escapeHtml(item.id)}"
            ${state.selected.has(item.id) ? 'checked' : ''}></td>
          <td class="num">${item.rowNo}</td>
          <td class="mono">${ui.escapeHtml(item.testCaseId || '—')}</td>
          <td class="mono">${ui.escapeHtml(item.keyValue || '—')}</td>
          <td class="mono">${ui.shortId(item.originalRecordId, 12)}</td>
          <td class="mono">${ui.shortId(item.businessRecordId, 12)}</td>
          <td class="mono" style="font-size:11px">${preview}</td>
          <td class="num">${item.formulaCount}</td>
          <td><div class="row-actions">
            <button class="tool-btn small" data-open="${ui.escapeHtml(item.id)}">纵向查看</button>
            <button class="tool-btn small" data-delete="${ui.escapeHtml(item.id)}">删除</button>
          </div></td>
        </tr>`;
    });
    ui.setHtml('caseRows', rows.length ? rows.join('')
      : '<tr><td colspan="9" class="empty">没有匹配的用例</td></tr>');
    bindRowActions();
    ui.renderPager('casePager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      loadCases();
    });
    updateSelectionInfo();
  } catch (error) {
    ui.showError(error);
  }
}

function bindRowActions() {
  ui.byId('caseRows').querySelectorAll('input[data-select]').forEach((checkbox) => {
    checkbox.addEventListener('change', () => {
      const id = checkbox.dataset.select;
      if (checkbox.checked) {
        state.selected.add(id);
      } else {
        state.selected.delete(id);
      }
      checkbox.closest('tr').classList.toggle('selected', checkbox.checked);
      updateSelectionInfo();
    });
  });
  ui.byId('caseRows').querySelectorAll('button[data-open]').forEach((button) => {
    button.addEventListener('click', () => {
      const index = state.caseIds.indexOf(button.dataset.open);
      openCaseDrawer(state.caseIds, index < 0 ? 0 : index, { onChanged: () => loadCases() });
    });
  });
  ui.byId('caseRows').querySelectorAll('button[data-delete]').forEach((button) => {
    button.addEventListener('click', () => deleteCase(button.dataset.delete));
  });
}

function updateSelectionInfo() {
  ui.byId('selectionInfo').textContent = state.selected.size ? `已选 ${state.selected.size} 条` : '未选择';
  ui.byId('bulkDeleteBtn').disabled = state.selected.size === 0;
  ui.byId('cloneBtn').disabled = state.selected.size === 0;
  ui.byId('exportBtn').disabled = false;
  ui.byId('dispatchBtn').disabled = state.selected.size === 0;
}

async function deleteCase(id) {
  if (!window.confirm('删除该用例？')) {
    return;
  }
  try {
    await del(`/api/cases/${encodeURIComponent(id)}`);
    state.selected.delete(id);
    ui.toast('已删除', 'ok');
    await loadBatch();
    await loadCases();
  } catch (error) {
    ui.showError(error);
  }
}

async function bulkDelete() {
  if (!state.selected.size) {
    return;
  }
  if (!window.confirm(`删除选中的 ${state.selected.size} 条用例？`)) {
    return;
  }
  try {
    await post('/api/cases/bulk-delete', { ids: [...state.selected] });
    state.selected.clear();
    ui.toast('已批量删除', 'ok');
    await loadBatch();
    await loadCases();
  } catch (error) {
    ui.showError(error);
  }
}

function openClonePanel() {
  const ids = [...state.selected];
  const modal = ui.openModal('克隆用例', `
    <div class="params">
      <div class="field"><label>份数（每条源用例）</label><input type="number" id="cloneCopies" value="1" min="1" max="200"></div>
      <div class="field"><label>命名模板</label><input id="cloneTemplate" value="{base}-{seq:02}"></div>
      <div class="field"><label>新批次备注</label><input id="cloneNote" placeholder="可选"></div>
    </div>
    <p class="muted" style="font-size:12px">可用 token：<code>{base}</code> <code>{seq:02}</code> <code>{uuid}</code> <code>{date:yyyyMMdd}</code> <code>{batch}</code></p>
    <div id="clonePreviewBox" class="issues"></div>`,
  `<button class="tool-btn" id="clonePreviewBtn">预览</button>
   <button class="tool-btn primary" id="cloneRunBtn" disabled>执行克隆</button>`);

  const readRequest = () => ({
    caseIds: ids,
    copies: Number(modal.root.querySelector('#cloneCopies').value || 1),
    template: modal.root.querySelector('#cloneTemplate').value,
    note: modal.root.querySelector('#cloneNote').value,
  });

  modal.root.querySelector('#clonePreviewBtn').addEventListener('click', async () => {
    try {
      const preview = await post('/api/clones/preview', readRequest());
      const rows = preview.sample.map((row) => `
        <div class="issue INFO"><span>${ui.escapeHtml(row.sourceTestCaseId)} → <strong>${ui.escapeHtml(row.newTestCaseId)}</strong>
        · key ${ui.escapeHtml(row.newKeyValue || '')}</span><code>${ui.escapeHtml(row.newOriginalRecordId)}</code></div>`).join('');
      const conflicts = preview.conflicts.map((conflict) => `
        <div class="issue ERROR"><span>${ui.escapeHtml(conflict)}</span></div>`).join('');
      modal.root.querySelector('#clonePreviewBox').innerHTML = `
        <div class="muted" style="font-size:12px">计划生成 ${preview.totalRows} 行（${preview.sourceCount} 条 × ${preview.copies} 份）</div>
        ${rows}${conflicts}`;
      modal.root.querySelector('#cloneRunBtn').disabled = preview.conflicts.length > 0;
    } catch (error) {
      ui.showError(error);
    }
  });

  modal.root.querySelector('#cloneRunBtn').addEventListener('click', async () => {
    try {
      const result = await post('/api/clones', readRequest());
      ui.toast(`已生成 ${result.created} 条`, 'ok');
      modal.close();
      window.location.href = `/batches/${encodeURIComponent(result.batchId)}`;
    } catch (error) {
      ui.showError(error);
    }
  });
}

function readOptions(root) {
  let delimiter = root.querySelector('#expDelimiter').value;
  if (delimiter === '\\t' || delimiter === 'TAB') {
    delimiter = '\t';
  }
  return {
    delimiter,
    quoteAll: root.querySelector('#expQuote').checked,
    includeHeader: true,
    lineEnding: root.querySelector('#expLine').value === 'LF' ? '\n' : '\r\n',
    bom: root.querySelector('#expBom').checked,
    managedColumns: root.querySelector('#expManaged').value,
    formulaAsValue: true,
  };
}

function openExportPanel() {
  const ids = [...state.selected];
  const scope = ids.length ? { caseIds: ids } : { batchId };
  const modal = ui.openModal('导出 CSV', `
    <div class="params">
      <div class="field"><label>分隔符</label><select id="expDelimiter">
        <option value=",">逗号 ,</option><option value=";">分号 ;</option>
        <option value="\t">制表符 TAB</option><option value="|">竖线 |</option></select></div>
      <div class="field"><label>管理列</label><select id="expManaged">
        <option value="KNOWN">交易字段 + _TestCaseId</option>
        <option value="ALL">全部（按源文件列序）</option>
        <option value="NONE">仅交易字段</option></select></div>
      <div class="field"><label>行结束符</label><select id="expLine">
        <option value="CRLF">CRLF</option><option value="LF">LF</option></select></div>
      <div class="field" style="display:flex;align-items:center;gap:14px;padding-top:18px">
        <label class="muted" style="display:flex;gap:6px;align-items:center;font-size:12px"><input type="checkbox" id="expQuote"> 全量加引号</label>
        <label class="muted" style="display:flex;gap:6px;align-items:center;font-size:12px"><input type="checkbox" id="expBom"> UTF-8 BOM</label>
        <label class="muted" style="display:flex;gap:6px;align-items:center;font-size:12px" title="用导入时保存的工作簿基线重算公式，导出重算后的值"><input type="checkbox" id="expRecalc" checked> 导出前重算公式</label>
      </div>
    </div>
    <div class="muted" style="font-size:12px;margin-bottom:6px">范围：${ids.length ? `选中 ${ids.length} 条用例` : '整个批次'}</div>
    <div id="expPrecheckBox"></div>`,
  `<button class="tool-btn" id="expPrecheckBtn">预检</button>
   <button class="tool-btn primary" id="expRunBtn" disabled>导出并下载</button>`);

  const request = () => ({
    ...scope,
    options: readOptions(modal.root),
    recalculateFormulas: modal.root.querySelector('#expRecalc').checked,
  });

  modal.root.querySelector('#expPrecheckBtn').addEventListener('click', async () => {
    try {
      const precheck = await post('/api/exports/precheck', request());
      const issues = (precheck.issues || []).map((issue) => `
        <div class="issue ${ui.escapeHtml(issue.level)}"><span>${ui.escapeHtml(issue.message)}</span>
        <code>${ui.escapeHtml(issue.code)}${issue.row ? ` · 行 ${issue.row}` : ''}</code></div>`).join('');
      const header = precheck.headers.map((value) => `<th>${ui.escapeHtml(value)}</th>`).join('');
      const sample = precheck.sampleRow.map((value) => `<td class="mono">${ui.escapeHtml(value)}</td>`).join('');
      modal.root.querySelector('#expPrecheckBox').innerHTML = `
        <div class="table-wrap" style="max-height:220px"><table><thead><tr>${header}</tr></thead>
        <tbody><tr>${sample}</tr></tbody></table></div>
        <div class="issues">${issues || '<div class="muted">预检通过</div>'}</div>`;
      modal.root.querySelector('#expRunBtn').disabled = !precheck.valid;
    } catch (error) {
      ui.showError(error);
    }
  });

  modal.root.querySelector('#expRunBtn').addEventListener('click', async () => {
    try {
      const result = await post('/api/exports', request());
      const warnings = result.warnings || [];
      if (warnings.length) {
        ui.toast(`已导出 ${result.rowCount} 行，${warnings.length} 条重算提示`, 'warn');
      } else {
        ui.toast(`已导出 ${result.rowCount} 行`, 'ok');
      }
      window.location.href = `/api/exports/${encodeURIComponent(result.exportId)}/download`;
      modal.close();
    } catch (error) {
      ui.showError(error);
    }
  });
}

function openDispatchPanel() {
  const ids = [...state.selected];
  const modal = ui.openModal('用批量导入工具导入', `
    <p class="muted" style="font-size:12px">将选中用例导出为 input CSV，分配任务文件夹并调用外部批量导入 API。</p>
    <div id="dispatchBox" class="issues"></div>`,
  `<button class="tool-btn" id="dispatchPrecheckBtn">预检</button>
   <button class="tool-btn primary" id="dispatchRunBtn" disabled>派发</button>`);

  modal.root.querySelector('#dispatchPrecheckBtn').addEventListener('click', async () => {
    try {
      const precheck = await post('/api/external-jobs/precheck', { caseIds: ids, options: null });
      modal.root.querySelector('#dispatchBox').innerHTML = (precheck.issues || []).map((issue) => `
        <div class="issue ${ui.escapeHtml(issue.level)}"><span>${ui.escapeHtml(issue.message)}</span>
        <code>${ui.escapeHtml(issue.code)}</code></div>`).join('')
        || `<div class="issue INFO"><span>预检通过，共 ${precheck.rowCount} 行</span></div>`;
      modal.root.querySelector('#dispatchRunBtn').disabled = !precheck.valid || !precheck.serviceEnabled;
    } catch (error) {
      ui.showError(error);
    }
  });

  modal.root.querySelector('#dispatchRunBtn').addEventListener('click', async () => {
    try {
      const job = await post('/api/external-jobs', { caseIds: ids, options: null });
      modal.close();
      window.location.href = `/external-jobs/${encodeURIComponent(job.id)}`;
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function deleteBatch() {
  if (!window.confirm('删除批次会级联软删其用例，确定继续？')) {
    return;
  }
  try {
    await del(`/api/batches/${encodeURIComponent(batchId)}`);
    window.location.href = state.batch && state.batch.taskId ? `/tasks/${state.batch.taskId}` : '/';
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await loadBatch();
  await loadCases();
  updateSelectionInfo();

  ui.on('caseSearch', 'input', debounce((event) => {
    state.q = event.target.value;
    state.page = 1;
    loadCases();
  }));
  ui.on('onlyFormula', 'change', (event) => {
    state.hasFormula = event.target.checked;
    state.page = 1;
    loadCases();
  });
  ui.on('pageSize', 'change', (event) => {
    state.size = Number(event.target.value);
    state.page = 1;
    loadCases();
  });
  ui.on('selectAll', 'change', (event) => {
    const checked = event.target.checked;
    state.caseIds.forEach((id) => (checked ? state.selected.add(id) : state.selected.delete(id)));
    loadCases();
  });
  ui.on('refreshCasesBtn', 'click', () => {
    loadBatch();
    loadCases();
  });
  ui.on('bulkDeleteBtn', 'click', bulkDelete);
  ui.on('cloneBtn', 'click', openClonePanel);
  ui.on('exportBtn', 'click', openExportPanel);
  ui.on('dispatchBtn', 'click', openDispatchPanel);
  ui.on('deleteBatchBtn', 'click', deleteBatch);
  ui.on('backBtn', 'click', () => {
    window.location.href = state.batch && state.batch.taskId ? `/tasks/${state.batch.taskId}` : '/';
  });
}

init();
