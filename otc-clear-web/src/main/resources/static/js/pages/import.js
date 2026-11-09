import { get, post, upload as uploadFile } from '../api.js';
import * as ui from '../ui.js';

const PAGE_SIZE = 20;
const MAX_SAMPLE_ROWS = 500;

const state = {
  ctx: null,
  upload: null,
  sheetIndex: 0,
  productCode: '',
  params: {},
  preview: null,
  raw: null,
  sampleLimit: PAGE_SIZE,
  loadingMore: false,
  timer: null,
};

function currentInputs() {
  return {
    headerRow: Number(ui.byId('headerRow').value || 0),
    tradeStartColumn: Number(ui.byId('tradeStartColumn').value || 0),
    dataStartRow: Number(ui.byId('dataStartRow').value || 0),
    dataEndRow: Number(ui.byId('dataEndRow').value || 0),
  };
}

function rememberParams() {
  if (state.upload) {
    state.params[state.sheetIndex] = currentInputs();
  }
}

function setStep(active) {
  [1, 2, 3].forEach((index) => {
    const node = ui.byId(`step${index}`);
    node.classList.toggle('active', index === active);
    node.classList.toggle('done', index < active);
  });
}

function renderSheets() {
  const sheets = state.upload.sheets || [];
  ui.byId('sheetCount').textContent = String(sheets.length);
  ui.setHtml('sheetList', sheets.map((sheet, index) => `
    <div class="item ${index === state.sheetIndex ? 'active' : ''}" data-sheet="${index}">
      <div class="item-id">${ui.escapeHtml(sheet.name || `Sheet ${index + 1}`)}</div>
      <div class="item-sub">#${index + 1} · ${sheet.rowCount} 行 · ${sheet.columnCount} 列${sheet.hidden ? ' · 隐藏' : ''}</div>
    </div>`).join(''));
  document.querySelectorAll('#sheetList .item').forEach((item) => {
    item.addEventListener('click', () => selectSheet(Number(item.dataset.sheet)));
  });
}

/** True when a product is chosen; without one the wizard only previews raw data. */
function hasProduct() {
  return Boolean(state.productCode);
}

function selectedProduct() {
  return (state.ctx?.products || []).find((product) => product.code === state.productCode) || null;
}

// ------------------------------------------------------------------ preview

async function refreshPreview(useDetectedValues = false) {
  if (!state.upload) {
    return;
  }
  state.sampleLimit = PAGE_SIZE;
  if (!hasProduct()) {
    await loadRawPreview();
    return;
  }
  if (selectedProduct()?.definitionValid === false) {
    state.preview = null;
    state.raw = null;
    updateProductHint();
    renderBlockedPreview('产品定义无效，无法校验该文件');
    return;
  }
  await loadProductPreview(useDetectedValues);
}

/** Raw preview: headers and cell values only, no field mapping. */
async function loadRawPreview() {
  const inputs = currentInputs();
  const headerRow = inputs.headerRow || 1;
  const rows = Math.min(MAX_SAMPLE_ROWS, headerRow + state.sampleLimit + 5);
  try {
    state.raw = await get(`/api/uploads/${encodeURIComponent(state.upload.uploadId)}`
      + `/sheets/${state.sheetIndex}/raw?rows=${rows}`);
    state.preview = null;
    ui.byId('sheetTitle').textContent =
      `${state.upload.sheets[state.sheetIndex].name || `Sheet ${state.sheetIndex + 1}`} · 原始预览`;
    updateProductHint();
    renderRawPreview();
  } catch (error) {
    ui.showError(error);
  }
}

/** Definition backed preview: layout, column kinds and sample rows. */
async function loadProductPreview(useDetectedValues = false) {
  const inputs = currentInputs();
  const body = {
    sheetIndex: state.sheetIndex,
    productCode: state.productCode || null,
    headerRow: inputs.headerRow || null,
    tradeStartColumn: inputs.tradeStartColumn || null,
    dataStartRow: inputs.dataStartRow || null,
    dataEndRow: inputs.dataEndRow || null,
    sampleLimit: state.sampleLimit,
  };
  try {
    const response = await post(`/api/uploads/${encodeURIComponent(state.upload.uploadId)}/preview`, body);
    state.preview = response.preview;
    state.raw = null;
    state.productCode = response.productCode;
    ui.byId('productSelect').value = response.productCode;
    const layout = response.preview.layout;
    if (layout) {
      if (useDetectedValues || !inputs.headerRow) {
        ui.byId('headerRow').value = layout.headerRow;
      }
      if (useDetectedValues || !inputs.tradeStartColumn) {
        ui.byId('tradeStartColumn').value = layout.tradeStartColumn;
      }
      if (useDetectedValues || !inputs.dataStartRow) {
        ui.byId('dataStartRow').value = layout.dataStartRow;
      }
      state.params[state.sheetIndex] = currentInputs();
    }
    ui.byId('sheetTitle').textContent =
      `${state.upload.sheets[state.sheetIndex].name || `Sheet ${state.sheetIndex + 1}`} · ${response.productCode}`;
    updateProductHint();
    renderPreview();
  } catch (error) {
    if (error.code === 'PRODUCT_DEFINITION_INVALID') {
      state.preview = null;
      updateProductHint();
      renderBlockedPreview(error.message);
      return;
    }
    ui.showError(error);
  }
}

function renderBlockedPreview(message) {
  ui.byId('validityLamp').className = 'status-lamp bad';
  ui.byId('validityLamp').textContent = '无法校验';
  ui.byId('previewLegend').classList.add('hidden');
  ui.setHtml('previewHead', '<th>列</th>');
  ui.setHtml('previewBody', `<tr><td class="empty">${ui.escapeHtml(message)}</td></tr>`);
  ui.setHtml('previewStats', '');
  renderMore();
  renderIssues([]);
}

/** Visible row range of the raw preview, derived from the wizard parameters. */
function rawRange() {
  const inputs = currentInputs();
  const headerRow = inputs.headerRow || 1;
  return {
    headerRow,
    start: inputs.dataStartRow || headerRow + 1,
    end: inputs.dataEndRow > 0 ? inputs.dataEndRow : Number.MAX_SAFE_INTEGER,
  };
}

/** Cell marker for a formula: colour is already taken by the column kind, so use a glyph instead. */
function formulaFlag(formula) {
  if (!formula) {
    return '';
  }
  return `<span class="fx-flag" title="${ui.escapeHtml(formula)}">ƒ</span>`;
}

/** Formula text behind a preview cell, if the source cell carries one. */
function formulaOf(row, assignment) {
  const formulas = row.formulas || {};
  if (assignment.kind === 'TRADE') {
    return formulas[assignment.fieldKey];
  }
  if (assignment.kind === 'MANAGED') {
    return formulas[assignment.managedKey];
  }
  return null;
}

function renderRawPreview() {
  const raw = state.raw;
  if (!raw) {
    return;
  }
  const { headerRow, start, end } = rawRange();
  const rows = raw.rows || [];
  const header = rows[headerRow - 1]?.values || [];
  const columnCount = Math.max(header.length, ...rows.map((row) => row.values.length), 1);
  const dataRows = rows.filter((row) => row.row >= start && row.row <= end);

  ui.byId('validityLamp').className = 'status-lamp idle';
  ui.byId('validityLamp').textContent = '原始预览';
  ui.byId('previewLegend').classList.add('hidden');

  const head = ['<th class="num">行</th>'];
  for (let column = 0; column < columnCount; column++) {
    head.push(`<th>${ui.escapeHtml(header[column] || `列 ${column + 1}`)}</th>`);
  }
  ui.setHtml('previewHead', head.join(''));

  ui.setHtml('previewBody', dataRows.length ? dataRows.map((row) => {
    const cells = [];
    for (let column = 0; column < columnCount; column++) {
      cells.push(`<td class="mono">${ui.escapeHtml(row.values[column] || '')}${
        formulaFlag((row.formulas || [])[column])}</td>`);
    }
    return `<tr><td class="num">${row.row}</td>${cells.join('')}</tr>`;
  }).join('') : '<tr><td class="empty">没有数据行</td></tr>');

  ui.byId('previewStats').textContent =
    `已预览 ${dataRows.length} 行 · ${columnCount} 列 · 选择产品后按定义校验并导入`;
  renderMore();
  renderIssues([]);
}

function renderPreview() {
  const preview = state.preview;
  if (!preview) {
    return;
  }
  const lamp = ui.byId('validityLamp');
  const issues = preview.issues || [];
  const errors = issues.filter((issue) => issue.level === 'ERROR').length;
  const warns = issues.filter((issue) => issue.level === 'WARN').length;
  lamp.className = `status-lamp ${errors ? 'bad' : warns ? 'warn' : 'ok'}`;
  lamp.textContent = errors ? `存在 ${errors} 个阻断问题` : warns ? `有 ${warns} 个警告` : '校验通过';
  ui.byId('previewLegend').classList.remove('hidden');

  const layout = preview.layout;
  if (!layout) {
    ui.setHtml('previewHead', '<th>列</th>');
    ui.setHtml('previewBody', '<tr><td class="empty">无法识别布局，请调整参数</td></tr>');
    ui.setHtml('previewStats', '');
    renderMore();
    renderIssues(issues);
    updateSubmitState();
    return;
  }

  const assignments = layout.assignments || [];
  const columnClass = (assignment) => (assignment.kind === 'TRADE' ? 'col-trade'
    : assignment.kind === 'MANAGED' ? 'col-managed' : 'col-unmapped');
  const headCells = ['<th class="num">行</th>'].concat(assignments.map((assignment) => {
    const label = assignment.kind === 'TRADE'
      ? `${assignment.header || assignment.fieldKey}<div class="mono-sm">${assignment.fieldKey}</div>`
      : `${assignment.header || '—'}`;
    return `<th class="${columnClass(assignment)}">${label}</th>`;
  }));
  ui.setHtml('previewHead', headCells.join(''));

  const samples = preview.sampleRows || [];
  const body = samples.map((row) => {
    const cells = assignments.map((assignment) => {
      const text = valueOf(row, assignment);
      const invalid = (row.issues || []).some((issue) => issue.fieldKey
        && issue.fieldKey === (assignment.fieldKey || assignment.managedKey));
      return `<td class="mono ${columnClass(assignment)} ${
        invalid ? 'num-fail' : ''}">${ui.escapeHtml(text)}${formulaFlag(formulaOf(row, assignment))}</td>`;
    });
    return `<tr><td class="num">${row.row}</td>${cells.join('')}</tr>`;
  });
  ui.setHtml('previewBody', body.length ? body.join('')
    : '<tr><td class="empty">没有数据行</td></tr>');

  const stats = preview.stats;
  ui.byId('previewStats').textContent =
    `数据行 ${stats.dataRows} · 映射交易列 ${stats.mappedTradeColumns} · 管理列 ${stats.managedColumns} · 样本中无效行 ${stats.sampleInvalidRows}`;
  renderMore();
  renderIssues(issues);
  updateSubmitState();
}

function valueOf(row, assignment) {
  if (assignment.kind === 'TRADE') {
    const value = row.values ? row.values[assignment.fieldKey] : undefined;
    return value === undefined || value === null ? '' : String(value);
  }
  if (assignment.kind === 'MANAGED') {
    const value = row.managed ? row.managed[assignment.managedKey] : undefined;
    return value === undefined || value === null ? '' : String(value);
  }
  const extra = row.extras ? row.extras[assignment.header] : undefined;
  return extra === undefined || extra === null ? '' : String(extra);
}

// --------------------------------------------------------------- streaming

function loadedRowCount() {
  if (!hasProduct()) {
    const { start, end } = rawRange();
    return (state.raw?.rows || []).filter((row) => row.row >= start && row.row <= end).length;
  }
  return (state.preview?.sampleRows || []).length;
}

function hasMoreRows() {
  if (state.sampleLimit >= MAX_SAMPLE_ROWS) {
    return false;
  }
  if (!hasProduct()) {
    return (state.raw?.rows || []).length >= state.sampleLimit;
  }
  return (state.preview?.sampleRows || []).length >= state.sampleLimit;
}

function renderMore() {
  const host = ui.byId('previewMore');
  if (!host) {
    return;
  }
  const loaded = loadedRowCount();
  if (state.loadingMore) {
    host.textContent = '正在加载更多…';
  } else if (hasMoreRows()) {
    host.textContent = `已加载 ${loaded} 行 · 滚动到底部加载更多`;
  } else {
    host.textContent = loaded ? `已加载全部 ${loaded} 行` : '';
  }
}

async function loadMore() {
  if (state.loadingMore || !hasMoreRows()) {
    return;
  }
  state.loadingMore = true;
  renderMore();
  state.sampleLimit = Math.min(state.sampleLimit + PAGE_SIZE, MAX_SAMPLE_ROWS);
  try {
    if (hasProduct()) {
      await loadProductPreview(false);
    } else {
      await loadRawPreview();
    }
  } finally {
    state.loadingMore = false;
    renderMore();
  }
}

function bindScrollPaging() {
  const wrap = ui.byId('previewWrap');
  if (!wrap) {
    return;
  }
  wrap.addEventListener('scroll', () => {
    if (state.loadingMore || !hasMoreRows()) {
      return;
    }
    if (wrap.scrollTop + wrap.clientHeight < wrap.scrollHeight - 40) {
      return;
    }
    loadMore();
  });
}

// ------------------------------------------------------------------- issues

/** Collapsible issue list; it always starts rolled up and the status lamp toggles it. */
function renderIssues(issues) {
  const errors = issues.filter((issue) => issue.level === 'ERROR').length;
  const warns = issues.filter((issue) => issue.level === 'WARN').length;
  ui.setHtml('previewIssues', issues.length ? issues.map((issue) => `
    <div class="issue ${ui.escapeHtml(issue.level)}">
      <span>${ui.escapeHtml(issue.message)}</span>
      <code>${ui.escapeHtml(issue.code)}${issue.row ? ` · 行 ${issue.row}` : ''}${issue.column ? ` · 列 ${issue.column}` : ''}</code>
    </div>`).join('') : `<div class="muted">${hasProduct() ? '没有发现问题。' : '选择产品后才会做字段映射校验。'}</div>`);
  const summary = ui.byId('previewIssueSummary');
  if (summary) {
    summary.textContent = issues.length
      ? `问题 ${issues.length} 个（错误 ${errors} · 警告 ${warns}）`
      : (hasProduct() ? '没有发现问题' : '待选择产品');
  }
  ui.byId('previewIssuePanel').open = false;
  syncIssueToggle(issues.length > 0);
}

/** Makes the status lamp a toggle for the issue panel once there is something to show. */
function syncIssueToggle(clickable) {
  const lamp = ui.byId('validityLamp');
  if (!lamp) {
    return;
  }
  lamp.classList.toggle('clickable', clickable);
  lamp.classList.toggle('open', clickable && ui.byId('previewIssuePanel').open);
  lamp.title = clickable ? '展开或收起下方问题列表' : '';
}

/** Same collapsible block for the import result, built as markup. */
function issuePanel(issues) {
  if (!issues.length) {
    return '<div class="muted" style="margin-top:10px">没有发现问题。</div>';
  }
  const errors = issues.filter((issue) => issue.level === 'ERROR').length;
  const warns = issues.filter((issue) => issue.level === 'WARN').length;
  const items = issues.slice(0, 40).map((issue) => `
    <div class="issue ${ui.escapeHtml(issue.level)}"><span>${ui.escapeHtml(issue.message)}</span>
    <code>${ui.escapeHtml(issue.code)}${issue.row ? ` · 行 ${issue.row}` : ''}</code></div>`).join('');
  return `<details class="issues-panel">
      <summary>问题 ${issues.length} 个（错误 ${errors} · 警告 ${warns}）</summary>
      <div class="issues">${items}</div>
    </details>`;
}

// -------------------------------------------------------------------- state

function updateProductHint() {
  const product = selectedProduct();
  const invalid = Boolean(product && product.definitionValid === false);
  const hint = ui.byId('productValidityHint');
  if (hint) {
    hint.textContent = invalid
      ? '该产品定义了重复列名且未开启严格模式，定义无效，无法导入。请在产品定义中删除重复列，或开启严格模式。'
      : '未选择产品：当前为原始数据预览，选择产品后按定义校验并可提交。';
    hint.classList.toggle('hidden', !invalid && hasProduct());
    hint.classList.toggle('error-hint', invalid);
  }
  updateSubmitState();
}

function updateSubmitState() {
  const preview = state.preview;
  const blocked = !hasProduct()
    || selectedProduct()?.definitionValid === false
    || !preview
    || !preview.layout
    || (preview.issues || []).some((issue) => issue.level === 'ERROR');
  ui.byId('submitImportBtn').disabled = Boolean(blocked);
}

function selectSheet(index) {
  rememberParams();
  state.sheetIndex = index;
  renderSheets();
  const remembered = state.params[index];
  const ids = ['headerRow', 'tradeStartColumn', 'dataStartRow', 'dataEndRow'];
  ids.forEach((id) => {
    ui.byId(id).value = (remembered && remembered[id]) || '';
  });
  refreshPreview(true);
}

async function handleFile(file) {
  if (!file) {
    return;
  }
  try {
    ui.toast('正在上传…');
    const view = await uploadFile('/api/uploads', file);
    state.upload = view;
    state.sheetIndex = 0;
    state.params = {};
    state.preview = null;
    state.raw = null;
    ui.byId('wizardArea').classList.remove('hidden');
    ui.byId('resultPanel').classList.add('hidden');
    ui.byId('uploadInfo').innerHTML =
      `已上传 <strong>${ui.escapeHtml(view.fileName)}</strong> · ${ui.bytes(view.sizeBytes)} · ${view.sheets.length} 个 sheet`;
    setStep(2);
    renderSheets();
    ['headerRow', 'tradeStartColumn', 'dataStartRow', 'dataEndRow'].forEach((id) => {
      ui.byId(id).value = '';
    });
    await refreshPreview(false);
  } catch (error) {
    ui.showError(error);
  }
}

// ------------------------------------------------------------------- submit

async function submit() {
  if (!state.upload || !hasProduct()) {
    return;
  }
  rememberParams();
  const params = state.params[state.sheetIndex] || {};
  const sheets = [{
    sheetIndex: state.sheetIndex,
    productCode: state.productCode,
    headerRow: params.headerRow || 0,
    tradeStartColumn: params.tradeStartColumn || 0,
    dataStartRow: params.dataStartRow || 0,
    dataEndRow: params.dataEndRow || 0,
  }];
  ui.byId('submitImportBtn').disabled = true;
  let result;
  try {
    result = await post('/api/imports', {
      uploadId: state.upload.uploadId,
      sheets,
      force: false,
      includeManagedColumns: ui.byId('includeManagedColumns').checked,
      includeUnmappedColumns: ui.byId('includeUnmappedColumns').checked,
      stopAtEmptyRow: ui.byId('stopAtEmptyRow').checked,
      stopAtFirstError: ui.byId('stopAtFirstError').checked,
    });
  } catch (error) {
    ui.showError(error);
    updateSubmitState();
    return;
  }
  setStep(3);
  const batches = result.batches || [];
  const issues = result.issues || [];
  const failed = result.status === 'FAILED' || !batches.length;
  try {
    renderResult(result, failed);
  } catch (error) {
    console.error(error);
    ui.setHtml('resultBody', `<div class="issue ERROR"><span>结果已生成，但渲染失败：${
      ui.escapeHtml(error.message || String(error))}</span></div>`);
  }
  ui.byId('resultPanel').classList.remove('hidden');
  if (failed) {
    ui.toast(`导入失败：没有批次被写入（${issues.filter((i) => i.level === 'ERROR').length} 个错误）`, 'error');
  } else if (result.status === 'SUCCEEDED') {
    ui.toast(`导入成功：${result.totalRows} 行，${batches.length} 个批次`, 'ok');
  } else {
    ui.toast(`部分导入：${result.totalRows} 行成功，${result.invalidRows} 行无效`, 'warn');
  }
  ui.byId('resultPanel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

/** Renders the import result; {@code failed} switches the summary to the failure layout. */
function renderResult(result, failed) {
  const batches = result.batches || [];
  const issues = result.issues || [];
  ui.byId('resultTitle').textContent = failed ? '导入失败' : '导入结果';
  const batchRows = batches.map((batch) => `
      <tr>
        <td class="mono">${ui.escapeHtml(String(batch.batchId || '').slice(0, 8))}</td>
        <td>${ui.escapeHtml(batch.productCode)}</td>
        <td>${ui.escapeHtml(batch.sheetName)}</td>
        <td class="num">${batch.totalRows}</td>
        <td class="num">${batch.invalidRows}</td>
        <td><a class="tool-btn small" href="/batches/${encodeURIComponent(batch.batchId)}">打开批次</a></td>
      </tr>`).join('');
  const summary = `
      <div class="kv" style="margin-bottom:12px">
        <div class="k">任务</div><div class="v"><a href="/tasks/${encodeURIComponent(result.taskId)}">${ui.escapeHtml(result.taskId)}</a></div>
        <div class="k">状态</div><div class="v">${ui.statusBadge(result.status)}</div>
        <div class="k">有效行</div><div class="v">${result.totalRows}</div>
        <div class="k">无效行</div><div class="v">${result.invalidRows}</div>
        <div class="k">批次</div><div class="v">${batches.length}</div>
      </div>`;
  const batchTable = batches.length
    ? `<div class="table-wrap"><table><thead><tr><th>批次</th><th>产品</th><th>Sheet</th>
        <th class="num">行数</th><th class="num">无效</th><th></th></tr></thead>
        <tbody>${batchRows}</tbody></table></div>`
    : `<div class="issue ERROR"><span>没有批次被写入，请根据下方问题修正后重试。</span><code>IMPORT_FAILED</code></div>`;
  ui.setHtml('resultBody', summary + batchTable + issuePanel(issues));
}

// --------------------------------------------------------------------- init

async function init() {
  state.ctx = await ui.initShell();
  if (state.ctx) {
    const options = state.ctx.products.map((product) =>
      `<option value="${product.code}">${product.code} · ${product.name}${
        product.definitionValid === false ? '（无效）' : ''}</option>`).join('');
    ui.byId('productSelect').innerHTML = `<option value=""></option>${options}`;
    state.productCode = '';
    updateProductHint();
  }

  const dropzone = ui.byId('dropzone');
  const fileInput = ui.byId('fileInput');
  dropzone.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', () => handleFile(fileInput.files[0]));
  ['dragenter', 'dragover'].forEach((type) => dropzone.addEventListener(type, (event) => {
    event.preventDefault();
    dropzone.classList.add('over');
  }));
  ['dragleave', 'drop'].forEach((type) => dropzone.addEventListener(type, (event) => {
    event.preventDefault();
    dropzone.classList.remove('over');
  }));
  dropzone.addEventListener('drop', (event) => {
    handleFile(event.dataTransfer.files[0]);
  });

  const schedule = () => {
    clearTimeout(state.timer);
    state.timer = setTimeout(() => {
      rememberParams();
      refreshPreview(false);
    }, 400);
  };
  ['headerRow', 'tradeStartColumn', 'dataStartRow', 'dataEndRow'].forEach((id) => ui.on(id, 'input', schedule));
  ui.on('productSelect', 'change', (event) => {
    state.productCode = event.target.value;
    // Another definition means the previous layout values are meaningless: let the server re-detect.
    ['headerRow', 'tradeStartColumn', 'dataStartRow', 'dataEndRow'].forEach((id) => {
      ui.byId(id).value = '';
    });
    updateProductHint();
    refreshPreview(true);
  });
  ui.on('submitImportBtn', 'click', () => submit());
  ui.on('validityLamp', 'click', () => {
    const lamp = ui.byId('validityLamp');
    if (!lamp.classList.contains('clickable')) {
      return;
    }
    const panel = ui.byId('previewIssuePanel');
    panel.open = !panel.open;
    lamp.classList.toggle('open', panel.open);
  });
  const issuePanelNode = ui.byId('previewIssuePanel');
  issuePanelNode.addEventListener('toggle', () => {
    ui.byId('validityLamp').classList.toggle('open', issuePanelNode.open);
  });
  bindScrollPaging();
}

init();
