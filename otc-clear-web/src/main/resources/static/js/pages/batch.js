import { del, get, post, query } from '../api.js';
import * as ui from '../ui.js';
import { openCaseDrawer } from './case-drawer.js';
import { openValidationPanel } from '../validation-panel.js';

const batchId = document.body.dataset.batchId;
const state = {
  batch: null,
  page: 1,
  size: 50,
  q: '',
  hasFormula: false,
  selected: new Set(),
  caseIds: [],
  casesById: new Map(),
  duplicateBusiness: new Set(),
  duplicateCore: new Set(),
};

/** Shared debounce; the page keeps its own default delay. */
const debounce = (fn, delay = 350) => ui.debounce(fn, delay);

async function loadBatch() {
  try {
    const batch = await get(`/api/batches/${encodeURIComponent(batchId)}`);
    state.batch = batch;
    ui.byId('batchTitle').textContent = `${batch.productCode} · ${batch.sheetName || '批次 ' + batch.id.slice(0, 8)}`;
    ui.byId('batchSubtitle').textContent =
      `${batch.sourceFilename} · 创建于 ${batch.createdAt} · 模式 ${batch.kind}`;
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
    state.casesById = new Map(result.items.map((item) => [item.id, item]));
    state.duplicateBusiness = new Set((result.duplicates && result.duplicates.business) || []);
    state.duplicateCore = new Set((result.duplicates && result.duplicates.core) || []);
    renderDuplicateInfo();
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
          <td class="mono rid" title="${ui.escapeHtml(item.originalRecordId || '')}">${ui.escapeHtml(item.originalRecordId || '—')}</td>
          <td class="mono rid" title="${ui.escapeHtml(item.businessRecordId || '')}">${ui.escapeHtml(item.businessRecordId || '—')}${dupMark('business', item.businessRecordId)}</td>
          <td class="mono rid" title="${ui.escapeHtml(item.coreBusinessRecordId || '')}">${ui.escapeHtml(item.coreBusinessRecordId || '—')}${dupMark('core', item.coreBusinessRecordId)}</td>
          <td class="mono" style="font-size:11px">${preview}</td>
          <td class="num">${item.formulaCount}</td>
          <td><div class="row-actions">
            ${ui.iconButton('eye', '纵向查看该用例', 'sm', `data-open="${ui.escapeHtml(item.id)}"`)}
            ${ui.iconButton('diff', '与其它用例对比', 'sm', `data-case-compare="${ui.escapeHtml(item.id)}"`)}
            ${ui.iconButton('check', '校验该用例的字段值', 'sm', `data-case-validate="${ui.escapeHtml(item.id)}"`)}
            ${ui.iconButton('trash', '删除该用例', 'sm danger', `data-delete="${ui.escapeHtml(item.id)}"`)}
          </div></td>
        </tr>`;
    });
    ui.setHtml('caseRows', rows.length ? rows.join('')
      : '<tr><td colspan="10" class="empty">没有匹配的用例</td></tr>');
    bindRowActions();
    syncSelectAll();
    ui.renderPager('casePager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      loadCases();
    });
    updateSelectionInfo();
  } catch (error) {
    ui.showError(error);
  }
}

/** Marks a record id that repeats inside this batch; it only informs, nothing is blocked. */
function dupMark(kind, value) {
  const duplicates = kind === 'business' ? state.duplicateBusiness : state.duplicateCore;
  if (!value || !duplicates.has(value)) {
    return '';
  }
  const label = kind === 'business' ? 'Business Record' : 'Core Business Record';
  return ` <span class="dup-mark" title="该 ${label} 在本批次内重复出现">重复</span>`;
}

/** Toolbar reminder listing how many duplicated ids the batch holds. */
function renderDuplicateInfo() {
  const info = ui.byId('dupInfo');
  const parts = [];
  if (state.duplicateBusiness.size) {
    parts.push(`Business Record 重复 ${state.duplicateBusiness.size} 组`);
  }
  if (state.duplicateCore.size) {
    parts.push(`Core Business Record 重复 ${state.duplicateCore.size} 组`);
  }
  info.hidden = parts.length === 0;
  info.textContent = parts.length ? `⚠ ${parts.join(' · ')}` : '';
  info.title = parts.length
    ? '仅作提醒：重复的行已在下表标记，不影响任何业务流程'
    : '';
}

/** Header checkbox state of the visible page; the selection itself survives paging. */
function syncSelectAll() {
  const box = ui.byId('selectAll');
  if (!box) {
    return;
  }
  const ids = state.caseIds || [];
  const selected = ids.filter((id) => state.selected.has(id)).length;
  box.checked = ids.length > 0 && selected === ids.length;
  box.indeterminate = selected > 0 && selected < ids.length;
  box.title = selected === ids.length && ids.length
    ? '取消选择本页'
    : `选择本页（当前共选中 ${state.selected.size} 条）`;
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
      syncSelectAll();
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
  ui.byId('caseRows').querySelectorAll('button[data-case-compare]').forEach((button) => {
    button.addEventListener('click', () => openCaseCompare(button.dataset.caseCompare));
  });
  ui.byId('caseRows').querySelectorAll('button[data-case-validate]').forEach((button) => {
    button.addEventListener('click', () => {
      const item = state.casesById.get(button.dataset.caseValidate) || {};
      openValidationPanel(`/api/cases/${encodeURIComponent(button.dataset.caseValidate)}/validation`, {
        title: '字段值校验',
        subtitle: `用例 ${item.testCaseId || button.dataset.caseValidate} · 第 ${item.rowNo} 行`,
      });
    });
  });
}

function updateSelectionInfo() {
  ui.byId('selectionInfo').textContent = state.selected.size ? `已选 ${state.selected.size} 条` : '未选择';
  ui.byId('bulkDeleteBtn').disabled = state.selected.size === 0;
  ui.byId('cloneBtn').disabled = state.selected.size === 0;
  ui.byId('exportBtn').disabled = false;
}

async function deleteCase(id) {
  if (!window.confirm('删除该用例？删除后不可恢复。')) {
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
  if (!window.confirm(`删除选中的 ${state.selected.size} 条用例？删除后不可恢复。`)) {
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

const OVERRIDE_MODE_LABELS = { VALUE: '设置为值', FORMULA: '设置为公式', CLEAR: '清空' };

function openExportPanel() {
  const ids = [...state.selected];
  const scope = ids.length ? { caseIds: ids } : { batchId };
  const overrides = [];
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
    <div class="muted" style="font-size:12px">范围：${ids.length ? `选中 ${ids.length} 条用例` : '整个批次'} ·
      导出会保存为记录（可在「导出记录」随时下载或删除），不会自动下载文件</div>
    <div class="exp-override">
      <div class="exp-override-head">
        <span class="exp-override-title">导出前临时更新</span>
        <span class="chip muted" id="expOverrideCount">未添加</span>
        <span class="muted" style="font-size:11.5px">只影响本次导出的 CSV，不写入用例</span>
      </div>
      <div class="exp-override-list" id="expOverrideList"></div>
      <button class="tool-btn small" id="expAddOverride">+ 添加临时更新</button>
    </div>
    <div id="expPrecheckBox"></div>`,
  `<button class="tool-btn" id="expPrecheckBtn">预检</button>
   <button class="tool-btn primary" id="expRunBtn" disabled>导出并保存</button>`);

  const root = modal.root;
  const request = () => ({
    ...scope,
    options: readOptions(root),
    recalculateFormulas: root.querySelector('#expRecalc').checked,
    overrides: overrides.map(({ header, display, ...rest }) => rest),
  });

  const renderOverrides = () => {
    const count = root.querySelector('#expOverrideCount');
    count.textContent = overrides.length ? `临时更新 ${overrides.length} 项` : '未添加';
    count.className = overrides.length ? 'badge warn' : 'chip muted';
    const list = root.querySelector('#expOverrideList');
    list.innerHTML = overrides.length
      ? overrides.map((override, index) => `
        <div class="exp-override-item">
          <span class="chip info">${ui.escapeHtml(OVERRIDE_MODE_LABELS[override.mode] || override.mode)}</span>
          <strong>${ui.escapeHtml(override.header)}</strong>
          <span class="mono">${ui.escapeHtml(override.display || '—')}</span>
          <button class="tool-btn small danger" data-remove-override="${index}">移除</button>
        </div>`).join('')
      : '<div class="muted exp-override-empty">未添加：导出的值来自用例当前存库的数据。</div>';
    list.querySelectorAll('button[data-remove-override]').forEach((button) => {
      button.addEventListener('click', () => {
        overrides.splice(Number(button.dataset.removeOverride), 1);
        renderOverrides();
      });
    });
    // the preview below belongs to the previous override set, so it has to be re-run
    root.querySelector('#expRunBtn').disabled = true;
  };

  root.querySelector('#expAddOverride').addEventListener('click', () => {
    openBulkFieldPanel({
      mode: 'export',
      onApply: (override) => {
        const index = overrides.findIndex((entry) => entry.fieldKey === override.fieldKey);
        if (index >= 0) {
          overrides.splice(index, 1, override);
          ui.toast('已替换该字段的临时更新', 'ok');
        } else {
          overrides.push(override);
        }
        renderOverrides();
      },
    });
  });

  root.querySelector('#expPrecheckBtn').addEventListener('click', async () => {
    try {
      const precheck = await post('/api/exports/precheck', request());
      const issues = (precheck.issues || []).map((issue) => `
        <div class="issue ${ui.escapeHtml(issue.level)}"><span>${ui.escapeHtml(issue.message)}</span>
        <code>${ui.escapeHtml(issue.code)}${issue.row ? ` · 行 ${issue.row}` : ''}</code></div>`).join('');
      const header = precheck.headers.map((value) => `<th>${ui.escapeHtml(value)}</th>`).join('');
      const sample = precheck.sampleRow.map((value) => `<td class="mono">${ui.escapeHtml(value)}</td>`).join('');
      root.querySelector('#expPrecheckBox').innerHTML = `
        <div class="muted" style="font-size:11.5px;margin:8px 0 4px">样例行（已按临时更新渲染）</div>
        <div class="table-wrap" style="max-height:220px"><table><thead><tr>${header}</tr></thead>
        <tbody><tr>${sample}</tr></tbody></table></div>
        <div class="issues">${issues || '<div class="muted">预检通过</div>'}</div>`;
      root.querySelector('#expRunBtn').disabled = !precheck.valid;
    } catch (error) {
      ui.showError(error);
    }
  });

  root.querySelector('#expRunBtn').addEventListener('click', async () => {
    try {
      const result = await post('/api/exports', request());
      const warnings = result.warnings || [];
      const applied = (result.overrides || []).length;
      const summary = `已保存导出记录：${result.rowCount} 行`
        + `${applied ? `（含 ${applied} 项临时更新）` : ''} · ${result.fileName}`;
      if (warnings.length) {
        ui.toast(`${summary}，${warnings.length} 条重算提示`, 'warn');
      } else {
        ui.toast(summary, 'ok');
      }
      showExportSaved(modal, result);
    } catch (error) {
      ui.showError(error);
    }
  });

  renderOverrides();
}

/** Confirms the stored record and offers the two follow up actions. */
function showExportSaved(modal, result) {
  const box = modal.root.querySelector('#expPrecheckBox');
  if (!box) {
    modal.close();
    return;
  }
  const overrides = result.overrides || [];
  const overrideBlock = overrides.length ? `
      <div class="export-saved-overrides">
        <div class="export-saved-override-title">本次导出包含 ${overrides.length} 项临时更新（未写入用例）</div>
        ${overrides.map((override) => `<div class="exp-override-item">
          <span class="chip info">${ui.escapeHtml(OVERRIDE_MODE_LABELS[override.mode] || override.mode)}</span>
          <strong>${ui.escapeHtml(override.header)}</strong>
          <span class="mono">${ui.escapeHtml(override.display || '—')}</span></div>`).join('')}
      </div>` : '';
  box.innerHTML = `
    <div class="export-saved">
      <div class="export-saved-title">已保存到导出记录</div>
      <div class="export-saved-meta mono">${ui.escapeHtml(result.fileName)} · ${result.rowCount} 行</div>
      ${overrideBlock}
      <div class="row-actions" style="margin-top:8px">
        <a class="tool-btn small" href="/exports?focus=${encodeURIComponent(result.exportId)}">查看导出记录</a>
        <a class="tool-btn small primary" href="/api/exports/${encodeURIComponent(result.exportId)}/download">立即下载</a>
      </div>
    </div>`;
  modal.root.querySelector('#expRunBtn').disabled = true;
}

async function deleteBatch() {
  if (!window.confirm('删除批次会一并删除其用例，且无法恢复，确定继续？')) {
    return;
  }
  try {
    await del(`/api/batches/${encodeURIComponent(batchId)}`);
    window.location.href = '/batches';
  } catch (error) {
    ui.showError(error);
  }
}

// ------------------------------------------------------- case comparison

const CASE_STATUS_LABEL = {
  SAME: '一致',
  DIFFERENT: '不同',
  LEFT_ONLY: '仅左侧',
  RIGHT_ONLY: '仅右侧',
};

const CASE_LIMIT = 20;

const MATCH_HINT = {
  CASE: '已按用例 ID 定位到该用例',
  BATCH: '已按批次 ID 定位，以下为该批次的用例',
  TASK: '已按任务 ID 定位，以下为该任务下各批次的用例',
  NONE: '没有找到匹配的用例、批次或任务 ID',
};

/** Compares the current case with any other case of the tenant, found through global search. */
function openCaseCompare(leftCaseId) {
  const left = state.casesById.get(leftCaseId) || { testCaseId: leftCaseId, keyValue: '', rowNo: '' };
  const modal = ui.openModal('用例对比', `
    <div class="be-field">
      <label class="be-label">当前用例</label>
      <div class="cmp-side">
        <strong class="mono">${ui.escapeHtml(left.testCaseId || '—')}</strong>
        <span class="mono muted">Trade ${ui.escapeHtml(left.keyValue || '—')}</span>
        <span class="muted">行 ${ui.escapeHtml(left.rowNo)}</span>
      </div>
    </div>
    <div class="be-field">
      <label class="be-label">对比用例</label>
      <input id="ccSearch" class="picker-input" autocomplete="off" spellcheck="false"
        placeholder="搜索 Test Case Id / Trade ID / 字段值，或粘贴用例 / 批次 / 任务 ID">
      <div class="picker-selected" id="ccPicked" hidden></div>
      <div class="picker-list" id="ccList" role="listbox"></div>
      <div class="picker-hint" id="ccHint"></div>
    </div>`, `
    <button class="tool-btn" data-act="cancel">取消</button>
    <button class="tool-btn primary" data-act="start">开始对比</button>`);

  const list = modal.root.querySelector('#ccList');
  const search = modal.root.querySelector('#ccSearch');
  const pickedBox = modal.root.querySelector('#ccPicked');
  const hint = modal.root.querySelector('#ccHint');
  let pickedCase = null;
  let currentItems = [];
  let currentTotal = 0;
  let active = -1;
  let loadToken = 0;

  const caseLabel = (item) => `${item.testCaseId || item.id} · Trade ${item.keyValue || '—'}`;
  const origin = (item) => [item.taskName ? `任务 ${item.taskName}` : '', item.batchName ? `批次 ${item.batchName}` : '']
    .filter(Boolean).join(' · ');

  function renderList() {
    const visible = currentItems.filter((item) => item.id !== leftCaseId);
    if (!visible.length) {
      list.innerHTML = `<div class="picker-empty">${currentTotal ? '只有当前这一条用例匹配' : '没有匹配的用例'}</div>`;
      return;
    }
    if (active >= visible.length) {
      active = -1;
    }
    const hidden = currentTotal - visible.length;
    const options = visible.map((item, index) => `
      <div class="picker-option ${index === active ? 'active' : ''} ${pickedCase && pickedCase.id === item.id ? 'picked' : ''}"
        role="option" data-index="${index}" data-case="${ui.escapeHtml(item.id)}">
        <span class="picker-option-label">${ui.escapeHtml(caseLabel(item))}</span>
        <span class="picker-option-sub">${ui.escapeHtml(origin(item))}</span>
      </div>`).join('');
    list.innerHTML = `<div class="picker-group">匹配 ${currentTotal} 条${
      hidden > 0 ? `（列出前 ${visible.length} 条，输入关键字可缩小范围）` : ''}</div>${options}`;
  }

  async function load(text) {
    const seq = ++loadToken;
    list.innerHTML = '<div class="picker-empty">正在搜索…</div>';
    try {
      const page = await get('/api/cases/search'
        + query({ q: text, size: CASE_LIMIT, page: 1 }));
      if (seq !== loadToken) {
        return;
      }
      currentItems = page.items || [];
      currentTotal = page.total || currentItems.length;
      active = -1;
      hint.textContent = MATCH_HINT[page.match] || '';
      renderList();
    } catch (error) {
      if (seq !== loadToken) {
        return;
      }
      currentItems = [];
      currentTotal = 0;
      list.innerHTML = `<div class="picker-empty">${ui.escapeHtml(error.message)}</div>`;
    }
  }

  function pickCase(item) {
    pickedCase = item;
    pickedBox.innerHTML = item
      ? `<span class="picker-chip"><span class="picker-chip-label">${ui.escapeHtml(caseLabel(item))}</span>
          <span class="picker-chip-origin">${ui.escapeHtml(origin(item))}</span>
          <button type="button" class="picker-chip-clear" title="清除">✕</button></span>`
      : '';
    pickedBox.hidden = !item;
    renderList();
  }

  pickedBox.addEventListener('click', (event) => {
    if (event.target.closest('.picker-chip-clear')) {
      pickCase(null);
      search.focus();
    }
  });
  const debouncedLoad = debounce(() => load(search.value.trim()));
  // typing again invalidates the choice, the visible list becomes the source of truth
  search.addEventListener('input', () => {
    if (pickedCase) {
      pickCase(null);
    }
    const text = search.value.trim();
    if (!text) {
      currentItems = [];
      currentTotal = 0;
      hint.textContent = '';
      list.innerHTML = '<div class="picker-empty">输入关键字开始搜索</div>';
      return;
    }
    list.innerHTML = '<div class="picker-empty">正在搜索…</div>';
    debouncedLoad();
  });
  list.addEventListener('click', (event) => {
    const node = event.target.closest('[data-case]');
    if (!node) {
      return;
    }
    const item = currentItems.find((row) => row.id === node.dataset.case);
    if (item) {
      pickCase(item);
    }
  });
  search.addEventListener('keydown', (event) => {
    const visible = currentItems.filter((item) => item.id !== leftCaseId);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (!visible.length) {
        return;
      }
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      active = (active + step + visible.length) % visible.length;
      renderList();
      const node = list.querySelector(`[data-index="${active}"]`);
      if (node) {
        node.scrollIntoView({ block: 'nearest' });
      }
    } else if (event.key === 'Enter' && active >= 0 && visible[active]) {
      event.preventDefault();
      pickCase(visible[active]);
      active = -1;
    }
  });

  hint.textContent = '';
  list.innerHTML = '<div class="picker-empty">输入关键字开始搜索</div>';
  search.focus();

  modal.root.querySelector('[data-act="cancel"]').addEventListener('click', () => modal.close());
  modal.root.querySelector('[data-act="start"]').addEventListener('click', async () => {
    if (!pickedCase) {
      ui.toast('请先选择要对比的用例', 'bad');
      search.focus();
      return;
    }
    try {
      const result = await post(`/api/cases/${encodeURIComponent(leftCaseId)}/compare`,
        { rightCaseId: pickedCase.id });
      modal.close();
      renderCaseCompare(result);
    } catch (error) {
      ui.showError(error);
    }
  });
}

/** Standalone panel with the field by field difference of the two cases. */
function renderCaseCompare(result) {
  const host = ui.byId('drawerHost') || document.body;
  const backdrop = document.createElement('div');
  backdrop.className = 'drawer-backdrop';
  const rows = result.fields.map((field) => `
    <tr class="cmp-${field.status.toLowerCase()}">
      <td class="mono">${ui.escapeHtml(field.fieldKey)}</td>
      <td>${ui.escapeHtml(field.header)}${
        field.scope === 'MANAGED' ? ' <span class="chip muted">管理列</span>' : ''}</td>
      <td class="mono val">${field.leftValue === null ? '<span class="muted">（无）</span>' : ui.escapeHtml(field.leftValue)}</td>
      <td class="mono val">${field.rightValue === null ? '<span class="muted">（无）</span>' : ui.escapeHtml(field.rightValue)}</td>
      <td class="mono formula">${field.leftFormula ? ui.escapeHtml(field.leftFormula) : '—'}</td>
      <td class="mono formula">${field.rightFormula ? ui.escapeHtml(field.rightFormula) : '—'}</td>
      <td>${ui.escapeHtml(CASE_STATUS_LABEL[field.status] || field.status)}</td>
    </tr>`).join('');
  const summary = result.summary;
  const sideText = (side) => `${side.batchName || side.batchId} · ${side.testCaseId || side.caseId}`
    + (side.keyValue ? ` · Trade ${side.keyValue}` : '');
  backdrop.innerHTML = `
    <div class="drawer wide cmp" role="dialog" aria-modal="true">
      <div class="drawer-head">
        <div>
          <h3>用例对比</h3>
          <div class="mono-sm">${ui.escapeHtml(sideText(result.left))} ↔ ${ui.escapeHtml(sideText(result.right))}</div>
        </div>
        <div class="row-actions">
          <button class="modal-close" id="ccClose" aria-label="关闭">×</button>
        </div>
      </div>
      <div class="drawer-body">
        <div class="cmp-summary">
          <span class="chip ${summary.fieldsDifferent ? 'fail' : 'muted'}">字段差异 ${summary.fieldsDifferent}</span>
          <span class="chip muted">一致 ${summary.fieldsSame}</span>
          <span class="chip muted">共同字段 ${summary.fieldsCompared}</span>
          <span class="chip muted">仅左 ${summary.fieldsLeftOnly}</span>
          <span class="chip muted">仅右 ${summary.fieldsRightOnly}</span>
          ${summary.formulaDifferent ? `<span class="chip warn">公式不同 ${summary.formulaDifferent}</span>` : ''}
          <span class="chip muted">${ui.escapeHtml(result.left.productName || result.left.productCode)}
            ↔ ${ui.escapeHtml(result.right.productName || result.right.productCode)}</span>
        </div>
        <h4 class="cmp-section">字段对比
          <label class="setting"><input type="checkbox" id="ccOnlyDiff">仅差异</label></h4>
        <div class="table-wrap"><table class="cmp-table" id="ccFields"><thead><tr>
          <th>字段 Key</th><th>名称</th><th>${ui.escapeHtml(result.left.testCaseId || '左侧')}</th>
          <th>${ui.escapeHtml(result.right.testCaseId || '右侧')}</th>
          <th>左侧公式</th><th>右侧公式</th><th>状态</th>
        </tr></thead><tbody>${rows || '<tr><td colspan="7" class="empty">没有字段</td></tr>'}</tbody></table></div>
      </div>
    </div>`;
  host.appendChild(backdrop);
  const close = () => {
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
  };
  const onKey = (event) => {
    if (event.key === 'Escape') {
      close();
    }
  };
  const onlyDiff = backdrop.querySelector('#ccOnlyDiff');
  onlyDiff.addEventListener('change', () => {
    backdrop.querySelectorAll('#ccFields tbody tr').forEach((row) => {
      row.hidden = onlyDiff.checked && row.classList.contains('cmp-same');
    });
  });
  backdrop.querySelector('#ccClose').addEventListener('click', close);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) {
      close();
    }
  });
  document.addEventListener('keydown', onKey);
}

// ------------------------------------------------------- bulk field update

const BULK_READ_ONLY = new Set(['testCaseId', 'originalRecordId', 'businessRecordId', 'sourceRow']);

const TYPE_HINTS = {
  DATE: 'YYYY-MM-DD',
  TIME: 'HH:mm 或 HH:mm:ss',
  DATETIME: 'YYYY-MM-DDTHH:mm:ss',
  INTEGER: '整数，例如 1500000',
  DECIMAL: '小数，例如 0.0325',
  BOOLEAN: 'true / false',
  TEXT: '文本',
};

/** One clickable entry of the searchable field picker. */
function bulkFieldItem(field, activeKey) {
  const mark = field.required ? '<span class="bulk-req" title="必填">*</span>' : '';
  const active = field.key === activeKey ? ' active' : '';
  return `<button type="button" class="bulk-field-item${active}" role="option"
    data-key="${ui.escapeHtml(field.key)}" aria-selected="${field.key === activeKey}">`
    + `${mark}${ui.escapeHtml(field.header)}</button>`;
}

/** Renders the field picker, keeping the two groups and the search term. */
function renderBulkFieldList(root, fields, activeKey, term) {
  const needle = (term || '').trim().toLowerCase();
  const matches = fields.filter((field) => !needle || field.header.toLowerCase().includes(needle)
    || field.key.toLowerCase().includes(needle));
  const groups = [
    ['交易字段', matches.filter((field) => field.role !== 'MANAGED')],
    ['管理列', matches.filter((field) => field.role === 'MANAGED')],
  ];
  const html = groups
    .filter(([, items]) => items.length)
    .map(([title, items]) => `<div class="bulk-field-group">${title}</div>`
      + items.map((field) => bulkFieldItem(field, activeKey)).join(''))
    .join('');
  const list = root.querySelector('#bulkFieldList');
  list.innerHTML = html || '<div class="bulk-field-empty">没有匹配的字段</div>';
  list.querySelectorAll('button[data-key]').forEach((button) => {
    button.addEventListener('click', () => {
      root.querySelector('#bulkField').value = button.dataset.key;
      list.querySelectorAll('button[data-key]').forEach((other) => {
        const isActive = other.dataset.key === button.dataset.key;
        other.classList.toggle('active', isActive);
        other.setAttribute('aria-selected', String(isActive));
      });
      syncBulkHints(root);
    });
  });
}

/** Field currently chosen in the dialog. */
function bulkSelectedField(root, fields) {
  const key = root.querySelector('#bulkField').value;
  return fields.find((field) => field.key === key) || fields[0] || null;
}

/** Fills the set dropdown with the sets matching the current mode. */
function renderBulkSets(root, sets, mode) {
  const select = root.querySelector('#bulkSet');
  const wanted = mode === 'FORMULA' ? 'FORMULA' : 'VALUE';
  const previous = select.value;
  const options = ['<option value="">不指定（自由输入）</option>'];
  sets.filter((set) => (set.type || 'VALUE') === wanted).forEach((set) => {
    options.push(`<option value="${ui.escapeHtml(set.code)}">${ui.escapeHtml(set.name)}`
      + ` · ${set.itemCount}</option>`);
  });
  select.innerHTML = options.join('');
  select.value = [...select.options].some((option) => option.value === previous) ? previous : '';
}

/** Loads the items of the chosen set into the value dropdown. */
async function renderBulkSetItems(root, sets) {
  const setCode = root.querySelector('#bulkSet').value;
  const select = root.querySelector('#bulkValueSelect');
  if (!setCode) {
    select.hidden = true;
    select.innerHTML = '';
    root.querySelector('#bulkValue').hidden = false;
    root.querySelector('#bulkFormula').hidden = root.querySelector('#bulkMode').value !== 'FORMULA';
    root.querySelector('#bulkValueRowLabel').textContent = '值';
    return;
  }
  const set = sets.find((entry) => entry.code === setCode);
  let items = [];
  try {
    items = await get(`/api/meta/sets/${encodeURIComponent(setCode)}/items`);
  } catch (error) {
    ui.showError(error);
  }
  const usable = items.filter((item) => item.enabled);
  select.innerHTML = usable.length
    ? usable.map((item) => {
      const text = item.content || item.code;
      return `<option value="${ui.escapeHtml(item.code)}">${ui.escapeHtml(item.label)}`
        + ` · ${ui.escapeHtml(text)}</option>`;
    }).join('')
    : '<option value="">该集合没有可用枚举值</option>';
  select.hidden = false;
  root.querySelector('#bulkValue').hidden = true;
  root.querySelector('#bulkFormula').hidden = true;
  root.querySelector('#bulkValueRowLabel').textContent = set && set.type === 'FORMULA' ? '公式' : '值';
}

/** Shows the selected field name and its constraints under the form. */
function renderBulkFieldInfo(root, field) {
  const box = root.querySelector('#bulkFieldInfo');
  if (!field) {
    box.hidden = true;
    return;
  }
  const chips = [`<span class="chip muted">${ui.escapeHtml(field.type || 'TEXT')}</span>`];
  if (field.required) {
    chips.push('<span class="badge warn">必填</span>');
  }
  if (field.keyField) {
    chips.push('<span class="badge info">唯一键</span>');
  }
  chips.push(`<span class="chip muted">${field.role === 'MANAGED' ? '管理列' : '交易字段'}</span>`);
  box.hidden = false;
  box.innerHTML = '<div class="bulk-info-label">已选字段</div>'
    + `<div class="bulk-info-name">${field.required ? '<span class="bulk-req">*</span>' : ''}`
    + `${ui.escapeHtml(field.header)}</div>`
    + `<div class="bulk-info-chips">${chips.join('')}</div>`;
}

/** Keeps the dialog hints and input controls in sync with field, mode and set. */
function syncBulkHints(root) {
  const fields = root.bulkFields;
  const sets = root.bulkSets;
  const field = bulkSelectedField(root, fields);
  const modeSelect = root.querySelector('#bulkMode');
  const valueRow = root.querySelector('#bulkValueRow');
  const formulaRow = root.querySelector('#bulkFormulaRow');
  const setRow = root.querySelector('#bulkSetRow');
  const hint = root.querySelector('#bulkHint');
  const managed = field && field.role === 'MANAGED';

  // managed columns never take a formula: disable the option and fall back to a plain value
  const formulaOption = [...modeSelect.options].find((option) => option.value === 'FORMULA');
  if (formulaOption) {
    formulaOption.disabled = Boolean(managed);
  }
  if (managed && modeSelect.value === 'FORMULA') {
    modeSelect.value = 'VALUE';
  }
  const mode = modeSelect.value;

  setRow.hidden = mode === 'CLEAR';
  formulaRow.hidden = mode !== 'FORMULA';
  root.querySelector('#bulkValue').placeholder = TYPE_HINTS[field && field.type] || '值';
  root.querySelector('#bulkFormula').placeholder = '=D2*E2';
  root.querySelector('#bulkFormula').disabled = Boolean(managed);

  if (mode === 'CLEAR') {
    root.querySelector('#bulkSet').value = '';
    renderBulkSetItems(root, sets);
  } else {
    renderBulkSets(root, sets, mode);
    renderBulkSetItems(root, sets);
  }

  // a formula typed by hand has no use for the plain value box; only a set selection needs it
  const setCode = root.querySelector('#bulkSet').value;
  const formulaShown = !formulaRow.hidden;
  valueRow.hidden = mode === 'CLEAR' || (mode === 'FORMULA' && !setCode);
  root.querySelector('#bulkValueStack').hidden = valueRow.hidden && !formulaShown;

  const notes = [];
  if (mode === 'FORMULA') {
    notes.push('公式只在导出时重算，页面上的旧值会保留到下次导出。');
  }
  if (mode === 'CLEAR') {
    notes.push('清空会同时移除该字段的值与公式。');
  }
  if (managed) {
    notes.push('管理列不支持批量设置公式。');
  }
  if (field && field.keyField) {
    notes.push('唯一键字段只能更新单条用例；批量设为同一值会被拒绝。');
  }
  if (!root.querySelector('#bulkSet').hidden && root.querySelector('#bulkSet').value) {
    notes.push('新值取自所选集合的枚举值。');
  }
  hint.textContent = notes.join(' ');
  hint.hidden = !notes.length;
  renderBulkFieldInfo(root, field);
}

/** Reports the dry-run preview and the execution result. */
function renderBulkResult(root, result) {
  const box = root.querySelector('#bulkResult');
  const head = result.dryRun
    ? `将影响 ${result.matched} 条用例（尚未写入）`
    : `已更新 ${result.updated} / ${result.matched} 条用例`;
  const samples = result.samples.length ? `
    <div class="table-wrap" style="margin-top:8px">
      <table>
        <thead><tr><th class="num">行号</th><th>Test Case Id</th><th>原值</th><th>新值</th></tr></thead>
        <tbody>${result.samples.map((sample) => `
          <tr>
            <td class="num">${sample.rowNo}</td>
            <td class="mono">${ui.escapeHtml(sample.testCaseId || ui.shortId(sample.caseId, 8))}</td>
            <td class="mono">${sample.before ? ui.escapeHtml(sample.before) : '—'}</td>
            <td class="mono">${sample.after ? ui.escapeHtml(sample.after) : '—'}</td>
          </tr>`).join('')}</tbody>
      </table>
    </div>` : '';
  const issues = result.issues.length ? `
    <div class="muted" style="margin-top:6px;font-size:12px">未更新：${
      result.issues.map((issue) => `#${issue.rowNo} ${ui.escapeHtml(issue.message)}`).join('；')}</div>` : '';
  box.innerHTML = `<div class="bulk-result">
      <div class="bulk-result-title">${ui.escapeHtml(head)}</div>
      ${samples}${issues}
    </div>`;
}

/**
 * Loads the product fields and meta sets used by the field editor; cached per product.
 *
 * @returns {Promise<{fields: Array, sets: Array}>}
 */
const editorCache = { productCode: null, fields: [], sets: [] };

async function loadEditorData() {
  const productCode = state.batch && state.batch.productCode;
  if (!productCode) {
    throw new Error('批次信息尚未加载完成');
  }
  if (editorCache.productCode === productCode) {
    return editorCache;
  }
  const product = await get(`/api/products/${encodeURIComponent(productCode)}`);
  const sets = (await get('/api/meta/sets')).map((entry) => ({ ...entry.set, itemCount: entry.set.itemCount }));
  editorCache.productCode = productCode;
  editorCache.fields = product.fields.filter((field) => !BULK_READ_ONLY.has(field.key));
  editorCache.sets = sets;
  return editorCache;
}

/** Text of the value or formula currently entered in the editor, used for summaries. */
function editorDisplayText(root, mode) {
  if (mode === 'CLEAR') {
    return '（清空）';
  }
  const setCode = root.querySelector('#bulkSet').value;
  if (setCode) {
    const option = root.querySelector('#bulkValueSelect').selectedOptions[0];
    if (!option) {
      return '';
    }
    const parts = option.textContent.split(' · ');
    return parts.length > 1 ? parts.slice(1).join(' · ') : option.value;
  }
  if (mode === 'FORMULA') {
    const formula = root.querySelector('#bulkFormula').value.trim();
    return formula ? `ƒ ${formula}` : '';
  }
  return root.querySelector('#bulkValue').value;
}

/**
 * Opens the field editor.
 *
 * <p>Without options it updates the batch for real; with {@code { mode: 'export', onApply }} it only
 * collects one transient override for the pending export and never writes to the database.
 */
async function openBulkFieldPanel(options = {}) {
  const forExport = options.mode === 'export';
  if (!state.batch) {
    ui.toast('批次信息尚未加载完成', 'error');
    return;
  }
  let data;
  try {
    data = await loadEditorData();
  } catch (error) {
    ui.showError(error);
    return;
  }
  const fields = data.fields;
  const sets = data.sets;
  if (!fields.length) {
    ui.toast('该产品没有可更新的字段', 'error');
    return;
  }
  // start on a trade field: managed columns cannot hold a formula
  const initialField = fields.find((field) => field.role !== 'MANAGED') || fields[0];
  const scopeOptions = [
    `<option value="batch">整批用例（${state.batch.activeCases} 条）</option>`,
    `<option value="filter"${state.q || state.hasFormula ? '' : ' disabled'}>当前筛选结果</option>`,
    `<option value="selected"${state.selected.size ? '' : ' disabled'}>仅勾选的 ${state.selected.size} 条</option>`,
  ].join('');
  const scopeRow = forExport ? '' : `
          <label class="field" id="bulkScopeRow"><span>范围</span><select id="bulkScope">${scopeOptions}</select></label>`;

  const modal = ui.openModal(forExport ? '添加临时更新（仅本次导出）' : '批量更新字段', `
    <div class="bulk-layout">
      <div class="bulk-picker">
        <input type="search" id="bulkFieldSearch" placeholder="搜索字段名" autocomplete="off">
        <div class="bulk-field-list" id="bulkFieldList" role="listbox" aria-label="字段"></div>
        <input type="hidden" id="bulkField" value="${ui.escapeHtml(initialField.key)}">
      </div>
      <div class="bulk-config">
        <div class="bulk-config-row">
          <label class="field"><span>方式</span>
            <select id="bulkMode">
              <option value="VALUE">设置为值</option>
              <option value="FORMULA">设置为公式</option>
              <option value="CLEAR">清空</option>
            </select>
          </label>
          <label class="field" id="bulkSetRow"><span>集合类型</span>
            <select id="bulkSet"><option value="">不指定（自由输入）</option></select>
          </label>
        </div>
        <div class="bulk-config-split">
          <div class="bulk-stack" id="bulkValueStack">
            <label class="field" id="bulkValueRow"><span id="bulkValueRowLabel">值</span>
              <input type="text" id="bulkValue">
              <select id="bulkValueSelect" hidden></select>
            </label>
            <label class="field" id="bulkFormulaRow" hidden><span>公式</span><input id="bulkFormula"></label>
          </div>${scopeRow}
        </div>
        <div class="bulk-hint muted" id="bulkHint"></div>
        <div class="bulk-field-info" id="bulkFieldInfo"></div>
      </div>
    </div>
    <div id="bulkResult"></div>`, forExport ? `
    <button class="tool-btn" data-act="cancel">取消</button>
    <button class="tool-btn primary" data-act="apply">添加</button>` : `
    <button class="tool-btn" data-act="cancel">取消</button>
    <button class="tool-btn" data-act="preview">预演</button>
    <button class="tool-btn primary" data-act="apply">执行更新</button>`, { wide: true });

  const root = modal.root;
  root.bulkFields = fields;
  root.bulkSets = sets;

  const modeOf = () => root.querySelector('#bulkMode').value;
  const payload = (dryRun) => {
    const scopeRow = root.querySelector('#bulkScope');
    const scope = scopeRow ? scopeRow.value : 'batch';
    const mode = modeOf();
    const setCode = root.querySelector('#bulkSet').value;
    const body = {
      fieldKey: root.querySelector('#bulkField').value,
      mode,
      dryRun,
      // the server rejects an empty selection without a declared scope, so a mistyped payload can
      // never rewrite the whole batch
      scope: scope === 'selected' ? 'SELECTION' : scope === 'filter' ? 'FILTER' : 'BATCH',
    };
    if (setCode) {
      body.valueSet = setCode;
      body.valueItem = root.querySelector('#bulkValueSelect').value;
    } else if (mode === 'FORMULA') {
      body.formula = root.querySelector('#bulkFormula').value;
    } else if (mode === 'VALUE') {
      body.value = root.querySelector('#bulkValue').value;
    }
    if (scope === 'filter') {
      body.query = state.q;
      body.hasFormula = state.hasFormula ? true : null;
    }
    if (scope === 'selected') {
      body.caseIds = [...state.selected];
    }
    return body;
  };
  const run = async (dryRun, button) => {
    if (button) {
      button.disabled = true;
    }
    try {
      const result = await post(`/api/batches/${encodeURIComponent(batchId)}/cases/bulk-update`, payload(dryRun));
      renderBulkResult(root, result);
      if (!dryRun) {
        ui.toast(`批量更新完成：${result.updated} 条`, 'ok');
        await loadBatch();
        await loadCases();
      }
    } catch (error) {
      ui.showError(error);
    } finally {
      if (button) {
        button.disabled = false;
      }
    }
  };

  /** Collects the editor state into the override shape of {@code ExportRequest.overrides}. */
  const collectOverride = () => {
    const field = bulkSelectedField(root, fields);
    const mode = modeOf();
    const setCode = root.querySelector('#bulkSet').value;
    const override = {
      fieldKey: field.key,
      header: field.header,
      mode,
      display: editorDisplayText(root, mode),
    };
    if (setCode) {
      override.valueSet = setCode;
      override.valueItem = root.querySelector('#bulkValueSelect').value;
      if (!override.valueItem) {
        ui.toast('所选集合没有可用枚举值', 'error');
        return null;
      }
    } else if (mode === 'FORMULA') {
      override.formula = root.querySelector('#bulkFormula').value.trim();
      if (!override.formula) {
        ui.toast('请输入公式，例如 =D2*E2', 'error');
        return null;
      }
      if (!override.formula.startsWith('=')) {
        ui.toast('公式必须以 = 开头', 'error');
        return null;
      }
    } else if (mode === 'VALUE') {
      override.value = root.querySelector('#bulkValue').value;
      if (!override.value.trim()) {
        ui.toast('请输入新值', 'error');
        return null;
      }
    }
    return override;
  };

  renderBulkFieldList(root, fields, initialField.key, '');
  root.querySelector('#bulkFieldSearch').addEventListener('input', (event) => {
    renderBulkFieldList(root, fields, root.querySelector('#bulkField').value, event.target.value);
  });
  root.querySelector('#bulkMode').addEventListener('change', () => syncBulkHints(root));
  root.querySelector('#bulkSet').addEventListener('change', () => syncBulkHints(root));
  syncBulkHints(root);
  root.querySelector('[data-act="cancel"]').addEventListener('click', () => modal.close());
  const previewBtn = root.querySelector('[data-act="preview"]');
  if (previewBtn) {
    previewBtn.addEventListener('click', () => run(true, previewBtn));
  }
  const applyBtn = root.querySelector('[data-act="apply"]');
  applyBtn.addEventListener('click', () => {
    if (forExport) {
      const override = collectOverride();
      if (!override) {
        return;
      }
      modal.close();
      options.onApply(override);
      return;
    }
    // changing the whole batch (or a filter scope) is destructive: ask first
    const scopeText = root.querySelector('#bulkScope')
      ? root.querySelector('#bulkScope').selectedOptions[0].textContent : '';
    if (!window.confirm(`确认对「${scopeText.trim()}」执行批量更新？此操作会直接改写用例数据。`)) {
      return;
    }
    run(false, applyBtn);
  });
}


async function init() {
  await ui.initShell();
  await loadBatch();
  await loadCases();
  updateSelectionInfo();
  ui.enableColumnResize(ui.byId('caseTable'), 'batch-cases');

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
    // only the visible page is affected; a selection made on another page stays and its count is
    // shown next to the toolbar, so "select all" can never quietly accumulate invisible rows
    state.caseIds.forEach((id) => (checked ? state.selected.add(id) : state.selected.delete(id)));
    ui.byId('caseRows').querySelectorAll('input[data-select]').forEach((box) => {
      box.checked = checked;
      box.closest('tr').classList.toggle('selected', checked);
    });
    updateSelectionInfo();
    syncSelectAll();
  });
  ui.on('refreshCasesBtn', 'click', () => {
    loadBatch();
    loadCases();
  });
  ui.on('bulkDeleteBtn', 'click', bulkDelete);
  ui.on('bulkFieldBtn', 'click', openBulkFieldPanel);
  ui.on('cloneBtn', 'click', openClonePanel);
  ui.on('exportBtn', 'click', openExportPanel);
  ui.on('deleteBatchBtn', 'click', deleteBatch);
  ui.on('validateBatchBtn', 'click', () => {
    const batch = state.batch || {};
    openValidationPanel(`/api/batches/${encodeURIComponent(batchId)}/validation`, {
      title: '批次字段值校验',
      subtitle: `批次 ${batch.name || batchId} · 产品 ${batch.productCode || '—'}`,
    });
  });
  ui.on('backToListBtn', 'click', () => { window.location.href = '/batches'; });
  ui.on('backBtn', 'click', () => {
    window.location.href = state.batch && state.batch.taskId ? `/tasks/${state.batch.taskId}` : '/batches';
  });
}

init();
