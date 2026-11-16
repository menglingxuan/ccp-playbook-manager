import { patch } from './api.js';
import * as ui from './ui.js';

/*
 * Inline remark editor shared by the task, batch and export lists.
 *
 * A remark may hold several lines but the list only shows one: the text is clipped with an ellipsis
 * and can be expanded, edited or cleared in place.
 */

const MAX_LENGTH = 2000;

const ICON_EDIT = `<svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"
  stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <path d="M11.5 2.5a1.6 1.6 0 0 1 2.3 2.3L5.6 13 2 14l1-3.6Z"></path></svg>`;
const ICON_PLUS = `<svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
  stroke-linecap="round" aria-hidden="true"><path d="M8 3.2v9.6M3.2 8h9.6"></path></svg>`;
const ICON_EXPAND = `<svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
  stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 6.5 8 10.5l4-4"></path></svg>`;
const ICON_COLLAPSE = `<svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
  stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9.5 8 5.5l4 4"></path></svg>`;
const ICON_SAVE = `<svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.8"
  stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 8.5 6.2 12 13 4.5"></path></svg>`;
const ICON_CANCEL = `<svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.7"
  stroke-linecap="round" aria-hidden="true"><path d="M4 4l8 8M12 4l-8 8"></path></svg>`;
const ICON_TRASH = `<svg class="ico" width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"
  stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
  <path d="M3 5h10M6.5 5V3.4h3V5M4.6 5l.6 8h5.6l.6-8M6.8 7.3v3.4M9.2 7.3v3.4"></path></svg>`;

/** Icon only control of the remark widget. */
function iconButton(action, icon, title, extra = '', hidden = false) {
  return `<button type="button" class="icon-btn sm${extra}" data-act="${action}" title="${title}"
    aria-label="${title}"${hidden ? ' hidden' : ''}>${icon}</button>`;
}

/** Edit and expand controls shown next to the single line summary. */
function toolButtons(note, expanded) {
  const tools = [];
  if (isLong(note)) {
    tools.push(expanded
      ? iconButton('toggle', ICON_COLLAPSE, '收起备注')
      : iconButton('toggle', ICON_EXPAND, '展开完整备注'));
  }
  tools.push(iconButton('edit', ICON_EDIT, '编辑备注'));
  return `<span class="remark-tools">${tools.join('')}</span>`;
}

const ENDPOINTS = {
  task: (id) => `/api/tasks/${encodeURIComponent(id)}/note`,
  // batches already carry their note on the generic update endpoint
  batch: (id) => `/api/batches/${encodeURIComponent(id)}`,
  export: (id) => `/api/exports/${encodeURIComponent(id)}/note`,
};

/** First line of a remark, clipped by CSS; used as the single line summary. */
function summary(note) {
  return (note || '').split('\n')[0];
}

/** True when the remark needs the expand toggle. */
function isLong(note) {
  return Boolean(note) && (note.includes('\n') || note.length > 70);
}

/**
 * Markup of one remark cell body.
 *
 * @param {string} kind task, batch or export
 * @param {string} id row id
 * @param {string} note stored remark, may be empty
 */
export function remarkHtml(kind, id, note) {
  const text = note || '';
  const attr = ui.escapeHtml(text);
  return `<div class="remark" data-remark data-kind="${kind}" data-id="${ui.escapeHtml(id)}" data-note="${attr}">
      <div class="remark-view"${text ? '' : ' hidden'}>
        <button type="button" class="remark-text" data-act="edit" title="点击编辑备注">${ui.escapeHtml(summary(text))}</button>
        ${text ? toolButtons(text) : ''}
      </div>
      <div class="remark-full" hidden></div>
      ${iconButton('edit', ICON_PLUS, '添加备注', ' remark-add', Boolean(text))}
      <div class="remark-editor" hidden>
        <textarea rows="2" maxlength="${MAX_LENGTH}" placeholder="可输入多行备注，最多 ${MAX_LENGTH} 字"></textarea>
        <div class="remark-actions">
          ${iconButton('save', ICON_SAVE, '保存备注', ' primary')}
          ${iconButton('cancel', ICON_CANCEL, '取消')}
          ${iconButton('clear', ICON_TRASH, '删除备注', ' danger')}
        </div>
        <div class="remark-error muted" hidden></div>
      </div>
    </div>`;
}

/** Repaints one cell from the stored note. */
function paint(cell, note, expanded = false) {
  const text = note || '';
  cell.dataset.note = text;
  const view = cell.querySelector('.remark-view');
  const add = cell.querySelector('.remark-add');
  const full = cell.querySelector('.remark-full');
  view.hidden = !text;
  add.hidden = Boolean(text);
  view.querySelector('.remark-text').textContent = summary(text);
  view.querySelector('.remark-text').title = text || '点击编辑备注';
  const tools = view.querySelector('.remark-tools');
  if (tools) {
    tools.remove();
  }
  if (text) {
    view.insertAdjacentHTML('beforeend', toolButtons(text, expanded));
  }
  full.hidden = !(text && expanded);
  full.textContent = expanded ? text : '';
}

function openEditor(cell) {
  const editor = cell.querySelector('.remark-editor');
  const area = editor.querySelector('textarea');
  area.value = cell.dataset.note || '';
  editor.hidden = false;
  editor.querySelector('.remark-error').hidden = true;
  cell.querySelector('.remark-view').hidden = true;
  cell.querySelector('.remark-add').hidden = true;
  area.focus();
  area.setSelectionRange(area.value.length, area.value.length);
}

function closeEditor(cell) {
  const editor = cell.querySelector('.remark-editor');
  editor.hidden = true;
  paint(cell, cell.dataset.note || '');
}

async function persist(cell, note) {
  const kind = cell.dataset.kind;
  const id = cell.dataset.id;
  const editor = cell.querySelector('.remark-editor');
  const error = editor.querySelector('.remark-error');
  try {
    const result = await patch(ENDPOINTS[kind](id), { note });
    paint(cell, result && result.note !== undefined ? result.note : note);
    editor.hidden = true;
    ui.toast(note ? '备注已保存' : '备注已删除', 'ok');
  } catch (failure) {
    error.textContent = failure.message || '保存备注失败';
    error.hidden = false;
    ui.showError(failure);
  }
}

/**
 * Wires the inline editors inside {@code root} once; events are delegated so re-rendered rows keep
 * working without rebinding.
 *
 * @param {HTMLElement} root element containing remark cells, usually the table body
 */
export function bindRemarks(root) {
  if (!root || root.dataset.remarksBound === 'true') {
    return;
  }
  root.dataset.remarksBound = 'true';
  root.addEventListener('click', (event) => {
    const button = event.target.closest('[data-act]');
    const cell = event.target.closest('[data-remark]');
    if (!button || !cell) {
      return;
    }
    const action = button.dataset.act;
    if (action === 'edit') {
      event.preventDefault();
      openEditor(cell);
    } else if (action === 'cancel') {
      closeEditor(cell);
    } else if (action === 'save') {
      const area = cell.querySelector('.remark-editor textarea');
      const note = (area.value || '').trim();
      if (note.length > MAX_LENGTH) {
        ui.toast(`备注不能超过 ${MAX_LENGTH} 字`, 'error');
        return;
      }
      persist(cell, note);
    } else if (action === 'clear') {
      persist(cell, '');
    } else if (action === 'toggle') {
      const full = cell.querySelector('.remark-full');
      const expanded = full.hidden;
      paint(cell, cell.dataset.note || '', expanded);
    }
  });
  root.addEventListener('keydown', (event) => {
    const cell = event.target.closest('[data-remark]');
    if (!cell) {
      return;
    }
    const area = cell.querySelector('.remark-editor textarea');
    if (!area || area !== event.target) {
      return;
    }
    if (event.key === 'Escape') {
      event.stopPropagation();
      closeEditor(cell);
    } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      persist(cell, (area.value || '').trim());
    }
  });
}
