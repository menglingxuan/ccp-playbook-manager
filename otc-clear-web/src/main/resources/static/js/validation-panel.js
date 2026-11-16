import * as api from './api.js';
import * as ui from './ui.js';

const t = ui.t;

function failureRows(rows) {
  if (!rows.length) {
    return `<tr><td colspan="7" class="empty">${ui.escapeHtml(t('validation.panel.noFailure'))}</td></tr>`;
  }
  return rows.map((row) => row.failures.map((failure) => `<tr class="v-fail${
    failure.severity === 'ERROR' ? ' v-block' : ''}">
      <td class="num">${row.rowNo}</td>
      <td class="mono">${ui.escapeHtml(row.testCaseId || '—')}</td>
      <td>${ui.escapeHtml(failure.header)}</td>
      <td class="mono val">${failure.value === null || failure.value === ''
    ? '<span class="muted">（空）</span>' : ui.escapeHtml(failure.value)}</td>
      <td class="mono">${ui.escapeHtml(failure.expression || '—')}</td>
      <td class="mono">${ui.escapeHtml((failure.failedRules || []).join(', ') || '—')}</td>
      <td>${failure.severity === 'ERROR'
    ? `<span class="chip fail">${ui.escapeHtml(t('validation.panel.severity.error'))}</span>`
    : `<span class="chip warn">${ui.escapeHtml(t('validation.panel.severity.warn'))}</span>`}</td>
    </tr>`).join('')).join('');
}

function fieldRows(fields) {
  if (!fields.length) {
    return `<tr><td colspan="7" class="empty">${ui.escapeHtml(t('validation.panel.noField'))}</td></tr>`;
  }
  return fields.map((field) => `<tr class="${field.ruled ? (field.failed ? 'v-fail' : '') : 'v-unruled'}">
      <td>${ui.escapeHtml(field.header)}</td>
      <td class="mono-sm">${ui.escapeHtml(field.fieldKey)}</td>
      <td>${field.scope === 'MANAGED'
    ? `<span class="chip muted">${ui.escapeHtml(t('validation.panel.managed'))}</span>` : '—'}</td>
      <td class="mono">${field.expression ? ui.escapeHtml(field.expression)
    : `<span class="muted">${ui.escapeHtml(t('validation.panel.unruled'))}</span>`}${
    field.implicit ? ` <span class="chip info" title="${ui.escapeHtml(t('validation.panel.implicitHint'))}">${
      ui.escapeHtml(t('validation.panel.implicit'))}</span>` : ''}</td>
      <td class="num">${field.checked}</td>
      <td class="num">${field.failed ? `<span class="v-bad">${field.failed}</span>` : 0}</td>
      <td class="num">${field.blocked ? `<span class="v-bad">${field.blocked}</span>` : 0}</td>
    </tr>`).join('');
}

/** Toolbar holding the switches and the button that runs the validation again. */
function optionsBar(settings) {
  return `
    <div class="drawer-tools v-toolbar">
      <label class="setting"><input type="checkbox" id="vIgnoreCase"${settings.ignoreCase
    ? ' checked' : ''}> ${ui.escapeHtml(t('validation.panel.ignoreCase'))}</label>
      <label class="setting"><input type="checkbox" id="vSkipBlank"${settings.skipBlank
    ? ' checked' : ''}> ${ui.escapeHtml(t('validation.panel.skipBlank'))}</label>
      <button class="tool-btn" id="vRefresh" type="button">${ui.escapeHtml(t('validation.panel.refresh'))}</button>
      <span class="muted v-switch-hint">${ui.escapeHtml(t('validation.panel.switchHint'))}</span>
    </div>`;
}

function panelHtml(result, options, settings) {
  const clean = result.rowsFailed === 0;
  const stat = result.fields.filter((field) => field.ruled).length;
  const unruledFree = !result.unruledFields.length;
  return `
        <div class="cmp-summary">
          <span class="chip ${clean ? 'pass' : 'fail'}">${clean
    ? ui.escapeHtml(t('validation.panel.allPassed'))
    : ui.escapeHtml(t('validation.panel.failedRows', result.rowsFailed))}</span>
          ${result.rowsBlocked
    ? `<span class="chip fail">${ui.escapeHtml(t('validation.panel.blockedRows', result.rowsBlocked))}</span>`
    : ''}
          <span class="chip muted">${ui.escapeHtml(t('validation.panel.rowsChecked', result.rowsChecked))}</span>
          <span class="chip muted">${ui.escapeHtml(t('validation.panel.ruledFields', stat))}</span>
          <span class="chip ${unruledFree ? 'muted' : 'warn'}">${ui.escapeHtml(
    t('validation.panel.unruledCount', result.unruledFields.length))}</span>
          <span class="chip muted">${ui.escapeHtml(result.productCode || '')} · ${ui.escapeHtml(result.scope)}</span>
          ${result.ignoreCase
    ? `<span class="chip info">${ui.escapeHtml(t('validation.panel.ignoreCase'))}</span>` : ''}
          ${result.skipBlank
    ? `<span class="chip info">${ui.escapeHtml(t('validation.panel.skipBlank'))}</span>` : ''}
          ${result.truncated
    ? `<span class="chip warn">${ui.escapeHtml(t('validation.panel.truncated'))}</span>` : ''}
        </div>
        <h4 class="cmp-section">${ui.escapeHtml(t('validation.panel.failures'))}</h4>
        <div class="table-wrap"><table class="cmp-table" id="vFailures"><thead><tr>
          <th>${ui.escapeHtml(t('validation.panel.col.row'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.caseId'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.field'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.value'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.expression'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.rules'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.severity'))}</th>
        </tr></thead><tbody>${failureRows(result.failures)}</tbody></table></div>
        <h4 class="cmp-section">${ui.escapeHtml(t('validation.panel.fieldStats'))}</h4>
        <div class="table-wrap"><table class="cmp-table" id="vFields"><thead><tr>
          <th>${ui.escapeHtml(t('validation.panel.col.field'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.fieldKey'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.scope'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.expression'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.checked'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.failed'))}</th>
          <th>${ui.escapeHtml(t('validation.panel.col.blocked'))}</th>
        </tr></thead><tbody>${fieldRows(result.fields)}</tbody></table></div>
        ${result.unruledFields.length ? `
          <h4 class="cmp-section">${ui.escapeHtml(t('validation.panel.unruledTitle'))}</h4>
          <div class="v-unruled-list">${result.unruledFields
    .map((header) => `<span class="chip muted">${ui.escapeHtml(header)}</span>`).join('')}</div>
          <div class="hint">${ui.escapeHtml(t('validation.panel.unruledHint'))}</div>` : ''}
        <div class="hint">${ui.escapeHtml(t('validation.panel.hint'))}</div>`;
}

/**
 * Fetches a validation result and shows it in a standalone panel.
 *
 * <p>The panel carries two switches (ignore case, skip blank) plus a refresh button; refreshing runs
 * the validation again with the switches currently ticked and repaints the same panel.
 *
 * @param {string} url  validation endpoint of a case or a batch, without a query string
 * @param {object} options panel title and subtitle
 * @returns {Promise<object|null>} the last result, or null when the request failed
 */
export async function openValidationPanel(url, options) {
  const { title, subtitle } = options || {};
  const settings = { ignoreCase: false, skipBlank: true };
  let result = null;
  const drawer = ui.openDrawer({
    title: title || t('validation.panel.title'),
    subtitle: subtitle || '',
    wide: true,
    toolsHtml: optionsBar(settings),
    bodyHtml: `<div class="hint">${ui.escapeHtml(t('validation.panel.refreshing'))}</div>`,
  });
  drawer.root.querySelector('.drawer').classList.add('vpanel');
  const order = ui.sequence();

  function paint() {
    drawer.body.innerHTML = panelHtml(result, { title, subtitle }, settings);
    drawer.root.querySelector('#vRefresh').addEventListener('click', () => {
      settings.ignoreCase = drawer.root.querySelector('#vIgnoreCase').checked;
      settings.skipBlank = drawer.root.querySelector('#vSkipBlank').checked;
      load(true);
    });
  }

  async function load(busy) {
    const token = order.next();
    const button = drawer.root.querySelector('#vRefresh');
    if (busy && button) {
      button.disabled = true;
      button.textContent = t('validation.panel.refreshing');
    }
    try {
      const loaded = await api.get(
        `${url}?ignoreCase=${settings.ignoreCase}&skipBlank=${settings.skipBlank}`);
      if (!token()) {
        // a newer refresh is on its way; the older answer must not repaint the panel
        return;
      }
      result = loaded;
      settings.ignoreCase = result.ignoreCase === true;
      settings.skipBlank = result.skipBlank === true;
      paint();
    } catch (error) {
      if (button) {
        button.disabled = false;
        button.textContent = t('validation.panel.refresh');
      }
      ui.showError(error);
    }
  }

  await load(false);
  return result;
}
