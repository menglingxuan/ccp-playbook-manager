import * as ui from './ui.js';

/**
 * Collapsible issue lists shared by every page that shows import problems.
 *
 * <p>A list renders only its first rows and folds the rest into a "more" row. A short list grows in
 * place; once it exceeds the configured limit the remaining issues open in a paginated drawer, so a
 * sheet with hundreds of issues never stretches the page. Both limits come from the server
 * ({@code app.ui.issue-preview-rows} / {@code app.ui.issue-inline-limit}, the latter overridable per
 * tenant) so the wizard and the task detail behave the same way.
 */

const DEFAULT_PREVIEW_ROWS = 5;
const DEFAULT_INLINE_LIMIT = 10;

/** Drawer page size of a long issue list. */
const DRAWER_PAGE_SIZE = 20;

const limits = { previewRows: DEFAULT_PREVIEW_ROWS, inlineLimit: DEFAULT_INLINE_LIMIT };

/** Whether a list currently shows every issue; kept per host so two lists do not share the state. */
const expandedHosts = new WeakSet();

/**
 * Applies the server side display preferences.
 *
 * @param preferences {@code {issuePreviewRows, issueInlineLimit}}, missing values keep the defaults
 */
export function configure(preferences) {
  limits.previewRows = Math.max(1, Number(preferences?.issuePreviewRows) || DEFAULT_PREVIEW_ROWS);
  limits.inlineLimit = Math.max(limits.previewRows,
      Number(preferences?.issueInlineLimit) || DEFAULT_INLINE_LIMIT);
}

/** Issues rendered before the "more" row. */
export function previewRows() {
  return limits.previewRows;
}

/** Issues that still expand in place; a longer list opens the paginated drawer instead. */
export function inlineLimit() {
  return limits.inlineLimit;
}

/** One issue block: message plus the machine readable code and its position. */
export function issueRowHtml(issue) {
  return `
    <div class="issue ${ui.escapeHtml(issue.level || 'WARN')}">
      <span>${ui.escapeHtml(issue.message)}</span>
      <code>${ui.escapeHtml(issue.code || '')}${issue.row ? ` · 行 ${issue.row}` : ''}${
    issue.column ? ` · 列 ${issue.column}` : ''}</code>
    </div>`;
}

/** One row of a table style list: which sheet failed and why. */
export function tableRowHtml(issue) {
  return `<tr><td>${ui.escapeHtml(issue.sheet || '')}</td>
      <td>${issue.code ? `<code>${ui.escapeHtml(issue.code)}</code>` : ''}</td>
      <td>${ui.escapeHtml(issue.message || '')}</td></tr>`;
}

/** The "more" row of a collapsed list. */
function moreHtml(hiddenCount) {
  return `<button class="link-btn issue-more" type="button" data-issue-more>更多…（还有 ${hiddenCount} 条）</button>`;
}

/**
 * Every issue in a paginated drawer.
 *
 * @param issues  the whole list
 * @param options {@code title}, {@code table} (rows are table rows), {@code rowHtml}
 * @return the drawer handle from {@link ui#openDrawer}
 */
export function openDrawer(issues, options = {}) {
  const rowHtml = options.rowHtml || issueRowHtml;
  const table = Boolean(options.table);
  let page = 0;
  const pages = Math.max(1, Math.ceil(issues.length / DRAWER_PAGE_SIZE));
  const drawer = ui.openDrawer({
    title: options.title || '问题清单',
    subtitle: `共 ${issues.length} 条 · 每页 ${DRAWER_PAGE_SIZE} 条`,
    wide: true,
    footerHtml: `<div class="issue-drawer-pager">
      <button class="tool-btn small" type="button" data-page="prev" title="上一页">‹</button>
      <span class="mono-sm" data-page-label></span>
      <button class="tool-btn small" type="button" data-page="next" title="下一页">›</button>
    </div>`,
  });
  const paint = () => {
    const slice = issues.slice(page * DRAWER_PAGE_SIZE, (page + 1) * DRAWER_PAGE_SIZE);
    drawer.body.innerHTML = table
      ? `<div class="table-wrap"><table class="issue-table">
          <thead><tr><th>Sheet</th><th>代码</th><th>说明</th></tr></thead>
          <tbody>${slice.map(rowHtml).join('')}</tbody></table></div>`
      : `<div class="issues">${slice.map(rowHtml).join('')}</div>`;
    if (drawer.foot) {
      drawer.foot.querySelector('[data-page-label]').textContent =
        `第 ${page + 1} / ${pages} 页 · 第 ${page * DRAWER_PAGE_SIZE + 1}–${
          page * DRAWER_PAGE_SIZE + slice.length} 条`;
      drawer.foot.querySelector('[data-page="prev"]').disabled = page === 0;
      drawer.foot.querySelector('[data-page="next"]').disabled = page >= pages - 1;
    }
    drawer.body.scrollTop = 0;
  };
  if (drawer.foot) {
    drawer.foot.querySelector('[data-page="prev"]').addEventListener('click', () => {
      page = Math.max(0, page - 1);
      paint();
    });
    drawer.foot.querySelector('[data-page="next"]').addEventListener('click', () => {
      page = Math.min(pages - 1, page + 1);
      paint();
    });
  }
  paint();
  return drawer;
}

/**
 * Paints a collapsible issue list into a host element.
 *
 * <p>The host keeps the expanded state: a re-render (a new page, a filter change) folds it back, while
 * pressing "more" only repaints that host.
 *
 * @param host    container that receives the markup
 * @param issues  the whole list
 * @param options {@code emptyHtml}, {@code rowHtml}, {@code table} (rows are table rows, so the "more"
 *                row is wrapped in a {@code <tr>}), {@code columns} for that colspan and
 *                {@code drawerTitle} for the drawer, {@code reset} to fold the list again
 */
export function mount(host, issues, options = {}) {
  if (!host) {
    return;
  }
  if (options.reset !== false) {
    expandedHosts.delete(host);
  }
  if (!issues.length) {
    host.innerHTML = options.emptyHtml || '<div class="muted">没有发现问题。</div>';
    return;
  }
  const expanded = expandedHosts.has(host);
  const rowHtml = options.rowHtml || issueRowHtml;
  const shown = expanded ? issues : issues.slice(0, previewRows());
  const more = expanded || issues.length <= previewRows() ? ''
    : (options.table
      ? `<tr><td colspan="${Number(options.columns) || 3}">${moreHtml(issues.length - previewRows())}</td></tr>`
      : moreHtml(issues.length - previewRows()));
  host.innerHTML = shown.map(rowHtml).join('') + more;
  const moreButton = host.querySelector('[data-issue-more]');
  if (!moreButton) {
    return;
  }
  moreButton.addEventListener('click', () => {
    if (issues.length <= inlineLimit()) {
      expandedHosts.add(host);
      mount(host, issues, { ...options, reset: false });
      return;
    }
    openDrawer(issues, {
      title: options.drawerTitle,
      table: options.table,
      rowHtml: options.rowHtml,
    });
  });
}
