/** DOM helpers, formatting, badges, modal, pagination and shell wiring shared by all pages. */
import { context } from './api.js';

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

const STATUS_LABELS = {
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
};

export function statusLabel(status) {
  return STATUS_LABELS[status] || status || '';
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
  }[status] || 'muted';
  return `<span class="badge ${tone}">${escapeHtml(statusLabel(status))}</span>`;
}

export function issueLevelBadge(level) {
  const tone = level === 'ERROR' ? 'fail' : level === 'WARN' ? 'warn' : 'info';
  return `<span class="badge ${tone}">${escapeHtml(level)}</span>`;
}

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

/** Opens a modal and returns the dialog element. */
export function openModal(title, bodyHtml, footerHtml = '') {
  const host = byId('modalHost') || document.body;
  const backdrop = document.createElement('div');
  backdrop.className = 'modal-backdrop';
  backdrop.innerHTML = `
    <div class="modal" role="dialog" aria-modal="true">
      <div class="modal-head">
        <h3>${escapeHtml(title)}</h3>
        <button class="modal-close" aria-label="关闭">×</button>
      </div>
      <div class="modal-body">${bodyHtml}</div>
      ${footerHtml ? `<div class="modal-foot">${footerHtml}</div>` : ''}
    </div>`;
  host.appendChild(backdrop);
  const close = () => backdrop.remove();
  backdrop.querySelector('.modal-close').addEventListener('click', close);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) {
      close();
    }
  });
  return { root: backdrop, close };
}

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

/** Wires the top bar with runtime context. */
export async function initShell() {
  applyStoredTheme();
  try {
    const ctx = await context();
    const badge = byId('ctxBadge');
    if (badge) {
      badge.textContent = ctx.mode === 'TENANT' ? `租户 ${ctx.tenantId}` : '单机模式';
    }
    const sub = byId('ctxSub');
    if (sub) {
      const productNames = ctx.products.map((product) => product.code).join(' / ');
      sub.textContent = `v${ctx.version} · 产品 ${productNames} · 外部工具${ctx.externalEnabled ? '已启用' : '未启用'}`;
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
