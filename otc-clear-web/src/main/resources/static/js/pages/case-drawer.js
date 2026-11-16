import { get, patch } from '../api.js';
import * as ui from '../ui.js';

const ADV_MODES = [
  ['KEEP', '不修改'],
  ['VALUE', '设置为值'],
  ['FORMULA', '设置为公式'],
  ['CLEAR', '清空'],
];

let metaSets = null;
const setItemsCache = new Map();

/** Meta sets used by the advanced editor, loaded once per page. */
async function loadMetaSets() {
  if (!metaSets) {
    metaSets = (await get('/api/meta/sets')).map((entry) => ({ ...entry.set }));
  }
  return metaSets;
}

/** Enabled items of one meta set, cached per code. */
async function loadSetItems(code) {
  if (!setItemsCache.has(code)) {
    setItemsCache.set(code, await get(`/api/meta/sets/${encodeURIComponent(code)}/items`));
  }
  return setItemsCache.get(code);
}

/** Value contributed by a set item: its content when present, otherwise its code. */
async function setItemText(setCode, itemCode) {
  if (!setCode || !itemCode) {
    return '';
  }
  const items = await loadSetItems(setCode);
  const item = items.find((entry) => entry.code === itemCode);
  if (!item) {
    return '';
  }
  const content = (item.content || '').trim();
  return content || item.code;
}

/**
 * Opens the vertical case drawer.
 *
 * @param {string[]} ids case ids of the current page, used for previous/next navigation
 * @param {number} startIndex index of the case to open
 * @param {{onChanged?: Function}} options callback after an edit
 */
export async function openCaseDrawer(ids, startIndex, options = {}) {
  const host = ui.byId('drawerHost') || document.body;
  const backdrop = document.createElement('div');
  backdrop.className = 'drawer-backdrop';
  backdrop.innerHTML = `
    <div class="drawer" role="dialog" aria-modal="true">
      <div class="drawer-head">
        <div>
          <h3 id="drawerTitle">用例</h3>
          <div class="mono-sm" id="drawerSub"></div>
        </div>
        <div class="row-actions">
          <button class="tool-btn small" id="drawerPrev">‹ 上一条</button>
          <button class="tool-btn small" id="drawerNext">下一条 ›</button>
          <button class="tool-btn small" id="drawerEdit">编辑</button>
          <button class="modal-close" id="drawerClose" aria-label="关闭">×</button>
        </div>
      </div>
      <div class="drawer-tools">
        <input type="search" id="fieldSearch" placeholder="搜索字段名 / 值…" style="flex:1 1 180px">
        <label class="muted" style="display:flex;gap:6px;align-items:center;font-size:12px">
          <input type="checkbox" id="onlyFormula"> 仅公式
        </label>
        <label class="muted" style="display:flex;gap:6px;align-items:center;font-size:12px">
          <input type="checkbox" id="onlyEmpty"> 仅空值
        </label>
      </div>
      <div class="drawer-body" id="drawerBody"></div>
    </div>`;
  host.appendChild(backdrop);

  let index = Math.max(0, Math.min(startIndex, ids.length - 1));
  let view = null;
  let detail = null;
  let editing = false;
  let advanced = false;

  const close = () => backdrop.remove();
  backdrop.querySelector('#drawerClose').addEventListener('click', close);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) {
      close();
    }
  });

  const filters = () => ({
    text: (backdrop.querySelector('#fieldSearch').value || '').toLowerCase(),
    onlyFormula: backdrop.querySelector('#onlyFormula').checked,
    onlyEmpty: backdrop.querySelector('#onlyEmpty').checked,
  });

  function renderRows() {
    const body = backdrop.querySelector('#drawerBody');
    if (!view) {
      body.innerHTML = '<div class="muted">加载中…</div>';
      return;
    }
    const current = filters();
    const sections = view.sections.map((section) => {
      const visible = section.rows.filter((row) => {
        if (current.onlyFormula && !row.formula) {
          return false;
        }
        if (current.onlyEmpty && row.value !== '') {
          return false;
        }
        if (current.text) {
          const haystack = `${row.header} ${row.fieldKey} ${row.value}`.toLowerCase();
          if (!haystack.includes(current.text)) {
            return false;
          }
        }
        return true;
      });
      if (!visible.length) {
        return '';
      }
      const rows = visible.map((row) => `
        <div class="vrow ${row.key ? 'is-key' : ''}">
          <div class="vkey">${ui.escapeHtml(row.header)}<div class="mono-sm">${ui.escapeHtml(row.fieldKey)}</div></div>
          <div class="vval ${row.value === '' ? 'blank' : ''}">${row.value === '' ? '—' : ui.escapeHtml(row.value)}</div>
          <div class="vformula">${row.formula ? `ƒ ${ui.escapeHtml(row.formula)}` : ''}</div>
        </div>`).join('');
      return `<div class="vertical-section"><h4>${ui.escapeHtml(section.title)}</h4>${rows}</div>`;
    });
    body.innerHTML = sections.join('') || '<div class="muted">没有匹配的字段。</div>';
  }

  function renderHeader() {
    backdrop.querySelector('#drawerTitle').textContent = view ? (view.testCaseId || view.caseId.slice(0, 8)) : '用例';
    backdrop.querySelector('#drawerSub').textContent = view
      ? `${view.productCode} · 批次内第 ${view.rowNo} / ${view.rowCount} 条`
      : '';
    backdrop.querySelector('#drawerPrev').disabled = index <= 0;
    backdrop.querySelector('#drawerNext').disabled = index >= ids.length - 1;
  }

  const order = ui.sequence();

  async function load() {
    const token = order.next();
    renderHeader();
    try {
      const loaded = await get(`/api/cases/${encodeURIComponent(ids[index])}/vertical`);
      if (!token()) {
        // the user clicked "next" again while this request was running
        return;
      }
      view = loaded;
      detail = null;
      editing = false;
      backdrop.querySelector('#drawerEdit').textContent = '编辑';
      renderHeader();
      renderRows();
    } catch (error) {
      if (token()) {
        ui.showError(error);
      }
    }
  }

  function renderEditor() {
    const body = backdrop.querySelector('#drawerBody');
    const trade = view.sections.find((section) => section.code === 'trade');
    const managed = view.sections.find((section) => section.code === 'managed');
    const heading = `<div class="adv-head">
        <label class="adv-toggle"><input type="checkbox" id="advToggle"${advanced ? ' checked' : ''}> 高级模式</label>
        <span class="adv-count" id="editChangedCount">未修改</span>
        <span class="muted adv-head-hint">${advanced
    ? '按字段选择「设置为值 / 设置为公式 / 清空」，可从「字段元信息」集合取值；只提交被改动的字段。'
    : '基础模式：直接编辑字段值并整表保存；公式原文保留，改动只影响导出值。'}</span>
      </div>`;

    const rowHtml = (row, role) => (advanced ? advancedRow(row, role) : basicRow(row, role));
    const rows = (trade ? trade.rows : []).map((row) => rowHtml(row, 'trade')).join('');
    const managedRows = (managed ? managed.rows : []).map((row) => rowHtml(row, 'managed')).join('');
    body.innerHTML = `
      ${heading}
      <div class="vertical-section"><h4>交易字段</h4>${rows}</div>
      ${managedRows ? `<div class="vertical-section"><h4>管理字段</h4>${managedRows}</div>` : ''}
      <div class="row-actions" style="margin-top:14px">
        <button class="tool-btn primary" id="saveCaseBtn">保存</button>
        <button class="tool-btn" id="cancelEditBtn">取消</button>
      </div>
      <p class="muted" style="font-size:12px;margin-top:10px">${advanced
    ? '「设置为值」会移除该字段原有公式；「清空」同时清除值与公式；管理字段与唯一键字段不支持公式。'
    : '公式字段的修改只影响导出值，公式原文会保留；并发编辑会返回 409。'}</p>`;

    body.querySelector('#advToggle').addEventListener('change', (event) => {
      advanced = event.target.checked;
      renderEditor();
    });
    body.querySelector('#cancelEditBtn').addEventListener('click', () => {
      editing = false;
      backdrop.querySelector('#drawerEdit').textContent = '编辑';
      renderRows();
    });
    const saveButton = body.querySelector('#saveCaseBtn');
    saveButton.addEventListener('click', async () => {
      // a double click would send the same version twice and the second answer is a 409
      saveButton.disabled = true;
      try {
        if (advanced) {
          await saveAdvanced(body);
        } else {
          await saveBasic(body);
        }
      } finally {
        saveButton.disabled = false;
      }
    });
    if (advanced) {
      body.querySelectorAll('[data-adv-row]').forEach((row) => {
        row.querySelector('.adv-mode').addEventListener('change', () => {
          syncAdvancedRow(row).then(() => refreshMarks(body));
        });
        row.querySelector('.adv-set').addEventListener('change', () => {
          syncAdvancedRow(row).then(() => refreshMarks(body));
        });
      });
    }
    // every edit updates the "changed" markers of the rows
    body.addEventListener('input', () => refreshMarks(body));
    body.addEventListener('change', () => refreshMarks(body));
    refreshMarks(body);
  }

  /** Marks the rows the current edit session would change and counts them. */
  function refreshMarks(body) {
    let changed = 0;
    body.querySelectorAll('.vrow[data-mark-row]').forEach((row) => {
      let modified;
      if (advanced) {
        // advanced submits every row whose mode is not "keep"
        modified = row.querySelector('.adv-mode').value !== 'KEEP';
      } else {
        const input = row.querySelector('input[data-field], input[data-managed]');
        modified = Boolean(input) && input.value !== (row.dataset.original || '');
      }
      const mark = row.querySelector('.edit-mark');
      if (mark) {
        mark.hidden = !modified;
      }
      row.classList.toggle('is-modified', modified);
      if (modified) {
        changed += 1;
      }
    });
    const counter = body.querySelector('#editChangedCount');
    if (counter) {
      counter.textContent = changed ? `已修改 ${changed} 项` : '未修改';
      counter.classList.toggle('has-changes', changed > 0);
    }
  }

  /** Marker shown before the field name of a row that the edit would change. */
  function editMark() {
    return '<span class="edit-mark" hidden title="已修改">\u270e</span>';
  }

  /** Plain text inputs, one per field, saved as a whole record. */
  function basicRow(row, role) {
    const attr = role === 'trade' ? 'data-field' : 'data-managed';
    return `<div class="vrow" data-mark-row data-original="${ui.escapeHtml(row.value)}">
        <div class="vkey">${editMark()}${ui.escapeHtml(row.header)}${row.formula
    ? `<div class="vformula">ƒ ${ui.escapeHtml(row.formula)}</div>` : ''}</div>
        <div><input type="text" ${attr}="${ui.escapeHtml(row.fieldKey)}"
          value="${ui.escapeHtml(row.value)}" class="vrow-input"></div>
        <div class="mono-sm">${ui.escapeHtml(role === 'trade' ? row.type : 'TEXT')}</div>
      </div>`;
  }

  /** Editors with mode, meta set and value/formula controls, like the bulk update dialog. */
  function advancedRow(row, role) {
    const formulaOption = role === 'trade' && !row.key ? '<option value="FORMULA">设置为公式</option>' : '';
    const meta = role === 'trade'
      ? `${row.type}${row.key ? ' · 唯一键' : ''}`
      : 'TEXT · 管理字段';
    return `<div class="vrow adv-row" data-mark-row data-adv-row data-role="${role}"
        data-key="${ui.escapeHtml(row.fieldKey)}" data-header="${ui.escapeHtml(row.header)}">
        <div class="vkey">${editMark()}${ui.escapeHtml(row.header)}
          <div class="mono-sm">${ui.escapeHtml(row.fieldKey)} · ${ui.escapeHtml(meta)}</div>
          ${row.formula ? `<div class="vformula">ƒ ${ui.escapeHtml(row.formula)}</div>` : ''}</div>
        <div class="adv-controls">
          <select class="adv-mode" aria-label="方式">
            ${ADV_MODES.map(([value, label]) => (value === 'FORMULA' && !formulaOption
        ? '' : `<option value="${value}">${label}</option>`)).join('')}
          </select>
          <select class="adv-set" hidden aria-label="集合类型"><option value="">不指定（自由输入）</option></select>
          <input class="adv-input" type="text" hidden value="${ui.escapeHtml(row.value)}">
          <select class="adv-items" hidden aria-label="集合取值"></select>
        </div>
        <div class="mono-sm">${ui.escapeHtml(role === 'trade' ? row.type : 'TEXT')}</div>
      </div>`;
  }

  /** Shows the controls that belong to the current mode and keeps the set list in sync. */
  async function syncAdvancedRow(row) {
    const mode = row.querySelector('.adv-mode').value;
    const setSelect = row.querySelector('.adv-set');
    const input = row.querySelector('.adv-input');
    const items = row.querySelector('.adv-items');
    const keepsControls = mode === 'VALUE' || mode === 'FORMULA';
    setSelect.hidden = !keepsControls;
    if (!keepsControls) {
      input.hidden = true;
      items.hidden = true;
      return;
    }
    const wanted = mode === 'FORMULA' ? 'FORMULA' : 'VALUE';
    const previous = setSelect.value;
    let sets = [];
    try {
      sets = (await loadMetaSets()).filter((set) => (set.type || 'VALUE') === wanted);
    } catch (error) {
      ui.showError(error);
    }
    setSelect.innerHTML = ['<option value="">不指定（自由输入）</option>']
      .concat(sets.map((set) => `<option value="${ui.escapeHtml(set.code)}">`
        + `${ui.escapeHtml(set.name)} · ${set.itemCount}</option>`)).join('');
    setSelect.value = sets.some((set) => set.code === previous) ? previous : '';
    const setCode = setSelect.value;
    if (!setCode) {
      input.hidden = false;
      input.placeholder = mode === 'FORMULA' ? '=D2*E2' : '值';
      items.hidden = true;
      return;
    }
    try {
      const usable = (await loadSetItems(setCode)).filter((item) => item.enabled);
      items.innerHTML = usable.length
        ? usable.map((item) => `<option value="${ui.escapeHtml(item.code)}">`
          + `${ui.escapeHtml(item.label)} · ${ui.escapeHtml((item.content || '').trim() || item.code)}</option>`).join('')
        : '<option value="">该集合没有可用枚举值</option>';
    } catch (error) {
      ui.showError(error);
    }
    input.hidden = true;
    items.hidden = false;
  }

  async function currentDetail() {
    if (!detail) {
      detail = await get(`/api/cases/${encodeURIComponent(ids[index])}`);
    }
    return detail;
  }

  /** Basic mode: every input is sent as the new value of its field. */
  async function saveBasic(body) {
    try {
      const record = await currentDetail();
      const values = {};
      const managedValues = {};
      body.querySelectorAll('input[data-field]').forEach((input) => {
        values[input.dataset.field] = input.value;
      });
      body.querySelectorAll('input[data-managed]').forEach((input) => {
        managedValues[input.dataset.managed] = input.value;
      });
      await patch(`/api/cases/${encodeURIComponent(ids[index])}`,
        { version: record.version, values, managed: managedValues });
      ui.toast('已保存', 'ok');
      await load();
      if (options.onChanged) {
        options.onChanged();
      }
    } catch (error) {
      ui.showError(error);
    }
  }

  /** Advanced mode: only the rows whose mode is not "不改動" are submitted. */
  async function saveAdvanced(body) {
    const values = {};
    const managedValues = {};
    const formulas = {};
    try {
      for (const row of body.querySelectorAll('[data-adv-row]')) {
        const mode = row.querySelector('.adv-mode').value;
        if (mode === 'KEEP') {
          continue;
        }
        const key = row.dataset.key;
        const header = row.dataset.header;
        const setCode = row.querySelector('.adv-set').value;
        const itemCode = row.querySelector('.adv-items').value;
        let text = '';
        if (mode !== 'CLEAR') {
          text = setCode ? await setItemText(setCode, itemCode) : row.querySelector('.adv-input').value;
          if (setCode && !text) {
            ui.toast(`字段「${header}」所选集合没有可用枚举值`, 'error');
            return;
          }
          if (mode === 'FORMULA' && !String(text).trim().startsWith('=')) {
            ui.toast(`字段「${header}」的公式必须以 = 开头`, 'error');
            return;
          }
        }
        if (row.dataset.role === 'managed') {
          managedValues[key] = mode === 'CLEAR' ? '' : text;
        } else if (mode === 'CLEAR') {
          values[key] = '';
          formulas[key] = '';
        } else if (mode === 'FORMULA') {
          formulas[key] = String(text).trim();
        } else {
          values[key] = text;
          // a plain value wins over the stored formula, otherwise the export would recalculate it
          formulas[key] = '';
        }
      }
      if (!Object.keys(values).length && !Object.keys(formulas).length
          && !Object.keys(managedValues).length) {
        ui.toast('没有需要保存的改动', 'warn');
        return;
      }
      const record = await currentDetail();
      await patch(`/api/cases/${encodeURIComponent(ids[index])}`, {
        version: record.version, values, managed: managedValues, formulas,
      });
      ui.toast('已保存', 'ok');
      await load();
      if (options.onChanged) {
        options.onChanged();
      }
    } catch (error) {
      ui.showError(error);
    }
  }

  backdrop.querySelector('#drawerEdit').addEventListener('click', async () => {
    if (editing) {
      return;
    }
    try {
      detail = await get(`/api/cases/${encodeURIComponent(ids[index])}`);
      editing = true;
      backdrop.querySelector('#drawerEdit').textContent = '编辑中';
      renderEditor();
    } catch (error) {
      ui.showError(error);
    }
  });
  backdrop.querySelector('#drawerPrev').addEventListener('click', () => {
    if (index > 0) {
      index -= 1;
      load();
    }
  });
  backdrop.querySelector('#drawerNext').addEventListener('click', () => {
    if (index < ids.length - 1) {
      index += 1;
      load();
    }
  });
  const filter = ui.debounce(() => {
    if (!editing) {
      renderRows();
    }
  }, 180);
  backdrop.querySelector('#fieldSearch').addEventListener('input', filter);
  ['onlyFormula', 'onlyEmpty'].forEach((id) => backdrop.querySelector(`#${id}`)
    .addEventListener('change', () => { if (!editing) { renderRows(); } }));

  await load();
  return { close };
}
