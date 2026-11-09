import { get, query } from '../api.js';
import * as ui from '../ui.js';

const state = { page: 1, size: 25, product: '', kind: '', q: '' };

const KIND_LABELS = { IMPORT: '文件导入', CLONE: '克隆', OUTPUT: '外部产出' };

function debounce(fn, delay = 350) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function kindChip(kind) {
  const label = KIND_LABELS[kind] || kind || '—';
  const tone = kind === 'CLONE' ? 'muted' : kind === 'OUTPUT' ? 'muted' : 'info';
  return `<span class="chip ${tone}">${ui.escapeHtml(label)}</span>`;
}

async function load() {
  try {
    const result = await get('/api/batches' + query({
      product: state.product, q: state.q, kind: state.kind, includeExternal: true,
      page: state.page, size: state.size,
    }));
    const rows = result.items
      .map((batch) => `
      <tr>
        <td class="mono"><a href="/batches/${encodeURIComponent(batch.id)}">${ui.shortId(batch.id, 8)}</a></td>
        <td>${ui.escapeHtml(batch.productCode || '—')}</td>
        <td>${kindChip(batch.kind)}</td>
        <td class="mono">${ui.escapeHtml(batch.sheetName || '—')}</td>
        <td class="num">${batch.totalRows}</td>
        <td class="num ${batch.invalidRows > 0 ? 'num-fail' : ''}">${batch.invalidRows}</td>
        <td>${ui.longTextCell(batch.note || batch.sourceFilename || '')}</td>
        <td class="mono">${ui.escapeHtml(batch.createdAt)}</td>
        <td>
          <div class="row-actions">
            <a class="tool-btn small" href="/batches/${encodeURIComponent(batch.id)}">查看</a>
            ${batch.taskId
              ? `<a class="tool-btn small" href="/tasks/${encodeURIComponent(batch.taskId)}">任务</a>`
              : ''}
          </div>
        </td>
      </tr>`);
    ui.setHtml('batchRows', rows.length ? rows.join('')
      : '<tr><td colspan="9" class="empty">还没有批次，从「导入向导」导入文件就会生成批次</td></tr>');
    ui.bindLongText(ui.byId('batchRows'));
    ui.renderPager('batchPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      load();
    });
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  const ctx = await ui.initShell();
  if (ctx) {
    ui.byId('batchProduct').innerHTML = '<option value="">全部产品</option>' + ctx.products
      .map((product) => `<option value="${product.code}">${product.code}</option>`).join('');
  }
  await load();
  ui.enableColumnResize(ui.byId('batchTable'), 'batches');

  ui.on('batchSearch', 'input', debounce((event) => {
    state.q = event.target.value.trim();
    state.page = 1;
    load();
  }));
  ui.on('batchProduct', 'change', (event) => {
    state.product = event.target.value;
    state.page = 1;
    load();
  });
  ui.on('batchKind', 'change', (event) => {
    state.kind = event.target.value;
    state.page = 1;
    load();
  });
  ui.on('refreshBatchesBtn', 'click', () => load());
}

init();
