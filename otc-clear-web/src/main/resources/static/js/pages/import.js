import { post, upload as uploadFile } from '../api.js';
import * as ui from '../ui.js';

const state = {
  ctx: null,
  upload: null,
  sheetIndex: 0,
  productCode: '',
  params: {},
  preview: null,
  timer: null,
};

function currentInputs() {
  return {
    headerRow: Number(ui.byId('headerRow').value || 0),
    tradeStartColumn: Number(ui.byId('tradeStartColumn').value || 0),
    dataStartRow: Number(ui.byId('dataStartRow').value || 0),
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

function renderPreview() {
  const preview = state.preview;
  if (!preview) {
    return;
  }
  const lamp = ui.byId('validityLamp');
  const issues = preview.issues || [];
  const hasError = issues.some((issue) => issue.level === 'ERROR');
  const hasWarn = issues.some((issue) => issue.level === 'WARN');
  lamp.className = `status-lamp ${hasError ? 'bad' : hasWarn ? 'warn' : 'ok'}`;
  lamp.textContent = hasError ? `存在 ${issues.filter((issue) => issue.level === 'ERROR').length} 个阻断问题`
    : hasWarn ? `有 ${issues.filter((issue) => issue.level === 'WARN').length} 个警告` : '校验通过';
  ui.byId('submitImportBtn').disabled = hasError || !preview.layout;
  ui.byId('submitAllBtn').disabled = hasError || !preview.layout;

  const layout = preview.layout;
  if (!layout) {
    ui.setHtml('previewHead', '<th>列</th>');
    ui.setHtml('previewBody', '<tr><td class="empty">无法识别布局，请调整参数</td></tr>');
    ui.setHtml('previewStats', '');
    renderIssues(issues);
    return;
  }

  const assignments = layout.assignments || [];
  const headCells = ['<th class="num">行</th>'].concat(assignments.map((assignment) => {
    const tone = assignment.kind === 'TRADE' ? 'col-trade'
      : assignment.kind === 'MANAGED' ? 'col-managed' : 'col-unmapped';
    const label = assignment.kind === 'TRADE'
      ? `${assignment.header || assignment.fieldKey}<div class="mono-sm">${assignment.fieldKey}</div>`
      : `${assignment.header || '—'}`;
    return `<th class="${tone}">${label}</th>`;
  }));
  ui.setHtml('previewHead', headCells.join(''));

  const samples = preview.sampleRows || [];
  const body = samples.map((row) => {
    const cells = assignments.map((assignment) => {
      const text = valueOf(row, assignment);
      const invalid = (row.issues || []).some((issue) => issue.fieldKey && issue.fieldKey === (assignment.fieldKey || assignment.managedKey));
      return `<td class="mono ${invalid ? 'num-fail' : ''}">${ui.escapeHtml(text)}</td>`;
    });
    return `<tr><td class="num">${row.row}</td>${cells.join('')}</tr>`;
  });
  ui.setHtml('previewBody', body.length ? body.join('')
    : '<tr><td class="empty">没有数据行</td></tr>');

  const stats = preview.stats;
  ui.byId('previewStats').textContent =
    `数据行 ${stats.dataRows} · 映射交易列 ${stats.mappedTradeColumns} · 管理列 ${stats.managedColumns} · 样本中无效行 ${stats.sampleInvalidRows}`;
  renderIssues(issues);
}

function renderIssues(issues) {
  ui.setHtml('previewIssues', issues.length ? issues.map((issue) => `
    <div class="issue ${ui.escapeHtml(issue.level)}">
      <span>${ui.escapeHtml(issue.message)}</span>
      <code>${ui.escapeHtml(issue.code)}${issue.row ? ` · 行 ${issue.row}` : ''}${issue.column ? ` · 列 ${issue.column}` : ''}</code>
    </div>`).join('') : '<div class="muted">没有发现问题。</div>');
}

async function refreshPreview(useDetectedValues = false) {
  if (!state.upload) {
    return;
  }
  const inputs = currentInputs();
  const body = {
    sheetIndex: state.sheetIndex,
    productCode: state.productCode || null,
    headerRow: inputs.headerRow || null,
    tradeStartColumn: inputs.tradeStartColumn || null,
    dataStartRow: inputs.dataStartRow || null,
  };
  try {
    const response = await post(`/api/uploads/${encodeURIComponent(state.upload.uploadId)}/preview`, body);
    state.preview = response.preview;
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
    renderPreview();
  } catch (error) {
    ui.showError(error);
  }
}

function selectSheet(index) {
  rememberParams();
  state.sheetIndex = index;
  renderSheets();
  const remembered = state.params[index];
  if (remembered) {
    ui.byId('headerRow').value = remembered.headerRow || '';
    ui.byId('tradeStartColumn').value = remembered.tradeStartColumn || '';
    ui.byId('dataStartRow').value = remembered.dataStartRow || '';
  } else {
    ui.byId('headerRow').value = '';
    ui.byId('tradeStartColumn').value = '';
    ui.byId('dataStartRow').value = '';
  }
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
    ui.byId('wizardArea').classList.remove('hidden');
    ui.byId('uploadInfo').innerHTML =
      `已上传 <strong>${ui.escapeHtml(view.fileName)}</strong> · ${ui.bytes(view.sizeBytes)} · ${view.sheets.length} 个 sheet`;
    setStep(2);
    renderSheets();
    await pickFirstUsableSheet();
  } catch (error) {
    ui.showError(error);
  }
}

/** Selects the first sheet whose layout can be resolved; falls back to the first sheet. */
async function pickFirstUsableSheet() {
  const total = state.upload.sheets.length;
  for (let index = 0; index < total; index += 1) {
    state.sheetIndex = index;
    ui.byId('headerRow').value = '';
    ui.byId('tradeStartColumn').value = '';
    ui.byId('dataStartRow').value = '';
    await refreshPreview(true);
    if (state.preview && state.preview.layout) {
      renderSheets();
      return;
    }
  }
  selectSheet(0);
}

async function submit(all) {
  if (!state.upload) {
    return;
  }
  rememberParams();
  const sheets = (all ? state.upload.sheets : [state.upload.sheets[state.sheetIndex]]).map((sheet, index) => {
    const sheetIndex = all ? index : state.sheetIndex;
    const params = state.params[sheetIndex] || {};
    return {
      sheetIndex,
      productCode: state.productCode,
      headerRow: params.headerRow || 0,
      tradeStartColumn: params.tradeStartColumn || 0,
      dataStartRow: params.dataStartRow || 0,
    };
  });
  try {
    ui.byId('submitImportBtn').disabled = true;
    ui.byId('submitAllBtn').disabled = true;
    const result = await post('/api/imports', { uploadId: state.upload.uploadId, sheets, force: false });
    setStep(3);
    ui.byId('resultPanel').classList.remove('hidden');
    const batchRows = result.batches.map((batch) => `
      <tr>
        <td class="mono">${batch.id.slice(0, 8)}</td>
        <td>${ui.escapeHtml(batch.productCode)}</td>
        <td>${ui.escapeHtml(batch.sheetName)}</td>
        <td class="num">${batch.totalRows}</td>
        <td class="num">${batch.invalidRows}</td>
        <td><a class="tool-btn small" href="/batches/${encodeURIComponent(batch.id)}">打开批次</a></td>
      </tr>`).join('');
    ui.setHtml('resultBody', `
      <div class="kv" style="margin-bottom:12px">
        <div class="k">任务</div><div class="v"><a href="/tasks/${encodeURIComponent(result.taskId)}">${result.taskId}</a></div>
        <div class="k">状态</div><div class="v">${ui.statusBadge(result.status)}</div>
        <div class="k">有效行</div><div class="v">${result.totalRows}</div>
        <div class="k">无效行</div><div class="v">${result.invalidRows}</div>
      </div>
      <table><thead><tr><th>批次</th><th>产品</th><th>Sheet</th><th class="num">行数</th><th class="num">无效</th><th></th></tr></thead>
      <tbody>${batchRows || '<tr><td colspan="6" class="empty">没有批次</td></tr>'}</tbody></table>
      <div class="issues">${(result.issues || []).slice(0, 40).map((issue) => `
        <div class="issue ${ui.escapeHtml(issue.level)}"><span>${ui.escapeHtml(issue.message)}</span>
        <code>${ui.escapeHtml(issue.code)}${issue.row ? ` · 行 ${issue.row}` : ''}</code></div>`).join('')}</div>`);
    ui.toast('导入完成', 'ok');
  } catch (error) {
    ui.showError(error);
    ui.byId('submitImportBtn').disabled = false;
    ui.byId('submitAllBtn').disabled = false;
  }
}

async function init() {
  state.ctx = await ui.initShell();
  if (state.ctx) {
    ui.byId('productSelect').innerHTML = state.ctx.products
      .map((product) => `<option value="${product.code}">${product.code} · ${product.name}</option>`).join('');
    state.productCode = state.ctx.products[0]?.code || '';
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
    const file = event.dataTransfer.files[0];
    handleFile(file);
  });

  const schedule = () => {
    clearTimeout(state.timer);
    state.timer = setTimeout(() => {
      rememberParams();
      refreshPreview(false);
    }, 400);
  };
  ['headerRow', 'tradeStartColumn', 'dataStartRow'].forEach((id) => ui.on(id, 'input', schedule));
  ui.on('productSelect', 'change', (event) => {
    state.productCode = event.target.value;
    refreshPreview(false);
  });
  ui.on('submitImportBtn', 'click', () => submit(false));
  ui.on('submitAllBtn', 'click', () => submit(true));
}

init();
