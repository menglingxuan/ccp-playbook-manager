import { get } from './api.js';
import * as ui from './ui.js';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Batch picker: filter by text, or paste a batch id (selects it) or a task id (lists the batches of
 * that task).
 *
 * <p>The picker renders itself into {@code host} and loads the candidates on creation, so callers can
 * open their dialog right away and read {@link Picker#value} later.
 *
 * @param {Element} host element that receives the markup
 * @param {{currentTaskId?: string, excludeId?: string, onPick?: (batch: object|null) => void}} options
 *   {@code currentTaskId} orders local batches first, {@code excludeId} hides one batch,
 *   {@code onPick} fires whenever the selection changes
 * @returns {{value: () => object|null, focus: () => void, ready: Promise<void>}}
 */
export function createBatchPicker(host, options = {}) {
  const { currentTaskId = '', excludeId = '', onPick = null } = options;
  host.innerHTML = `
    <div class="picker">
      <input class="picker-input" autocomplete="off" spellcheck="false"
        placeholder="搜索批次名称，或粘贴批次 ID / 任务 ID">
      <div class="picker-selected" hidden></div>
      <div class="picker-hint"></div>
      <div class="picker-list" role="listbox"></div>
    </div>`;
  const input = host.querySelector('.picker-input');
  const list = host.querySelector('.picker-list');
  const selectedBox = host.querySelector('.picker-selected');
  const hint = host.querySelector('.picker-hint');

  const entries = new Map();
  const taskNames = new Map();
  let picked = null;
  let scope = null;
  let rows = [];
  let active = -1;
  let resolveToken = 0;

  const label = (item) => `${item.name || 'Untitled'} (${item.id})`;
  const productOf = (item) => item.productName || item.productCode || '';
  const taskOf = (item) => taskNames.get(item.taskId) || '其它任务';
  const matched = (item, query) => [item.name, item.id, productOf(item), taskOf(item)]
    .some((text) => (text || '').toLowerCase().includes(query));

  function renderList() {
    const query = input.value.trim().toLowerCase();
    const pool = [...entries.values()].filter((item) => item.id !== excludeId);
    const scoped = scope ? pool.filter((item) => item.taskId === scope.id) : pool;
    const skipFilter = scope
      && (scope.id.toLowerCase().startsWith(query) || query.startsWith(scope.id.toLowerCase()));
    rows = query && !skipFilter ? scoped.filter((item) => matched(item, query)) : scoped;
    rows.sort((left, right) => {
      const leftLocal = left.taskId === currentTaskId ? 0 : 1;
      const rightLocal = right.taskId === currentTaskId ? 0 : 1;
      return leftLocal - rightLocal || (left.name || '').localeCompare(right.name || '');
    });
    if (active >= rows.length) {
      active = -1;
    }
    const items = rows.map((item, index) => `
      <div class="picker-option ${index === active ? 'active' : ''} ${picked && picked.id === item.id ? 'picked' : ''}"
        role="option" data-index="${index}" data-batch="${ui.escapeHtml(item.id)}">
        <span class="picker-option-label">${ui.escapeHtml(label(item))}</span>
        <span class="picker-option-sub">${ui.escapeHtml([productOf(item), item.taskId === currentTaskId ? '' : taskOf(item)].filter(Boolean).join(' · '))}</span>
      </div>`).join('');
    const heading = scope
      ? `<div class="picker-group">任务「${ui.escapeHtml(scope.name)}」下的批次（${rows.length}）</div>`
      : `<div class="picker-group">可选批次（${rows.length}）</div>`;
    list.innerHTML = rows.length
      ? heading + items
      : '<div class="picker-empty">没有匹配的批次，可直接粘贴批次 ID 或任务 ID</div>';
  }

  function setPicked(item) {
    picked = item;
    selectedBox.innerHTML = item
      ? `<span class="picker-chip"><span class="picker-chip-label">${ui.escapeHtml(item.name || 'Untitled')}</span>
          <code>${ui.escapeHtml(item.id)}</code>
          <button type="button" class="picker-chip-clear" title="清除">✕</button></span>`
      : '';
    selectedBox.hidden = !item;
    active = -1;
    renderList();
    if (onPick) {
      onPick(item);
    }
  }

  async function resolveId(id) {
    const token = ++resolveToken;
    hint.textContent = '正在按 ID 查找…';
    try {
      const batch = await get(`/api/batches/${encodeURIComponent(id)}`);
      if (token !== resolveToken) {
        return;
      }
      const item = entries.get(batch.id) || batch;
      entries.set(item.id, item);
      if (item.id === excludeId) {
        hint.textContent = '该批次不可用于本次对比，请选择其它批次';
        return;
      }
      scope = null;
      setPicked(item);
      hint.textContent = '已按批次 ID 选中该批次';
      return;
    } catch (error) {
      if (token !== resolveToken) {
        return;
      }
      if (error.status && error.status !== 404) {
        hint.textContent = error.message;
        return;
      }
    }
    try {
      const detail = await get(`/api/tasks/${encodeURIComponent(id)}`);
      if (token !== resolveToken) {
        return;
      }
      const task = detail.task || {};
      (detail.batches || []).forEach((item) => {
        if (!entries.has(item.id)) {
          entries.set(item.id, item);
        }
      });
      scope = { id: task.id || id, name: task.name || id.slice(0, 8) };
      taskNames.set(scope.id, scope.name);
      setPicked(null);
      hint.textContent = `已定位任务「${scope.name}」，下方仅显示该任务的批次`;
      renderList();
    } catch (taskError) {
      if (token !== resolveToken) {
        return;
      }
      scope = null;
      hint.textContent = taskError.status === 404 ? '没有找到匹配的批次或任务 ID' : taskError.message;
      renderList();
    }
  }

  function onInput() {
    const query = input.value.trim();
    if (picked && query !== label(picked)) {
      setPicked(null);
    }
    hint.textContent = '';
    if (scope && !query) {
      scope = null;
    }
    if (UUID_PATTERN.test(query)) {
      resolveId(query);
      return;
    }
    renderList();
  }

  input.addEventListener('input', onInput);
  input.addEventListener('keydown', (event) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      const step = event.key === 'ArrowDown' ? 1 : -1;
      active = rows.length ? (active + step + rows.length) % rows.length : -1;
      renderList();
      const node = list.querySelector(`[data-index="${active}"]`);
      if (node) {
        node.scrollIntoView({ block: 'nearest' });
      }
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const query = input.value.trim();
      if (active >= 0 && rows[active]) {
        setPicked(rows[active]);
        input.value = '';
        hint.textContent = '';
        renderList();
      } else if (UUID_PATTERN.test(query)) {
        resolveId(query);
      }
    }
  });
  list.addEventListener('click', (event) => {
    const node = event.target.closest('[data-batch]');
    if (!node) {
      return;
    }
    const item = entries.get(node.dataset.batch);
    if (item) {
      setPicked(item);
      input.value = '';
      hint.textContent = '';
      renderList();
    }
  });
  selectedBox.addEventListener('click', (event) => {
    if (event.target.closest('.picker-chip-clear')) {
      setPicked(null);
      input.focus();
    }
  });

  list.innerHTML = '<div class="picker-empty">正在加载批次…</div>';
  const ready = Promise.all([get('/api/batches?size=200'), get('/api/tasks?size=200')])
    .then(([batchPage, taskPage]) => {
      (taskPage.items || []).forEach((task) => {
        taskNames.set(task.id, task.name || task.id.slice(0, 8));
      });
      (batchPage.items || []).forEach((item) => entries.set(item.id, item));
      renderList();
    })
    .catch((error) => {
      list.innerHTML = `<div class="picker-empty">${ui.escapeHtml(error.message)}</div>`;
    });

  async function selectById(id) {
    if (!id) {
      return;
    }
    let item = entries.get(id);
    if (!item) {
      try {
        item = await get(`/api/batches/${encodeURIComponent(id)}`);
      } catch (error) {
        return;
      }
      entries.set(item.id, item);
    }
    setPicked(item);
  }

  return {
    value: () => picked,
    focus: () => input.focus(),
    selectById,
    ready,
  };
}

export { UUID_PATTERN };
