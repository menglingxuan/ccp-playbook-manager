import * as api from './api.js';
import * as ui from './ui.js';

const t = ui.t;

/** Rows shown per status group; unchanged rows are only listed briefly, the counts stay complete. */
const ROW_LIMIT = 200;
const UNCHANGED_ROW_LIMIT = 30;

/**
 * Backup and restore of one definition scope.
 *
 * <p>Export downloads the catalogue as an Excel workbook (the restorable form) or as a replayable SQL
 * script. Import uploads a workbook, shows the pre-check in a standalone panel and applies it with
 * the selected mode.
 */
export function definitionBackup(scope, options = {}) {
  const label = options.label || scope;

  function download(format) {
    window.location = `/api/definitions/export?scope=${encodeURIComponent(scope)}&format=${format}`;
  }

  function openPicker() {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = '.xlsx';
    input.addEventListener('change', () => {
      const file = input.files && input.files[0];
      if (file) {
        void preview(file);
      }
    });
    input.click();
  }

  async function preview(file) {
    let plan = null;
    const drawer = ui.openDrawer({
      title: t('definitions.import.title', label),
      subtitle: file.name,
      wide: true,
      bodyHtml: `<div class="hint">${ui.escapeHtml(t('definitions.import.checking'))}</div>`,
      onClose: () => {
        // an abandoned preview must not keep a plan in memory on the server
        if (plan && plan.planId) {
          api.del(`/api/definitions/import/${encodeURIComponent(plan.planId)}`).catch(() => null);
        }
      },
    });
    try {
      plan = await api.upload(
        `/api/definitions/import/preview?scope=${encodeURIComponent(scope)}`, file);
    } catch (error) {
      ui.showError(error);
      drawer.close();
      return;
    }
    render(drawer, plan);
  }

  function render(drawer, plan) {
    const counts = plan.counts || {};
    const statuses = [
      ['ADD', 'ok', t('definitions.status.add')],
      ['CONFLICT', 'warn', t('definitions.status.conflict')],
      ['UNCHANGED', 'muted', t('definitions.status.unchanged')],
      ['INVALID', 'fail', t('definitions.status.invalid')],
    ];
    const chips = statuses
      .map(([key, tone, textLabel]) => `<span class="chip ${tone}">${
        ui.escapeHtml(textLabel)} ${counts[key] || 0}</span>`)
      .join('');
    const groups = statuses
      .map(([key, tone, textLabel]) => groupHtml(plan, key, tone, textLabel))
      .join('');
    const notes = (plan.notes || []).map((note) => `<div class="hint">${ui.escapeHtml(note)}</div>`).join('');
    const modes = [
      ['MERGE_OVERWRITE', t('definitions.mode.overwrite')],
      ['MERGE_SKIP', t('definitions.mode.skip')],
      ['FULL_RESTORE', t('definitions.mode.restore')],
    ]
      .map(([value, textLabel], index) => `
        <label class="setting"><input type="radio" name="defMode" value="${value}"${
    index === 0 ? ' checked' : ''}> ${ui.escapeHtml(textLabel)}</label>`)
      .join('');

    drawer.setTitle(t('definitions.import.title', plan.scope === 'META'
      ? t('definitions.scope.meta') : t('definitions.scope.product')), plan.fileName || '');
    const root = drawer.root;
    root.querySelector('.drawer-tools').innerHTML = `
          <span class="v-switch-hint">${ui.escapeHtml(t('definitions.import.modeHint'))}</span>
          ${modes}`;
    drawer.body.innerHTML = `
          <div class="cmp-summary">${chips}
            <span class="chip muted">${ui.escapeHtml(t('definitions.import.total', counts.TOTAL || 0))}</span>
          </div>
          ${notes}
          ${groups}
          <div class="hint">${ui.escapeHtml(t('definitions.import.hint'))}</div>`;
    root.querySelector('.drawer-foot').innerHTML = `
          <button class="tool-btn" data-close>${ui.escapeHtml(t('common.cancel'))}</button>
          <button class="tool-btn primary" data-apply
            ${(counts.ADD || 0) + (counts.CONFLICT || 0) === 0 ? 'disabled' : ''}>${
    ui.escapeHtml(t('definitions.import.apply'))}</button>`;

    root.querySelectorAll('[data-close]').forEach((button) => {
      button.addEventListener('click', () => {
        // closing discards the stored plan, so it can no longer be applied
        drawer.close();
      });
    });
    const applyButton = root.querySelector('[data-apply]');
    applyButton.addEventListener('click', async () => {
      const mode = root.querySelector('input[name="defMode"]:checked').value;
      if (mode === 'FULL_RESTORE'
          && !window.confirm(t('definitions.import.confirmRestore'))) {
        return;
      }
      applyButton.disabled = true;
      applyButton.textContent = t('definitions.import.applying');
      try {
        const result = await api.post(
          `/api/definitions/import/${encodeURIComponent(plan.planId)}`, { mode });
        drawer.close();
        ui.toast(t('definitions.import.applied', result.created, result.updated, result.skipped,
          result.invalid, result.deleted), 'ok');
        if (typeof options.onApplied === 'function') {
          await options.onApplied(result);
        }
      } catch (error) {
        applyButton.disabled = false;
        applyButton.textContent = t('definitions.import.apply');
        ui.showError(error);
      }
    });
  }

  function groupHtml(plan, status, tone, label) {
    const rows = (plan.rows || []).filter((row) => row.status === status);
    if (!rows.length) {
      return '';
    }
    const limit = status === 'UNCHANGED' ? UNCHANGED_ROW_LIMIT : ROW_LIMIT;
    const visible = rows.slice(0, limit);
    const body = visible
      .map((row) => `<tr>
        <td class="mono-sm">${ui.escapeHtml(row.table)}</td>
        <td class="mono">${ui.escapeHtml(row.key)}</td>
        <td class="muted">${ui.escapeHtml((row.messages || []).join('；') || '—')}</td>
      </tr>`)
      .join('');
    const more = rows.length > visible.length
      ? `<div class="hint">${ui.escapeHtml(t('definitions.import.more', rows.length - visible.length))}</div>`
      : '';
    return `
      <h4 class="cmp-section"><span class="chip ${tone}">${ui.escapeHtml(label)}</span> ${
      ui.escapeHtml(t('definitions.import.groupCount', rows.length))}</h4>
      <div class="table-wrap"><table class="cmp-table"><thead><tr>
        <th>${ui.escapeHtml(t('definitions.col.table'))}</th>
        <th>${ui.escapeHtml(t('definitions.col.key'))}</th>
        <th>${ui.escapeHtml(t('definitions.col.detail'))}</th>
      </tr></thead><tbody>${body}</tbody></table></div>
      ${more}`;
  }

  return { download, openPicker };
}

/**
 * Wires the export/import buttons of a page.
 *
 * @param {string} scope {@code PRODUCT} or {@code META}
 * @param {object} ids   element ids: sql, xlsx, import
 * @param {object} options label and refresh callback
 */
export function bindDefinitionBackup(scope, ids, options = {}) {
  const backup = definitionBackup(scope, options);
  ui.on(ids.sql, 'click', () => backup.download('sql'));
  ui.on(ids.xlsx, 'click', () => backup.download('xlsx'));
  ui.on(ids.import, 'click', () => backup.openPicker());
}
