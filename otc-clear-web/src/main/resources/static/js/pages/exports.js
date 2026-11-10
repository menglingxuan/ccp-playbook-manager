import { del, get, query } from '../api.js';
import * as ui from '../ui.js';

/* Export management: exports are stored as records first, the file is downloaded on demand. */

const state = { page: 1, size: 25, q: '', product: '', from: '', to: '', sort: 'created_desc' };

const SORT_FIELDS = new Set(['created', 'rows', 'size']);

const OPTION_LABELS = {
  delimiter: (value) => (value === '\t' ? 'TAB' : `“${value}”`),
  quoteAll: (value) => (value ? '全引号' : ''),
  managedColumns: (value) => (value === 'ALL' ? '含未映射列' : '交易字段'),
  bom: (value) => (value ? 'BOM' : ''),
  lineEnding: (value) => value,
};

function debounce(fn, delay = 300) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function optionSummary(options) {
  if (!options) {
    return '<span class="muted">—</span>';
  }
  const parts = [];
  Object.entries(OPTION_LABELS).forEach(([key, render]) => {
    if (options[key] === undefined || options[key] === null || options[key] === '') {
      return;
    }
    const text = render(options[key]);
    if (text) {
      parts.push(text);
    }
  });
  return parts.length ? parts.map((text) => `<span class="chip muted">${ui.escapeHtml(text)}</span>`).join(' ')
    : '<span class="muted">—</span>';
}

function rowHtml(item) {
  const scope = item.scopeType === 'BATCH'
    ? `<a class="mono" href="/batches/${encodeURIComponent(item.batchId || '')}" title="打开批次">批次 ${ui.escapeHtml(ui.shortId(item.batchId || '', 8))}</a>`
    : '<span class="muted">按用例选择</span>';
  const download = item.fileExists
    ? `<a class="tool-btn small" href="${ui.escapeHtml(item.downloadUrl)}" title="下载 CSV 文件">下载</a>`
    : '<span class="tool-btn small" aria-disabled="true" title="文件已不在磁盘上">文件缺失</span>';
  return `
    <tr data-export="${ui.escapeHtml(item.id)}">
      <td class="mono">${ui.escapeHtml(item.createdAt)}</td>
      <td class="mono" title="${ui.escapeHtml(item.fileName)}">${ui.escapeHtml(item.fileName)}</td>
      <td>${ui.escapeHtml(item.productCode || '')}</td>
      <td>${scope}</td>
      <td class="num">${item.rowCount}</td>
      <td class="num">${ui.bytes(item.fileSize)}</td>
      <td class="export-options">${optionSummary(item.options)}</td>
      <td>${ui.statusBadge(item.status)}</td>
      <td>
        <div class="row-actions">
          ${download}
          ${item.batchId ? `<a class="tool-btn small" href="/batches/${encodeURIComponent(item.batchId)}">批次</a>` : ''}
          <button class="tool-btn small danger" data-delete="${ui.escapeHtml(item.id)}">删除</button>
        </div>
      </td>
    </tr>`;
}

function renderSortHeaders() {
  document.querySelectorAll('#exportTable thead th[data-sort]').forEach((th) => {
    const field = th.dataset.sort;
    const active = state.sort.startsWith(`${field}_`);
    th.classList.toggle('sorted', active);
    const arrow = th.querySelector('.sort-arrow');
    if (arrow) {
      arrow.textContent = !active ? '⇅' : state.sort.endsWith('_asc') ? '↑' : '↓';
    }
  });
}

function cycleSort(field) {
  if (!SORT_FIELDS.has(field)) {
    return;
  }
  if (state.sort === `${field}_asc`) {
    state.sort = `${field}_desc`;
  } else if (state.sort === `${field}_desc`) {
    state.sort = '';
  } else {
    state.sort = `${field}_asc`;
  }
  state.page = 1;
  renderSortHeaders();
  load();
}

async function load() {
  try {
    const result = await get('/api/exports' + query({
      q: state.q, product: state.product, from: state.from, to: state.to, sort: state.sort,
      page: state.page, size: state.size,
    }));
    const rows = result.items.map(rowHtml);
    ui.setHtml('exportRows', rows.length ? rows.join('')
      : '<tr><td colspan="9" class="empty">还没有导出记录：在批次详情页导出后会出现在这里</td></tr>');
    ui.setHtml('exportTotal', `共 ${result.total} 条`);
    ui.byId('exportRows').querySelectorAll('button[data-delete]').forEach((button) => {
      button.addEventListener('click', () => remove(button.dataset.delete));
    });
    ui.renderPager('exportPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      load();
    });
  } catch (error) {
    ui.showError(error);
  }
}

async function remove(id) {
  if (!window.confirm('删除该导出记录？同时删除已存档的 CSV 文件，无法恢复。')) {
    return;
  }
  try {
    await del(`/api/exports/${encodeURIComponent(id)}`);
    ui.toast('导出记录已删除', 'ok');
    load();
  } catch (error) {
    ui.showError(error);
  }
}

async function loadProductOptions() {
  const select = ui.byId('exportProduct');
  try {
    const ctx = await get('/api/context');
    select.innerHTML = '<option value="">全部产品</option>' + ctx.products
      .map((product) => `<option value="${ui.escapeHtml(product.code)}">${ui.escapeHtml(product.code)}</option>`)
      .join('');
  } catch (error) {
    ui.showError(error);
  }
}

/** Flashes the record that the batch page just created ({@code ?focus=<id>}). */
function highlightFocused() {
  const focus = new URLSearchParams(window.location.search).get('focus');
  if (!focus) {
    return;
  }
  const row = ui.byId('exportRows').querySelector(`tr[data-export="${focus}"]`);
  if (!row) {
    return;
  }
  row.scrollIntoView({ block: 'center' });
  ui.flashElement(row);
}

async function init() {
  await ui.initShell();
  await loadProductOptions();
  await load();
  renderSortHeaders();
  ui.enableColumnResize(ui.byId('exportTable'), 'exports');
  highlightFocused();

  ui.on('exportSearch', 'input', debounce((event) => {
    state.q = event.target.value;
    state.page = 1;
    load();
  }, 350));
  ui.on('exportProduct', 'change', (event) => {
    state.product = event.target.value;
    state.page = 1;
    load();
  });
  ['exportFrom', 'exportTo'].forEach((id) => ui.on(id, 'change', () => {
    state.from = ui.byId('exportFrom').value;
    state.to = ui.byId('exportTo').value;
    state.page = 1;
    load();
  }));
  const head = ui.byId('exportTable').querySelector('thead');
  head.addEventListener('click', (event) => {
    const th = event.target.closest('th[data-sort]');
    if (th) {
      cycleSort(th.dataset.sort);
    }
  });
  head.addEventListener('keydown', (event) => {
    const th = event.target.closest('th[data-sort]');
    if (th && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      cycleSort(th.dataset.sort);
    }
  });
}

init();
