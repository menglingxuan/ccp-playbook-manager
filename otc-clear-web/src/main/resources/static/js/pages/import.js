import { get, post, put, upload as uploadFile } from '../api.js';
import * as issueList from '../issue-list.js';
import * as ui from '../ui.js';

const DEFAULT_PAGE_SIZE = 20;

/** Only the newest preview request may paint; the parameters can change while a request runs. */
const previewOrder = ui.sequence();

const state = {
  ctx: null,
  upload: null,
  sheetIndex: 0,
  productCode: '',
  params: {},
  preview: null,
  raw: null,
  /** Rows of the page currently shown; the sheet is never loaded as a whole. */
  rows: [],
  totalRows: 0,
  offset: 0,
  pageSize: DEFAULT_PAGE_SIZE,
  loadingRows: false,
  /** Header cells of the raw preview, kept while paging moves past the header row. */
  rawHeader: [],
  rawHeaderKey: null,
  /** Sheet level issues of the head preview; the pager merges the issues of the visible page. */
  headIssues: [],
  /** Sheets ticked for the "import selected sheets" action. */
  selectedSheets: new Set(),
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
    <div class="item sheet-item ${index === state.sheetIndex ? 'active' : ''}" data-sheet="${index}">
      <input type="checkbox" class="sheet-pick" data-sheet-pick="${index}"
        ${state.selectedSheets.has(index) ? 'checked' : ''}
        aria-label="${ui.escapeHtml(`选择 ${sheet.name || `Sheet ${index + 1}`}`)}">
      <div class="sheet-item-body">
        <div class="item-id">${ui.escapeHtml(sheet.name || `Sheet ${index + 1}`)}</div>
        <div class="item-sub">#${index + 1} · ${sheet.rowCount} 行 · ${sheet.columnCount} 列${sheet.hidden ? ' · 隐藏' : ''}</div>
      </div>
    </div>`).join(''));
  document.querySelectorAll('#sheetList .sheet-item').forEach((item) => {
    item.addEventListener('click', (event) => {
      if (event.target.closest('[data-sheet-pick]')) {
        return;
      }
      selectSheet(Number(item.dataset.sheet));
    });
  });
  document.querySelectorAll('#sheetList [data-sheet-pick]').forEach((box) => {
    box.addEventListener('change', () => {
      const index = Number(box.dataset.sheetPick);
      if (box.checked) {
        state.selectedSheets.add(index);
      } else {
        state.selectedSheets.delete(index);
      }
      syncSheetSelection();
    });
  });
  syncSheetSelection();
}

/** Keeps the "import selected sheets" button in step with the ticked sheets. */
function syncSheetSelection() {
  const button = ui.byId('importSelectedBtn');
  if (!button) {
    return;
  }
  const count = state.selectedSheets.size;
  button.textContent = count ? `导入所选 Sheet（${count}）` : '导入所选 Sheet';
  button.disabled = count === 0 || !hasProduct();
}

/** Tick or untick every sheet, used by the "select all" link. */
function toggleAllSheets(select) {
  const sheets = state.upload?.sheets || [];
  state.selectedSheets = new Set(select ? sheets.map((_, index) => index) : []);
  document.querySelectorAll('#sheetList [data-sheet-pick]').forEach((box) => {
    box.checked = select;
  });
  syncSheetSelection();
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
  // a parameter or sheet change always starts at the first page
  state.offset = 0;
  state.rows = [];
  state.totalRows = 0;
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
  const token = previewOrder.next();
  const headerRow = Math.max(1, currentInputs().headerRow || 1);
  const base = `/api/uploads/${encodeURIComponent(state.upload.uploadId)}/sheets/${state.sheetIndex}`;
  state.loadingRows = true;
  renderPager();
  try {
    const raw = await get(`${base}/raw?rows=${state.pageSize}&offset=${headerRow + state.offset}`);
    if (!token()) {
      return false;
    }
    state.raw = raw;
    state.rows = raw.rows || [];
    // the pager counts data rows, so the header row is not part of the total
    state.totalRows = Math.max(0, Number(raw.totalRows || 0) - headerRow);
    const headerKey = `${state.sheetIndex}:${headerRow}`;
    if (state.rawHeaderKey !== headerKey) {
      // the header sits above the first page, so it is fetched once per sheet and layout
      const head = await get(`${base}/raw?rows=1&offset=${headerRow - 1}`);
      if (!token()) {
        return false;
      }
      state.rawHeader = (head.rows && head.rows[0] ? head.rows[0].values : []) || [];
      state.rawHeaderKey = headerKey;
    }
    state.preview = null;
    ui.byId('sheetTitle').textContent =
      `${state.upload.sheets[state.sheetIndex].name || `Sheet ${state.sheetIndex + 1}`} · 原始预览`;
    updateProductHint();
    state.loadingRows = false;
    renderRawPreview();
    renderPager();
    return true;
  } catch (error) {
    ui.showError(error);
    return false;
  } finally {
    state.loadingRows = false;
  }
}

/** Definition backed preview: layout, column kinds and the rows of the current page. */
async function loadProductPreview(useDetectedValues = false) {
  const token = previewOrder.next();
  const inputs = currentInputs();
  const body = {
    sheetIndex: state.sheetIndex,
    productCode: state.productCode || null,
    headerRow: inputs.headerRow || null,
    tradeStartColumn: inputs.tradeStartColumn || null,
    dataStartRow: inputs.dataStartRow || null,
    dataEndRow: inputs.dataEndRow || null,
    sampleLimit: state.pageSize,
    sampleOffset: state.offset,
  };
  try {
    const response = await post(`/api/uploads/${encodeURIComponent(state.upload.uploadId)}/preview`, body);
    if (!token()) {
      // the parameters changed while this preview was running; the newer answer wins
      return false;
    }
    state.preview = response.preview;
    state.headIssues = response.preview.issues || [];
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
    // the head preview carries the layout; the rows always come from the paging endpoint so the
    // pager knows the real row count and the window is exact
    await loadPageRows();
    return true;
  } catch (error) {
    if (error.code === 'PRODUCT_DEFINITION_INVALID') {
      state.preview = null;
      updateProductHint();
      renderBlockedPreview(error.message);
      return false;
    }
    ui.showError(error);
    return false;
  }
}

function renderBlockedPreview(message) {
  ui.byId('validityLamp').className = 'status-lamp bad';
  ui.byId('validityLamp').textContent = '无法校验';
  ui.byId('previewLegend').classList.add('hidden');
  ui.setHtml('previewHead', '<th>列</th>');
  ui.setHtml('previewBody', `<tr><td class="empty">${ui.escapeHtml(message)}</td></tr>`);
  ui.setHtml('previewStats', '');
  state.rows = [];
  state.totalRows = 0;
  renderPager();
  renderIssues([]);
}

/**
 * Loads the page of rows that the pager points at.
 *
 * <p>Only this window is transferred: the wizard can walk a file of any size while the browser and
 * the server both hold one page.
 */
async function loadPageRows() {
  if (!state.upload || !state.preview || !state.preview.layout) {
    renderPager();
    return false;
  }
  const token = previewOrder.next();
  state.loadingRows = true;
  renderPager();
  const inputs = currentInputs();
  try {
    const page = await post(
      `/api/uploads/${encodeURIComponent(state.upload.uploadId)}/preview/rows`, {
        sheetIndex: state.sheetIndex,
        productCode: state.productCode || null,
        headerRow: inputs.headerRow || null,
        tradeStartColumn: inputs.tradeStartColumn || null,
        dataStartRow: inputs.dataStartRow || null,
        dataEndRow: inputs.dataEndRow || null,
        sampleLimit: state.pageSize,
        sampleOffset: state.offset,
      });
    if (!token()) {
      return false;
    }
    state.rows = page.rows || [];
    state.totalRows = Number(page.totalRows || 0);
    // the paper trail of the visible page: sheet level issues stay, row issues follow the pager
    state.preview = { ...state.preview, issues: [...(state.headIssues || []), ...(page.issues || [])] };
    if (state.offset > 0 && state.rows.length === 0 && state.totalRows > 0) {
      // the last page moved (rows were deleted or the end row changed): show the valid last page
      state.offset = Math.max(0, (Math.ceil(state.totalRows / state.pageSize) - 1) * state.pageSize);
      state.loadingRows = false;
      return loadPageRows();
    }
    state.loadingRows = false;
    renderPreview();
    renderPager();
    return true;
  } catch (error) {
    ui.showError(error);
    return false;
  } finally {
    state.loadingRows = false;
  }
}

/** Moves to another page of rows and refreshes only the table. */
function goToPage(offset) {
  state.offset = Math.max(0, offset);
  if (hasProduct() && state.preview && state.preview.layout) {
    loadPageRows();
    return;
  }
  loadRawPreview();
}

/** Pager state: range text and button availability. */
function renderPager() {
  const range = ui.byId('previewRange');
  const first = ui.byId('previewFirst');
  const prev = ui.byId('previewPrev');
  const next = ui.byId('previewNext');
  const last = ui.byId('previewLast');
  if (!range || !prev) {
    return;
  }
  const total = state.totalRows;
  const size = state.pageSize;
  const start = total === 0 ? 0 : state.offset + 1;
  const end = Math.min(state.offset + size, total);
  range.textContent = state.loadingRows
    ? '加载中…'
    : (total === 0 ? '没有数据行' : `第 ${start}–${end} 行 / 共 ${total} 行`);
  const atStart = state.offset <= 0;
  const atEnd = state.offset + size >= total;
  [first, prev].forEach((button) => { if (button) { button.disabled = atStart || state.loadingRows; } });
  [next, last].forEach((button) => { if (button) { button.disabled = atEnd || state.loadingRows; } });
  const sizeSelect = ui.byId('previewPageSize');
  if (sizeSelect && Number(sizeSelect.value) !== size) {
    sizeSelect.value = String(size);
  }
}

/** Offset of the last page; the pager clamps there when the row count shrinks. */
function lastPageOffset() {
  const pages = Math.max(1, Math.ceil(state.totalRows / state.pageSize));
  return (pages - 1) * state.pageSize;
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
  const inputs = currentInputs();
  const headerRow = Math.max(1, inputs.headerRow || 1);
  const rows = raw.rows || [];
  const header = state.rawHeader || [];
  const columnCount = Math.max(header.length, ...rows.map((row) => row.values.length), 1);
  const dataRows = rows.filter((row) => row.row > headerRow);

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
    `本页 ${dataRows.length} 行 · ${columnCount} 列 · 选择产品后按定义校验并导入`;
  renderPager();
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
  // rows can be invalid without a sheet level issue (type mismatch, missing required value)
  const sampleInvalid = (preview.stats && preview.stats.sampleInvalidRows) || 0;
  lamp.className = `status-lamp ${errors ? 'bad' : sampleInvalid || warns ? 'warn' : 'ok'}`;
  lamp.textContent = errors ? `存在 ${errors} 个阻断问题`
    : sampleInvalid ? `样本中 ${sampleInvalid} 行存在问题`
      : warns ? `有 ${warns} 个警告` : '校验通过';
  ui.byId('previewLegend').classList.remove('hidden');

  const layout = preview.layout;
  if (!layout) {
    ui.setHtml('previewHead', '<th>列</th>');
    ui.setHtml('previewBody', '<tr><td class="empty">无法识别布局，请调整参数</td></tr>');
    ui.setHtml('previewStats', '');
    renderPager();
    renderIssues(issues);
    updateSubmitState();
    return;
  }

  const assignments = layout.assignments || [];
  const columnClass = (assignment) => (assignment.kind === 'TRADE' ? 'col-trade'
    : assignment.kind === 'MANAGED' ? 'col-managed' : 'col-unmapped');
  const headCells = ['<th class="num">行</th>'].concat(assignments.map((assignment) => {
    const header = assignment.header || assignment.fieldKey;
    const label = assignment.kind === 'TRADE'
      ? `${ui.escapeHtml(header)}<div class="mono-sm">${ui.escapeHtml(assignment.fieldKey)}</div>`
      : `${ui.escapeHtml(assignment.header || '—')}`;
    return `<th class="${columnClass(assignment)}">${label}</th>`;
  }));
  ui.setHtml('previewHead', headCells.join(''));

  const samples = state.rows || [];
  const body = samples.map((row) => {
    const cells = assignments.map((assignment) => {
      const text = valueOf(row, assignment);
      const cellIssues = (row.issues || []).filter((issue) => issue.fieldKey
        && issue.fieldKey === (assignment.fieldKey || assignment.managedKey));
      const blocking = cellIssues.some((issue) => issue.level === 'ERROR');
      const title = cellIssues.length
        ? ` title="${ui.escapeHtml(cellIssues.map((issue) => issue.message).join(' / '))}"`
        : '';
      return `<td class="mono ${columnClass(assignment)} ${
        cellIssues.length ? (blocking ? 'num-fail' : 'num-warn') : ''}"${title}>${
        ui.escapeHtml(text)}${formulaFlag(formulaOf(row, assignment))}</td>`;
    });
    return `<tr><td class="num">${row.row}</td>${cells.join('')}</tr>`;
  });
  ui.setHtml('previewBody', body.length ? body.join('')
    : '<tr><td class="empty">没有数据行</td></tr>');

  const stats = preview.stats || {};
  const pageInvalid = samples.filter((row) => row.valid === false).length;
  ui.byId('previewStats').textContent =
    `数据行 ${state.totalRows || stats.dataRows || 0} · 映射交易列 ${stats.mappedTradeColumns || 0}`
    + ` · 管理列 ${stats.managedColumns || 0} · 本页 ${samples.length} 行（无效 ${pageInvalid} 行）`;
  renderPager();
  renderIssues(issues);
  updateSubmitState();
}

/** Cell text of one preview cell: the original text recorded at import time wins over the typed value. */
function valueOf(row, assignment) {
  const raw = row.rawTexts || {};
  if (assignment.kind === 'TRADE') {
    const original = raw.data ? raw.data[assignment.fieldKey] : undefined;
    if (original !== undefined && original !== null) {
      return String(original);
    }
    const value = row.values ? row.values[assignment.fieldKey] : undefined;
    return value === undefined || value === null ? '' : String(value);
  }
  if (assignment.kind === 'MANAGED') {
    const original = raw.managed ? raw.managed[assignment.managedKey] : undefined;
    if (original !== undefined && original !== null) {
      return String(original);
    }
    const value = row.managed ? row.managed[assignment.managedKey] : undefined;
    return value === undefined || value === null ? '' : String(value);
  }
  const extra = row.extras ? row.extras[assignment.header] : undefined;
  return extra === undefined || extra === null ? '' : String(extra);
}

// ------------------------------------------------------------------- issues

/** Collapsible issue list; it always starts rolled up and the status lamp toggles it. */
function renderIssues(issues) {
  const errors = issues.filter((issue) => issue.level === 'ERROR').length;
  const warns = issues.filter((issue) => issue.level === 'WARN').length;
  issueList.mount(ui.byId('previewIssues'), issues, {
    emptyHtml: `<div class="muted">${hasProduct()
      ? '没有发现问题。' : '选择产品后才会做字段映射校验。'}</div>`,
  });
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
  return `<details class="issues-panel">
      <summary>问题 ${issues.length} 个（错误 ${errors} · 警告 ${warns}）</summary>
      <div class="issues" id="resultIssues"></div>
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
  syncSheetSelection();
}

function updateSubmitState() {
  const preview = state.preview;
  // a blocking validation rule rejects only its own row on the server, so it never disables the submit
  const blocked = !hasProduct()
    || selectedProduct()?.definitionValid === false
    || !preview
    || !preview.layout
    || (preview.issues || []).some(
      (issue) => issue.level === 'ERROR' && issue.code !== 'VALIDATION_FAILED');
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
    state.rows = [];
    state.totalRows = 0;
    state.offset = 0;
    state.selectedSheets = new Set();
    state.rawHeader = [];
    state.rawHeaderKey = null;
    state.headIssues = [];
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

/** Stores the tenant preference and repaints the visible issue lists with the new limit. */
async function saveIssueInlineLimit(value) {
  const input = ui.byId('issueInlineLimit');
  const requested = Number(value);
  try {
    const saved = await put('/api/settings/ui', { issueInlineLimit: requested });
    issueList.configure(saved);
    ui.toast(`问题清单展开上限已设为 ${issueList.inlineLimit()} 条`, 'ok');
  } catch (error) {
    ui.showError(error);
  }
  if (input) {
    input.value = String(issueList.inlineLimit());
  }
  // repaint with the stored limit: an expanded list may have to fold back into the pager
  const issues = (state.preview && state.preview.issues) || [];
  issueList.mount(ui.byId('previewIssues'), issues, {
    emptyHtml: `<div class="muted">${hasProduct()
      ? '没有发现问题。' : '选择产品后才会做字段映射校验。'}</div>`,
  });
}

// ------------------------------------------------------------------- submit

/** Fills the task-name suggestions with the latest tasks so an existing name can be reused. */
async function loadTaskNames() {
  try {
    const result = await get('/api/tasks?page=1&size=50&sort=created_desc');
    const names = (result.items || []).map((task) => task.name).filter(Boolean);
    ui.byId('importTaskNames').innerHTML = names
      .map((name) => `<option value="${ui.escapeHtml(name)}"></option>`).join('');
  } catch (error) {
    // suggestions are optional: a failed lookup must not block the wizard
    console.warn('Cannot load task names', error);
  }
}

async function submit() {
  if (!state.upload || !hasProduct()) {
    return;
  }
  rememberParams();
  const params = state.params[state.sheetIndex] || {};
  await runImport([sheetSelection(state.sheetIndex, params)]);
}

/** One entry of the commit request; 0 means "detect it again on the server". */
function sheetSelection(sheetIndex, params) {
  return {
    sheetIndex,
    productCode: state.productCode,
    headerRow: params.headerRow || 0,
    tradeStartColumn: params.tradeStartColumn || 0,
    dataStartRow: params.dataStartRow || 0,
    dataEndRow: params.dataEndRow || 0,
  };
}

/** Commits the sheets and renders the result, which ends the wizard on step 3. */
async function runImport(sheets) {
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
      taskName: (ui.byId('importTaskName').value || '').trim() || null,
    });
  } catch (error) {
    ui.showError(error);
    updateSubmitState();
    return null;
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
    ui.toast(`${result.joined ? '导入成功（并入任务）' : '导入成功'}：${result.totalRows} 行，${batches.length} 个批次`, 'ok');
  } else {
    ui.toast(`部分导入：写入 ${result.totalRows} 行，其中 ${result.invalidRows} 行存在问题`, 'warn');
  }
  ui.byId('resultPanel').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  return result;
}

// -------------------------------------------------- multi sheet import

/** Display name of a sheet; the 1 based index keeps it unique when names repeat. */
function sheetName(index) {
  const sheet = (state.upload?.sheets || [])[index];
  return sheet && sheet.name ? sheet.name : `Sheet ${index + 1}`;
}

/** Ticked sheet indices in file order. */
function selectedSheetIndexes() {
  return [...state.selectedSheets].sort((left, right) => left - right);
}

/**
 * Imports every ticked sheet at once.
 *
 * <p>Each sheet is validated first: a blocking issue refuses the whole run, warnings are listed and
 * have to be confirmed explicitly. Only then are all sheets committed by one request.
 */
async function importSelected() {
  const indexes = selectedSheetIndexes();
  if (!state.upload || !hasProduct() || !indexes.length) {
    return;
  }
  rememberParams();
  const button = ui.byId('importSelectedBtn');
  const uploadId = encodeURIComponent(state.upload.uploadId);
  button.disabled = true;
  button.textContent = '正在校验…';  const blocking = [];
  const warnings = [];
  const selections = [];
  try {
    for (const sheetIndex of indexes) {
      const name = `${sheetName(sheetIndex)}（#${sheetIndex + 1}）`;
      const params = state.params[sheetIndex] || {};
      let response;
      try {
        response = await post(`/api/uploads/${uploadId}/preview`, {
          sheetIndex,
          productCode: state.productCode,
          headerRow: params.headerRow || null,
          tradeStartColumn: params.tradeStartColumn || null,
          dataStartRow: params.dataStartRow || null,
          dataEndRow: params.dataEndRow || null,
        });
      } catch (error) {
        blocking.push({ sheet: name, code: error.code || '', message: error.message || String(error) });
        continue;
      }
      const preview = response.preview || {};
      if (!preview.layout) {
        blocking.push({ sheet: name, code: 'NO_LAYOUT', message: '无法识别布局：未找到表头行或交易列' });
        continue;
      }
      (preview.issues || []).forEach((issue) => {
        const entry = { sheet: name, code: issue.code || '', message: issue.message || '' };
        if (issue.level === 'ERROR' && issue.code !== 'VALIDATION_FAILED') {
          blocking.push(entry);
        } else if (issue.level === 'WARN') {
          warnings.push(entry);
        }
      });
      selections.push({ sheetIndex, productCode: response.productCode || state.productCode, params });
    }
  } finally {
    button.disabled = false;
    syncSheetSelection();
  }
  if (blocking.length) {
    ui.toast(`导入已取消：${blocking.length} 个阻断型问题`, 'error');
    await askModal('导入已取消', `所选 ${indexes.length} 个 Sheet 中存在 ${blocking.length} 个阻断型问题，`
      + '请先修正后再导入。', blocking, [{ label: '关闭', value: 'cancel', tone: 'primary' }]);
    return;
  }
  if (warnings.length) {
    const answer = await askModal('存在警告，确认继续？',
      `所选 ${indexes.length} 个 Sheet 中有 ${warnings.length} 个警告。警告不会阻止导入，`
      + '但相关单元格可能仍需人工复核。', warnings, [
        { label: '取消', value: 'cancel' },
        { label: '仍然导入', value: 'continue', tone: 'primary' },
      ]);
    if (answer !== 'continue') {
      return;
    }
  }
  await runImport(selections.map((selection) => sheetSelection(selection.sheetIndex, selection.params)));
}

/** Lists pre-check issues in a modal and resolves with the clicked action (or 'cancel'). */
function askModal(title, intro, issues, buttons) {
  return new Promise((resolve) => {
    const body = `<p>${ui.escapeHtml(intro)}</p><div id="askIssueHost"></div>`;
    const footer = buttons.map((button) => `<button class="tool-btn${
      button.tone === 'primary' ? ' primary' : ''}"
      data-ask="${ui.escapeHtml(button.value)}">${ui.escapeHtml(button.label)}</button>`).join('');
    const modal = ui.openModal(title, body, footer, { wide: true });
    const host = modal.root.querySelector('#askIssueHost');
    // the pre-check list is a table, so "more" expands inside the table or opens the drawer with columns
    host.innerHTML = `<div class="table-wrap"><table class="issue-table">
        <thead><tr><th>Sheet</th><th>代码</th><th>说明</th></tr></thead>
        <tbody id="askIssueRows"></tbody></table></div>`;
    const rowsHost = host.querySelector('#askIssueRows');
    issueList.mount(rowsHost, issues, {
      rowHtml: issueList.tableRowHtml,
      table: true,
      drawerTitle: '预检问题清单',
      emptyHtml: '<tr><td colspan="3" class="muted">没有需要确认的问题。</td></tr>',
    });
    let settled = false;
    const settle = (value) => {
      if (settled) {
        return;
      }
      settled = true;
      document.removeEventListener('keydown', onKey, true);
      modal.close();
      resolve(value);
    };
    const onKey = (event) => {
      if (event.key === 'Escape') {
        settle('cancel');
      }
    };
    document.addEventListener('keydown', onKey, true);
    modal.root.querySelectorAll('[data-ask]').forEach((node) => {
      node.addEventListener('click', () => settle(node.dataset.ask));
    });
    modal.root.querySelector('.modal-close').addEventListener('click', () => settle('cancel'));
    modal.root.addEventListener('click', (event) => {
      if (event.target === modal.root) {
        settle('cancel');
      }
    });
  });
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
        <td><div class="row-actions">${ui.iconLink('layers', `/batches/${encodeURIComponent(batch.batchId)}`, '打开批次工作台', 'sm')}</div></td>
      </tr>`).join('');
  const summary = `
      <div class="kv" style="margin-bottom:12px">
        <div class="k">任务</div><div class="v">
          <a href="/tasks/${encodeURIComponent(result.taskId)}">${
  ui.escapeHtml(result.taskName || result.taskId)}</a>
          ${result.joined ? '<span class="chip info" title="批次已追加到已有任务，没有新建任务">并入已有任务</span>' : ''}
        </div>
        <div class="k">状态</div><div class="v">${ui.statusBadge(result.status)}</div>
        <div class="k">已写入行</div><div class="v">${result.totalRows}</div>
        <div class="k">其中有问题的行</div><div class="v">${result.invalidRows}</div>
        <div class="k">批次</div><div class="v">${batches.length}</div>
      </div>`;
  const batchTable = batches.length
    ? `<div class="table-wrap"><table><thead><tr><th>批次</th><th>产品</th><th>Sheet</th>
        <th class="num">行数</th><th class="num">无效</th><th></th></tr></thead>
        <tbody>${batchRows}</tbody></table></div>`
    : `<div class="issue ERROR"><span>没有批次被写入，请根据下方问题修正后重试。</span><code>IMPORT_FAILED</code></div>`;
  ui.setHtml('resultBody', summary + batchTable + issuePanel(issues));
  issueList.mount(ui.byId('resultIssues'), issues);
}

// --------------------------------------------------------------------- init

async function init() {
  state.ctx = await ui.initShell();
  if (state.ctx) {
    const options = state.ctx.products.map((product) =>
      `<option value="${ui.escapeHtml(product.code)}">${ui.escapeHtml(product.code)} · ${
        ui.escapeHtml(product.name)}${product.definitionValid === false ? '（无效）' : ''}</option>`).join('');
    ui.byId('productSelect').innerHTML = `<option value=""></option>${options}`;
    state.productCode = '';
    issueList.configure(state.ctx.ui);
    ui.byId('issueInlineLimit').value = String(issueList.inlineLimit());
    updateProductHint();
  }
  await loadTaskNames();

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
  ui.on('importSelectedBtn', 'click', () => importSelected());
  ui.on('selectAllSheets', 'click', (event) => {
    event.preventDefault();
    const total = (state.upload?.sheets || []).length;
    toggleAllSheets(state.selectedSheets.size < total);
  });
  ui.on('previewFirst', 'click', () => goToPage(0));
  ui.on('previewPrev', 'click', () => goToPage(state.offset - state.pageSize));
  ui.on('previewNext', 'click', () => goToPage(state.offset + state.pageSize));
  ui.on('previewLast', 'click', () => goToPage(lastPageOffset()));
  ui.on('previewPageSize', 'change', (event) => {
    state.pageSize = Math.max(1, Number(event.target.value) || DEFAULT_PAGE_SIZE);
    goToPage(0);
  });
  ui.on('issueInlineLimit', 'change', (event) => saveIssueInlineLimit(event.target.value));
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
}

init();
