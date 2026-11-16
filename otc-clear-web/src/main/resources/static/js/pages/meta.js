import * as api from '../api.js';
import * as ui from '../ui.js';
import { bindDefinitionBackup } from '../definition-backup.js';
import { invalidateRules } from '../validation-editor.js';

const t = ui.t;

const state = {
  sets: [],
  setCode: null,
};

/** Display label of a meta set kind. */
function kindLabel(type) {
  if (type === 'FORMULA') {
    return t('meta.kind.formula');
  }
  if (type === 'TAG') {
    return t('meta.kind.tag');
  }
  return type === 'VALIDATION' ? t('meta.kind.validation') : t('meta.kind.value');
}

/** True when the current set holds field validation rules. */
function isRuleSet() {
  const entry = currentSet();
  return Boolean(entry && entry.set.type === 'VALIDATION');
}

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
      const setKind = kindLabel(set.type);
      return `<div class="item ${active}" data-set="${ui.escapeHtml(set.code)}">
        <div class="item-id">${ui.escapeHtml(set.code)}</div>
        <div class="item-sub">${ui.escapeHtml(set.name)} · ${set.itemCount} · ${ui.escapeHtml(setKind)}
          · ${ui.escapeHtml(kind)}</div>
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
  const setKind = kindLabel(set.type);
  ui.byId('setTitle').textContent = `${set.code} · ${set.name} · ${setKind}`;
  ui.byId('setMeta').innerHTML = `
    <div class="kv">
      <span class="k">${ui.escapeHtml(t('common.description'))}</span>
      <span class="v">${ui.escapeHtml(set.description || '—')}</span>
      <span class="k">${ui.escapeHtml(t('meta.column.items'))}</span>
      <span class="v">${set.itemCount}</span>
      <span class="k">${ui.escapeHtml(t('meta.form.kind'))}</span>
      <span class="v">${ui.escapeHtml(setKind)}</span>
      <span class="k">${ui.escapeHtml(t('meta.column.type'))}</span>
      <span class="v">${ui.escapeHtml(set.builtIn ? t('meta.builtIn') : t('meta.customSet'))}</span>
    </div>`;

  const rows = entry.items
    .map((item) => `
      <tr data-item="${ui.escapeHtml(item.code)}">
        <td class="num">${item.sortOrder}</td>
        <td class="mono">${ui.escapeHtml(item.code)}${
  item.readonly ? ' <span class="chip muted" title="业务处理依赖该取值，不可编辑或删除">必须</span>' : ''}</td>
        <td>${ui.escapeHtml(item.label)}</td>
        <td class="mono">${item.content ? ui.escapeHtml(item.content) : '<span class="muted">—</span>'}${
  set.type === 'VALIDATION' && item.content
    ? ` <span class="chip ${item.severity === 'ERROR' ? 'fail' : 'warn'}" title="${ui.escapeHtml(
      t('meta.form.severityHint'))}">${ui.escapeHtml(item.severity === 'ERROR'
      ? t('meta.severity.errorShort') : t('meta.severity.warnShort'))}</span>`
    : ''}</td>
        <td>${item.refSetCode
          ? `<span class="chip info">${ui.escapeHtml(item.refSetCode)}</span>`
          : '<span class="muted">—</span>'}</td>
        <td>${item.enabled
          ? `<span class="badge pass">${ui.escapeHtml(t('meta.enabled'))}</span>`
          : `<span class="badge muted">${ui.escapeHtml(t('meta.disabled'))}</span>`}</td>
        <td>${ui.longTextCell(item.remark, 26)}</td>
        <td>
          <div class="row-actions">
            ${item.readonly ? '' : ui.iconButton('edit', t('common.edit'), 'sm', 'data-act="edit"')}
            ${item.readonly ? '' : ui.iconButton('trash', t('common.delete'), 'sm danger', 'data-act="delete"')}
          </div>
        </td>
      </tr>`)
    .join('');
  const body = ui.byId('itemRows');
  body.innerHTML = rows || `<tr><td class="empty" colspan="8">${ui.escapeHtml(t('common.none'))}</td></tr>`;
  // the item of a row is looked up once per row instead of scanning the list for every row
  const itemsByCode = new Map(entry.items.map((item) => [item.code, item]));
  body.querySelectorAll('tr[data-item]').forEach((row) => {
    const item = itemsByCode.get(row.dataset.item);
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
      <div class="field"><label>${ui.escapeHtml(t('meta.form.kind'))}</label>
        <select id="msKind">
          <option value="VALUE">${ui.escapeHtml(t('meta.kind.value'))}</option>
          <option value="FORMULA">${ui.escapeHtml(t('meta.kind.formula'))}</option>
          <option value="TAG">${ui.escapeHtml(t('meta.kind.tag'))}</option>
          <option value="VALIDATION">${ui.escapeHtml(t('meta.kind.validation'))}</option>
        </select></div>
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
        type: modal.root.querySelector('#msKind').value,
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
      <div class="field"><label>${ui.escapeHtml(t('meta.form.kind'))}</label>
        <select id="esKind">
          <option value="VALUE"${set.type === 'VALUE' ? ' selected' : ''}>${
  ui.escapeHtml(t('meta.kind.value'))}</option>
          <option value="FORMULA"${set.type === 'FORMULA' ? ' selected' : ''}>${
  ui.escapeHtml(t('meta.kind.formula'))}</option>
          <option value="TAG"${set.type === 'TAG' ? ' selected' : ''}>${
  ui.escapeHtml(t('meta.kind.tag'))}</option>
          <option value="VALIDATION"${set.type === 'VALIDATION' ? ' selected' : ''}>${
  ui.escapeHtml(t('meta.kind.validation'))}</option>
        </select></div>
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
        type: modal.root.querySelector('#esKind').value,
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
  const isRule = entry.set.type === 'VALIDATION';
  const ruleType = editing && item.ruleType ? item.ruleType : 'REGEX';
  const severity = editing && item.severity ? item.severity : 'WARN';
  const ruleForm = isRule ? `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('meta.form.ruleType'))}</label>
        <select id="miRuleType">
          <option value="REGEX"${ruleType === 'REGEX' ? ' selected' : ''}>${ui.escapeHtml(t('meta.ruleType.regex'))}</option>
          <option value="WILDCARD"${ruleType === 'WILDCARD' ? ' selected' : ''}>${ui.escapeHtml(t('meta.ruleType.wildcard'))}</option>
        </select></div>
      <div class="field"><label>${ui.escapeHtml(t('meta.form.severity'))}</label>
        <select id="miSeverity">
          <option value="WARN"${severity === 'WARN' ? ' selected' : ''}>${ui.escapeHtml(t('meta.severity.warn'))}</option>
          <option value="ERROR"${severity === 'ERROR' ? ' selected' : ''}>${ui.escapeHtml(t('meta.severity.error'))}</option>
        </select>
        <div class="hint">${ui.escapeHtml(t('meta.form.severityHint'))}</div></div>
      <div class="field"><label>${ui.escapeHtml(t('meta.form.rulePattern'))}</label>
        <input id="miContent" type="text" class="mono" placeholder="${ui.escapeHtml(
    ruleType === 'REGEX' ? '^[A-Z]{3}$' : '???')}" value="${editing ? ui.escapeHtml(item.content || '') : ''}">
        <div class="hint" id="miPatternState"></div></div>
    </div>
    <div class="field"><label>${ui.escapeHtml(t('meta.form.ruleTest'))}</label>
      <div class="inline-row">
        <input id="miTestText" type="text" placeholder="${ui.escapeHtml(t('meta.form.ruleTestHint'))}">
        <button class="tool-btn" id="miTestRun" type="button">${ui.escapeHtml(t('meta.form.ruleTestRun'))}</button>
      </div>
      <div class="hint" id="miTestResult"></div></div>` : '';
  const modal = ui.openModal(editing ? `${t('common.edit')} · ${item.code}` : t('meta.btn.newItem'), `
    <div class="params">
      <div class="field"><label>${ui.escapeHtml(t('meta.form.itemCode'))}</label>
        <input id="miCode" type="text" value="${editing ? ui.escapeHtml(item.code) : ''}" ${editing ? 'readonly' : ''}>
        ${isRule ? `<div class="hint">${ui.escapeHtml(t('meta.form.ruleCodeHint'))}</div>` : ''}</div>
      <div class="field"><label>${ui.escapeHtml(t('meta.form.label'))}</label>
        <input id="miLabel" type="text" value="${editing ? ui.escapeHtml(item.label) : ''}">
        <div class="hint">${ui.escapeHtml(t('meta.form.labelHint'))}</div></div>
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
    ${ruleForm}
    <div class="field"><label>${ui.escapeHtml(t('common.remark'))}</label>
      <textarea id="miRemark" rows="2" placeholder="${ui.escapeHtml(t('products.form.remarkHint'))}">${
  editing ? ui.escapeHtml(item.remark || '') : ''}</textarea></div>
    ${isRule ? '' : `<div class="field"><label>${ui.escapeHtml(t('meta.form.content'))}</label>
      <textarea id="miContent" rows="2" placeholder="${ui.escapeHtml(
    entry.set.type === 'FORMULA' ? '=D2*E2' : t('meta.form.contentHint'))}">${
  editing ? ui.escapeHtml(item.content || '') : ''}</textarea></div>`}
    <div class="hint">${ui.escapeHtml(t('meta.form.contentHint'))}</div>
    <div class="hint">${ui.escapeHtml(t('meta.form.refHint'))}</div>`,
  `<button class="tool-btn" id="miCancel">${ui.escapeHtml(t('common.cancel'))}</button>
   <button class="tool-btn primary" id="miSave">${ui.escapeHtml(t('common.save'))}</button>`);
  if (editing && item.refSetCode) {
    modal.root.querySelector('#miRef').value = item.refSetCode;
  }
  const saveBtn = modal.root.querySelector('#miSave');
  const patternInput = modal.root.querySelector('#miContent');
  const stateEl = modal.root.querySelector('#miPatternState');
  const testResultEl = modal.root.querySelector('#miTestResult');
  let patternValid = !isRule;

  /**
   * Compiles the pattern on the server so the user sees syntax errors before saving.
   */
  const checkPattern = async () => {
    if (!isRule) {
      return true;
    }
    const expression = patternInput.value.trim();
    patternValid = false;
    saveBtn.disabled = true;
    if (!expression) {
      stateEl.className = 'hint';
      stateEl.textContent = t('meta.form.rulePatternRequired');
      return false;
    }
    try {
      const result = await api.post('/api/meta/rules/match', {
        ruleType: modal.root.querySelector('#miRuleType').value,
        expression,
        text: '',
      });
      patternValid = result.valid;
      stateEl.className = result.valid ? 'hint ok' : 'hint error';
      stateEl.textContent = result.valid ? t('meta.form.rulePatternOk') : result.message;
    } catch (error) {
      stateEl.className = 'hint error';
      stateEl.textContent = error.message || String(error);
    }
    saveBtn.disabled = !patternValid;
    return patternValid;
  };
  if (isRule) {
    let timer = null;
    patternInput.addEventListener('input', () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(checkPattern, 350);
    });
    modal.root.querySelector('#miRuleType').addEventListener('change', checkPattern);
    modal.root.querySelector('#miTestRun').addEventListener('click', async () => {
      if (!(await checkPattern())) {
        testResultEl.className = 'hint error';
        testResultEl.textContent = stateEl.textContent;
        return;
      }
      try {
        const result = await api.post('/api/meta/rules/match', {
          ruleType: modal.root.querySelector('#miRuleType').value,
          expression: patternInput.value.trim(),
          text: modal.root.querySelector('#miTestText').value,
        });
        testResultEl.className = result.matched ? 'hint ok' : 'hint error';
        testResultEl.textContent = result.matched
          ? t('meta.form.ruleTestMatched')
          : t('meta.form.ruleTestFailed');
      } catch (error) {
        testResultEl.className = 'hint error';
        testResultEl.textContent = error.message || String(error);
      }
    });
    checkPattern();
  }
  modal.root.querySelector('#miCancel').addEventListener('click', modal.close);
  saveBtn.addEventListener('click', async () => {
    if (isRule && !(await checkPattern())) {
      return;
    }
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
      content: modal.root.querySelector('#miContent').value.trim() || null,
      ruleType: isRule ? modal.root.querySelector('#miRuleType').value : null,
      severity: isRule ? modal.root.querySelector('#miSeverity').value : null,
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
  bindDefinitionBackup('META', {
    sql: 'btnExportSql',
    xlsx: 'btnExportXlsx',
    import: 'btnImportDefinitions',
  }, {
    label: t('definitions.scope.meta'),
    onApplied: async () => {
      invalidateRules();
      await load();
    },
  });
  try {
    await load();
  } catch (error) {
    ui.showError(error);
  }
}

init();
