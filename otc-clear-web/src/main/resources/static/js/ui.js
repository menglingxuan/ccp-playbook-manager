/** DOM helpers, icons, i18n, formatting, badges, modal, pagination and shell wiring shared by all pages. */
import { context, get } from './api.js';

export function escapeHtml(value) {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

export function byId(id) {
  return document.getElementById(id);
}

export function on(id, event, handler) {
  const node = byId(id);
  if (node) {
    node.addEventListener(event, handler);
  }
}

export function setHtml(id, html) {
  const node = byId(id);
  if (node) {
    node.innerHTML = html;
  }
}

// ----------------------------------------------------------------------- i18n

const I18N_KEY = 'otc.i18n';
let messages = null;
let activeLocale = 'zh-CN';

/** Loads the message bundle of the active locale once per page. */
export async function loadMessages() {
  if (messages) {
    return messages;
  }
  try {
    const bundle = await get('/api/i18n');
    messages = bundle.messages || {};
    activeLocale = bundle.locale || 'zh-CN';
  } catch (error) {
    messages = {};
  }
  return messages;
}

export function locale() {
  return activeLocale;
}

/** Translates a key; `{0}`, `{1}` … placeholders are replaced by the extra arguments. */
export function t(key, ...args) {
  const raw = messages && messages[key] !== undefined ? messages[key] : key;
  if (!args.length) {
    return raw;
  }
  return raw.replace(/\{(\d+)\}/g, (match, index) => {
    const value = args[Number(index)];
    return value === undefined || value === null ? match : String(value);
  });
}

/** Applies `data-i18n`, `data-i18n-title` and `data-i18n-placeholder` inside the given root. */
export function applyI18n(root = document) {
  root.querySelectorAll('[data-i18n]').forEach((node) => {
    const text = t(node.dataset.i18n);
    if (text !== node.dataset.i18n) {
      node.textContent = text;
    }
  });
  root.querySelectorAll('[data-i18n-title]').forEach((node) => {
    node.title = t(node.dataset.i18nTitle);
  });
  root.querySelectorAll('[data-i18n-placeholder]').forEach((node) => {
    node.placeholder = t(node.dataset.i18nPlaceholder);
  });
}

const LANG_COOKIE = 'lang';
const LANG_LABELS = { 'zh-CN': 'zh-CN', 'zh-HK': 'zh-HK', en: 'en' };

/** Normalizes a locale tag to one of the three supported codes. */
export function normalizeLocale(value) {
  if (!value) {
    return 'zh-CN';
  }
  if (LANG_LABELS[value]) {
    return value;
  }
  const lower = String(value).toLowerCase();
  if (lower.startsWith('zh-hk') || lower.startsWith('zh_hk') || lower.startsWith('zh-hant')) {
    return 'zh-HK';
  }
  if (lower.startsWith('zh')) {
    return 'zh-CN';
  }
  return 'en';
}

export function setLocale(code) {
  const value = normalizeLocale(code);
  document.cookie = `${LANG_COOKIE}=${value}; path=/; max-age=31536000; samesite=lax`;
  window.location.reload();
}

// ---------------------------------------------------------------------- icons

const ICONS = {
  plus: 'M8 3v10M3 8h10',
  edit: 'M2.5 13.5l.9-3.2 7-7 2.3 2.3-7 7-3.2.9zM10.2 3.6l2.2 2.2',
  trash: 'M3 4.5h10M6.4 4.5V2.9h3.2v1.6M4.6 4.5l.6 8.6h5.6l.6-8.6',
  up: 'M4.5 9.8L8 6.2l3.5 3.6',
  down: 'M4.5 6.2L8 9.8l3.5-3.6',
  search: 'M7 11.5a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM10.4 10.4L13.6 13.6',
  sync: 'M13.2 8a5.2 5.2 0 1 1-1.6-3.7M13.2 2.4V5.2h-2.8',
  reset: 'M2.8 8a5.2 5.2 0 1 0 1.6-3.7M2.8 2.4V5.2h2.8',
  filter: 'M2.5 4h11M4.8 8h6.4M6.8 12h2.4',
  close: 'M4 4l8 8M12 4l-8 8',
  expand: 'M6 3.5L10.5 8L6 12.5',
  goto: 'M6.2 3.2h6.6v6.6M12.8 3.2L3.4 12.6',
  globe: 'M8 14A6 6 0 1 0 8 2a6 6 0 0 0 0 12zM2.4 6h11.2M2.4 10h11.2M8 2c1.7 1.8 2.6 3.8 2.6 6S9.7 12.2 8 14c-1.7-1.8-2.6-3.8-2.6-6S6.3 3.8 8 2z',
  layers: 'M8 2.2l5.4 2.8L8 7.8 2.6 5 8 2.2zM2.6 8L8 10.8 13.4 8M2.6 11L8 13.8l5.4-2.8',
  list: 'M5.5 4.2h8M5.5 8h8M5.5 11.8h8M2.6 4.2h.01M2.6 8h.01M2.6 11.8h.01',
  lock: 'M4.6 7.2V5.4a3.4 3.4 0 0 1 6.8 0v1.8M3.6 7.2h8.8v5.4a1 1 0 0 1-1 1H4.6a1 1 0 0 1-1-1V7.2z',
  unlock: 'M4.6 7.2V5.4a3.4 3.4 0 0 1 6.6-1.1M3.6 7.2h8.8v5.4a1 1 0 0 1-1 1H4.6a1 1 0 0 1-1-1V7.2z',
  grip: 'M6 3.6h.01M6 8h.01M6 12.4h.01M10 3.6h.01M10 8h.01M10 12.4h.01',
  upload: 'M8 10.6V2.8M4.9 5.9L8 2.8l3.1 3.1M2.8 10.4v1.4a1 1 0 0 0 1 1h8.4a1 1 0 0 0 1-1v-1.4',
};

/** Returns an inline SVG icon that inherits the current text color. */
export function icon(name, size = 14) {
  const path = ICONS[name] || ICONS.list;
  return `<svg class="ico" width="${size}" height="${size}" viewBox="0 0 16 16" fill="none"
    stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"
    aria-hidden="true" focusable="false"><path d="${path}"/></svg>`;
}

/** Builds an icon-only button with a tooltip and an accessible name. */
export function iconButton(name, title, extraClass = '', attrs = '') {
  const label = escapeHtml(title);
  return `<button type="button" class="icon-btn ${extraClass}" title="${label}" aria-label="${label}" ${attrs}>${icon(name)}</button>`;
}

// -------------------------------------------------------------------- statuses

const STATUS_KEYS = {
  PENDING: '待处理',
  RUNNING: '进行中',
  SUCCEEDED: '成功',
  PARTIAL: '部分成功',
  FAILED: '失败',
  DELETED: '已删除',
  DISPATCHED: '已派发',
  STOPPED: '已停止',
  UNKNOWN: '未知',
  IMPORTED: '已导入',
  DISCOVERED: '已发现',
  SKIPPED: '已跳过',
  COMPLETED: '已完成',
  ACTIVE: '有效',
  ENABLED: '启用',
  DISABLED: '停用',
};

export function statusLabel(status) {
  if (!status) {
    return '';
  }
  const key = `status.${status}`;
  const text = t(key);
  return text === key ? STATUS_KEYS[status] || status : text;
}

export function statusBadge(status) {
  const tone = {
    SUCCEEDED: 'pass',
    PARTIAL: 'warn',
    FAILED: 'fail',
    DELETED: 'muted',
    RUNNING: 'info',
    PENDING: 'muted',
    DISPATCHED: 'info',
    STOPPED: 'warn',
    UNKNOWN: 'warn',
    IMPORTED: 'pass',
    DISCOVERED: 'info',
    SKIPPED: 'muted',
    COMPLETED: 'pass',
    ACTIVE: 'pass',
    ENABLED: 'pass',
    DISABLED: 'muted',
  }[status] || 'muted';
  return `<span class="badge ${tone}">${escapeHtml(statusLabel(status))}</span>`;
}

export function issueLevelBadge(level) {
  const tone = level === 'ERROR' ? 'fail' : level === 'WARN' ? 'warn' : 'info';
  return `<span class="badge ${tone}">${escapeHtml(level)}</span>`;
}

// ---------------------------------------------------------------------- toast

export function toast(message, type = 'info') {
  const host = byId('toastHost');
  if (!host) {
    return;
  }
  const node = document.createElement('div');
  node.className = `toast ${type}`;
  node.textContent = message;
  host.appendChild(node);
  setTimeout(() => node.remove(), type === 'error' ? 7000 : 3500);
}

export function showError(error) {
  toast(error && error.message ? error.message : String(error), 'error');
}

export function bytes(size) {
  if (!size) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = size;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

/** Renders a pagination bar into the container. */
export function renderPager(containerId, page, totalPages, total, onGo) {
  const container = byId(containerId);
  if (!container) {
    return;
  }
  if (!totalPages || totalPages <= 1) {
    container.innerHTML = `<span class="pg-info">共 ${total} 条</span>`;
    return;
  }
  const buttons = [];
  const pushPage = (number) => {
    buttons.push(`<button class="pg ${number === page ? 'cur' : ''}" data-page="${number}">${number}</button>`);
  };
  buttons.push(`<button class="pg" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>‹</button>`);
  const windowStart = Math.max(1, page - 2);
  const windowEnd = Math.min(totalPages, windowStart + 4);
  if (windowStart > 1) {
    pushPage(1);
    if (windowStart > 2) {
      buttons.push('<span class="pg-info">…</span>');
    }
  }
  for (let number = windowStart; number <= windowEnd; number += 1) {
    pushPage(number);
  }
  if (windowEnd < totalPages) {
    if (windowEnd < totalPages - 1) {
      buttons.push('<span class="pg-info">…</span>');
    }
    pushPage(totalPages);
  }
  buttons.push(`<button class="pg" data-page="${page + 1}" ${page >= totalPages ? 'disabled' : ''}>›</button>`);
  buttons.push(`<span class="pg-info">共 ${total} 条</span>`);
  container.innerHTML = buttons.join('');
  container.querySelectorAll('button[data-page]').forEach((button) => {
    button.addEventListener('click', () => {
      const target = Number(button.dataset.page);
      if (target >= 1 && target <= totalPages && target !== page) {
        onGo(target);
      }
    });
  });
}

// ---------------------------------------------------------------------- modal

/** Opens a modal and returns the dialog element. */
export function openModal(title, bodyHtml, footerHtml = '') {
  const host = byId('modalHost') || document.body;
  const backdrop = document.createElement('div');
  backdrop.className = 'modal-backdrop';
  backdrop.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal-head">
        <h3>${escapeHtml(title)}</h3>
        <button class="modal-close" aria-label="${escapeHtml(t('common.close'))}">×</button>
      </div>
      <div class="modal-body">${bodyHtml}</div>
      ${footerHtml ? `<div class="modal-foot">${footerHtml}</div>` : ''}
    </div>`;
  host.appendChild(backdrop);
  const close = () => {
    backdrop.remove();
    document.removeEventListener('keydown', onKey);
  };
  const onKey = (event) => {
    if (event.key === 'Escape') {
      close();
    }
  };
  document.addEventListener('keydown', onKey);
  backdrop.querySelector('.modal-close').addEventListener('click', close);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) {
      close();
    }
  });
  applyI18n(backdrop);
  return { root: backdrop, close };
}

// -------------------------------------------------------------- long text panel

let valuePanel = null;

export function closeValuePanel() {
  if (valuePanel) {
    valuePanel.remove();
    valuePanel = null;
  }
}

/**
 * Opens the floating panel that shows the full text of a long cell; the panel is resizable and
 * offers a copy button.
 */
export function openValuePanel(anchor, text) {
  if (valuePanel && valuePanel.__anchor === anchor) {
    closeValuePanel();
    return;
  }
  closeValuePanel();
  const panel = document.createElement('div');
  panel.className = 'val-panel';
  panel.innerHTML = `<pre class="val-panel-pre"></pre>
    <button type="button" class="val-panel-copy" title="${escapeHtml(t('common.copy'))}"
      aria-label="${escapeHtml(t('common.copy'))}">${icon('list')}</button>`;
  panel.querySelector('.val-panel-pre').textContent = text;
  document.body.appendChild(panel);

  const rect = anchor.getBoundingClientRect();
  const width = panel.offsetWidth;
  const height = panel.offsetHeight;
  let left = rect.left;
  let top = rect.bottom + 6;
  if (left + width > window.innerWidth - 8) {
    left = Math.max(8, window.innerWidth - width - 8);
  }
  if (top + height > window.innerHeight - 8) {
    top = Math.max(8, rect.top - height - 6);
  }
  panel.style.left = `${left}px`;
  panel.style.top = `${top}px`;
  panel.__anchor = anchor;

  panel.addEventListener('click', async (event) => {
    if (!event.target.closest('.val-panel-copy')) {
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      toast(t('common.copied'), 'ok');
    } catch (error) {
      showError(error);
    }
  });
  valuePanel = panel;
}

/** Renders a table cell that truncates long text and opens the floating panel on click. */
export function longTextCell(text, limit = 24) {
  if (text === null || text === undefined || text === '') {
    return '<span class="muted">—</span>';
  }
  const flat = String(text).replace(/\s+/g, ' ').trim();
  if (flat.length <= limit) {
    return `<span class="cell-text">${escapeHtml(flat)}</span>`;
  }
  return `<button type="button" class="cell-text long" data-long-expand="1"
    data-long-text="${escapeHtml(text)}" title="${escapeHtml(t('common.expand'))}">${escapeHtml(flat.slice(0, limit))}…</button>`;
}

/** Wires long-text buttons inside a container and closes the panel on outside clicks. */
export function bindLongText(container) {
  container.querySelectorAll('[data-long-expand]').forEach((button) => {
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      openValuePanel(button, button.getAttribute('data-long-text') || '');
    });
  });
}

document.addEventListener('click', (event) => {
  if (valuePanel && !event.target.closest('.val-panel') && !event.target.closest('[data-long-expand]')) {
    closeValuePanel();
  }
});

// ---------------------------------------------------------------------- theme

const THEME_KEY = 'otc.theme';

export function applyStoredTheme() {
  const stored = localStorage.getItem(THEME_KEY) || 'light';
  document.body.className = stored === 'light' ? '' : stored;
  const select = byId('themeSelect');
  if (select) {
    select.value = stored;
    select.addEventListener('change', () => {
      const value = select.value;
      localStorage.setItem(THEME_KEY, value);
      document.body.className = value === 'light' ? '' : value;
    });
  }
}

/** Reflects the active locale in the language selector and reloads on change. */
export function applyLanguageSelector() {
  const select = byId('langSelect');
  if (!select) {
    return;
  }
  select.value = normalizeLocale(activeLocale);
  select.addEventListener('change', () => setLocale(select.value));
}

/** Wires the top bar with runtime context. */
export async function initShell() {
  await loadMessages();
  applyStoredTheme();
  applyLanguageSelector();
  applyI18n(document);
  try {
    const ctx = await context();
    const badge = byId('ctxBadge');
    if (badge) {
      badge.textContent = ctx.mode === 'TENANT' ? t('shell.mode.tenant', ctx.tenantId) : t('shell.mode.single');
    }
    const sub = byId('ctxSub');
    if (sub) {
      const productNames = ctx.products.map((product) => product.code).join(' / ');
      sub.textContent = `v${ctx.version} · ${t('shell.products')} ${productNames} · `
        + t(ctx.externalEnabled ? 'shell.external.on' : 'shell.external.off');
    }
    return ctx;
  } catch (error) {
    showError(error);
    return null;
  }
}

export function cell(value, fallback = '—') {
  const text = value === null || value === undefined || value === '' ? fallback : value;
  return `<span class="${text === fallback ? 'muted' : ''}">${escapeHtml(text)}</span>`;
}

export function shortId(value, length = 8) {
  if (!value) {
    return '';
  }
  return value.length > length ? `${value.slice(0, length)}…` : value;
}

/** Flashes a row or list item to draw attention after a jump. */
export function flashElement(element) {
  if (!element) {
    return;
  }
  element.classList.remove('item-flash', 'row-flash');
  void element.offsetWidth;
  element.classList.add(element.tagName === 'TR' ? 'row-flash' : 'item-flash');
  element.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

/** Highlights every case-insensitive occurrence of {@code query} in an already escaped string. */
export function highlight(escapedText, query) {
  const text = String(escapedText ?? '');
  const needle = (query || '').trim();
  if (!needle) {
    return text;
  }
  const lower = text.toLowerCase();
  const q = needle.toLowerCase();
  let out = '';
  let index = 0;
  while (index < text.length) {
    const found = lower.indexOf(q, index);
    if (found === -1) {
      out += text.slice(index);
      break;
    }
    out += `${text.slice(index, found)}<mark>${text.slice(found, found + q.length)}</mark>`;
    index = found + q.length;
  }
  return out;
}
