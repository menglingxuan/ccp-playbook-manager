import * as api from '../api.js';
import * as ui from '../ui.js';

const t = ui.t;

const state = {
  sets: [],
  setCode: null,
};

function currentSet() {
  return state.sets.find((entry) => entry.set.code === state.setCode) || null;
}

function renderSetList() {
  const host = ui.byId('setList');
  if (!state.sets.length) {
    host.innerHTML = `<div class="item muted">${ui.escapeHtml(t('meta.sidebar.empty'))}</div>`;
    return;
  }
  host.innerHTML = state.sets
    .map(({ set }) => {
      const active = set.code === state.setCode ? 'active' : '';
      const kind = set.builtIn ? t('meta.builtIn') : t('meta.customSet');
      return `<div class="item ${active}" data-set="${ui.escapeHtml(set.code)}">
        <div class="item-id">${ui.escapeHtml(set.code)}</div>
        <div class="item-sub">${ui.escapeHtml(set.name)} · ${set.itemCount} · ${ui.escapeHtml(kind)}</div>
      </div>`;
    })
    .join('');
  host.querySelectorAll('.item[data-set]').forEach((node) => {
    node.addEventListener('click', () => {
      state.setCode = node.dataset.set;
      renderSetList();
      renderSetPanel();
    });
  });
}

function renderSetPanel() {
  const entry = currentSet();
  ui.byId('btnEditSet').disabled = !entry;
  ui.byId('btnDeleteSet').disabled = !entry || entry.set.builtIn;
  ui.byId('btnNewItem').disabled = !entry;
  ui.byId('setPlaceholder').classList.toggle('hidden', Boolean(entry));
  ui.byId('setMeta').classList.toggle('hidden', !entry);
  ui.byId('setTableWrap').classList.toggle('hidden', !entry);
  if (!entry) {
    ui.byId('setTitle').textContent = t('meta.panel.placeholder.title');
    return;
  }
  const set = entry.set;
  ui.byId('setTitle').textContent = `${set.code} · ${set.name}`;
  ui.byId('setMeta').innerHTML = `
    <div class="kv">
      <span class="k">${ui.escapeHtml(t('common.description'))}</span>
      <span class="v">${ui.escapeHtml(set.description || '—')}</span>
      <span class="k">${ui.escapeHtml(t('meta.column.items'))}</span>
      <span class="v">${set.itemCount}</span>
      <span class="k">${ui.escapeHtml(t('meta.column.type'))}</span>
      <span class="v">${ui.escapeHtml(set.builtIn ? t('meta.builtIn') : t('meta.customSet'))}</span>
    </div>`;

  const rows = entry.items
    .map((item) => `
      <tr data-item="${ui.escapeHtml(item.code)}">
        <td class="num">${item.sortOrder}</td>
        <td class="mono">${ui.escapeHtml(item.code)}</td>
        <td>${ui.escapeHtml(item.label)}</td>
        <td>${item.refSetCode
          ? `<span class="chip info">${ui.escapeHtml(item.refSetCode)}</span>`
          : '<span class="muted">—</span>'}</td>
        <td>${item.enabled
          ? `<span class="badge pass">${ui.escapeHtml(t('meta.enabled'))}</span>`
          : `<span class="badge muted">${ui.escapeHtml(t('meta.disabled'))}</span>`}</td>
        <td>${ui.longTextCell(item.remark, 26)}</td>
        <td>
          <div class="row-actions">
            ${ui.iconButton('edit', t('common.edit'), 'sm', 'data-act="edit"')}
            ${ui.iconButton('trash', t('common.delete'), 'sm danger', 'data-act="delete"')}
          </div>
        </td>
      </tr>`)
    .join('');
  const body = ui.byId('itemRows');
  body.innerHTML = rows || `<tr><td class="empty" colspan="7">${ui.escapeHtml(t('common.none'))}</td></tr>`;
  body.querySelectorAll('tr[data-item]').forEach((row) => {
    const item = entry.items.find((entryItem) => entryItem.code === row.dataset.item);
    row.querySelectorAll('button[data-act]').forEach((button) => {
      button.addEventListener('click', () => {
        if (button.dataset.act === 'edit') {
          openItemEditor(item);
        } else {
          deleteItem(item);
        }
      });
    });
  });
  ui.bindLongText(body);
}

async function load() {
  state.sets = await api.get('/api/meta/sets');
  if (!state.sets.some((entry) => entry.set.code === state.setCode)) {
    state.setCode = state.sets.length ? state.sets[0].set.code : null;
  }
  renderSetList();
  renderSetPanel();
}

function setOptions(excludeCode) {
  return state.sets
    .filter((entry) => entry.set.code !== excludeCode)
    .map((entry) => `<option value="${ui.escapeHtml(entry.set.code)}">${ui.escapeHtml(
      `${entry.set.code} · ${entry.set.name}`)}</option>`)
    .join('');
}

function openSetEditor() {
  const modal = ui.openModal(t('meta.btn.newSet'), `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('meta.form.setCode'))}</label>
        <input id="msCode" type="text" placeholder="CONV_METHOD"></div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.displayName'))}</label>
        <input id="msName" type="text"></div>
    </div>
    <div class="field"><label>${ui.escapeHtml(t('common.description'))}</label>
      <input id="msDesc" type="text" placeholder="${ui.escapeHtml(t('products.form.optionalHint'))}"></div>`,
  `<button class="tool-btn" id="msCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="msSave">${ui.escapeHtml(t('common.add'))}</button>`);
  modal.root.querySelector('#msCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#msSave').addEventListener('click', async () => {
    try {
      const created = await api.post('/api/meta/sets', {
        code: modal.root.querySelector('#msCode').value.trim().toUpperCase(),
        name: modal.root.querySelector('#msName').value.trim(),
        description: modal.root.querySelector('#msDesc').value.trim() || null,
      });
      modal.close();
      ui.toast(t('meta.toast.setCreated', created.code), 'ok');
      state.setCode = created.code;
      await load();
    } catch (error) {
      ui.showError(error);
    }
  });
}

function openSetMetaEditor() {
  const entry = currentSet();
  if (!entry) {
    return;
  }
  const set = entry.set;
  const modal = ui.openModal(`${t('meta.btn.editSet')} · ${set.code}`, `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('products.form.displayName'))}</label>
        <input id="esName" type="text" value="${ui.escapeHtml(set.name)}"></div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.sortOrder'))}</label>
        <input id="esOrder" type="number" min="0" value="${set.sortOrder}"></div>
    </div>
    <div class="field"><label>${ui.escapeHtml(t('common.description'))}</label>
      <input id="esDesc" type="text" value="${ui.escapeHtml(set.description || '')}"></div>`,
  `<button class="tool-btn" id="esCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="esSave">${ui.escapeHtml(t('common.save'))}</button>`);
  modal.root.querySelector('#esCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#esSave').addEventListener('click', async () => {
    try {
      await api.patch(`/api/meta/sets/${encodeURIComponent(set.code)}`, {
        code: set.code,
        name: modal.root.querySelector('#esName').value.trim(),
        description: modal.root.querySelector('#esDesc').value.trim() || null,
        sortOrder: Number(modal.root.querySelector('#esOrder').value) || set.sortOrder,
      });
      modal.close();
      ui.toast(t('meta.toast.setSaved'), 'ok');
      await load();
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function deleteSet() {
  const entry = currentSet();
  if (!entry) {
    return;
  }
  const set = entry.set;
  if (!window.confirm(t('meta.confirm.deleteSet', set.code))) {
    return;
  }
  try {
    await api.del(`/api/meta/sets/${encodeURIComponent(set.code)}`);
    ui.toast(t('meta.toast.setDeleted'), 'ok');
    state.setCode = null;
    await load();
  } catch (error) {
    ui.showError(error);
  }
}

function openItemEditor(item) {
  const entry = currentSet();
  if (!entry) {
    return;
  }
  const editing = Boolean(item);
  const modal = ui.openModal(editing ? `${t('common.edit')} · ${item.code}` : t('meta.btn.newItem'), `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('meta.form.itemCode'))}</label>
        <input id="miCode" type="text" value="${editing ? ui.escapeHtml(item.code) : ''}" ${editing ? 'readonly' : ''}></div>
      <div class="field"><label>${ui.escapeHtml(t('meta.form.label'))}</label>
        <input id="miLabel" type="text" value="${editing ? ui.escapeHtml(item.label) : ''}"></div>
      <div class="field"><label>${ui.escapeHtml(t('meta.form.refSet'))}</label>
        <select id="miRef">
          <option value="">${ui.escapeHtml(t('meta.form.noRef'))}</option>
          ${setOptions(entry.set.code)}
        </select>
      </div>
      <div class="field"><label>${ui.escapeHtml(t('products.form.sortOrder'))}</label>
        <input id="miOrder" type="number" min="0" value="${editing ? item.sortOrder : entry.items.length + 1}"></div>
      <div class="field"><label>${ui.escapeHtml(t('meta.form.status'))}</label>
        <select id="miEnabled">
          <option value="true" ${editing && !item.enabled ? '' : 'selected'}>${ui.escapeHtml(t('meta.enabled'))}</option>
          <option value="false" ${editing && !item.enabled ? 'selected' : ''}>${ui.escapeHtml(t('meta.disabled'))}</option>
        </select>
      </div>
    </div>
    <div class="field"><label>${ui.escapeHtml(t('common.remark'))}</label>
      <textarea id="miRemark" rows="2" placeholder="${ui.escapeHtml(t('products.form.remarkHint'))}">${
  editing ? ui.escapeHtml(item.remark || '') : ''}</textarea></div>
    <div class="hint">${ui.escapeHtml(t('meta.form.refHint'))}</div>`,
  `<button class="tool-btn" id="miCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="miSave">${ui.escapeHtml(t('common.save'))}</button>`);
  if (editing && item.refSetCode) {
    modal.root.querySelector('#miRef').value = item.refSetCode;
  }
  modal.root.querySelector('#miCancel').addEventListener('click', modal.close);
  modal.root.querySelector('#miSave').addEventListener('click', async () => {
    const order = Number(modal.root.querySelector('#miOrder').value) || null;
    if (order !== null && entry.items.some((other) => other.sortOrder === order
        && (!editing || other.code !== item.code))) {
      ui.toast(t('meta.toast.orderTaken', order), 'error');
      return;
    }
    const payload = {
      code: modal.root.querySelector('#miCode').value.trim().toUpperCase(),
      label: modal.root.querySelector('#miLabel').value.trim(),
      refSetCode: modal.root.querySelector('#miRef').value || null,
      sortOrder: order,
      enabled: modal.root.querySelector('#miEnabled').value === 'true',
      remark: modal.root.querySelector('#miRemark').value.trim() || null,
    };
    try {
      if (editing) {
        await api.patch(
          `/api/meta/sets/${encodeURIComponent(entry.set.code)}/items/${encodeURIComponent(item.code)}`, payload);
      } else {
        await api.post(`/api/meta/sets/${encodeURIComponent(entry.set.code)}/items`, payload);
      }
      modal.close();
      ui.toast(t('meta.toast.itemSaved'), 'ok');
      await load();
    } catch (error) {
      ui.showError(error);
    }
  });
}

async function deleteItem(item) {
  const entry = currentSet();
  if (!entry) {
    return;
  }
  if (!window.confirm(t('meta.confirm.deleteItem', item.label))) {
    return;
  }
  try {
    await api.del(`/api/meta/sets/${encodeURIComponent(entry.set.code)}/items/${encodeURIComponent(item.code)}`);
    ui.toast(t('meta.toast.itemDeleted'), 'ok');
    await load();
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  const ctx = await ui.initShell();
  if (!ctx) {
    return;
  }
  ui.on('btnNewSet', 'click', openSetEditor);
  ui.on('btnEditSet', 'click', openSetMetaEditor);
  ui.on('btnDeleteSet', 'click', deleteSet);
  ui.on('btnNewItem', 'click', () => openItemEditor(null));
  try {
    await load();
  } catch (error) {
    ui.showError(error);
  }
}

init();
