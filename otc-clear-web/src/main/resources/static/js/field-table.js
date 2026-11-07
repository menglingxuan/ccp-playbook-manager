/**
 * Reusable field table used by the base product panel and the product drawer.
 *
 * <p>Provides column sorting (ascending → descending → original), field filtering, duplicate header
 * highlighting, a read-only lock and drag & drop row reordering.
 */
import * as ui from './ui.js';

const t = ui.t;

const SORT_KEYS = new Set(['order', 'baseOrder', 'header', 'valueType', 'role', 'required']);

const normalize = (value) => String(value ?? '').trim().toLowerCase();

/** Creates a field table bound to the `[data-ft]` markers found inside {@code root}. */
export function createFieldTable(config) {
  const {
    root,
    showBaseOrder = false,
    locked: initialLocked = true,
    onEdit,
    onDelete,
    onReorder,
    onLockedAction,
  } = config;

  const node = (role) => root.querySelector(`[data-ft="${role}"]`);

  const state = {
    options: {},
    fields: [],
    locked: initialLocked,
    filters: { query: '', valueType: '', role: '', tag: '', requiredOnly: false },
    sort: { key: null, dir: 0 },
    selectedId: null,
    dragId: null,
  };

  const metaItem = (setCode, code) =>
    (state.options[setCode] || []).find((item) => item.code === code) || null;

  const label = (setCode, code) => {
    const item = metaItem(setCode, code);
    if (!item) {
      return code || '—';
    }
    return item.refSetCode ? `${item.label} · ${item.refSetCode}` : item.label;
  };

  // ------------------------------------------------------------ filter model

  function activeFilterCount() {
    const f = state.filters;
    return [f.query, f.valueType, f.role, f.tag].filter(Boolean).length + (f.requiredOnly ? 1 : 0);
  }

  function isViewModified() {
    return activeFilterCount() > 0 || Boolean(state.sort.key);
  }

  function matches(field) {
    const { query, valueType, role, tag, requiredOnly } = state.filters;
    if (valueType && field.valueTypeCode !== valueType) {
      return false;
    }
    if (role && field.roleCode !== role) {
      return false;
    }
    if (tag && !(field.tags || []).includes(tag)) {
      return false;
    }
    if (requiredOnly && !field.required) {
      return false;
    }
    const q = normalize(query);
    if (!q) {
      return true;
    }
    return [
      field.header,
      ...(field.aliases || []),
      label('VALUE_TYPE', field.valueTypeCode),
      label('FIELD_ROLE', field.roleCode),
      ...(field.tags || []),
      ...(field.tags || []).map((code) => (metaItem('FIELD_TAG', code) || {}).label || code),
      field.remark || '',
    ]
      .join(' ')
      .toLowerCase()
      .includes(q);
  }

  function compare(left, right) {
    switch (state.sort.key) {
      case 'order':
        return left.sortOrder - right.sortOrder;
      case 'baseOrder':
        return (left.baseSortOrder ?? left.sortOrder) - (right.baseSortOrder ?? right.sortOrder);
      case 'required':
        return Number(left.required) - Number(right.required);
      case 'valueType':
        return label('VALUE_TYPE', left.valueTypeCode).localeCompare(label('VALUE_TYPE', right.valueTypeCode));
      case 'role':
        return label('FIELD_ROLE', left.roleCode).localeCompare(label('FIELD_ROLE', right.roleCode));
      default:
        return String(left.header).localeCompare(String(right.header));
    }
  }

  function visibleFields() {
    const filtered = state.fields.filter(matches);
    if (!state.sort.key || !state.sort.dir) {
      return filtered;
    }
    return [...filtered].sort((a, b) => compare(a, b) * state.sort.dir);
  }

  /** Headers appearing more than once, compared case-insensitively. */
  function duplicateHeaders() {
    const counts = new Map();
    state.fields.forEach((field) => {
      const key = normalize(field.header);
      counts.set(key, (counts.get(key) || 0) + 1);
    });
    return new Set([...counts.entries()].filter(([, count]) => count > 1).map(([key]) => key));
  }

  // ---------------------------------------------------------------- render

  function sortArrow(key) {
    if (state.sort.key !== key) {
      return '⇅';
    }
    return state.sort.dir === 1 ? '↑' : '↓';
  }

  function columnDefs() {
    const defs = [{ key: 'order', label: t('products.column.order'), cls: 'num col-no' }];
    if (showBaseOrder) {
      defs.push({ key: 'baseOrder', label: t('products.column.baseOrder'), cls: 'num col-no' });
    }
    defs.push(
      { key: 'header', label: t('products.column.header') },
      { key: 'valueType', label: t('products.column.valueType') },
      { key: 'role', label: t('products.column.role') },
      { key: null, label: t('products.column.tags') },
      { key: 'required', label: t('products.column.required') },
      { key: null, label: t('products.column.remark') },
      { key: null, label: t('products.column.actions'), cls: 'col-actions' },
    );
    return defs;
  }

  function renderHead() {
    const head = node('head');
    if (!head) {
      return;
    }
    head.innerHTML = columnDefs()
      .map((column) => {
        if (!column.key || !SORT_KEYS.has(column.key)) {
          return `<th class="${column.cls || ''}">${ui.escapeHtml(column.label)}</th>`;
        }
        const sorted = state.sort.key === column.key ? 'sorted' : '';
        return `<th class="th-sort ${column.cls || ''} ${sorted}" data-sort="${column.key}" tabindex="0"
          title="${ui.escapeHtml(t('products.column.sortHint'))}">${ui.escapeHtml(column.label)}<span
          class="sort-arrow">${sortArrow(column.key)}</span></th>`;
      })
      .join('');
  }

  function renderFilterSelects() {
    const build = (role, setCode, allLabel) => {
      const select = node(role);
      if (!select) {
        return;
      }
      const current = select.value;
      select.innerHTML = `<option value="">${ui.escapeHtml(allLabel)}</option>`
        + (state.options[setCode] || [])
          .map((item) => `<option value="${ui.escapeHtml(item.code)}">${ui.escapeHtml(
            item.refSetCode ? `${item.label} · ${item.refSetCode}` : item.label)}</option>`)
          .join('');
      select.value = current;
    };
    build('type', 'VALUE_TYPE', `${t('products.column.valueType')}: ${t('common.all')}`);
    build('role', 'FIELD_ROLE', `${t('products.column.role')}: ${t('common.all')}`);
    build('tag', 'FIELD_TAG', `${t('products.column.tags')}: ${t('common.all')}`);
  }

  function renderChips() {
    const host = node('chips');
    if (!host) {
      return;
    }
    const chips = [];
    const push = (text, clear) => {
      chips.push(`<span class="filter-chip">${ui.escapeHtml(text)}
        <button type="button" data-clear="${clear}" aria-label="${ui.escapeHtml(t('common.reset'))}">×</button></span>`);
    };
    const f = state.filters;
    if (f.query) {
      push(`${t('common.search')}: ${f.query}`, 'query');
    }
    if (f.valueType) {
      push(`${t('products.column.valueType')}: ${label('VALUE_TYPE', f.valueType)}`, 'valueType');
    }
    if (f.role) {
      push(`${t('products.column.role')}: ${label('FIELD_ROLE', f.role)}`, 'role');
    }
    if (f.tag) {
      push(`${t('products.column.tags')}: ${(metaItem('FIELD_TAG', f.tag) || {}).label || f.tag}`, 'tag');
    }
    if (f.requiredOnly) {
      push(t('products.field.requiredOnly'), 'requiredOnly');
    }
    if (chips.length > 1) {
      chips.push(`<button type="button" class="filter-clear-all" data-clear="all">${
        ui.escapeHtml(t('common.clearAll'))}</button>`);
    }
    host.innerHTML = chips.join('');
  }

  function renderNotice(duplicates) {
    const host = node('notice');
    if (!host) {
      return;
    }
    const parts = [];
    if (duplicates.size) {
      const names = state.fields
        .filter((field) => duplicates.has(normalize(field.header)))
        .map((field) => field.header);
      const unique = [...new Set(names)];
      parts.push(`<span class="notice-chip dup">${ui.escapeHtml(
        t('products.dup.banner', unique.join(' / ')))}</span>`);
    }
    if (!state.locked && isViewModified()) {
      parts.push(`<span class="notice-chip">${ui.escapeHtml(t('products.field.dragBlocked'))}</span>`);
    }
    host.innerHTML = parts.join('');
    host.classList.toggle('hidden', !parts.length);
  }

  function roleTone(roleCode) {
    return roleCode === 'MANAGED' ? 'info' : 'muted';
  }

  function tagChips(tags) {    if (!tags || !tags.length) {
      return '<span class="muted">—</span>';
    }
    return `<span class="tag-list">${tags
      .map((tag) => `<span class="chip muted">${ui.escapeHtml((metaItem('FIELD_TAG', tag) || {}).label || tag)}</span>`)
      .join('')}</span>`;
  }

  function actionButtons() {
    const disabled = state.locked ? 'disabled' : '';
    const editTitle = state.locked ? t('products.lock.actionHint') : t('common.edit');
    const deleteTitle = state.locked ? t('products.lock.actionHint') : t('common.delete');
    return `
      ${ui.iconButton('edit', editTitle, 'sm', `data-act="edit" ${disabled}`)}
      ${ui.iconButton('trash', deleteTitle, 'sm danger', `data-act="delete" ${disabled}`)}`;
  }

  function renderRows() {
    const body = node('body');
    if (!body) {
      return;
    }
    const duplicates = duplicateHeaders();
    const fields = visibleFields();
    const total = state.fields.length;
    const count = node('count');
    if (count) {
      count.textContent = t('products.field.matched', fields.length, total);
    }
    renderNotice(duplicates);

    const columns = columnDefs().length;
    if (!fields.length) {
      const message = total ? t('products.field.emptyFiltered') : t('products.field.empty');
      body.innerHTML = `<tr><td class="empty" colspan="${columns}">${ui.escapeHtml(message)}</td></tr>`;
      body.dataset.dragEnabled = 'false';
      return;
    }

    const canDrag = !state.locked && !isViewModified();
    body.dataset.dragEnabled = String(canDrag);

    body.innerHTML = fields
      .map((field) => {
        const duplicate = duplicates.has(normalize(field.header));
        const selected = state.selectedId === field.id ? 'selected' : '';
        const grip = canDrag
          ? `<span class="drag-grip" title="${ui.escapeHtml(t('products.dragHint'))}">${ui.icon('grip', 12)}</span>`
          : '';
        const dupBadge = duplicate
          ? ` <span class="badge mark dup-badge" title="${ui.escapeHtml(t('products.dup.hint'))}">${
            ui.escapeHtml(t('products.dup.badge'))}</span>`
          : '';
        const baseOrderCell = showBaseOrder
          ? `<td class="num col-no parent-no">${field.baseSortOrder ?? field.sortOrder}</td>`
          : '';
        return `
        <tr data-field="${field.id}" draggable="${canDrag}" class="${duplicate ? 'dup-row' : ''} ${selected}">
          <td class="num col-no">${grip}<span class="no-value">${field.sortOrder}</span></td>
          ${baseOrderCell}
          <td class="col-header">${ui.escapeHtml(field.header)}${dupBadge}</td>
          <td><span class="chip info">${ui.escapeHtml(label('VALUE_TYPE', field.valueTypeCode))}</span></td>
          <td><span class="chip ${roleTone(field.roleCode)}">${
          ui.escapeHtml(label('FIELD_ROLE', field.roleCode))}</span></td>
          <td>${tagChips(field.tags)}</td>
          <td>${field.required
          ? `<span class="badge pass">${ui.escapeHtml(t('common.required'))}</span>`
          : `<span class="badge muted">${ui.escapeHtml(t('common.optional'))}</span>`}</td>
          <td>${ui.longTextCell(field.remark, 22)}</td>
          <td class="col-actions"><div class="row-actions">${actionButtons()}</div></td>
        </tr>`;
      })
      .join('');

    ui.bindLongText(body);
  }

  function render() {
    renderFilterSelects();
    renderHead();
    renderChips();
    renderRows();
  }

  // --------------------------------------------------------------- drag drop

  function clearDropMarkers() {
    node('body')?.querySelectorAll('.drop-before, .drop-after, .dragging')
      .forEach((row) => row.classList.remove('drop-before', 'drop-after', 'dragging'));
  }

  function commitReorder(draggedId, targetId, after) {
    const ids = state.fields.map((field) => field.id);
    const from = ids.indexOf(draggedId);
    if (from < 0) {
      return;
    }
    ids.splice(from, 1);
    let to = ids.indexOf(targetId);
    if (to < 0) {
      return;
    }
    if (after) {
      to += 1;
    }
    ids.splice(to, 0, draggedId);
    if (onReorder) {
      onReorder(ids);
    }
  }

  function bindDrag() {
    const body = node('body');
    if (!body) {
      return;
    }
    body.addEventListener('dragstart', (event) => {
      const row = event.target.closest('tr[data-field]');
      if (!row || body.dataset.dragEnabled !== 'true') {
        event.preventDefault();
        return;
      }
      state.dragId = row.dataset.field;
      state.selectedId = state.dragId;
      row.classList.add('dragging');
      event.dataTransfer.effectAllowed = 'move';
      try {
        event.dataTransfer.setData('text/plain', state.dragId);
      } catch (error) {
        // some browsers restrict payloads; the state already tracks the id
      }
    });
    body.addEventListener('dragover', (event) => {
      if (!state.dragId || body.dataset.dragEnabled !== 'true') {
        return;
      }
      event.preventDefault();
      event.dataTransfer.dropEffect = 'move';
      const row = event.target.closest('tr[data-field]');
      body.querySelectorAll('.drop-before, .drop-after').forEach((el) => el.classList.remove('drop-before', 'drop-after'));
      if (!row || row.dataset.field === state.dragId) {
        return;
      }
      const rect = row.getBoundingClientRect();
      const after = event.clientY - rect.top > rect.height / 2;
      row.classList.add(after ? 'drop-after' : 'drop-before');
    });
    body.addEventListener('drop', (event) => {
      if (!state.dragId) {
        return;
      }
      event.preventDefault();
      const row = event.target.closest('tr[data-field]');
      const dragged = state.dragId;
      state.dragId = null;
      if (!row) {
        clearDropMarkers();
        return;
      }
      const after = row.classList.contains('drop-after');
      const targetId = row.dataset.field;
      clearDropMarkers();
      if (targetId !== dragged) {
        commitReorder(dragged, targetId, after);
      }
    });
    body.addEventListener('dragend', () => {
      state.dragId = null;
      clearDropMarkers();
    });
  }

  // ---------------------------------------------------------------- events

  let searchTimer = null;

  function bindFilters() {
    const search = node('search');
    search?.addEventListener('input', () => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        state.filters.query = search.value.trim();
        renderRows();
      }, 150);
    });
    node('type')?.addEventListener('change', (event) => {
      state.filters.valueType = event.target.value;
      renderRows();
    });
    node('role')?.addEventListener('change', (event) => {
      state.filters.role = event.target.value;
      renderRows();
    });
    node('tag')?.addEventListener('change', (event) => {
      state.filters.tag = event.target.value;
      renderRows();
    });
    node('required')?.addEventListener('click', (event) => {
      state.filters.requiredOnly = !state.filters.requiredOnly;
      event.currentTarget.classList.toggle('on', state.filters.requiredOnly);
      renderRows();
    });
    node('reset')?.addEventListener('click', resetView);
    node('chips')?.addEventListener('click', (event) => {
      const button = event.target.closest('button[data-clear]');
      if (button) {
        clearFilter(button.dataset.clear);
      }
    });
  }

  function bindHead() {
    node('head')?.addEventListener('click', (event) => {
      const th = event.target.closest('th[data-sort]');
      if (th) {
        cycleSort(th.dataset.sort);
      }
    });
    node('head')?.addEventListener('keydown', (event) => {
      const th = event.target.closest('th[data-sort]');
      if (th && (event.key === 'Enter' || event.key === ' ')) {
        event.preventDefault();
        cycleSort(th.dataset.sort);
      }
    });
  }

  function bindRows() {
    const body = node('body');
    body?.addEventListener('click', (event) => {
      const action = event.target.closest('button[data-act]');
      if (action) {
        const field = state.fields.find((item) => item.id === action.closest('tr')?.dataset.field);
        if (!field) {
          return;
        }
        if (state.locked) {
          if (onLockedAction) {
            onLockedAction();
          }
          return;
        }
        if (action.dataset.act === 'edit' && onEdit) {
          onEdit(field);
        } else if (action.dataset.act === 'delete' && onDelete) {
          onDelete(field);
        }
        return;
      }
      const row = event.target.closest('tr[data-field]');
      if (!row) {
        return;
      }
      state.selectedId = state.selectedId === row.dataset.field ? null : row.dataset.field;
      body.querySelectorAll('tr[data-field]').forEach((tr) => {
        tr.classList.toggle('selected', tr.dataset.field === state.selectedId);
      });
    });
  }

  function cycleSort(key) {
    if (!SORT_KEYS.has(key)) {
      return;
    }
    if (state.sort.key !== key) {
      state.sort = { key, dir: 1 };
    } else if (state.sort.dir === 1) {
      state.sort = { key, dir: -1 };
    } else {
      state.sort = { key: null, dir: 0 };
    }
    renderHead();
    renderChips();
    renderRows();
  }

  function clearFilter(target) {
    if (target === 'all') {
      resetView();
      return;
    }
    if (target === 'sort') {
      state.sort = { key: null, dir: 0 };
    } else if (target === 'requiredOnly') {
      state.filters.requiredOnly = false;
      node('required')?.classList.remove('on');
    } else {
      state.filters[target] = '';
      const roles = { query: 'search', valueType: 'type', role: 'role', tag: 'tag' };
      const select = node(roles[target]);
      if (select) {
        select.value = '';
      }
    }
    renderFilterSelects();
    renderHead();
    renderChips();
    renderRows();
  }

  function resetView() {
    state.filters = { query: '', valueType: '', role: '', tag: '', requiredOnly: false };
    state.sort = { key: null, dir: 0 };
    state.selectedId = null;
    const search = node('search');
    if (search) {
      search.value = '';
    }
    ['type', 'role', 'tag'].forEach((role) => {
      const select = node(role);
      if (select) {
        select.value = '';
      }
    });
    node('required')?.classList.remove('on');
    render();
  }

  function focusField(fieldId) {
    resetView();
    const row = node('body')?.querySelector(`tr[data-field="${fieldId}"]`);
    if (!row) {
      return false;
    }
    ui.flashElement(row);
    return true;
  }

  bindFilters();
  bindHead();
  bindRows();
  bindDrag();

  return {
    setOptions(options) {
      state.options = options || {};
      renderFilterSelects();
    },
    setFields(fields) {
      state.fields = [...(fields || [])];
      state.selectedId = null;
      render();
    },
    setLocked(locked) {
      state.locked = Boolean(locked);
      renderRows();
      if (node('lock')) {
        node('lock').setAttribute('aria-pressed', String(!state.locked));
      }
    },
    isLocked() {
      return state.locked;
    },
    resetView,
    focusField,
    render,
    fieldCount() {
      return state.fields.length;
    },
  };
}
