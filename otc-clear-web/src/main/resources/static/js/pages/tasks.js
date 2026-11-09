import { del, get, patch, query } from '../api.js';
import * as ui from '../ui.js';

const state = { page: 1, size: 25, product: '', q: '', from: '', to: '', sort: '' };

const NAME_MAX_LENGTH = 256;

/** Sortable headers cycle through ascending, descending and the original order. */
const SORT_FIELDS = new Set(['name', 'batches', 'created']);

function debounce(fn, delay = 300) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function renderSummary(summary) {
  const cards = [
    { key: '', label: '全部任务', value: Object.values(summary).reduce((sum, value) => sum + value, 0) },
    { key: 'SUCCEEDED', label: '成功', value: summary.SUCCEEDED || 0, tone: 'ok' },
    { key: 'PARTIAL', label: '部分成功', value: summary.PARTIAL || 0, tone: 'warn' },
    { key: 'FAILED', label: '失败', value: summary.FAILED || 0, tone: 'bad' },
    { key: 'RUNNING', label: '进行中', value: summary.RUNNING || 0 },
  ];
  ui.setHtml('summaryCards', cards.map((card) => `
    <div class="card">
      <div class="card-label">${card.label}</div>
      <div class="card-value ${card.tone || ''}">${card.value}</div>
    </div>`).join(''));
}

async function loadSummary() {
  try {
    renderSummary(await get('/api/tasks/summary'));
  } catch (error) {
    ui.showError(error);
  }
}

function baseProductCell(task) {
  const codes = task.baseProducts || [];
  if (!codes.length) {
    return '<span class="muted">—</span>';
  }
  const text = codes.join(', ');
  return `<span class="base-products" title="${ui.escapeHtml(text)}">${ui.escapeHtml(text)}</span>`;
}

function sourceCell(task) {
  const missing = task.sourceExists === false;
  const tip = missing
    ? `文件已失效：${task.sourcePath || task.sourceName || ''}`
    : (task.sourcePath || task.sourceName || '');
  return `<td class="mono source-cell" title="${ui.escapeHtml(tip)}">`
    + `<span class="ellipsis${missing ? ' source-missing' : ''}">${ui.escapeHtml(task.sourceName)}</span></td>`;
}

function rowHtml(task) {
  return `
      <tr data-task="${ui.escapeHtml(task.id)}">
        <td class="mono id-cell" title="${ui.escapeHtml(task.id)}">
          <span class="ellipsis">${ui.escapeHtml(task.id)}</span>
        </td>
        <td class="name-cell">
          <button type="button" class="name-edit" data-rename="${ui.escapeHtml(task.id)}"
                  data-name="${ui.escapeHtml(task.name || '')}"
                  title="${ui.escapeHtml(task.name || '')}（点击重命名）">${ui.escapeHtml(task.name || '')}</button>
        </td>
        <td>${task.kind === 'EXTERNAL_OUTPUT' ? '<span class="chip muted">外部产出</span>' : '<span class="chip info">文件导入</span>'}</td>
        ${sourceCell(task)}
        <td class="num" title="${ui.escapeHtml(String(task.batchCount ?? 0))} 个批次">${task.batchCount ?? 0}</td>
        <td>${baseProductCell(task)}</td>
        <td class="mono">${ui.escapeHtml(task.createdAt)}</td>
        <td>
          <div class="row-actions">
            <a class="tool-btn small" href="/tasks/${encodeURIComponent(task.id)}">查看</a>
            <button class="tool-btn small danger" data-delete="${ui.escapeHtml(task.id)}">删除</button>
          </div>
        </td>
      </tr>`;
}

/** Paints the sort arrows of the header so the current order and direction stay visible. */
function renderSortHeaders() {
  document.querySelectorAll('#taskTable thead th[data-sort]').forEach((th) => {
    const field = th.dataset.sort;
    const active = state.sort.startsWith(field + '_');
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
    const result = await get('/api/tasks' + query({
      product: state.product, q: state.q, from: state.from, to: state.to, sort: state.sort,
      page: state.page, size: state.size,
    }));
    const rows = result.items.map(rowHtml);
    ui.setHtml('taskRows', rows.length ? rows.join('')
      : '<tr><td colspan="8" class="empty">还没有导入任务，点击「新建导入」上传用例文件</td></tr>');
    ui.byId('taskRows').querySelectorAll('button[data-delete]').forEach((button) => {
      button.addEventListener('click', () => removeTask(button.dataset.delete));
    });
    ui.byId('taskRows').querySelectorAll('button[data-rename]').forEach((button) => {
      button.addEventListener('click', () => startRename(button));
    });
    ui.renderPager('taskPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      load();
    });
  } catch (error) {
    ui.showError(error);
  }
}

/** Swaps the name cell into an input; Enter or blur commits, Escape cancels. */
function startRename(button) {
  if (button.dataset.editing === 'true') {
    return;
  }
  button.dataset.editing = 'true';
  const original = button.dataset.name;
  const input = document.createElement('input');
  input.type = 'text';
  input.className = 'name-input';
  input.maxLength = NAME_MAX_LENGTH;
  input.value = original;
  button.replaceWith(input);
  input.focus();
  input.select();

  let done = false;
  const finish = async (commit) => {
    if (done) {
      return;
    }
    done = true;
    const next = input.value.trim();
    if (!commit || next === original) {
      input.replaceWith(button);
      button.dataset.editing = 'false';
      return;
    }
    if (!next) {
      ui.toast('任务名称不能为空', 'bad');
      input.replaceWith(button);
      button.dataset.editing = 'false';
      return;
    }
    try {
      await patch(`/api/tasks/${encodeURIComponent(button.dataset.rename)}/name`, { name: next });
      button.dataset.name = next;
      button.textContent = next;
      button.title = `${next}（点击重命名）`;
      ui.toast('任务名称已更新', 'ok');
    } catch (error) {
      ui.showError(error);
    }
    input.replaceWith(button);
    button.dataset.editing = 'false';
  };

  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      finish(true);
    } else if (event.key === 'Escape') {
      event.preventDefault();
      finish(false);
    }
  });
  input.addEventListener('blur', () => finish(true));
}

async function removeTask(id) {
  if (!window.confirm('删除任务会级联软删其批次与用例，确定继续？')) {
    return;
  }
  try {
    await del(`/api/tasks/${encodeURIComponent(id)}`);
    ui.toast('任务已删除', 'ok');
    load();
    loadSummary();
  } catch (error) {
    ui.showError(error);
  }
}

/** Base products grouped by platform, so the filter follows the definition hierarchy. */
async function loadBaseProductOptions() {
  const select = ui.byId('taskProduct');
  if (!select) {
    return;
  }
  const options = ['<option value="">全部基产品</option>'];
  try {
    const platforms = await get('/api/catalog');
    platforms.forEach((platform) => {
      const items = platform.baseProducts || [];
      if (!items.length) {
        return;
      }
      options.push(`<optgroup label="${ui.escapeHtml(platform.name || platform.code)}">`);
      items.forEach((base) => {
        options.push(`<option value="${ui.escapeHtml(base.code)}">${ui.escapeHtml(base.code)}</option>`);
      });
      options.push('</optgroup>');
    });
  } catch (error) {
    ui.showError(error);
  }
  select.innerHTML = options.join('');
}

async function init() {
  await ui.initShell();
  await loadBaseProductOptions();
  renderSummary({});
  await loadSummary();
  await load();
  ui.enableColumnResize(ui.byId('taskTable'), 'tasks-v2');
  renderSortHeaders();

  const table = ui.byId('taskTable');
  table.querySelector('thead').addEventListener('click', (event) => {
    const th = event.target.closest('th[data-sort]');
    if (th) {
      cycleSort(th.dataset.sort);
    }
  });
  table.querySelector('thead').addEventListener('keydown', (event) => {
    const th = event.target.closest('th[data-sort]');
    if (th && (event.key === 'Enter' || event.key === ' ')) {
      event.preventDefault();
      cycleSort(th.dataset.sort);
    }
  });

  ui.on('taskSearch', 'input', debounce((event) => {
    state.q = event.target.value;
    state.page = 1;
    load();
  }, 350));
  ui.on('taskProduct', 'change', (event) => {
    state.product = event.target.value;
    state.page = 1;
    load();
  });
  ['taskFrom', 'taskTo'].forEach((id) => ui.on(id, 'change', () => {
    state.from = ui.byId('taskFrom').value;
    state.to = ui.byId('taskTo').value;
    state.page = 1;
    load();
  }));
  ui.on('newImportBtn', 'click', () => {
    window.location.href = '/import';
  });
}

init();
