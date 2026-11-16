import { del, get, query } from '../api.js';
import * as ui from '../ui.js';
import { bindRemarks, remarkHtml } from '../remark.js';

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

/** Shared debounce; the page keeps its own default delay. */
const debounce = (fn, delay = 300) => ui.debounce(fn, delay);

/**
 * Compact CSV option summary.
 *
 * @param {object} options stored export options
 * @param {boolean} compact single line text for the dense list, chips otherwise
 */
function optionSummary(options, compact = false) {
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
  if (!parts.length) {
    return '<span class="muted">—</span>';
  }
  if (compact) {
    const text = parts.join(' · ');
    return `<span class="exp-opts" title="${ui.escapeHtml(text)}">${ui.escapeHtml(text)}</span>`;
  }
  return parts.map((text) => `<span class="chip muted">${ui.escapeHtml(text)}</span>`).join(' ');
}

const OVERRIDE_MODE_LABELS = { VALUE: '值', FORMULA: '公式', CLEAR: '清空' };

/** Overrides stored on the record, empty when the export used the stored data only. */
function overridesOf(item) {
  const list = item.options && item.options.overrides;
  return Array.isArray(list) ? list : [];
}

/** Marker with the overridden fields of one export; empty when there was no override. */
function overrideCell(overrides) {
  if (!overrides.length) {
    return '<span class="muted">—</span>';
  }
  const rows = overrides.map((override) => `
    <div class="exp-ov-row">
      <span class="chip info">${ui.escapeHtml(OVERRIDE_MODE_LABELS[override.mode] || override.mode)}</span>
      <span>${ui.escapeHtml(override.header || override.fieldKey)}</span>
      <span class="mono" title="${ui.escapeHtml(override.display || '')}">${ui.escapeHtml(override.display || '（空）')}</span>
    </div>`).join('');
  const names = overrides.map((override) => override.header || override.fieldKey).join('、');
  return `<details class="exp-ov">
      <summary><span class="badge warn exp-ov-pill" title="临时更新 ${overrides.length} 项（未写入用例），点击展开明细：${ui.escapeHtml(names)}">${overrides.length} 项</span></summary>
      ${rows}
    </details>`;
}

function rowHtml(item) {
  const overrides = overridesOf(item);
  const batchId = item.batchId || '';
  const batchExport = item.scopeType === 'BATCH';
  const scopeLabel = batchExport ? '整批' : '非整批';
  const scopeTitle = batchId
    ? `${batchExport ? '整批' : '非整批（按所选用例）'}导出：所属批次 ${batchId}，点击打开`
    : '非整批导出：按所选用例导出（历史记录或跨多个批次，未关联批次）';
  const scope = batchId
    ? `<a class="exp-scope" href="/batches/${encodeURIComponent(batchId)}" title="${ui.escapeHtml(scopeTitle)}">${scopeLabel}</a>`
    : `<span class="muted exp-scope" title="${ui.escapeHtml(scopeTitle)}">${scopeLabel}</span>`;
  const confirmAttrs = overrides.length
    ? ` data-ov-confirm="${ui.escapeHtml(overrides.map((o) => o.header || o.fieldKey).join('、'))}"`
    : '';
  const download = item.fileExists
    ? ui.iconLink('download', item.downloadUrl, '下载 CSV 文件', `sm${overrides.length ? ' warn' : ''}`, confirmAttrs)
    : `<span class="icon-btn sm" aria-disabled="true" title="文件已不在磁盘上">${ui.icon('download', 13)}</span>`;
  return `
    <tr data-export="${ui.escapeHtml(item.id)}">
      <td class="mono exp-time" title="${ui.escapeHtml(item.createdAt)}">${ui.escapeHtml(item.createdAt)}</td>
      <td class="mono" title="${ui.escapeHtml(item.fileName)}">
        <button class="link-btn mono exp-file" data-detail="${ui.escapeHtml(item.id)}" title="查看数据详情：${ui.escapeHtml(item.fileName)}">${ui.escapeHtml(item.fileName)}</button>
      </td>
      <td>${ui.escapeHtml(item.productCode || '')}</td>
      <td class="exp-scope-cell">${scope}</td>
      <td class="num">${item.rowCount}</td>
      <td class="num">${ui.bytes(item.fileSize)}</td>
      <td class="exp-opts-cell">${optionSummary(item.options, true)}</td>
      <td class="exp-ov-cell">${overrideCell(overrides)}</td>
      <td class="remark-col">${remarkHtml('export', item.id, item.note)}</td>
      <td>
        <div class="row-actions">
          ${ui.iconButton('eye', '查看数据详情', 'sm', `data-detail="${ui.escapeHtml(item.id)}"`)}
          ${download}
          ${ui.iconButton('trash', '删除导出记录', 'sm danger', `data-delete="${ui.escapeHtml(item.id)}"`)}
        </div>
      </td>
    </tr>`;
}

/**
 * Vertical view of one stored export row: every column on its own line.
 *
 * <p>Rows can be searched and switched without leaving the drawer, and the fields can be filtered
 * by header, value or emptiness.
 */
function openExportVerticalDrawer(item, startRow = 0) {
  const host = ui.byId('drawerHost') || document.body;
  const backdrop = document.createElement('div');
  backdrop.className = 'drawer-backdrop';
  backdrop.innerHTML = `
    <div class="drawer" role="dialog" aria-modal="true">
      <div class="drawer-head">
        <div>
          <h3>纵向查看</h3>
          <div class="mono-sm" id="vertSub">${ui.escapeHtml(item.fileName)}</div>
        </div>
        <div class="row-actions">
          <button class="tool-btn small" id="vertPrev">‹ 上一条</button>
          <button class="tool-btn small" id="vertNext">下一条 ›</button>
          <button class="tool-btn small" id="vertDetail">数据详情</button>
          <button class="modal-close" id="vertClose" aria-label="关闭">×</button>
        </div>
      </div>
      <div class="drawer-tools">
        <input type="search" id="vertRowSearch" placeholder="搜索行内容（跳转匹配行）…" style="flex:1 1 200px">
        <select id="vertRowSelect"></select>
        <span class="muted" id="vertRowCount"></span>
      </div>
      <div class="drawer-tools">
        <input type="search" id="vertFieldSearch" placeholder="搜索字段名 / 值…" style="flex:1 1 180px">
        <label class="muted" style="display:flex;gap:6px;align-items:center;font-size:12px">
          <input type="checkbox" id="vertOnlyEmpty"> 仅空值
        </label>
        <span class="muted" id="vertFieldCount"></span>
      </div>
      <div class="drawer-body" id="vertBody"></div>
    </div>`;
  host.appendChild(backdrop);

  const $ = (selector) => backdrop.querySelector(selector);
  const onKey = (event) => {
    if (event.key === 'Escape') {
      close();
    }
  };
  const close = () => {
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
  };
  document.addEventListener('keydown', onKey);
  $('#vertClose').addEventListener('click', close);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) {
      close();
    }
  });

  let preview = null;
  let limit = 200;
  let index = Math.max(0, startRow);
  let rowNeedle = '';

  /** Short preview of a row, used as the label of the row selector. */
  const rowLabel = (row, position) => {
    const firstValue = row.find((cell) => cell && cell.trim() !== '') || '';
    const text = firstValue.length > 28 ? `${firstValue.slice(0, 28)}…` : firstValue;
    return `#${position + 1}${text ? ` · ${text}` : ''}`;
  };

  const renderRowSelect = () => {
    const select = $('#vertRowSelect');
    const matches = preview.rows
      .map((row, position) => ({ row, position }))
      .filter((entry) => !rowNeedle
        || entry.row.some((cell) => (cell || '').toLowerCase().includes(rowNeedle)));
    select.innerHTML = matches.length
      ? matches.map((entry) => `<option value="${entry.position}">${ui.escapeHtml(rowLabel(entry.row, entry.position))}</option>`).join('')
      : '<option value="">没有匹配的行</option>';
    select.value = matches.some((entry) => entry.position === index)
      ? String(index)
      : (matches.length ? String(matches[0].position) : '');
    $('#vertRowCount').textContent = rowNeedle
      ? `匹配 ${matches.length} 行（已载入 ${preview.shownRows} / ${preview.rowCount} 行）`
      : `第 ${preview.rows.length ? index + 1 : 0} / ${preview.rowCount} 行`;
    $('#vertPrev').disabled = index <= 0;
    $('#vertNext').disabled = index >= preview.rowCount - 1;
  };

  const renderFields = () => {
    const body = $('#vertBody');
    if (!preview || !preview.rows.length) {
      body.innerHTML = '<div class="muted">该导出没有数据行。</div>';
      return;
    }
    const row = preview.rows[Math.min(index, preview.rows.length - 1)] || [];
    const needle = ($('#vertFieldSearch').value || '').trim().toLowerCase();
    const onlyEmpty = $('#vertOnlyEmpty').checked;
    let visible = 0;
    const rows = preview.headers.map((header, column) => {
      const value = row[column] === undefined ? '' : row[column];
      if (onlyEmpty && value !== '') {
        return '';
      }
      if (needle && !`${header} ${value}`.toLowerCase().includes(needle)) {
        return '';
      }
      visible += 1;
      return `<div class="vrow exp-vrow">
          <div class="vcol-no" title="第 ${column + 1} 列">${column + 1}</div>
          <div class="vkey">${ui.escapeHtml(header)}</div>
          <div class="vval ${value === '' ? 'blank' : ''}">${value === '' ? '—' : ui.escapeHtml(value)}</div>
        </div>`;
    });
    body.innerHTML = rows.join('') || '<div class="muted">没有匹配的字段。</div>';
    if (rows.some((html) => html !== '')) {
      body.innerHTML = `<div class="vrow exp-vrow exp-vrow-head">
          <div class="vcol-no">#</div>
          <div class="vkey">列名</div>
          <div class="vval">值</div>
        </div>` + body.innerHTML;
    }
    $('#vertFieldCount').textContent = `显示 ${visible} / ${preview.headers.length} 个字段`;
  };

  const renderAll = () => {
    $('#vertSub').textContent = `${item.fileName} · 第 ${preview.rows.length ? index + 1 : 0} / ${preview.rowCount} 行`;
    renderRowSelect();
    renderFields();
  };

  const loadPreview = async (nextLimit) => {
    limit = nextLimit;
    try {
      preview = await get(`/api/exports/${encodeURIComponent(item.id)}/preview?limit=${limit}`);
      // the requested row may sit beyond the first page: widen once before clamping
      if (index >= preview.rows.length && preview.truncated && limit < 500) {
        await loadPreview(500);
        return;
      }
      if (index >= preview.rows.length) {
        index = Math.max(0, preview.rows.length - 1);
      }
      renderAll();
    } catch (error) {
      ui.showError(error);
      $('#vertBody').innerHTML = '<div class="muted">无法读取导出文件。</div>';
    }
  };

  /** Moves to another row, loading more rows when the target is beyond the loaded window. */
  const goTo = async (target) => {
    if (target < 0) {
      return;
    }
    if (target >= preview.rows.length && preview.truncated && limit < 500) {
      await loadPreview(500);
    }
    index = Math.min(target, Math.max(0, preview.rows.length - 1));
    renderAll();
  };

  $('#vertPrev').addEventListener('click', () => goTo(index - 1));
  $('#vertNext').addEventListener('click', () => goTo(index + 1));
  $('#vertDetail').addEventListener('click', () => {
    close();
    openExportDrawer(item);
  });
  $('#vertRowSelect').addEventListener('change', (event) => {
    const target = Number(event.target.value);
    if (!Number.isNaN(target) && event.target.value !== '') {
      goTo(target);
    }
  });
  $('#vertRowSearch').addEventListener('input', (event) => {
    rowNeedle = (event.target.value || '').trim().toLowerCase();
    renderRowSelect();
    // jump to the first matching row so the vertical body follows the search
    const select = $('#vertRowSelect');
    if (rowNeedle && select.value !== '') {
      goTo(Number(select.value));
    }
  });
  ['vertFieldSearch', 'vertOnlyEmpty'].forEach((id) => {
    backdrop.querySelector(`#${id}`)
      .addEventListener(id === 'vertOnlyEmpty' ? 'change' : 'input', renderFields);
  });
  loadPreview(limit);
}

/** Drawer with the metadata and the stored CSV rows of one export record. */
function openExportDrawer(item) {  const host = ui.byId('drawerHost') || document.body;
  const backdrop = document.createElement('div');
  backdrop.className = 'drawer-backdrop';
  const overrides = overridesOf(item);
  const overrideBlock = overrides.length
    ? `<div class="exp-meta-item"><div class="k">临时更新</div><div class="v">${overrides.map((override) =>
      `<div class="exp-ov-row"><span class="chip info">${ui.escapeHtml(OVERRIDE_MODE_LABELS[override.mode] || override.mode)}</span>`
      + `<span>${ui.escapeHtml(override.header || override.fieldKey)}</span>`
      + `<span class="mono">${ui.escapeHtml(override.display || '（空）')}</span></div>`).join('')}</div></div>`
    : '';
  backdrop.innerHTML = `
    <div class="drawer wide" role="dialog" aria-modal="true">
      <div class="drawer-head">
        <div>
          <h3>导出详情</h3>
          <div class="mono-sm">${ui.escapeHtml(item.fileName)} · ${item.rowCount} 行 · ${ui.bytes(item.fileSize)}</div>
        </div>
        <div class="row-actions">
          ${item.fileExists
    ? ui.iconLink('download', item.downloadUrl, '下载 CSV 文件', `sm${overrides.length ? ' warn' : ''}`,
      overrides.length ? `data-ov-confirm="${ui.escapeHtml(overrides.map((o) => o.header || o.fieldKey).join('、'))}"` : '')
    : `<span class="icon-btn sm" aria-disabled="true" title="文件已不在磁盘上">${ui.icon('download', 13)}</span>`}
          <button class="modal-close" id="expDrawerClose" aria-label="关闭">×</button>
        </div>
      </div>
      <div class="drawer-body" id="expDrawerBody"></div>
    </div>`;
  host.appendChild(backdrop);
  const root = backdrop.querySelector('#expDrawerBody');
  const onKey = (event) => {
    if (event.key === 'Escape') {
      close();
    }
  };
  const close = () => {
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
  };
  document.addEventListener('keydown', onKey);
  backdrop.querySelector('#expDrawerClose').addEventListener('click', close);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) {
      close();
    }
  });

  let preview = null;
  let limit = 100;
  let needle = '';

  const metaHtml = `
    <div class="exp-meta">
      <div class="exp-meta-item"><div class="k">导出时间</div><div class="v mono">${ui.escapeHtml(item.createdAt)}</div></div>
      <div class="exp-meta-item"><div class="k">导出 ID</div><div class="v mono">${ui.escapeHtml(item.id)}</div></div>
      <div class="exp-meta-item"><div class="k">产品</div><div class="v">${ui.escapeHtml(item.productCode || '—')}</div></div>
      <div class="exp-meta-item"><div class="k">范围</div><div class="v">${item.scopeType === 'BATCH' && item.batchId
    ? `<a class="mono" href="/batches/${encodeURIComponent(item.batchId)}">批次 ${ui.escapeHtml(ui.shortId(item.batchId, 8))}</a>`
    : '按用例选择'}</div></div>
      <div class="exp-meta-item"><div class="k">行数 / 大小</div><div class="v">${item.rowCount} 行 · ${ui.bytes(item.fileSize)}</div></div>
      <div class="exp-meta-item"><div class="k">状态</div><div class="v">${ui.statusBadge(item.status)}</div></div>
      <div class="exp-meta-item"><div class="k">CSV 选项</div><div class="v">${optionSummary(item.options)}</div></div>
      ${overrideBlock}
    </div>
    <div class="drawer-tools" style="border-radius:8px;border:1px solid var(--border);margin:0 0 10px">
      <input type="search" id="expPreviewSearch" placeholder="搜索单元格内容…" style="flex:1 1 200px">
      <span class="muted" id="expPreviewInfo"></span>
      <div class="spacer"></div>
      <button class="tool-btn small" id="expPreviewMore" hidden>显示更多行</button>
    </div>
    <div id="expPreviewTable"></div>`;

  const renderTable = () => {
    if (!preview) {
      return;
    }
    const table = root.querySelector('#expPreviewTable');
    const info = root.querySelector('#expPreviewInfo');
    const more = root.querySelector('#expPreviewMore');
    const matches = (row) => !needle
      || row.some((cell) => (cell || '').toLowerCase().includes(needle));
    const visible = preview.rows
      .map((row, position) => ({ row, position }))
      .filter((entry) => matches(entry.row));
    const header = `<tr><th class="num">#</th><th class="icon-cell" data-no-resize="true">查看</th>${preview.headers
      .map((value) => `<th>${ui.escapeHtml(value)}</th>`).join('')}</tr>`;
    const body = visible.map((entry) => `
      <tr><td class="num rowno">${entry.position + 1}</td>
        <td class="icon-cell">
          <button class="icon-btn sm" data-vertical-row="${entry.position}"
            title="纵向查看该行（字段一行一条）" aria-label="纵向查看">
            <svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                 stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M1.5 8s2.4-4.2 6.5-4.2S14.5 8 14.5 8s-2.4 4.2-6.5 4.2S1.5 8 1.5 8Z"></path>
              <circle cx="8" cy="8" r="1.9"></circle>
            </svg>
          </button>
        </td>
        ${entry.row
    .map((cell) => `<td class="mono" title="${ui.escapeHtml(cell || '')}">${cell === '' ? '—' : ui.escapeHtml(cell)}</td>`)
    .join('')}</tr>`).join('');
    table.innerHTML = `<div class="table-wrap" style="max-height:calc(100vh - 360px)">
        <table class="exp-preview-table"><thead>${header}</thead><tbody>${body
    || `<tr><td colspan="${preview.headers.length + 2}" class="empty">没有匹配的行</td></tr>`}</tbody></table>
      </div>`;
    table.querySelectorAll('button[data-vertical-row]').forEach((button) => {
      button.addEventListener('click', () => {
        // the vertical view replaces the table view so only one drawer is on screen
        openExportVerticalDrawer(item, Number(button.dataset.verticalRow));
        close();
      });
    });
    const shown = visible.length;
    info.textContent = needle
      ? `匹配 ${shown} 行（已载入 ${preview.shownRows} / ${preview.rowCount} 行）`
      : `已载入 ${preview.shownRows} / ${preview.rowCount} 行`;
    more.hidden = preview.shownRows >= preview.rowCount;
  };

  const loadPreview = async (nextLimit) => {
    limit = nextLimit;
    try {
      preview = await get(`/api/exports/${encodeURIComponent(item.id)}/preview?limit=${limit}`);
      renderTable();
    } catch (error) {
      ui.showError(error);
      root.querySelector('#expPreviewTable').innerHTML = '<div class="muted">无法读取导出文件。</div>';
    }
  };

  root.innerHTML = metaHtml;
  root.querySelector('#expPreviewSearch').addEventListener('input', (event) => {
    needle = (event.target.value || '').trim().toLowerCase();
    renderTable();
  });
  root.querySelector('#expPreviewMore').addEventListener('click', () => loadPreview(Math.min(limit + 200, 500)));
  const downloadLink = backdrop.querySelector('a[data-ov-confirm]');
  if (downloadLink) {
    downloadLink.addEventListener('click', (event) => {
      const fields = downloadLink.dataset.ovConfirm || '';
      if (!window.confirm(`该导出包含临时更新（${fields}）：这些字段的值只写进了 CSV，没有写入用例。仍要下载吗？`)) {
        event.preventDefault();
      }
    });
  }
  loadPreview(limit);
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
  const order = ui.sequence();
  const token = order.next();
  ui.markLoading('exportRows', 10);
  try {
    const result = await get('/api/exports' + query({
      q: state.q, product: state.product, from: state.from, to: state.to, sort: state.sort,
      page: state.page, size: state.size,
    }));
    if (!token()) {
      return;
    }
    state.page = result.page;
    const rows = result.items.map(rowHtml);
    ui.setHtml('exportRows', rows.length ? rows.join('')
      : '<tr><td colspan="10" class="empty">还没有导出记录：在批次详情页导出后会出现在这里</td></tr>');
    bindRemarks(ui.byId('exportRows'));
    ui.setHtml('exportTotal', `共 ${result.total} 条`);
    ui.byId('exportRows').querySelectorAll('button[data-delete]').forEach((button) => {
      button.addEventListener('click', () => remove(button.dataset.delete));
    });
    ui.byId('exportRows').querySelectorAll('[data-detail]').forEach((button) => {
      button.addEventListener('click', () => {
        const item = result.items.find((entry) => entry.id === button.dataset.detail);
        if (item) {
          openExportDrawer(item);
        }
      });
    });
    ui.byId('exportRows').querySelectorAll('a[data-ov-confirm]').forEach((link) => {
      link.addEventListener('click', (event) => {
        const fields = link.dataset.ovConfirm || '';
        const ok = window.confirm(`该导出包含临时更新（${fields}）：这些字段的值只写进了 CSV，没有写入用例。仍要下载吗？`);
        if (!ok) {
          event.preventDefault();
        }
      });
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
  const row = Array.from(ui.byId('exportRows').querySelectorAll('tr[data-export]'))
    .find((item) => item.dataset.export === focus);
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
