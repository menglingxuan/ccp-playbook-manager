import { del, get, patch, query } from '../api.js';
import * as ui from '../ui.js';

/*
 * Batch console: dock (recent/pinned batches) -> drawer (batch management) -> sidebar (cases) ->
 * main table (field rows of the selected case). The task -> batch detail page stays untouched.
 */

const STORE_KEY = 'otc.console.v1';
const CASE_PAGE_SIZE = 30;
const DEFAULT_SORT = 'created_desc';

const state = {
  panel: {
    open: false,
    favoritesOpen: false,
    selectedId: null,
    side: 'left',
    width: 380,
    q: '',
    product: '',
    kind: '',
    tag: '',
    favorite: false,
    pinned: false,
    from: '',
    to: '',
    includeDeleted: false,
    sort: DEFAULT_SORT,
    page: 1,
    size: 20,
    items: [],
    total: 0,
    totalPages: 0,
    facets: null,
    dock: [],
  },
  cases: {
    batchId: null,
    q: '',
    sort: 'row',
    hasFormula: '',
    page: 1,
    items: [],
    total: 0,
    summary: { total: 0, withFormula: 0 },
    loading: false,
    collapsed: false,
  },
  detail: {
    caseId: null,
    vertical: null,
    col: { header: '', value: '', type: '', formula: false },
    locked: false,
    sort: { key: null, dir: 0 },
  },
};

const TYPE_LABEL = {
  TEXT: '文本',
  DATE: '日期',
  DECIMAL: '数值',
  INTEGER: '整数',
  BOOLEAN: '布尔',
};

const KIND_LABEL = {
  IMPORT: '文件导入',
  CLONE: '克隆',
  EXTERNAL_OUTPUT: '外部产出',
};

const SORT_LABEL = {
  created_desc: '创建时间 ↓',
  created_asc: '创建时间 ↑',
  name_asc: '文件名 ↑',
  name_desc: '文件名 ↓',
  rows_desc: '行数 ↓',
  rows_asc: '行数 ↑',
  invalid_desc: '无效行 ↓',
  product_asc: '产品 ↑',
  product_desc: '产品 ↓',
};

const KEY_HELP = [
  ['/', '聚焦搜索框'],
  ['G', '全局搜索'],
  ['B', '批次管理面板'],
  ['F', '收藏夹'],
  ['J / K', '下一条 / 上一条用例'],
  ['Enter', '进入批次详情页'],
  ['Space', '收藏当前批次'],
  ['L', '锁定字段筛选'],
  ['Esc', '关闭面板 / 清空筛选'],
];

function escapeHtml(value) {
  return ui.escapeHtml(value);
}

function debounce(fn, delay = 280) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

// ------------------------------------------------------------------ preferences

function loadPrefs() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}');
    if (saved.sort) {
      state.panel.sort = saved.sort;
    }
    if (saved.size) {
      state.panel.size = saved.size;
    }
    if (saved.batchId) {
      state.panel.selectedId = saved.batchId;
    }
    if (saved.side === 'right' || saved.side === 'left') {
      state.panel.side = saved.side;
    }
    if (saved.panelWidth) {
      state.panel.width = Math.max(300, Math.min(680, Number(saved.panelWidth) || 380));
    }
    state.cases.collapsed = saved.collapsed === true;
    state.detail.locked = saved.locked === true;
  } catch (ignored) {
    // a broken preference must never block the page
  }
}

function savePrefs() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify({
      sort: state.panel.sort,
      size: state.panel.size,
      batchId: state.cases.batchId,
      side: state.panel.side,
      panelWidth: state.panel.width,
      collapsed: state.cases.collapsed,
      locked: state.detail.locked,
    }));
  } catch (ignored) {
    // storage may be full or disabled
  }
}

// ------------------------------------------------------------------ data access

function batchQuery(extra = {}) {
  const p = state.panel;
  return query({
    q: p.q,
    product: p.product,
    kind: p.kind,
    tag: p.tag,
    favorite: p.favorite ? 'true' : '',
    pinned: p.pinned ? 'true' : '',
    from: p.from,
    to: p.to,
    includeDeleted: p.includeDeleted ? 'true' : '',
    sort: p.sort,
    ...extra,
  });
}

async function loadDock() {
  try {
    state.panel.dock = await get('/api/batches/dock?limit=14');
  } catch (error) {
    ui.showError(error);
  }
  renderDock();
}

async function loadFacets() {
  try {
    state.panel.facets = await get('/api/batches/facets' + batchQuery());
  } catch (error) {
    state.panel.facets = null;
  }
}

async function loadBatches() {
  try {
    const result = await get('/api/batches' + batchQuery({
      page: state.panel.page, size: state.panel.size,
    }));
    state.panel.items = result.items;
    state.panel.total = result.total;
    state.panel.totalPages = result.totalPages;
  } catch (error) {
    ui.showError(error);
    state.panel.items = [];
  }
  renderPanel();
  renderFavorites();
}

async function loadCases(reset = true) {
  const cases = state.cases;
  if (!cases.batchId || cases.loading) {
    return;
  }
  cases.loading = true;
  try {
    const page = reset ? 1 : cases.page + 1;
    const result = await get(`/api/batches/${encodeURIComponent(cases.batchId)}/cases` + query({
      q: cases.q, sort: cases.sort, hasFormula: cases.hasFormula, page, size: CASE_PAGE_SIZE,
    }));
    cases.page = page;
    cases.items = reset ? result.items : cases.items.concat(result.items);
    cases.total = result.total;
    cases.summary = result.summary || cases.summary;
    if (reset) {
      const keep = cases.items.some((item) => item.id === state.detail.caseId);
      if (!keep) {
        state.detail.caseId = cases.items.length ? cases.items[0].id : null;
        state.detail.vertical = null;
      }
    }
  } catch (error) {
    ui.showError(error);
    if (reset) {
      cases.items = [];
      cases.total = 0;
      if (error.status === 404) {
        // the remembered batch was deleted meanwhile: fall back to the newest one
        cases.batchId = null;
        state.detail.caseId = null;
        state.detail.vertical = null;
        savePrefs();
      }
    }
  } finally {
    cases.loading = false;
  }
  renderSidebar();
  if (!cases.batchId) {
    const fallback = (state.panel.items[0] && state.panel.items[0].id)
      || (state.panel.dock[0] && state.panel.dock[0].id);
    if (fallback) {
      await selectBatch(fallback);
      return;
    }
  }
  if (!state.detail.vertical && state.detail.caseId) {
    await loadVertical();
  } else {
    renderDetail();
  }
}

async function loadVertical() {
  if (!state.detail.caseId) {
    state.detail.vertical = null;
    renderDetail();
    return;
  }
  try {
    state.detail.vertical = await get(`/api/cases/${encodeURIComponent(state.detail.caseId)}/vertical`);
  } catch (error) {
    ui.showError(error);
    state.detail.vertical = null;
  }
  renderDetail();
}

// ------------------------------------------------------------------ dock

function shortLabel(batch) {
  const stamp = String(batch.createdAt || '');
  const month = stamp.slice(5, 7);
  const day = stamp.slice(8, 10);
  return { month, day };
}

function renderDock() {
  const dock = ui.byId('batchDock');
  if (!dock) {
    return;
  }
  const chips = state.panel.dock.map((batch) => {
    const active = batch.id === state.cases.batchId;
    const label = `${batch.productCode} · ${batch.createdAt}`
      + (batch.tags && batch.tags.length ? ` · ${batch.tags.join(', ')}` : '')
      + (batch.note ? ` · ${batch.note}` : '');
    const { month, day } = shortLabel(batch);
    const marks = [];
    if (batch.pinned) {
      marks.push('📌');
    }
    if (batch.favorite) {
      marks.push('★');
    }
    return `<span class="bd-slot">
      <button class="bd-chip${active ? ' active' : ''}" data-batch="${escapeHtml(batch.id)}"
              title="${escapeHtml(label)}" aria-label="${escapeHtml(label)}">
        <span class="bd-mm">${escapeHtml(month)}</span><span class="bd-dd">${escapeHtml(day)}</span>
      </button>
      ${marks.length ? `<span class="bd-badge" aria-hidden="true">${marks.join('')}</span>` : ''}
    </span>`;
  });
  dock.innerHTML = `
    <button class="bd-btn${state.panel.open ? ' on' : ''}" id="dockToggle"
            title="批次管理（B）" aria-label="批次管理">☰</button>
    <button class="bd-btn${state.panel.favoritesOpen ? ' on' : ''}" id="dockFavorites"
            title="收藏夹（F）" aria-label="收藏夹">★</button>
    <div class="bd-sep"></div>
    <div class="bd-recent">${chips.join('') || '<span class="bd-empty">没有批次</span>'}</div>
    <div class="bd-sep"></div>
    <span class="bd-count" title="当前筛选下的批次数">${state.panel.total || state.panel.dock.length}</span>
    <button class="bd-btn" id="dockFlip" title="停靠到${state.panel.side === 'left' ? '右' : '左'}侧"
            aria-label="切换停靠侧">⇄</button>
    <button class="bd-btn" id="dockRefresh" title="刷新批次数据" aria-label="刷新">↻</button>`;
  dock.querySelectorAll('button[data-batch]').forEach((button) => {
    button.addEventListener('click', () => selectBatch(button.dataset.batch));
  });
  ui.byId('dockToggle').addEventListener('click', () => togglePanel());
  ui.byId('dockFavorites').addEventListener('click', () => toggleFavorites());
  ui.byId('dockFlip').addEventListener('click', () => flipSide());
  ui.byId('dockRefresh').addEventListener('click', () => refreshAll());
}

/** Moves the dock and the drawers to the other side of the viewport. */
function flipSide() {
  state.panel.side = state.panel.side === 'left' ? 'right' : 'left';
  applySide();
  savePrefs();
  renderDock();
  ui.toast(`已停靠到${state.panel.side === 'left' ? '左' : '右'}侧`, 'ok');
}

function applySide() {
  // the class lives on <html>: the theme switcher rewrites body.className wholesale
  document.documentElement.classList.toggle('batch-side-right', state.panel.side === 'right');
}

function applyPanelWidth() {
  document.documentElement.style.setProperty('--batch-w', `${state.panel.width}px`);
}

/** Drags the drawer's inner edge to change its width. */
function bindPanelResize(panel) {
  const grip = panel.querySelector('.bp-resize');
  if (!grip) {
    return;
  }
  grip.addEventListener('mousedown', (event) => {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = panel.getBoundingClientRect().width;
    const sign = state.panel.side === 'left' ? 1 : -1;
    grip.classList.add('active');
    const onMove = (moveEvent) => {
      const width = Math.max(300, Math.min(680, Math.round(startWidth + (moveEvent.clientX - startX) * sign)));
      state.panel.width = width;
      applyPanelWidth();
    };
    const onUp = () => {
      grip.classList.remove('active');
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      savePrefs();
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  });
}

// ------------------------------------------------------------------ batch drawer

function togglePanel(force) {
  state.panel.open = force === undefined ? !state.panel.open : force;
  if (state.panel.open) {
    state.panel.favoritesOpen = false;
  }
  savePrefs();
  renderPanel();
  renderFavorites();
  renderDock();
}

function toggleFavorites(force) {
  state.panel.favoritesOpen = force === undefined ? !state.panel.favoritesOpen : force;
  if (state.panel.favoritesOpen) {
    state.panel.open = false;
  }
  renderPanel();
  renderDock();
  renderFavorites();
}

function activeFilterChips() {
  const p = state.panel;
  const chips = [];
  if (p.q) {
    chips.push({ key: 'q', label: `搜索：${p.q}` });
  }
  if (p.product) {
    chips.push({ key: 'product', label: `产品：${p.product}` });
  }
  if (p.kind) {
    chips.push({ key: 'kind', label: `来源：${KIND_LABEL[p.kind] || p.kind}` });
  }
  if (p.tag) {
    chips.push({ key: 'tag', label: `标签：${p.tag}` });
  }
  if (p.favorite) {
    chips.push({ key: 'favorite', label: '仅收藏' });
  }
  if (p.pinned) {
    chips.push({ key: 'pinned', label: '仅置顶' });
  }
  if (p.includeDeleted) {
    chips.push({ key: 'includeDeleted', label: '含已删除' });
  }
  if (p.from || p.to) {
    chips.push({ key: 'range', label: `日期：${p.from || '…'} ~ ${p.to || '…'}` });
  }
  return chips;
}

function optionHtml(value, label, current) {
  return `<option value="${escapeHtml(value)}"${value === current ? ' selected' : ''}>${escapeHtml(label)}</option>`;
}

function batchItemHtml(batch) {
  const active = batch.id === state.cases.batchId;
  const tags = (batch.tags || [])
    .map((tag) => `<span class="bp-tag" data-tag="${escapeHtml(tag)}">${escapeHtml(tag)}</span>`).join('');
  const note = batch.note
    ? `<div class="bp-note" title="${escapeHtml(batch.note)}">${escapeHtml(batch.note)}</div>`
    : '';
  return `
    <div class="bp-item${active ? ' active' : ''}" data-batch="${escapeHtml(batch.id)}">
      <div class="bp-item-head">
        <button class="bp-open" data-open="${escapeHtml(batch.id)}"
                title="加载该批次的用例与字段">${escapeHtml(batch.sourceFilename)}</button>
        <span class="bp-kind">${escapeHtml(KIND_LABEL[batch.kind] || batch.kind)}</span>
        <span class="bp-product">${escapeHtml(batch.productCode)}</span>
        ${ui.statusBadge(batch.status)}
      </div>
      <div class="bp-meta">
        <span title="已导入行数 / 无效行">${batch.totalRows} 行${batch.invalidRows ? ` · ${batch.invalidRows} 无效` : ''}</span>
        <span class="mono">${escapeHtml(batch.createdAt)}</span>
        ${batch.sheetName ? `<span class="bp-sheet" title="Sheet">${escapeHtml(batch.sheetName)}</span>` : ''}
      </div>
      ${tags || note ? `<div class="bp-tags">${tags}${note}</div>` : ''}
      <div class="bp-actions">
        <button class="bp-act${batch.favorite ? ' on' : ''}" data-favorite="${escapeHtml(batch.id)}"
                title="${batch.favorite ? '取消收藏' : '收藏'}">★</button>
        <button class="bp-act${batch.pinned ? ' on' : ''}" data-pin="${escapeHtml(batch.id)}"
                title="${batch.pinned ? '取消置顶' : '置顶'}">📌</button>
        <button class="bp-act" data-meta="${escapeHtml(batch.id)}" title="编辑备注与标签">✎</button>
        <a class="bp-act" href="/batches/${encodeURIComponent(batch.id)}" title="进入批次详情页">↗</a>
        <button class="bp-act danger" data-delete="${escapeHtml(batch.id)}" title="删除批次">🗑</button>
      </div>
    </div>`;
}

function bindInput(id, apply) {
  const node = ui.byId(id);
  if (!node) {
    return;
  }
  const run = debounce(() => {
    apply(node.value);
    reloadList();
  }, 300);
  node.addEventListener('input', run);
  node.addEventListener('change', () => {
    apply(node.value);
    reloadList();
  });
}

function bindChange(id, apply) {
  const node = ui.byId(id);
  if (!node) {
    return;
  }
  node.addEventListener('change', () => {
    apply(node.value);
    reloadList();
  });
}

function clearFilter(key) {
  const p = state.panel;
  if (key === 'range') {
    p.from = '';
    p.to = '';
  } else if (key === 'favorite' || key === 'pinned' || key === 'includeDeleted') {
    p[key] = false;
  } else {
    p[key] = '';
  }
  reloadList();
}

async function reloadList() {
  state.panel.page = 1;
  await loadFacets();
  await loadBatches();
  renderDock();
}

function renderPanel() {
  const p = state.panel;
  const panel = ui.byId('batchPanel');
  if (!panel) {
    return;
  }
  panel.hidden = !p.open;
  if (!p.open) {
    panel.innerHTML = '';
    return;
  }
  const facets = p.facets;
  const productOptions = ['<option value="">全部产品</option>']
    .concat((facets?.products || []).map((item) => optionHtml(item.value, `${item.value} (${item.count})`, p.product)))
    .join('');
  const tagOptions = ['<option value="">全部标签</option>']
    .concat((facets?.tags || []).map((item) => optionHtml(item.value, `${item.value} (${item.count})`, p.tag)))
    .join('');
  const kindOptions = ['<option value="">全部来源</option>']
    .concat(['IMPORT', 'CLONE'].map((code) => optionHtml(code, KIND_LABEL[code], p.kind)))
    .join('');
  const sortOptions = Object.entries(SORT_LABEL)
    .map(([value, label]) => `<option value="${value}"${p.sort === value ? ' selected' : ''}>${escapeHtml(label)}</option>`)
    .join('');
  const rows = p.items.map(batchItemHtml).join('');
  panel.innerHTML = `
    <div class="bp-head">
      <span class="bp-title">批次管理</span>
      <span class="bp-count">${p.total}</span>
      <span class="bp-head-right">
        <button class="bp-icon" id="bpFlip" title="停靠到${p.side === 'left' ? '右' : '左'}侧"
                aria-label="切换停靠侧">⇄</button>
        <button class="bp-icon" id="bpClose" title="关闭（Esc）" aria-label="关闭">✕</button>
      </span>
    </div>
    <div class="bp-resize" id="bpResize" title="拖动调整宽度"></div>
    <div class="bp-controls">
      <input type="search" id="bpSearch" placeholder="搜索文件名 / Sheet / 备注 / 标签 / 产品…"
             value="${escapeHtml(p.q)}">
      <div class="bp-row">
        <select id="bpProduct" aria-label="产品">${productOptions}</select>
        <select id="bpKind" aria-label="来源">${kindOptions}</select>
      </div>
      <div class="bp-row">
        <select id="bpTag" aria-label="标签">${tagOptions}</select>
        <select id="bpSort" aria-label="排序">${sortOptions}</select>
      </div>
      <div class="bp-row">
        <label class="bp-date">创建日期
          <input type="date" id="bpFrom" value="${escapeHtml(p.from)}" aria-label="创建日期起">
          <span class="muted">–</span>
          <input type="date" id="bpTo" value="${escapeHtml(p.to)}" aria-label="创建日期止">
        </label>
      </div>
      <div class="bp-toggles">
        <button class="bp-toggle${p.favorite ? ' on' : ''}" id="bpFavorite"
                title="只看收藏">★ 收藏 (${facets?.favorite ?? 0})</button>
        <button class="bp-toggle${p.pinned ? ' on' : ''}" id="bpPinned"
                title="只看置顶">📌 置顶 (${facets?.pinned ?? 0})</button>
        <button class="bp-toggle${p.includeDeleted ? ' on' : ''}" id="bpDeleted"
                title="包含软删除的批次">已删除</button>
      </div>
    </div>
    <div class="bp-chips">${activeFilterChips().map((chip) => `
      <span class="bp-chip" data-clear="${chip.key}">${escapeHtml(chip.label)}<span class="bp-chip-x">✕</span></span>`).join('')}
    </div>
    <div class="bp-list" id="bpList">${rows || '<div class="bp-empty">没有匹配的批次</div>'}</div>
    <div class="bp-foot">
      <div class="pagination" id="bpPager"></div>
    </div>`;

  ui.byId('bpClose').addEventListener('click', () => togglePanel(false));
  ui.byId('bpFlip').addEventListener('click', () => flipSide());
  bindPanelResize(panel);
  bindInput('bpSearch', (value) => { p.q = value; });
  bindChange('bpProduct', (value) => { p.product = value; });
  bindChange('bpKind', (value) => { p.kind = value; });
  bindChange('bpTag', (value) => { p.tag = value; });
  bindChange('bpSort', (value) => { p.sort = value; savePrefs(); });
  bindChange('bpFrom', (value) => { p.from = value; });
  bindChange('bpTo', (value) => { p.to = value; });
  ui.byId('bpFavorite').addEventListener('click', () => {
    p.favorite = !p.favorite;
    reloadList();
  });
  ui.byId('bpPinned').addEventListener('click', () => {
    p.pinned = !p.pinned;
    reloadList();
  });
  ui.byId('bpDeleted').addEventListener('click', () => {
    p.includeDeleted = !p.includeDeleted;
    reloadList();
  });
  panel.querySelectorAll('[data-clear]').forEach((chip) => {
    chip.addEventListener('click', () => clearFilter(chip.dataset.clear));
  });
  bindBatchRows(panel);
  ui.renderPager('bpPager', p.page, p.totalPages, p.total, (page) => {
    p.page = page;
    loadBatches();
  });
}

function bindBatchRows(root) {
  root.querySelectorAll('[data-open]').forEach((node) => {
    node.addEventListener('click', (event) => {
      if (event.metaKey || event.ctrlKey) {
        return;
      }
      event.preventDefault();
      selectBatch(node.dataset.open);
    });
  });
  root.querySelectorAll('[data-favorite]').forEach((node) => {
    node.addEventListener('click', () => toggleFlag(node.dataset.favorite, 'favorite'));
  });
  root.querySelectorAll('[data-pin]').forEach((node) => {
    node.addEventListener('click', () => toggleFlag(node.dataset.pin, 'pinned'));
  });
  root.querySelectorAll('[data-meta]').forEach((node) => {
    node.addEventListener('click', () => openMetaEditor(node.dataset.meta));
  });
  root.querySelectorAll('[data-delete]').forEach((node) => {
    node.addEventListener('click', () => removeBatch(node.dataset.delete));
  });
  root.querySelectorAll('.bp-tag').forEach((node) => {
    node.addEventListener('click', () => {
      state.panel.tag = node.dataset.tag;
      reloadList();
    });
  });
}

function findBatch(batchId) {
  return state.panel.dock.find((item) => item.id === batchId)
    || state.panel.items.find((item) => item.id === batchId);
}

async function toggleFlag(batchId, field) {
  const batch = findBatch(batchId);
  if (!batch) {
    return;
  }
  try {
    const updated = await patch(`/api/batches/${encodeURIComponent(batchId)}/meta`,
      { [field]: !batch[field] });
    Object.assign(batch, { favorite: updated.favorite, pinned: updated.pinned, tags: updated.tags });
    ui.toast(field === 'favorite'
      ? (updated.favorite ? '已收藏' : '已取消收藏')
      : (updated.pinned ? '已置顶' : '已取消置顶'), 'ok');
    await loadDock();
    await reloadList();
    renderDetail();
  } catch (error) {
    ui.showError(error);
  }
}

function openMetaEditor(batchId) {
  const batch = findBatch(batchId) || { id: batchId, note: '', tags: [] };
  const modal = ui.openModal('编辑批次信息', `
    <label class="field"><span>备注（≤500 字符）</span>
      <textarea id="metaNote" rows="3" maxlength="500">${escapeHtml(batch.note || '')}</textarea>
    </label>
    <label class="field"><span>标签（逗号分隔，≤10 个，每个 ≤32 字符）</span>
      <input type="text" id="metaTags" value="${escapeHtml((batch.tags || []).join(', '))}">
    </label>`, `
    <button class="tool-btn" data-act="cancel">取消</button>
    <button class="tool-btn primary" data-act="save">保存</button>`);
  modal.root.querySelector('[data-act="cancel"]').addEventListener('click', () => modal.close());
  modal.root.querySelector('[data-act="save"]').addEventListener('click', async () => {
    const note = modal.root.querySelector('#metaNote').value;
    const tags = modal.root.querySelector('#metaTags').value
      .split(',').map((tag) => tag.trim()).filter(Boolean);
    try {
      await patch(`/api/batches/${encodeURIComponent(batchId)}/meta`, { note, tags });
      modal.close();
      ui.toast('批次信息已更新', 'ok');
      await loadDock();
      await reloadList();
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function removeBatch(batchId) {
  const batch = findBatch(batchId);
  const label = batch ? batch.sourceFilename : batchId;
  if (!window.confirm(`删除批次「${label}」？其用例会一并软删除。`)) {
    return;
  }
  try {
    await del(`/api/batches/${encodeURIComponent(batchId)}`);
    ui.toast('批次已删除', 'ok');
    if (state.cases.batchId === batchId) {
      state.cases.batchId = null;
      state.cases.items = [];
      state.cases.total = 0;
      state.detail.caseId = null;
      state.detail.vertical = null;
      renderSidebar();
      renderDetail();
    }
    await loadDock();
    await reloadList();
  } catch (error) {
    ui.showError(error);
  }
}

function renderFavorites() {
  const panel = ui.byId('favoritesPanel');
  if (!panel) {
    return;
  }
  panel.hidden = !state.panel.favoritesOpen;
  if (!state.panel.favoritesOpen) {
    panel.innerHTML = '';
    return;
  }
  const favorites = state.panel.dock.filter((batch) => batch.favorite);
  panel.innerHTML = `
    <div class="bp-head">
      <span class="bp-title">收藏夹</span>
      <span class="bp-count">${favorites.length}</span>
      <span class="bp-head-right">
        <button class="bp-icon" id="favClose" title="关闭（Esc）" aria-label="关闭">✕</button>
      </span>
    </div>
    <div class="bp-list">${favorites.map(batchItemHtml).join('')
      || '<div class="bp-empty">还没有收藏的批次：在列表里点 ★</div>'}</div>`;
  ui.byId('favClose').addEventListener('click', () => toggleFavorites(false));
  bindBatchRows(panel);
}

// ------------------------------------------------------------------ sidebar (cases)

function renderSidebar() {
  const cases = state.cases;
  const side = ui.byId('consoleSide');
  if (!side) {
    return;
  }
  side.classList.toggle('collapsed', cases.collapsed);
  const expand = ui.byId('sideExpand');
  if (expand) {
    expand.hidden = !cases.collapsed;
  }
  ui.setHtml('caseCount', String(cases.total));
  const summary = cases.summary || { total: 0, withFormula: 0 };
  ui.setHtml('caseSummary', cases.batchId
    ? `<span>共 ${summary.total} 条</span><span>含公式 ${summary.withFormula} 条</span>`
      + (cases.q ? `<span>筛选：${escapeHtml(cases.q)}</span>` : '')
    : '<span>未选择批次</span>');
  const nav = ui.byId('caseNav');
  if (nav) {
    nav.innerHTML = `
      <button class="side-btn" id="casePrev" title="上一条（K）" aria-label="上一条">↑</button>
      <button class="side-btn" id="caseNext" title="下一条（J）" aria-label="下一条">↓</button>`;
    ui.byId('casePrev').addEventListener('click', () => moveCase(-1));
    ui.byId('caseNext').addEventListener('click', () => moveCase(1));
  }
  const rows = cases.items.map((item) => {
    const active = item.id === state.detail.caseId;
    return `
      <button class="cs-item${active ? ' active' : ''}" data-case="${escapeHtml(item.id)}">
        <span class="cs-id">${escapeHtml(item.testCaseId || ui.shortId(item.id))}</span>
        ${item.formulaCount ? '<span class="cs-formula" title="含公式">ƒ</span>' : ''}
        <span class="cs-key mono" title="${escapeHtml(item.keyValue || '')}">${escapeHtml(item.keyValue || '—')}</span>
        <span class="cs-row">#${item.rowNo}</span>
      </button>`;
  });
  ui.setHtml('caseList', rows.join('') || `<div class="cs-empty">${
    cases.batchId ? '没有匹配的用例' : '从上方 dock 或批次面板选择一个批次'}</div>`);
  ui.byId('caseList').querySelectorAll('button[data-case]').forEach((button) => {
    button.addEventListener('click', () => selectCase(button.dataset.case));
  });
  const foot = ui.byId('caseFoot');
  if (foot) {
    foot.textContent = cases.items.length ? `已加载 ${cases.items.length} / ${cases.total}` : '';
  }
}

function currentCaseIndex() {
  return state.cases.items.findIndex((item) => item.id === state.detail.caseId);
}

function moveCase(delta) {
  const items = state.cases.items;
  if (!items.length) {
    return;
  }
  const next = currentCaseIndex() + delta;
  if (next < 0) {
    return;
  }
  if (next >= items.length) {
    if (items.length < state.cases.total) {
      loadCases(false).then(() => {
        const extended = state.cases.items;
        if (extended[next]) {
          selectCase(extended[next].id);
        }
      });
    }
    return;
  }
  selectCase(items[next].id);
}

function bindSidebar() {
  ui.byId('sideCollapse').addEventListener('click', () => {
    state.cases.collapsed = true;
    savePrefs();
    renderSidebar();
  });
  ui.byId('sideExpand').addEventListener('click', () => {
    state.cases.collapsed = false;
    savePrefs();
    renderSidebar();
  });
  ui.byId('caseSearch').addEventListener('input', debounce((event) => {
    state.cases.q = event.target.value;
    loadCases(true);
  }, 300));
  ui.byId('caseSort').addEventListener('change', (event) => {
    state.cases.sort = event.target.value;
    loadCases(true);
  });
  ui.byId('caseFormula').addEventListener('change', (event) => {
    state.cases.hasFormula = event.target.value;
    loadCases(true);
  });
  const list = ui.byId('caseList');
  list.addEventListener('scroll', () => {
    if (list.scrollTop + list.clientHeight >= list.scrollHeight - 48
      && state.cases.items.length < state.cases.total && !state.cases.loading) {
      loadCases(false);
    }
  });
}

// ------------------------------------------------------------------ selection

async function selectBatch(batchId, options = {}) {
  if (!batchId) {
    return;
  }
  state.cases.batchId = batchId;
  state.cases.page = 1;
  state.cases.items = [];
  state.cases.total = 0;
  state.detail.caseId = options.caseId || null;
  state.detail.vertical = null;
  savePrefs();
  renderPanel();
  renderDock();
  renderSidebar();
  await loadCases(true);
  if (options.caseId) {
    await selectCase(options.caseId);
  }
}

async function selectCase(caseId) {
  if (!caseId) {
    return;
  }
  state.detail.caseId = caseId;
  renderSidebar();
  await loadVertical();
}

// ------------------------------------------------------------------ main table

function fieldRows() {
  const vertical = state.detail.vertical;
  if (!vertical) {
    return [];
  }
  const rows = [];
  (vertical.sections || []).forEach((section) => {
    (section.rows || []).forEach((row) => rows.push({ ...row, section: section.title }));
  });
  return rows;
}

function visibleRows() {
  const filters = state.detail.col;
  const needleHeader = filters.header.trim().toLowerCase();
  const needleValue = filters.value.trim().toLowerCase();
  let rows = fieldRows().filter((row) => {
    if (needleHeader
      && !String(row.header || '').toLowerCase().includes(needleHeader)
      && !String(row.fieldKey || '').toLowerCase().includes(needleHeader)) {
      return false;
    }
    if (needleValue && !String(row.value || '').toLowerCase().includes(needleValue)) {
      return false;
    }
    if (filters.type && row.type !== filters.type) {
      return false;
    }
    if (filters.formula && !row.formula) {
      return false;
    }
    return true;
  });
  const { key, dir } = state.detail.sort;
  if (key && dir) {
    rows = rows.slice().sort((left, right) => String(left[key] ?? '')
      .localeCompare(String(right[key] ?? ''), 'zh-Hans-CN') * dir);
  }
  return rows;
}

function detailRow() {
  const caseId = state.detail.caseId;
  const fromList = state.cases.items.find((item) => item.id === caseId);
  if (fromList) {
    return fromList;
  }
  const vertical = state.detail.vertical;
  return vertical && vertical.caseId === caseId
    ? {
      id: vertical.caseId,
      testCaseId: vertical.testCaseId,
      productCode: vertical.productCode,
      rowNo: vertical.rowNo,
    }
    : null;
}

function sortHeader(key, label) {
  const sort = state.detail.sort;
  const active = sort.key === key;
  const arrow = active ? (sort.dir === 1 ? '↑' : '↓') : '⇅';
  return `<th class="th-sort${active ? ' sorted' : ''}" data-sort="${key}" tabindex="0">${label}<span class="sort-arrow">${arrow}</span></th>`;
}

function sortFields(key) {
  const sort = state.detail.sort;
  if (sort.key !== key) {
    state.detail.sort = { key, dir: 1 };
  } else if (sort.dir === 1) {
    state.detail.sort = { key, dir: -1 };
  } else {
    state.detail.sort = { key: null, dir: 0 };
  }
  renderDetail();
}

function detailFilterChips() {
  const chips = [];
  const filters = state.detail.col;
  if (filters.header) {
    chips.push({ key: 'header', label: `字段：${filters.header}` });
  }
  if (filters.value) {
    chips.push({ key: 'value', label: `值：${filters.value}` });
  }
  if (filters.type) {
    chips.push({ key: 'type', label: `类型：${TYPE_LABEL[filters.type] || filters.type}` });
  }
  if (filters.formula) {
    chips.push({ key: 'formula', label: '仅公式' });
  }
  if (state.detail.sort.key) {
    chips.push({
      key: 'sort',
      label: `排序：${state.detail.sort.key} ${state.detail.sort.dir === 1 ? '↑' : '↓'}`,
    });
  }
  return chips;
}

function toggleLock() {
  state.detail.locked = !state.detail.locked;
  savePrefs();
  ui.toast(state.detail.locked ? '筛选已锁定：切换用例/批次时保留' : '筛选已解锁', 'ok');
  renderDetail();
}

function renderDetail() {
  const head = ui.byId('detailHead');
  const filters = ui.byId('detailFilters');
  const body = ui.byId('detailBody');
  if (!head || !filters || !body) {
    return;
  }
  const row = detailRow();
  if (!row) {
    head.innerHTML = '<div class="detail-placeholder">选择一条用例查看字段明细</div>';
    filters.innerHTML = '';
    body.innerHTML = '';
    return;
  }
  const batch = findBatch(state.cases.batchId);
  const index = currentCaseIndex();
  head.innerHTML = `
    <div class="dh-main">
      <span class="dh-id mono">${escapeHtml(row.testCaseId || row.id)}</span>
      ${row.keyValue ? `<span class="dh-key mono" title="唯一字段">${escapeHtml(row.keyValue)}</span>` : ''}
      <span class="dh-chip">#${row.rowNo}</span>
      ${row.productCode ? `<span class="dh-chip">${escapeHtml(row.productCode)}</span>` : ''}
      ${batch ? `<span class="dh-file" title="${escapeHtml(batch.sourceFilename)}">${escapeHtml(batch.sourceFilename)}</span>` : ''}
    </div>
    <div class="dh-actions">
      <span class="dh-nav">
        <button class="tool-btn small" id="detailPrev" title="上一条（K）"${index <= 0 ? ' disabled' : ''}>↑</button>
        <button class="tool-btn small" id="detailNext" title="下一条（J）"
                ${index >= state.cases.items.length - 1 && state.cases.items.length >= state.cases.total ? ' disabled' : ''}>↓</button>
      </span>
      ${batch ? `<button class="tool-btn small" id="detailFavorite" title="收藏当前批次（Space）">★</button>
        <a class="tool-btn small" href="/batches/${encodeURIComponent(batch.id)}" title="进入批次详情页（Enter）">批次详情</a>` : ''}
    </div>`;
  const prev = ui.byId('detailPrev');
  const next = ui.byId('detailNext');
  if (prev) {
    prev.addEventListener('click', () => moveCase(-1));
  }
  if (next) {
    next.addEventListener('click', () => moveCase(1));
  }
  const favorite = ui.byId('detailFavorite');
  if (favorite) {
    favorite.addEventListener('click', () => toggleFlag(batch.id, 'favorite'));
  }

  const types = [...new Set(fieldRows().map((item) => item.type).filter(Boolean))];
  filters.innerHTML = `
    <div class="df-row">
      <input type="search" id="dfHeader" placeholder="筛选字段" value="${escapeHtml(state.detail.col.header)}">
      <input type="search" id="dfValue" placeholder="筛选值" value="${escapeHtml(state.detail.col.value)}">
      <select id="dfType" aria-label="字段类型">
        <option value="">全部类型</option>
        ${types.map((type) => `<option value="${escapeHtml(type)}"${state.detail.col.type === type ? ' selected' : ''}>${escapeHtml(TYPE_LABEL[type] || type)}</option>`).join('')}
      </select>
      <button class="df-toggle${state.detail.col.formula ? ' on' : ''}" id="dfFormula" title="只看公式字段">ƒ 公式</button>
      <button class="df-lock${state.detail.locked ? ' on' : ''}" id="dfLock"
              title="锁定筛选（L）：切换用例/批次时保留">${state.detail.locked ? '🔒' : '🔓'} 锁定</button>
      <span class="df-count">${visibleRows().length} / ${fieldRows().length}</span>
    </div>
    <div class="df-chips">${detailFilterChips().map((chip) => `
      <span class="bp-chip" data-col-clear="${chip.key}">${escapeHtml(chip.label)}<span class="bp-chip-x">✕</span></span>`).join('')}</div>`;

  ui.byId('dfHeader').addEventListener('input', debounce((event) => {
    state.detail.col.header = event.target.value;
    renderDetail();
  }, 220));
  ui.byId('dfValue').addEventListener('input', debounce((event) => {
    state.detail.col.value = event.target.value;
    renderDetail();
  }, 220));
  ui.byId('dfType').addEventListener('change', (event) => {
    state.detail.col.type = event.target.value;
    renderDetail();
  });
  ui.byId('dfFormula').addEventListener('click', () => {
    state.detail.col.formula = !state.detail.col.formula;
    renderDetail();
  });
  ui.byId('dfLock').addEventListener('click', () => toggleLock());
  filters.querySelectorAll('[data-col-clear]').forEach((chip) => {
    chip.addEventListener('click', () => {
      const key = chip.dataset.colClear;
      if (key === 'formula') {
        state.detail.col.formula = false;
      } else if (key === 'sort') {
        state.detail.sort = { key: null, dir: 0 };
      } else {
        state.detail.col[key] = '';
      }
      renderDetail();
    });
  });

  const rows = visibleRows();
  body.innerHTML = `
    <div class="table-wrap detail-table-wrap">
      <table class="detail-table">
        <thead>
        <tr>
          ${sortHeader('header', '字段')}
          ${sortHeader('value', '值')}
          <th class="col-formula">公式</th>
          ${sortHeader('type', '类型')}
        </tr>
        </thead>
        <tbody>
        ${rows.map((item) => `
          <tr class="${item.key ? 'row-key' : ''}">
            <td class="col-header">
              <span class="ft-header">${escapeHtml(item.header)}</span>
              <span class="ft-key mono">${escapeHtml(item.fieldKey)}</span>
              ${item.key ? '<span class="ft-badge" title="唯一字段">key</span>' : ''}
            </td>
            <td class="col-value">${item.value
              ? `<span class="ft-value">${escapeHtml(item.value)}</span>`
              : '<span class="muted">—</span>'}</td>
            <td class="col-formula">${item.formula
              ? `<span class="ft-formula" title="${escapeHtml(item.formula)}">ƒ ${escapeHtml(item.formula)}</span>`
              : ''}</td>
            <td class="col-type">${escapeHtml(TYPE_LABEL[item.type] || item.type || '')}</td>
          </tr>`).join('') || '<tr><td colspan="4" class="empty">没有匹配的字段</td></tr>'}
        </tbody>
      </table>
    </div>`;
  body.querySelectorAll('th[data-sort]').forEach((th) => {
    th.addEventListener('click', () => sortFields(th.dataset.sort));
    th.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        sortFields(th.dataset.sort);
      }
    });
  });
}

// ------------------------------------------------------------------ global search

function openGlobalSearch() {
  const modal = ui.openModal('全局搜索', `
    <div class="gs-bar">
      <input type="search" id="gsInput" placeholder="批次 / 用例 / 字段值…  支持 tag: product: case: value: scope:">
      <button class="tool-btn primary" id="gsRun">搜索</button>
    </div>
    <div class="gs-hint">限定词：<code>tag:</code> <code>product:</code> <code>batch:</code> <code>case:</code>
      <code>value:</code> <code>note:</code> <code>file:</code> <code>scope:batch|case</code></div>
    <div class="gs-results" id="gsResults"></div>`);
  const input = modal.root.querySelector('#gsInput');
  input.value = ui.byId('globalSearch').value || '';
  input.focus();
  const run = () => executeSearch(modal, input.value);
  modal.root.querySelector('#gsRun').addEventListener('click', run);
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      run();
    }
  });
  if (input.value.trim().length >= 2) {
    run();
  }
}

async function executeSearch(modal, expression) {
  const host = modal.root.querySelector('#gsResults');
  if (!expression.trim()) {
    host.innerHTML = '';
    return;
  }
  host.innerHTML = '<div class="gs-loading">搜索中…</div>';
  try {
    const result = await get('/api/search' + query({ q: expression, limit: 20 }));
    const batches = (result.batches || []).map((hit) => `
      <button class="gs-item" data-gs-batch="${escapeHtml(hit.batchId)}">
        <span class="gs-kind">批次</span>
        <span class="gs-title">${escapeHtml(hit.sourceFilename)}</span>
        <span class="gs-meta">${escapeHtml(hit.productCode)} · ${escapeHtml(hit.createdAt)}${
          hit.tags && hit.tags.length ? ' · ' + escapeHtml(hit.tags.join(', ')) : ''}</span>
      </button>`).join('');
    const cases = (result.cases || []).map((hit) => `
      <button class="gs-item" data-gs-case="${escapeHtml(hit.caseId)}" data-gs-batch="${escapeHtml(hit.batchId)}">
        <span class="gs-kind">用例</span>
        <span class="gs-title">${escapeHtml(hit.testCaseId || ui.shortId(hit.caseId))}
          <span class="mono">${escapeHtml(hit.keyValue || '')}</span></span>
        <span class="gs-meta">#${hit.rowNo} · ${escapeHtml(hit.productCode || '')}${
          hit.formulaCount ? ' · ƒ' + hit.formulaCount : ''}</span>
      </button>`).join('');
    host.innerHTML = (batches || cases)
      ? `<div class="gs-group">批次（${result.totalBatches}）</div>${batches || '<div class="gs-none">无</div>'}
         <div class="gs-group">用例（${result.totalCases}）</div>${cases || '<div class="gs-none">无</div>'}
         ${result.truncated ? '<div class="gs-hint">结果已截断，请补充限定词</div>' : ''}`
      : '<div class="gs-none">没有匹配结果</div>';
    host.querySelectorAll('[data-gs-case]').forEach((node) => {
      node.addEventListener('click', async () => {
        modal.close();
        await selectBatch(node.dataset.gsBatch, { caseId: node.dataset.gsCase });
      });
    });
    host.querySelectorAll('[data-gs-batch]:not([data-gs-case])').forEach((node) => {
      node.addEventListener('click', async () => {
        modal.close();
        await selectBatch(node.dataset.gsBatch);
      });
    });
  } catch (error) {
    host.innerHTML = `<div class="gs-none">${escapeHtml(error.message || '搜索失败')}</div>`;
  }
}

// ------------------------------------------------------------------ keyboard

function showHelp() {
  ui.openModal('快捷键与搜索语法', `
    <div class="help-grid">
      ${KEY_HELP.map(([key, label]) => `<div class="help-row"><kbd>${escapeHtml(key)}</kbd><span>${escapeHtml(label)}</span></div>`).join('')}
    </div>
    <div class="help-section">搜索限定词</div>
    <div class="help-grid">
      <div class="help-row"><kbd>tag:x</kbd><span>按批次标签</span></div>
      <div class="help-row"><kbd>product:IRS</kbd><span>按产品代码</span></div>
      <div class="help-row"><kbd>batch:x</kbd><span>文件名 / Sheet / 备注 / 标签</span></div>
      <div class="help-row"><kbd>case:x</kbd><span>用例 ID / Trade ID / 字段值</span></div>
      <div class="help-row"><kbd>value:x</kbd><span>字段值</span></div>
      <div class="help-row"><kbd>scope:batch</kbd><span>只返回批次（或 case）</span></div>
    </div>`);
}

function bindKeys() {
  document.addEventListener('keydown', (event) => {
    const tag = (event.target.tagName || '').toLowerCase();
    const typing = tag === 'input' || tag === 'textarea' || tag === 'select'
      || event.target.isContentEditable;
    if (event.key === 'Escape') {
      if (state.panel.open) {
        togglePanel(false);
      } else if (state.panel.favoritesOpen) {
        toggleFavorites(false);
      } else if (typing) {
        event.target.blur();
      } else {
        state.detail.col = { header: '', value: '', type: '', formula: false };
        renderDetail();
      }
      return;
    }
    if (typing || event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }
    switch (event.key) {
      case '/':
        event.preventDefault();
        ui.byId('globalSearch').focus();
        break;
      case 'g':
      case 'G':
        event.preventDefault();
        openGlobalSearch();
        break;
      case 'b':
      case 'B':
        event.preventDefault();
        togglePanel();
        break;
      case 'f':
      case 'F':
        event.preventDefault();
        toggleFavorites();
        break;
      case 'j':
      case 'J':
        event.preventDefault();
        moveCase(1);
        break;
      case 'k':
      case 'K':
        event.preventDefault();
        moveCase(-1);
        break;
      case 'l':
      case 'L':
        event.preventDefault();
        toggleLock();
        break;
      case ' ':
        if (state.cases.batchId) {
          event.preventDefault();
          toggleFlag(state.cases.batchId, 'favorite');
        }
        break;
      case 'Enter':
        if (state.cases.batchId) {
          event.preventDefault();
          window.location.href = `/batches/${encodeURIComponent(state.cases.batchId)}`;
        }
        break;
      default:
        break;
    }
  });
}

// ------------------------------------------------------------------ lifecycle

async function refreshAll() {
  await loadFacets();
  await loadBatches();
  await loadDock();
}

async function init() {
  await ui.initShell();
  loadPrefs();
  applySide();
  applyPanelWidth();

  ui.byId('globalSearch').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      openGlobalSearch();
    }
  });
  ui.byId('globalSearchBtn').addEventListener('click', openGlobalSearch);
  ui.byId('consoleHelpBtn').addEventListener('click', showHelp);
  bindSidebar();
  bindKeys();

  await loadFacets();
  await loadDock();
  await loadBatches();

  const known = state.panel.items.some((item) => item.id === state.panel.selectedId)
    || state.panel.dock.some((item) => item.id === state.panel.selectedId);
  const preferred = (known ? state.panel.selectedId : null)
    || (state.panel.items[0] && state.panel.items[0].id)
    || (state.panel.dock[0] && state.panel.dock[0].id);
  if (preferred) {
    await selectBatch(preferred);
  } else {
    renderSidebar();
    renderDetail();
    renderFavorites();
  }
  renderPanel();
}

init();
