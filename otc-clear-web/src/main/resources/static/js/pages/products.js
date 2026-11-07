import * as api from '../api.js';
import * as ui from '../ui.js';
import { createFieldTable } from '../field-table.js';

const t = ui.t;

const state = {
  platforms: [],
  platformCode: null,
  baseId: null,
  base: null,
  options: {},
  productId: null,
  productFields: [],
  baseLocked: true,
  drawerLocked: true,
};

let baseTable = null;
let drawerTable = null;

const metaItem = (setCode, code) =>
  (state.options[setCode] || []).find((item) => item.code === code) || null;

const metaLabel = (setCode, code) => {
  const item = metaItem(setCode, code);
  if (!item) {
    return code || '—';
  }
  return item.refSetCode ? `${item.label} · ${item.refSetCode}` : item.label;
};

// ------------------------------------------------------------------ rendering

function renderPlatformTabs() {
  const host = ui.byId('platformTabs');
  if (!host) {
    return;
  }
  host.innerHTML = state.platforms
    .map((platform) => {
      const active = platform.code === state.platformCode ? 'active' : '';
      const baseCount = platform.baseProducts.length;
      const productCount = platform.baseProducts.reduce((sum, base) => sum + base.productCount, 0);
      return `<button class="step ${active}" data-platform="${ui.escapeHtml(platform.code)}">
        ${ui.escapeHtml(platform.name || platform.code)}
        <span class="pg-info">${ui.escapeHtml(t('products.tab.summary', baseCount, productCount))}</span>
      </button>`;
    })
    .join('');
  host.querySelectorAll('button[data-platform]').forEach((button) => {
    button.addEventListener('click', () => {
      if (button.dataset.platform === state.platformCode) {
        return;
      }
      state.platformCode = button.dataset.platform;
      state.baseId = null;
      state.base = null;
      renderPlatformTabs();
      renderBaseList();
      renderBasePanel();
    });
  });
}

function currentPlatform() {
  return state.platforms.find((platform) => platform.code === state.platformCode) || null;
}

function renderBaseList() {
  const host = ui.byId('baseList');
  const platform = currentPlatform();
  if (!host) {
    return;
  }
  if (!platform || !platform.baseProducts.length) {
    host.innerHTML = `<div class="item muted">${ui.escapeHtml(t('products.sidebar.empty'))}</div>`;
    return;
  }
  host.innerHTML = platform.baseProducts
    .map((base) => {
      const active = base.id === state.baseId ? 'active' : '';
      return `<div class="item ${active}" data-base="${base.id}">
        <div class="item-id">${ui.escapeHtml(base.code)}</div>
        <div class="item-sub">${ui.escapeHtml(base.name)} · ${ui.escapeHtml(t('common.fields'))} ${base.fieldCount}
          · ${ui.escapeHtml(t('common.product'))} ${base.productCount}</div>
      </div>`;
    })
    .join('');
  host.querySelectorAll('.item[data-base]').forEach((node) => {
    node.addEventListener('click', () => selectBase(node.dataset.base));
  });
}

function applyLockUi() {
  const lockButton = ui.byId('btnLockBase');
  if (lockButton) {
    lockButton.disabled = !state.base;
    lockButton.setAttribute('aria-pressed', String(!state.baseLocked));
    lockButton.title = state.baseLocked ? t('products.lock.unlock') : t('products.lock.lock');
  }
  const editable = Boolean(state.base) && !state.baseLocked;
  ui.byId('btnEditBase').disabled = !editable;
  ui.byId('btnDeleteBase').disabled = !editable;
  ui.byId('btnAddField').disabled = !editable;
}

function renderBasePanel() {
  const base = state.base;
  const placeholder = ui.byId('basePlaceholder');
  const table = ui.byId('baseFieldTable');
  const description = ui.byId('baseDescription');

  ui.byId('productPanel').classList.toggle('hidden', !base);
  ui.byId('basePlaceholder').classList.toggle('hidden', Boolean(base));
  table.classList.toggle('hidden', !base);
  applyLockUi();

  if (!base) {
    ui.byId('baseTitle').textContent = t('products.panel.placeholder.title');
    description.classList.add('hidden');
    description.textContent = '';
    baseTable.setFields([]);
    return;
  }

  const summary = base.baseProduct;
  ui.byId('baseTitle').textContent = `${summary.platformCode} · ${summary.code} · ${summary.name}`;
  const text = (summary.description || '').trim();
  description.textContent = text;
  description.title = text;
  description.classList.toggle('hidden', !text);

  baseTable.setLocked(state.baseLocked);
  baseTable.setFields(base.fields);
  renderProductRows();
}

function strictSwitch(product) {
  const on = product.strictMatch ? 'on' : '';
  const checked = product.strictMatch ? 'checked' : '';
  return `<label class="switch ${on}" title="${ui.escapeHtml(t('products.products.strictHint'))}">
      <input type="checkbox" data-act="strict" ${checked} aria-label="${ui.escapeHtml(
    t('products.products.column.strict'))}">
      <span class="switch-track"><span class="switch-thumb"></span></span>
    </label>`;
}

function renderProductRows() {
  const host = ui.byId('productRows');
  const base = state.base;
  if (!host || !base) {
    return;
  }
  ui.byId('btnNewProduct').disabled = false;
  if (!base.products.length) {
    host.innerHTML = `<tr><td class="empty" colspan="7">${ui.escapeHtml(t('products.products.empty'))}</td></tr>`;
    return;
  }
  host.innerHTML = base.products
    .map((product) => `
      <tr data-product="${product.id}">
        <td class="mono">${ui.escapeHtml(product.code)}</td>
        <td>${ui.escapeHtml(product.name)}</td>
        <td class="num">${product.fieldCount}</td>
        <td class="num">${product.caseCount}</td>
        <td>${ui.statusBadge(product.status)}</td>
        <td class="col-switch">${strictSwitch(product)}</td>
        <td>
          <div class="row-actions">
            ${ui.iconButton('list', t('products.btn.productFields'), 'sm', 'data-act="open"')}
            ${ui.iconButton('edit', t('common.edit'), 'sm', 'data-act="rename"')}
            ${ui.iconButton('trash', t('common.delete'), 'sm danger', 'data-act="delete"')}
          </div>
        </td>
      </tr>`)
    .join('');
  host.querySelectorAll('tr[data-product]').forEach((row) => {
    const product = base.products.find((item) => item.id === row.dataset.product);
    row.querySelectorAll('button[data-act]').forEach((button) => {
      button.addEventListener('click', () => {
        const act = button.dataset.act;
        if (act === 'open') {
          openProductDrawer(product.id);
        } else if (act === 'rename') {
          openProductEditor(product);
        } else {
          deleteProduct(product);
        }
      });
    });
    const toggle = row.querySelector('input[data-act="strict"]');
    toggle?.addEventListener('change', () => toggleStrictMode(product, toggle));
  });
}

/** Persists the strict matching switch without touching name or description. */
async function toggleStrictMode(product, input) {
  const target = input.checked;
  input.closest('.switch')?.classList.toggle('on', target);
  try {
    await api.patch(`/api/catalog/products/${product.id}`, { strictMatch: target });
    product.strictMatch = target;
    ui.toast(target ? t('products.toast.strictOn') : t('products.toast.strictOff'), 'ok');
  } catch (error) {
    input.checked = !target;
    input.closest('.switch')?.classList.toggle('on', !target);
    ui.showError(error);
  }
}

// ------------------------------------------------------------------- loading

async function loadPlatforms() {
  state.platforms = await api.get('/api/catalog');
  if (!state.platforms.some((platform) => platform.code === state.platformCode)) {
    state.platformCode = state.platforms.length ? state.platforms[0].code : null;
  }
  renderPlatformTabs();
  renderBaseList();
}

async function loadBase(baseId) {
  state.baseId = baseId;
  state.base = baseId ? await api.get(`/api/catalog/base-products/${baseId}`) : null;
  state.baseLocked = true;
  renderBaseList();
  renderBasePanel();
}

async function selectBase(baseId) {
  try {
    await loadBase(baseId);
  } catch (error) {
    ui.showError(error);
  }
}

async function refreshBase(baseId) {
  await loadPlatforms();
  await loadBase(baseId);
}

/** Reloads the base product but keeps the current edit lock state. */
async function refreshBaseKeepingLock(baseId) {
  const locked = state.baseLocked;
  await refreshBase(baseId);
  state.baseLocked = locked;
  baseTable.setLocked(locked);
  applyLockUi();
}

async function refreshAll() {
  try {
    await loadPlatforms();
    const exists = (currentPlatform()?.baseProducts || []).some((base) => base.id === state.baseId);
    await loadBase(exists ? state.baseId : null);
  } catch (error) {
    ui.showError(error);
  }
}

// -------------------------------------------------------------- base editing

/** Reads the first CSV line and splits it into header names (quotes and BOM aware). */
function parseCsvHeaders(text) {
  const clean = String(text || '').replace(/^\uFEFF/, '');
  const firstLine = clean.split(/\r?\n/).find((line) => line.trim().length > 0) || '';
  const headers = [];
  let current = '';
  let quoted = false;
  for (let index = 0; index < firstLine.length; index++) {
    const char = firstLine[index];
    if (quoted) {
      if (char === '"') {
        if (firstLine[index + 1] === '"') {
          current += '"';
          index++;
        } else {
          quoted = false;
        }
      } else {
        current += char;
      }
    } else if (char === '"') {
      quoted = true;
    } else if (char === ',') {
      headers.push(current.trim());
      current = '';
    } else {
      current += char;
    }
  }
  headers.push(current.trim());
  return headers.filter((header) => header.length > 0);
}

function csvHeaderDropZone() {
  return `
    <div class="csv-zone" id="baseCsvZone" tabindex="0" role="button">
      ${ui.icon('upload', 18)}
      <div class="csv-zone-title">${ui.escapeHtml(t('products.form.csvTitle'))}</div>
      <div class="csv-zone-sub">${ui.escapeHtml(t('products.form.csvSub'))}</div>
      <input type="file" id="baseCsvInput" accept=".csv,text/csv" hidden>
      <div class="csv-zone-picked hidden" id="baseCsvPicked"></div>
    </div>`;
}

function openBaseEditor() {
  const platform = currentPlatform();
  if (!platform) {
    return;
  }
  api.get('/api/catalog/base-codes/suggestions').then(openBaseEditorModal).catch(ui.showError);
}

function openBaseEditorModal(suggestions) {
  const used = new Set((currentPlatform()?.baseProducts || []).map((base) => base.code));
  const free = (suggestions || []).filter((code) => !used.has(code));
  const modal = ui.openModal(t('products.btn.newBase'), `
    <div class="params">
      <div class="field">
        <label>${ui.escapeHtml(t('products.form.baseCode'))}</label>
        <input id="baseCode" type="text" list="baseCodeList" autocomplete="off"
               placeholder="NB_IRS">
        <datalist id="baseCodeList">
          ${free.map((code) => `<option value="${ui.escapeHtml(code)}"></option>`).join('')}
        </datalist>
        <div class="hint">${ui.escapeHtml(t('products.form.baseCodeHint'))}</div>
      </div>
      <div class="field">
        <label>${ui.escapeHtml(t('products.form.displayName'))}</label>
        <input id="baseName" type="text" placeholder="${ui.escapeHtml(t('products.form.blankNameHint'))}">
      </div>
    </div>
    <div class="field" style="margin-bottom:12px">
      <label>${ui.escapeHtml(t('products.form.description'))}</label>
      <input id="baseDesc" type="text" placeholder="${ui.escapeHtml(t('products.form.optionalHint'))}">
    </div>
    <div class="field">
      <label>${ui.escapeHtml(t('products.form.csvSection'))}</label>
      ${csvHeaderDropZone()}
      <div class="hint">${ui.escapeHtml(t('products.form.csvHint'))}</div>
    </div>`,
  `<button class="tool-btn" id="baseCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="baseSave">${ui.escapeHtml(t('common.add'))}</button>`);

  let headers = [];
  const zone = modal.root.querySelector('#baseCsvZone');
  const input = modal.root.querySelector('#baseCsvInput');
  const picked = modal.root.querySelector('#baseCsvPicked');

  const showPicked = () => {
    picked.classList.toggle('hidden', !headers.length);
    picked.innerHTML = headers.length
      ? `${ui.escapeHtml(t('products.form.csvPicked', headers.length))}
         <span class="csv-preview">${ui.escapeHtml(headers.slice(0, 4).join(' · '))}${
  headers.length > 4 ? ' …' : ''}</span>`
      : '';
    zone.classList.toggle('has-file', headers.length > 0);
  };

  const readFile = async (file) => {
    if (!file) {
      return;
    }
    try {
      headers = parseCsvHeaders(await file.text());
      showPicked();
      if (!headers.length) {
        ui.toast(t('products.toast.csvEmpty'), 'error');
      }
    } catch (error) {
      ui.showError(error);
    }
  };

  zone.addEventListener('click', () => input.click());
  zone.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      input.click();
    }
  });
  input.addEventListener('change', () => readFile(input.files && input.files[0]));
  zone.addEventListener('dragover', (event) => {
    event.preventDefault();
    zone.classList.add('dragging');
  });
  zone.addEventListener('dragleave', () => zone.classList.remove('dragging'));
  zone.addEventListener('drop', (event) => {
    event.preventDefault();
    zone.classList.remove('dragging');
    readFile(event.dataTransfer?.files?.[0]);
  });

  modal.root.querySelector('#baseCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#baseSave').addEventListener('click', async () => {
    const code = modal.root.querySelector('#baseCode').value.trim().toUpperCase();
    if (!code) {
      ui.toast(t('products.toast.baseCodeRequired'), 'error');
      return;
    }
    try {
      const created = await api.post(
        `/api/catalog/platforms/${encodeURIComponent(state.platformCode)}/base-products`,
        {
          code,
          name: modal.root.querySelector('#baseName').value.trim() || null,
          description: modal.root.querySelector('#baseDesc').value.trim() || null,
        },
      );
      if (headers.length) {
        const result = await api.post(`/api/catalog/base-products/${created.id}/fields/from-headers`,
          { headers });
        ui.toast(t('products.toast.baseFieldsImported', (result.created || []).length), 'ok');
      }
      modal.close();
      ui.toast(t('products.toast.baseCreated', created.code), 'ok');
      await loadPlatforms();
      await loadBase(created.id);
    } catch (error) {
      ui.showError(error);
    }
  });
}

function openBaseMetaEditor() {
  const base = state.base;
  if (!base) {
    return;
  }
  if (state.baseLocked) {
    ui.toast(t('products.lock.actionHint'), 'error');
    return;
  }
  const summary = base.baseProduct;
  const modal = ui.openModal(t('products.btn.editBase'), `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('products.form.displayName'))}</label>
        <input id="ebName" type="text" value="${ui.escapeHtml(summary.name)}"></div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.sortOrder'))}</label>
        <input id="ebOrder" type="number" min="1" value="${summary.sortOrder}"></div>
    </div>
    <div class="field"><label>${ui.escapeHtml(t('products.form.description'))}</label>
      <input id="ebDesc" type="text" value="${ui.escapeHtml(summary.description || '')}"></div>`,
  `<button class="tool-btn" id="ebCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="ebSave">${ui.escapeHtml(t('common.save'))}</button>`);
  modal.root.querySelector('#ebCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#ebSave').addEventListener('click', async () => {
    try {
      await api.patch(`/api/catalog/base-products/${summary.id}`, {
        name: modal.root.querySelector('#ebName').value.trim(),
        description: modal.root.querySelector('#ebDesc').value.trim() || null,
        sortOrder: Number(modal.root.querySelector('#ebOrder').value) || summary.sortOrder,
      });
      modal.close();
      ui.toast(t('products.toast.baseSaved'), 'ok');
      await refreshBase(summary.id);
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function deleteBase() {
  const base = state.base;
  if (!base) {
    return;
  }
  if (state.baseLocked) {
    ui.toast(t('products.lock.actionHint'), 'error');
    return;
  }
  const summary = base.baseProduct;
  if (!window.confirm(t('products.confirm.deleteBase', summary.code))) {
    return;
  }
  try {
    await api.del(`/api/catalog/base-products/${summary.id}`);
    ui.toast(t('products.toast.baseDeleted'), 'ok');
    state.baseId = null;
    state.base = null;
    await refreshAll();
  } catch (error) {
    ui.showError(error);
  }
}

// ------------------------------------------------------------- base fields

/** Tag selector rendered as directly clickable pills; the active ones use the accent style. */
function tagSelector(selected) {
  const chips = (state.options.FIELD_TAG || [])
    .map((item) => {
      const on = selected.includes(item.code) ? 'on' : '';
      return `<button type="button" class="tag-pill ${on}" data-tag="${ui.escapeHtml(item.code)}"
        aria-pressed="${selected.includes(item.code) ? 'true' : 'false'}">${ui.escapeHtml(item.label)}</button>`;
    })
    .join('');
  return `<div class="tag-pills" id="ffTags">${chips || `<span class="muted">${ui.escapeHtml(t('common.none'))}</span>`}</div>`;
}

function readSelectedTags(root) {
  return [...root.querySelectorAll('#ffTags .tag-pill.on')].map((pill) => pill.dataset.tag);
}

function bindTagPills(root) {
  root.querySelectorAll('#ffTags .tag-pill').forEach((pill) => {
    pill.addEventListener('click', () => {
      if (pill.disabled) {
        return;
      }
      const on = pill.classList.toggle('on');
      pill.setAttribute('aria-pressed', String(on));
    });
  });
}

/** True when another field already uses the given order inside the list. */
function orderTaken(fields, order, selfId) {
  const value = Number(order);
  return (fields || []).some((item) => Number(item.sortOrder) === value && item.id !== selfId);
}

function nextOrder(fields) {
  return (fields || []).reduce((max, item) => Math.max(max, Number(item.sortOrder) || 0), 0) + 1;
}

function openFieldEditor(field) {
  const base = state.base;
  if (!base) {
    return;
  }
  if (state.baseLocked) {
    ui.toast(t('products.lock.actionHint'), 'error');
    return;
  }
  const editing = Boolean(field);
  const tags = field ? field.tags : [];
  const modal = ui.openModal(editing ? `${t('common.edit')} · ${field.header}` : t('products.form.newField'), `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('products.form.header'))}</label>
        <input id="ffHeader" type="text" value="${editing ? ui.escapeHtml(field.header) : ''}"></div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.valueType'))}</label>
        <select id="ffType">
          ${(state.options.VALUE_TYPE || []).map((item) => `<option value="${ui.escapeHtml(item.code)}"
            ${editing && field.valueTypeCode === item.code ? 'selected' : ''}>${ui.escapeHtml(item.label)}</option>`).join('')}
        </select>
      </div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.role'))}</label>
        <select id="ffRole">
          ${(state.options.FIELD_ROLE || []).map((item) => `<option value="${ui.escapeHtml(item.code)}"
            ${editing && field.roleCode === item.code ? 'selected' : ''}>${ui.escapeHtml(item.label)}</option>`).join('')}
        </select>
      </div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.required'))}</label>
        <select id="ffRequired">
          <option value="false" ${editing && field.required ? '' : 'selected'}>${ui.escapeHtml(t('common.optional'))}</option>
          <option value="true" ${editing && field.required ? 'selected' : ''}>${ui.escapeHtml(t('common.required'))}</option>
        </select>
      </div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.order'))}</label>
        <input id="ffOrder" type="number" min="1" value="${editing ? field.sortOrder : nextOrder(base.fields)}"></div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.aliases'))}</label>
        <input id="ffAliases" type="text" value="${editing ? ui.escapeHtml(field.aliases.join(', ')) : ''}"></div>
    </div>
    <div class="field" style="margin-bottom:10px"><label>${ui.escapeHtml(t('products.form.tags'))}</label>${tagSelector(tags)}</div>
    <div class="field"><label>${ui.escapeHtml(t('products.form.remark'))}</label>
      <input id="ffRemark" type="text" value="${editing ? ui.escapeHtml(field.remark || '') : ''}"></div>
    <div class="hint" id="ffHint"></div>`,
  `<button class="tool-btn" id="ffCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="ffSave">${ui.escapeHtml(t('common.save'))}</button>`);

  const typeSelect = modal.root.querySelector('#ffType');
  const hint = modal.root.querySelector('#ffHint');
  const syncHint = () => {
    const item = metaItem('VALUE_TYPE', typeSelect.value);
    if (!item || !item.refSetCode) {
      hint.textContent = t('products.form.headerHint');
      return;
    }
    const values = (state.options[item.refSetCode] || []).map((entry) => entry.label).join('、');
    hint.textContent = t('products.form.refHint', item.refSetCode, values || '—');
  };
  typeSelect.addEventListener('change', syncHint);
  syncHint();

  bindTagPills(modal.root);

  modal.root.querySelector('#ffCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#ffSave').addEventListener('click', async () => {
    const header = modal.root.querySelector('#ffHeader').value.trim();
    if (!header) {
      ui.toast(t('products.toast.headerRequired'), 'error');
      return;
    }
    const order = Number(modal.root.querySelector('#ffOrder').value) || null;
    if (order !== null && orderTaken(base.fields, order, editing ? field.id : null)) {
      ui.toast(t('products.toast.orderTaken', order), 'error');
      return;
    }
    const payload = {
      fieldKey: editing ? field.fieldKey : null,
      header,
      displayName: editing ? field.displayName : header,
      aliases: modal.root.querySelector('#ffAliases').value.split(',')
        .map((alias) => alias.trim()).filter(Boolean),
      valueTypeCode: typeSelect.value,
      roleCode: modal.root.querySelector('#ffRole').value,
      tags: readSelectedTags(modal.root),
      required: modal.root.querySelector('#ffRequired').value === 'true',
      remark: modal.root.querySelector('#ffRemark').value.trim() || null,
      sortOrder: order,
    };
    try {
      if (editing) {
        await api.patch(`/api/catalog/base-products/${base.baseProduct.id}/fields/${field.id}`, payload);
      } else {
        await api.post(`/api/catalog/base-products/${base.baseProduct.id}/fields`, payload);
      }
      modal.close();
      ui.toast(t('products.toast.fieldSaved'), 'ok');
      await refreshBaseKeepingLock(base.baseProduct.id);
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function deleteBaseField(field) {
  const base = state.base;
  if (!base) {
    return;
  }
  if (state.baseLocked) {
    ui.toast(t('products.lock.actionHint'), 'error');
    return;
  }
  if (!window.confirm(t('products.confirm.deleteField', field.header))) {
    return;
  }
  try {
    const result = await api.del(`/api/catalog/base-products/${base.baseProduct.id}/fields/${field.id}`);
    ui.toast(t('products.toast.fieldDeleted', result.removedProductFields), 'ok');
    await refreshBaseKeepingLock(base.baseProduct.id);
  } catch (error) {
    ui.showError(error);
  }
}

async function reorderBaseFields(fieldIds) {
  const base = state.base;
  if (!base) {
    return;
  }
  try {
    await api.post(`/api/catalog/base-products/${base.baseProduct.id}/fields/reorder`, { fieldIds });
    await refreshBaseKeepingLock(base.baseProduct.id);
  } catch (error) {
    ui.showError(error);
    await refreshBaseKeepingLock(base.baseProduct.id);
  }
}

// ------------------------------------------------------------------ products

function openProductCreator() {
  const base = state.base;
  if (!base) {
    return;
  }
  const modal = ui.openModal(t('products.btn.newProduct'), `
    <div class="hint" style="margin-bottom:10px">${ui.escapeHtml(base.baseProduct.code)} · ${ui.escapeHtml(base.baseProduct.name)}</div>
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('products.form.productCode'))}</label>
        <input id="prCode" type="text" placeholder="IRS_SIMPLE"></div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.displayName'))}</label>
        <input id="prName" type="text"></div>
    </div>
    <div class="field"><label>${ui.escapeHtml(t('products.form.description'))}</label>
      <input id="prDesc" type="text" placeholder="${ui.escapeHtml(t('products.form.optionalHint'))}">
    </div>
    <div class="panel-note">${ui.escapeHtml(t('products.form.newProductHint', base.fields.length))}</div>`,
  `<button class="tool-btn" id="prCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="prSave">${ui.escapeHtml(t('common.add'))}</button>`);

  modal.root.querySelector('#prCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#prSave').addEventListener('click', async () => {
    const code = modal.root.querySelector('#prCode').value.trim().toUpperCase();
    const name = modal.root.querySelector('#prName').value.trim();
    if (!code) {
      ui.toast(t('products.toast.codeRequired'), 'error');
      return;
    }
    try {
      const created = await api.post(`/api/catalog/base-products/${base.baseProduct.id}/products`, {
        code,
        name: name || code,
        description: modal.root.querySelector('#prDesc').value.trim() || null,
        excludedFieldKeys: [],
      });
      modal.close();
      ui.toast(t('products.toast.productCreated', created.code), 'ok');
      await refreshBaseKeepingLock(base.baseProduct.id);
    } catch (error) {
      ui.showError(error);
    }
  });
}

function openProductEditor(product) {
  const modal = ui.openModal(`${t('common.edit')} · ${product.code}`, `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('products.form.displayName'))}</label>
        <input id="peName" type="text" value="${ui.escapeHtml(product.name)}"></div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.strict'))}</label>
        <label class="switch ${product.strictMatch ? 'on' : ''}">
          <input type="checkbox" id="peStrict" ${product.strictMatch ? 'checked' : ''}>
          <span class="switch-track"><span class="switch-thumb"></span></span>
          <span class="switch-label">${ui.escapeHtml(product.strictMatch
    ? t('products.form.strictOn') : t('products.form.strictOff'))}</span>
        </label>
        <div class="hint">${ui.escapeHtml(t('products.form.strictHint'))}</div>
      </div>
    </div>
    <div class="field"><label>${ui.escapeHtml(t('products.form.description'))}</label>
      <input id="peDesc" type="text" value="${ui.escapeHtml(product.description || '')}"></div>`,
  `<button class="tool-btn" id="peCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="peSave">${ui.escapeHtml(t('common.save'))}</button>`);
  const strictInput = modal.root.querySelector('#peStrict');
  const strictLabel = modal.root.querySelector('.switch-label');
  strictInput.addEventListener('change', () => {
    strictInput.closest('.switch').classList.toggle('on', strictInput.checked);
    strictLabel.textContent = strictInput.checked ? t('products.form.strictOn') : t('products.form.strictOff');
  });
  modal.root.querySelector('#peCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#peSave').addEventListener('click', async () => {
    try {
      await api.patch(`/api/catalog/products/${product.id}`, {
        name: modal.root.querySelector('#peName').value.trim(),
        description: modal.root.querySelector('#peDesc').value.trim() || null,
        strictMatch: strictInput.checked,
      });
      modal.close();
      ui.toast(t('products.toast.productSaved'), 'ok');
      await refreshBaseKeepingLock(state.baseId);
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function deleteProduct(product) {
  if (!window.confirm(t('products.confirm.deleteProduct', product.code))) {
    return;
  }
  try {
    await api.del(`/api/catalog/products/${product.id}`);
    ui.toast(t('products.toast.productDeleted'), 'ok');
    if (state.productId === product.id) {
      closeDrawer();
    }
    await refreshBaseKeepingLock(state.baseId);
  } catch (error) {
    ui.showError(error);
  }
}

// ------------------------------------------------------------- product drawer

function closeDrawer() {
  const host = ui.byId('productDrawer');
  if (host) {
    host.remove();
  }
  state.productId = null;
  state.productFields = [];
  drawerTable = null;
}

function lockButtonHtml() {
  return `<button class="icon-btn" id="pdLock" aria-pressed="false" title="${ui.escapeHtml(t('products.lock.unlock'))}">
      <svg class="ico lock-on" width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor"
           stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M4.6 7.2V5.4a3.4 3.4 0 0 1 6.8 0v1.8M3.6 7.2h8.8v5.4a1 1 0 0 1-1 1H4.6a1 1 0 0 1-1-1V7.2z"></path>
      </svg>
      <svg class="ico lock-off" width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor"
           stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M4.6 7.2V5.4a3.4 3.4 0 0 1 6.6-1.1M3.6 7.2h8.8v5.4a1 1 0 0 1-1 1H4.6a1 1 0 0 1-1-1V7.2z"></path>
      </svg>
    </button>`;
}

async function openProductDrawer(productId) {
  state.productId = productId;
  const detail = await api.get(`/api/catalog/products/${productId}`);
  renderDrawer(detail);
}

function renderDrawer(detail) {
  closeDrawer();
  state.productId = detail.product.id;
  state.productFields = detail.fields || [];
  state.drawerLocked = true;
  const product = detail.product;
  const overlay = document.createElement('div');
  overlay.className = 'drawer-backdrop';
  overlay.id = 'productDrawer';
  overlay.innerHTML = `
    <div class="drawer wide" role="dialog" aria-modal="true">
      <div class="drawer-head">
        <div class="panel-title-block">
          <h3>${ui.escapeHtml(product.code)} · ${ui.escapeHtml(product.name)}</h3>
          <div class="panel-sub">${ui.escapeHtml(t('common.fields'))} ${product.fieldCount}
            · ${ui.escapeHtml(t('common.cases'))} ${product.caseCount}</div>
        </div>
        <button class="modal-close" aria-label="${ui.escapeHtml(t('common.close'))}">×</button>
      </div>
      <div class="drawer-body">
        <div class="drawer-hint">
          <div class="hint">${ui.escapeHtml(t('products.drawer.inheritHint'))}</div>
          <div class="row-actions">
            ${lockButtonHtml()}
            <button class="icon-btn" id="pdSync" title="${ui.escapeHtml(t('products.drawer.syncHint'))}">
              <svg class="ico" width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                   stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M13.2 8a5.2 5.2 0 1 1-1.6-3.7M13.2 2.4V5.2h-2.8"></path>
              </svg>
            </button>
          </div>
        </div>
        <div id="productFieldTable">
          <div class="filters-bar">
            <div class="filter-input">
              <svg class="ico" width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                   stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="7" cy="7" r="4.5"></circle>
                <path d="M10.4 10.4L13.6 13.6"></path>
              </svg>
              <input type="search" data-ft="search" autocomplete="off"
                     placeholder="${ui.escapeHtml(t('products.field.searchPlaceholder'))}">
            </div>
            <select data-ft="type" aria-label="value type"></select>
            <select data-ft="role" aria-label="role"></select>
            <select data-ft="tag" aria-label="tag"></select>
            <button class="toggle-btn" data-ft="required">${ui.escapeHtml(t('products.field.requiredOnly'))}</button>
            <button class="icon-btn sm" data-ft="reset" title="${ui.escapeHtml(t('common.reset'))}">
              <svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                   stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M2.8 8a5.2 5.2 0 1 0 1.6-3.7M2.8 2.4V5.2h2.8"></path>
              </svg>
            </button>
            <span class="filters-count" data-ft="count"></span>
          </div>
          <div class="notice-bar hidden" data-ft="notice"></div>
          <div class="filters" data-ft="chips"></div>
          <div class="table-wrap">
            <table class="table-dense field-table">
              <thead><tr data-ft="head"></tr></thead>
              <tbody data-ft="body"></tbody>
            </table>
          </div>
        </div>
      </div>
    </div>`;
  document.body.appendChild(overlay);

  overlay.querySelector('.modal-close').addEventListener('click', closeDrawer);
  overlay.addEventListener('click', (event) => {
    if (event.target === overlay) {
      closeDrawer();
    }
  });

  const lockButton = overlay.querySelector('#pdLock');
  lockButton.addEventListener('click', () => {
    state.drawerLocked = !state.drawerLocked;
    lockButton.setAttribute('aria-pressed', String(!state.drawerLocked));
    lockButton.title = state.drawerLocked ? t('products.lock.unlock') : t('products.lock.lock');
    drawerTable.setLocked(state.drawerLocked);
  });
  overlay.querySelector('#pdSync').addEventListener('click', async () => {
    if (state.drawerLocked) {
      ui.toast(t('products.lock.actionHint'), 'error');
      return;
    }
    try {
      await api.post(`/api/catalog/products/${product.id}/sync`, { addMissing: true });
      ui.toast(t('products.toast.synced'), 'ok');
      await refreshDrawerKeepingLock(product.id);
      await refreshBaseKeepingLock(state.baseId);
    } catch (error) {
      ui.showError(error);
    }
  });

  drawerTable = createFieldTable({
    root: overlay.querySelector('#productFieldTable'),
    showBaseOrder: true,
    locked: state.drawerLocked,
    onEdit: (field) => openProductFieldEditor(product, field),
    onDelete: (field) => removeProductField(product, field),
    onReorder: (fieldIds) => reorderProductFields(product.id, fieldIds),
    onLockedAction: () => ui.toast(t('products.lock.actionHint'), 'error'),
  });
  drawerTable.setOptions(state.options);
  drawerTable.setFields(detail.fields);
}

/** Marks the drawer as editable, keeping the freshly rendered table in sync. */
function unlockDrawer() {
  state.drawerLocked = false;
  const lock = ui.byId('pdLock');
  if (lock) {
    lock.setAttribute('aria-pressed', 'true');
    lock.title = t('products.lock.lock');
  }
  if (drawerTable) {
    drawerTable.setLocked(false);
  }
}

/** Reloads the product detail and restores the previous edit lock state. */
async function refreshDrawerKeepingLock(productId) {
  const locked = state.drawerLocked;
  const detail = await api.get(`/api/catalog/products/${productId}`);
  renderDrawer(detail);
  if (locked) {
    if (drawerTable) {
      drawerTable.setLocked(true);
    }
  } else {
    unlockDrawer();
  }
}

function openProductFieldEditor(product, field) {
  if (state.drawerLocked) {
    ui.toast(t('products.lock.actionHint'), 'error');
    return;
  }
  const businessField = field.roleCode === 'BUSINESS';
  const requiredLocked = businessField || field.requiredFromBase;
  const requiredHint = businessField
    ? t('products.form.businessRequiredHint')
    : t('products.drawer.requiredLocked');
  const modal = ui.openModal(`${t('products.btn.productFields')} · ${field.header}`, `
    <div class="params">
      <div class="field">
        <label>${ui.escapeHtml(t('products.form.required'))}</label>
        <select id="pfRequired" ${requiredLocked ? 'disabled' : ''}>
          <option value="false" ${field.required ? '' : 'selected'}>${ui.escapeHtml(t('common.optional'))}</option>
          <option value="true" ${field.required ? 'selected' : ''}>${ui.escapeHtml(t('common.required'))}</option>
        </select>
        ${requiredLocked ? `<div class="hint">${ui.escapeHtml(requiredHint)}</div>` : ''}
      </div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.order'))}</label>
        <input id="pfOrder" type="number" min="1" value="${field.sortOrder}"></div>
    </div>
    <div class="field" style="margin-bottom:10px"><label>${ui.escapeHtml(t('products.form.tags'))}</label>${tagSelector(field.tags)}</div>
    <div class="field"><label>${ui.escapeHtml(t('products.form.remark'))}</label>
      <input id="pfRemark" type="text" value="${ui.escapeHtml(field.remark || '')}"></div>`,
  `<button class="tool-btn" id="pfCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="pfSave">${ui.escapeHtml(t('common.save'))}</button>`);
  bindTagPills(modal.root);

  modal.root.querySelector('#pfCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#pfSave').addEventListener('click', async () => {
    const order = Number(modal.root.querySelector('#pfOrder').value) || field.sortOrder;
    if (orderTaken(state.productFields, order, field.id)) {
      ui.toast(t('products.toast.orderTaken', order), 'error');
      return;
    }
    const requiredSelect = modal.root.querySelector('#pfRequired');
    const required = businessField
      ? true
      : requiredSelect.disabled ? field.required : requiredSelect.value === 'true';
    const payload = {
      required,
      sortOrder: order,
      tags: readSelectedTags(modal.root),
      remark: modal.root.querySelector('#pfRemark').value.trim() || null,
    };
    try {
      await api.patch(`/api/catalog/products/${product.id}/fields/${field.id}`, payload);
      modal.close();
      ui.toast(t('products.toast.productFieldSaved'), 'ok');
      await refreshDrawerKeepingLock(product.id);
      await refreshBaseKeepingLock(state.baseId);
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function removeProductField(product, field) {
  if (state.drawerLocked) {
    ui.toast(t('products.lock.actionHint'), 'error');
    return;
  }
  if (!window.confirm(t('products.confirm.removeProductField', field.header))) {
    return;
  }
  try {
    await api.del(`/api/catalog/products/${product.id}/fields/${field.id}`);
    ui.toast(t('products.toast.productFieldRemoved'), 'ok');
    await refreshDrawerKeepingLock(product.id);
    await refreshBaseKeepingLock(state.baseId);
  } catch (error) {
    ui.showError(error);
  }
}

async function reorderProductFields(productId, fieldIds) {
  try {
    await api.post(`/api/catalog/products/${productId}/fields/reorder`, { fieldIds });
    await refreshDrawerKeepingLock(productId);
    await refreshBaseKeepingLock(state.baseId);
  } catch (error) {
    ui.showError(error);
    await refreshDrawerKeepingLock(productId);
  }
}

// -------------------------------------------------------------- global search

const SEARCH_GROUPS = ['BASE_PRODUCT', 'PRODUCT', 'BASE_FIELD', 'PRODUCT_FIELD'];

function searchRow(hit, query) {
  const platform = `<span class="chip muted">${ui.escapeHtml(hit.platformCode)}</span>`;
  const base = `<span class="mono">${ui.escapeHtml(hit.baseProductCode)}</span>`;
  const isField = hit.kind === 'BASE_FIELD' || hit.kind === 'PRODUCT_FIELD';
  const productPart = hit.kind === 'PRODUCT' || hit.kind === 'PRODUCT_FIELD'
    ? `<span class="muted">›</span><span class="mono">${ui.escapeHtml(hit.productCode || '')}</span>`
    : '';
  const title = isField
    ? ui.highlight(ui.escapeHtml(hit.header || ''), query)
    : ui.highlight(ui.escapeHtml(hit.baseProductName || hit.baseProductCode), query);
  const matchBadge = `<span class="chip warn">${ui.escapeHtml(t(`search.match.${hit.matchedIn}`))}</span>`;
  const tagBadges = (hit.tags || [])
    .map((tag) => {
      const item = metaItem('FIELD_TAG', tag);
      return `<span class="chip muted">${ui.escapeHtml(item ? item.label : tag)}</span>`;
    })
    .join('');
  const showSnippet = !(isField && hit.matchedIn === 'HEADER');
  const snippetHtml = showSnippet ? ui.highlight(ui.escapeHtml(hit.snippet || ''), query) : '';
  return `<div class="gs-row" role="button" tabindex="0"
      data-kind="${hit.kind}" data-platform="${ui.escapeHtml(hit.platformCode)}"
      data-base="${ui.escapeHtml(hit.baseProductId)}" data-product="${ui.escapeHtml(hit.productId || '')}"
      data-field="${ui.escapeHtml(hit.fieldId || '')}">
    <span class="gs-path">${platform}${base}${productPart}</span>
    <span class="gs-title">${title}${isField ? tagBadges : ''}</span>
    <span class="gs-snippet">${matchBadge}${snippetHtml ? ` ${snippetHtml}` : ''}</span>
    <span class="gs-goto">${ui.escapeHtml(t('search.goto'))} ›</span>
  </div>`;
}

let searchTimer = null;
let searchSequence = 0;

function platformLabel() {
  const platform = currentPlatform();
  return platform ? platform.name || platform.code : (state.platformCode || '—');
}

/** Opens the global catalogue search dialog for the current platform. */
function openGlobalSearch() {
  const modal = ui.openModal(t('search.title'), `
    <input class="gs-input" id="gsInput" type="search" autocomplete="off"
      placeholder="${ui.escapeHtml(t('search.placeholder'))}">
    <div class="gs-results" id="gsResults"></div>`);
  modal.root.classList.add('gs-modal');

  const input = modal.root.querySelector('#gsInput');
  const results = modal.root.querySelector('#gsResults');
  const scopeText = t('search.scopePlatform', platformLabel());

  let timer = null;
  let sequence = 0;

  const render = (data, query) => {
    const head = `<div class="gs-results-head">
        <span>${ui.escapeHtml(scopeText)}</span>
        <span>${ui.escapeHtml(t('search.resultCount', data.total))}</span>
      </div>`;
    if (!data.hits.length) {
      results.innerHTML = `${head}<div class="gs-empty">${ui.escapeHtml(t('search.noResults'))}</div>`;
      return;
    }
    results.innerHTML = head + SEARCH_GROUPS
      .map((kind) => {
        const hits = data.hits.filter((hit) => hit.kind === kind);
        if (!hits.length) {
          return '';
        }
        return `<section class="gs-group">
          <div class="gs-group-head">${ui.escapeHtml(t(`search.group.${kind}`))}
            <span class="count">${hits.length}</span></div>
          ${hits.map((hit) => searchRow(hit, query)).join('')}
        </section>`;
      })
      .join('');
    results.querySelectorAll('.gs-row').forEach((row) => {
      const activate = () => {
        modal.close();
        locate(row.dataset);
      };
      row.addEventListener('click', activate);
      row.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          activate();
        }
      });
    });
  };

  const run = async () => {
    const query = input.value.trim();
    const current = ++sequence;
    if (!query) {
      results.innerHTML = '';
      return;
    }
    const platform = encodeURIComponent(state.platformCode || '');
    try {
      const data = await api.get(`/api/catalog/search?platform=${platform}&q=${encodeURIComponent(query)}`);
      if (current === sequence) {
        render(data, query);
      }
    } catch (error) {
      if (current === sequence) {
        ui.showError(error);
      }
    }
  };

  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(run, 180);
  });
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      clearTimeout(timer);
      run();
    }
  });
  setTimeout(() => input.focus(), 40);
}

async function locate(dataset) {
  try {
    state.platformCode = dataset.platform;
    await loadPlatforms();
    await loadBase(dataset.base);
    if (dataset.kind === 'BASE_PRODUCT') {
      ui.flashElement(document.querySelector(`#baseList .item[data-base="${dataset.base}"]`));
      return;
    }
    if (dataset.kind === 'PRODUCT' || dataset.kind === 'PRODUCT_FIELD') {
      await openProductDrawer(dataset.product);
      if (dataset.kind === 'PRODUCT_FIELD' && drawerTable) {
        drawerTable.focusField(dataset.field);
      }
      return;
    }
    if (baseTable) {
      baseTable.focusField(dataset.field);
    }
  } catch (error) {
    ui.showError(error);
  }
}

// ---------------------------------------------------------------------- init

async function init() {
  const ctx = await ui.initShell();
  if (!ctx) {
    return;
  }
  try {
    state.options = await api.get('/api/meta/options');
  } catch (error) {
    ui.showError(error);
  }

  baseTable = createFieldTable({
    root: ui.byId('baseFieldTable'),
    showBaseOrder: false,
    locked: true,
    onEdit: (field) => openFieldEditor(field),
    onDelete: (field) => deleteBaseField(field),
    onReorder: (fieldIds) => reorderBaseFields(fieldIds),
    onLockedAction: () => ui.toast(t('products.lock.actionHint'), 'error'),
  });
  baseTable.setOptions(state.options);

  ui.on('btnNewBase', 'click', openBaseEditor);
  ui.on('btnEditBase', 'click', openBaseMetaEditor);
  ui.on('btnDeleteBase', 'click', deleteBase);
  ui.on('btnAddField', 'click', () => openFieldEditor(null));
  ui.on('btnNewProduct', 'click', openProductCreator);
  ui.on('btnGlobalSearch', 'click', openGlobalSearch);
  ui.on('btnLockBase', 'click', () => {
    if (!state.base) {
      return;
    }
    state.baseLocked = !state.baseLocked;
    baseTable.setLocked(state.baseLocked);
    applyLockUi();
  });
  document.addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault();
      openGlobalSearch();
    }
  });
  await refreshAll();
}

init();
