import { del, get, patch, query } from '../api.js';
import * as ui from '../ui.js';
import { bindRemarks, remarkHtml } from '../remark.js';

const state = {
  page: 1, size: 25, product: '', q: '', from: '', to: '', sort: '', status: '',
  /** Last status counts, kept so a click can repaint the active card without another request. */
  summary: {},
};

const NAME_MAX_LENGTH = 256;

/** Sortable headers cycle through ascending, descending and the original order. */
const SORT_FIELDS = new Set(['name', 'batches', 'created']);

/**
 * Status cards in display order; {@code status} is the filter the card applies.
 *
 * <p>A card click filters the list by that status, a second click on the active card clears it. The
 * hover text explains what the status means, which is what an operator needs to read the numbers.
 * An import runs in one transaction, so a task is never visible as queued: there is no "待处理" card,
 * and 「进行中」 only appears if such a row really exists (for example left by a future async import).
 */
const STATUS_CARDS = [
  {
    status: '',
    label: '全部任务',
    definition: '当前筛选条件下（搜索 / 基产品 / 日期）的全部任务，不区分状态',
  },
  {
    status: 'RUNNING',
    label: '进行中',
    definition: '任务正在解析写入；正常情况下导入在一个事务内完成，该状态只在存在残留记录时出现',
    hideWhenZero: true,
  },
  {
    status: 'SUCCEEDED',
    label: '成功',
    tone: 'ok',
    definition: '批次全部写入，且没有无效行、没有 Sheet 导入失败',
  },
  {
    status: 'PARTIAL',
    label: '部分成功',
    tone: 'warn',
    definition: '有批次写入，但同时存在无效行或有 Sheet 导入失败',
  },
  {
    status: 'FAILED',
    label: '失败',
    tone: 'bad',
    definition: '没有任何批次被写入（所有 Sheet 都被拒绝）',
  },
];

const STATUS_LABELS = STATUS_CARDS.reduce((labels, card) => {
  if (card.status) {
    labels[card.status] = card.label;
  }
  return labels;
}, {});

const debounce = (fn, delay = 300) => ui.debounce(fn, delay);

/** Total of the status counts, i.e. the number of tasks matching the current filters. */
function summaryTotal(summary) {
  return Object.values(summary || {}).reduce((sum, value) => sum + (Number(value) || 0), 0);
}

/**
 * Paints the status cards.
 *
 * <p>Every card is a filter button: the label names the filter, the value is the number of tasks the
 * list shows after clicking it, so the card is checked against the list rather than trusted blindly.
 * A card marked {@code hideWhenZero} is only drawn when it has tasks, and never while it is the
 * active filter, so a filter can always be released.
 *
 * @param summary status to count, already narrowed by the search, product and date filters
 */
function renderSummary(summary) {
  state.summary = summary || {};
  const cards = STATUS_CARDS.map((card) => ({
    ...card,
    value: card.status ? (state.summary[card.status] || 0) : summaryTotal(state.summary),
  })).filter((card) => !card.hideWhenZero || card.value > 0 || state.status === card.status);
  ui.setHtml('summaryCards', cards.map((card) => {
    const active = state.status === card.status;
    // the hover text carries the definition of the status, not the filter action
    const definition = `${card.label}${card.status ? `（${card.status}）` : ''}：${card.definition}`;
    return `
    <button type="button" class="card clickable${active ? ' active-filter' : ''}"
            data-status="${ui.escapeHtml(card.status)}" aria-pressed="${active}"
            aria-label="${ui.escapeHtml(`${card.label} ${card.value} 个任务`)}"
            title="${ui.escapeHtml(definition)}">
      <div class="card-label">${ui.escapeHtml(card.label)}</div>
      <div class="card-value ${card.tone || ''}">${card.value}</div>
    </button>`;
  }).join(''));
}

/** Reloads the counts with the current search, product and date filters. */
async function loadSummary() {
  try {
    renderSummary(await get('/api/tasks/summary' + query({
      product: state.product, q: state.q, from: state.from, to: state.to,
    })));
  } catch (error) {
    ui.showError(error);
  }
}

/** Repaints the active-filter chips; only the status card has no dedicated input. */
function renderChips() {
  const host = ui.byId('taskChips');
  if (!host) {
    return;
  }
  if (!state.status) {
    host.innerHTML = '';
    return;
  }
  host.innerHTML = `<span class="filter-chip">状态：${
    ui.escapeHtml(STATUS_LABELS[state.status] || state.status)}`
    + '<button type="button" data-clear-status aria-label="清除状态筛选">×</button></span>';
  host.querySelector('[data-clear-status]').addEventListener('click', () => applyStatus(''));
}

/** Applies a status filter and reloads the list and the counts. */
function applyStatus(status) {
  state.status = state.status === status ? '' : status;
  state.page = 1;
  renderSummary(state.summary);
  renderChips();
  load();
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
        <td class="remark-col">${remarkHtml('task', task.id, task.note)}</td>
        <td>
          <div class="row-actions">
            ${ui.iconLink('eye', `/tasks/${encodeURIComponent(task.id)}`, '查看任务详情', 'sm')}
            ${ui.iconButton('trash', '删除任务', 'sm danger', `data-delete="${ui.escapeHtml(task.id)}"`)}
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
  const order = ui.sequence();
  const token = order.next();
  ui.markLoading('taskRows', 9);
  try {
    const result = await get('/api/tasks' + query({
      status: state.status, product: state.product, q: state.q, from: state.from, to: state.to,
      sort: state.sort, page: state.page, size: state.size,
    }));
    if (!token()) {
      // a newer filter or page is loading; its answer must win
      return;
    }
    state.page = result.page;
    const rows = result.items.map(rowHtml);
    const filtered = Boolean(state.q || state.product || state.from || state.to || state.status);
    ui.setHtml('taskRows', rows.length ? rows.join('')
      : `<tr><td colspan="9" class="empty">${filtered
        ? '没有符合当前筛选条件的任务' : '还没有导入任务，点击「新建导入」上传用例文件'}</td></tr>`);
    bindRemarks(ui.byId('taskRows'));
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
    if (token()) {
      ui.showError(error);
    }
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
  if (!window.confirm('删除任务会彻底删除其批次、用例与导出记录（不可恢复），确定继续？')) {
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
  renderChips();
  await loadSummary();
  await load();
  ui.enableColumnResize(ui.byId('taskTable'), 'tasks-v2');
  renderSortHeaders();

  ui.byId('summaryCards').addEventListener('click', (event) => {
    const card = event.target.closest('[data-status]');
    if (card) {
      applyStatus(card.dataset.status || '');
    }
  });

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
    // the cards count the tasks matching the search, so they must follow it
    loadSummary();
    load();
  }, 350));
  ui.on('taskProduct', 'change', (event) => {
    state.product = event.target.value;
    state.page = 1;
    loadSummary();
    load();
  });
  ['taskFrom', 'taskTo'].forEach((id) => ui.on(id, 'change', () => {
    state.from = ui.byId('taskFrom').value;
    state.to = ui.byId('taskTo').value;
    state.page = 1;
    loadSummary();
    load();
  }));
  ui.on('newImportBtn', 'click', () => {
    window.location.href = '/import';
  });
}

init();
