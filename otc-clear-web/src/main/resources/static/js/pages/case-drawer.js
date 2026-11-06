import { get, patch } from '../api.js';
import * as ui from '../ui.js';

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

  async function load() {
    renderHeader();
    try {
      view = await get(`/api/cases/${encodeURIComponent(ids[index])}/vertical`);
      detail = null;
      editing = false;
      backdrop.querySelector('#drawerEdit').textContent = '编辑';
      renderHeader();
      renderRows();
    } catch (error) {
      ui.showError(error);
    }
  }

  function renderEditor() {
    const body = backdrop.querySelector('#drawerBody');
    const trade = view.sections.find((section) => section.code === 'trade');
    const managed = view.sections.find((section) => section.code === 'managed');
    const rows = (trade ? trade.rows : []).map((row) => `
      <div class="vrow">
        <div class="vkey">${ui.escapeHtml(row.header)}${row.formula ? `<div class="vformula">ƒ ${ui.escapeHtml(row.formula)}</div>` : ''}</div>
        <div><input type="text" data-field="${ui.escapeHtml(row.fieldKey)}" value="${ui.escapeHtml(row.value)}"
          style="width:100%;padding:6px 8px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--text)"></div>
        <div class="mono-sm">${ui.escapeHtml(row.type)}</div>
      </div>`).join('');
    const managedRows = (managed ? managed.rows : []).map((row) => `
      <div class="vrow">
        <div class="vkey">${ui.escapeHtml(row.header)}</div>
        <div><input type="text" data-managed="${ui.escapeHtml(row.fieldKey)}" value="${ui.escapeHtml(row.value)}"
          style="width:100%;padding:6px 8px;border:1px solid var(--border);border-radius:6px;background:var(--surface);color:var(--text)"></div>
        <div class="mono-sm">TEXT</div>
      </div>`).join('');
    body.innerHTML = `
      <div class="vertical-section"><h4>交易字段</h4>${rows}</div>
      ${managedRows ? `<div class="vertical-section"><h4>管理字段</h4>${managedRows}</div>` : ''}
      <div class="row-actions" style="margin-top:14px">
        <button class="tool-btn primary" id="saveCaseBtn">保存</button>
        <button class="tool-btn" id="cancelEditBtn">取消</button>
      </div>
      <p class="muted" style="font-size:12px;margin-top:10px">公式字段的修改只影响导出值，公式原文会保留；并发编辑会返回 409。</p>`;
    body.querySelector('#cancelEditBtn').addEventListener('click', () => {
      editing = false;
      backdrop.querySelector('#drawerEdit').textContent = '编辑';
      renderRows();
    });
        body.querySelector('#saveCaseBtn').addEventListener('click', async () => {
      try {
        if (!detail) {
          detail = await get(`/api/cases/${encodeURIComponent(ids[index])}`);
        }
        const values = {};
        const managedValues = {};
        body.querySelectorAll('input[data-field]').forEach((input) => {
          values[input.dataset.field] = input.value;
        });
        body.querySelectorAll('input[data-managed]').forEach((input) => {
          managedValues[input.dataset.managed] = input.value;
        });
        await patch(`/api/cases/${encodeURIComponent(ids[index])}`,
          { version: detail.version, values, managed: managedValues });
        ui.toast('已保存', 'ok');
        await load();
        if (options.onChanged) {
          options.onChanged();
        }
      } catch (error) {
        ui.showError(error);
      }
    });
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
  ['fieldSearch'].forEach((id) => backdrop.querySelector(`#${id}`)
    .addEventListener('input', () => { if (!editing) { renderRows(); } }));
  ['onlyFormula', 'onlyEmpty'].forEach((id) => backdrop.querySelector(`#${id}`)
    .addEventListener('change', () => { if (!editing) { renderRows(); } }));

  await load();
  return { close };
}
