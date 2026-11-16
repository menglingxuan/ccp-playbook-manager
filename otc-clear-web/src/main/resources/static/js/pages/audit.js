import { get, query } from '../api.js';
import * as ui from '../ui.js';

/* Audit panel: filter the trail by module, audited object, action, text and date. */

const state = {
  module: '', entityType: '', action: '', q: '', from: '', to: '', page: 1, size: 25,
  expanded: new Set(),
  items: new Map(),
};

const MODULE_LABELS = {
  PRODUCT_DEFINITION: '产品定义',
  META_DICTIONARY: '元数据',
  TASK: '任务（历史）',
  BATCH: '批次（历史）',
  CASE: '用例（历史）',
  EXPORT: '导出记录（历史）',
  OUTPUT: '外部任务（历史）',
  EXTERNAL_JOB: '外部派发（历史）',
  OTHER: '其他（历史）',
};

const ENTITY_LABELS = {
  BASE_PRODUCT: '基产品',
  BASE_FIELD: '基产品字段',
  PRODUCT: '产品',
  PRODUCT_FIELD: '产品字段',
  META_SET: '元数据集合',
  META_ITEM: '元数据项',
  TASK: '任务',
  BATCH: '批次',
  CASE: '用例',
  EXPORT: '导出记录',
  OUTPUT: '外部任务',
  EXTERNAL_JOB: '外部派发',
};

const MODULE_ENTITIES = {
  PRODUCT_DEFINITION: ['BASE_PRODUCT', 'BASE_FIELD', 'PRODUCT', 'PRODUCT_FIELD'],
  META_DICTIONARY: ['META_SET', 'META_ITEM'],
  TASK: ['TASK'],
  BATCH: ['BATCH'],
  CASE: ['CASE'],
  EXPORT: ['EXPORT'],
  OUTPUT: ['OUTPUT'],
  EXTERNAL_JOB: ['EXTERNAL_JOB'],
};

const ACTION_LABELS = {
  CREATE: '新建',
  UPDATE: '更新',
  DELETE: '删除',
  REORDER: '调整顺序',
  SYNC: '同步字段',
  IMPORT_FIELDS: '导入字段',
  IMPORT: '导入（历史）',
  RENAME: '重命名（历史）',
  META: '元数据（历史）',
  CLONE: '克隆（历史）',
  EXPORT: '导出（历史）',
};

function moduleLabel(code) {
  return MODULE_LABELS[code] || code || '—';
}

function entityLabel(code) {
  return ENTITY_LABELS[code] || code || '—';
}

function actionLabel(code) {
  return ACTION_LABELS[code] || code || '—';
}

/** Shared debounce; the page keeps its own default delay. */
const debounce = (fn, delay = 350) => ui.debounce(fn, delay);

function filterParams() {
  return {
    module: state.module,
    entityType: state.entityType,
    action: state.action,
    q: state.q,
    from: state.from,
    to: state.to,
  };
}

function withCounts(codes, counts) {
  return codes.map((code) => ({ code, count: counts ? counts[code] : undefined }));
}

/** Fills one select: value, label and optional facet count. */
function renderOptions(selectId, emptyLabel, entries, selected) {
  const select = ui.byId(selectId);
  const options = [`<option value="">${ui.escapeHtml(emptyLabel)}</option>`];
  entries.forEach((entry) => {
    const count = entry.count === undefined || entry.count === null ? '' : ` · ${entry.count}`;
    options.push(`<option value="${ui.escapeHtml(entry.code)}">${ui.escapeHtml(entry.label + count)}</option>`);
  });
  select.innerHTML = options.join('');
  select.value = entries.some((entry) => entry.code === selected) ? selected : '';
}

function entityEntries(counts) {
  const codes = state.module
    ? (MODULE_ENTITIES[state.module] || [])
    : Object.keys(counts || {});
  return withCounts(codes, counts || {}).map((entry) => ({ ...entry, label: entityLabel(entry.code) }));
}

async function loadFacets() {
  try {
    const facets = await get('/api/audit/facets' + query(filterParams()));
    const moduleCounts = facets.modules || {};
    const moduleCodes = Object.keys(MODULE_LABELS).filter((code) => moduleCounts[code]);
    Object.keys(moduleCounts).forEach((code) => {
      if (!moduleCodes.includes(code)) {
        moduleCodes.push(code);
      }
    });
    renderOptions('auditModule', '全部模块',
      withCounts(moduleCodes, moduleCounts).map((entry) => ({ ...entry, label: moduleLabel(entry.code) })),
      state.module);

    const entityCounts = facets.entities || {};
    const actionCounts = facets.actions || {};
    renderOptions('auditEntity', '全部对象', entityEntries(entityCounts), state.entityType);

    const actionCodes = Object.keys(ACTION_LABELS).filter((code) => actionCounts[code]);
    Object.keys(actionCounts).forEach((code) => {
      if (!actionCodes.includes(code)) {
        actionCodes.push(code);
      }
    });
    renderOptions('auditAction', '全部动作',
      withCounts(actionCodes, actionCounts).map((entry) => ({ ...entry, label: actionLabel(entry.code) })),
      state.action);
  } catch (error) {
    ui.showError(error);
  }
}

function detailText(detail) {
  if (!detail) {
    return '—';
  }
  return detail.length > 90 ? `${detail.slice(0, 90)}…` : detail;
}

function rowHtml(item) {
  const open = state.expanded.has(item.id);
  const rows = [`
    <tr data-audit="${ui.escapeHtml(item.id)}" class="${open ? 'row-open' : ''}">
      <td class="mono">${ui.escapeHtml(item.createdAt)}</td>
      <td><span class="chip muted">${ui.escapeHtml(moduleLabel(item.module))}</span></td>
      <td>${ui.escapeHtml(entityLabel(item.entityType))}</td>
      <td>${ui.escapeHtml(actionLabel(item.action))}</td>
      <td class="mono" title="${ui.escapeHtml(item.entityId || '')}">${ui.escapeHtml(item.entityId || '—')}</td>
      <td class="audit-detail-cell" title="${ui.escapeHtml(item.detail || '')}">
        <button class="link-btn" data-toggle="${ui.escapeHtml(item.id)}">${ui.escapeHtml(detailText(item.detail))}</button>
      </td>
    </tr>`];
  if (open) {
    rows.push(`
      <tr class="audit-detail-row">
        <td colspan="6"><pre class="audit-detail">${ui.escapeHtml(prettyDetail(item.detail))}</pre></td>
      </tr>`);
  }
  return rows.join('');
}

function prettyDetail(detail) {
  if (!detail) {
    return '';
  }
  try {
    return JSON.stringify(JSON.parse(detail), null, 2);
  } catch (error) {
    return detail;
  }
}

async function load() {
  try {
    const result = await get('/api/audit' + query({ ...filterParams(), page: state.page, size: state.size }));
    state.items = new Map(result.items.map((item) => [item.id, item]));
    ui.setHtml('auditRows', result.items.length ? result.items.map(rowHtml).join('')
      : '<tr><td colspan="6" class="empty">没有匹配的审计记录</td></tr>');
    ui.setHtml('auditTotal', `共 ${result.total} 条`);
    ui.byId('auditRows').querySelectorAll('button[data-toggle]').forEach((button) => {
      button.addEventListener('click', () => toggle(button.dataset.toggle, button));
    });
    ui.renderPager('auditPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      load();
    });
  } catch (error) {
    ui.showError(error);
  }
}

function toggle(id, button) {
  const expanded = state.expanded.has(id);
  if (expanded) {
    state.expanded.delete(id);
  } else {
    state.expanded.add(id);
  }
  // the detail row is inserted next to the clicked row: reloading the whole page would lose the
  // scroll position and the focus of the user
  const row = button ? button.closest('tr') : ui.byId('auditRows')
    .querySelector(`tr[data-audit="${id}"]`);
  if (!row) {
    load();
    return;
  }
  row.classList.toggle('row-open', !expanded);
  const detailRow = row.nextElementSibling;
  if (expanded) {
    if (detailRow && detailRow.classList.contains('audit-detail-row')) {
      detailRow.remove();
    }
    return;
  }
  const item = state.items && state.items.get(id);
  if (!item) {
    load();
    return;
  }
  const inserted = document.createElement('tr');
  inserted.className = 'audit-detail-row';
  inserted.innerHTML = `<td colspan="6"><pre class="audit-detail">${
    ui.escapeHtml(prettyDetail(item.detail))}</pre></td>`;
  row.after(inserted);
}

function reload() {
  state.page = 1;
  loadFacets();
  load();
}

async function init() {
  await ui.initShell();
  await loadFacets();
  await load();
  ui.enableColumnResize(ui.byId('auditTable'), 'audit');

  ui.on('auditModule', 'change', (event) => {
    state.module = event.target.value;
    state.entityType = '';
    reload();
  });
  ui.on('auditEntity', 'change', (event) => {
    state.entityType = event.target.value;
    reload();
  });
  ui.on('auditAction', 'change', (event) => {
    state.action = event.target.value;
    reload();
  });
  ui.on('auditSearch', 'input', debounce((event) => {
    state.q = event.target.value;
    reload();
  }));
  ['auditFrom', 'auditTo'].forEach((id) => ui.on(id, 'change', () => {
    state.from = ui.byId('auditFrom').value;
    state.to = ui.byId('auditTo').value;
    reload();
  }));
  ui.on('auditReload', 'click', reload);
  ui.on('auditReset', 'click', () => {
    state.module = '';
    state.entityType = '';
    state.action = '';
    state.q = '';
    state.from = '';
    state.to = '';
    ui.byId('auditSearch').value = '';
    ui.byId('auditFrom').value = '';
    ui.byId('auditTo').value = '';
    reload();
  });
}

init();
