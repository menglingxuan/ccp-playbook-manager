import * as api from './api.js';
import * as ui from './ui.js';

const t = ui.t;

let ruleCache = null;

/**
 * Loads the enabled validation rules used as suggestion chips.
 *
 * @param {boolean} force reload even when cached
 * @returns {Promise<Array<object>>} rules with code, label, content and ruleType
 */
export async function loadRules(force) {
  if (!ruleCache || force) {
    ruleCache = await api.get('/api/meta/rules');
  }
  return ruleCache;
}

/** Drops the cache so the next editor mount reloads the rules. */
export function invalidateRules() {
  ruleCache = null;
}

function chipsHtml(prefix, rules) {
  if (!rules.length) {
    return `<div class="hint">${ui.escapeHtml(t('validation.noRules'))}</div>`;
  }
  const chips = rules
    .map((rule) => {
      const severity = rule.severity === 'ERROR'
        ? t('meta.severity.errorShort') : t('meta.severity.warnShort');
      return `<button type="button" class="chip rule-chip" data-code="${ui.escapeHtml(rule.code)}"
      title="${ui.escapeHtml(`${rule.ruleType || 'REGEX'} ${rule.content || ''} · ${severity}`)}">${
        ui.escapeHtml(rule.code)}</button>`;
    })
    .join('');
  return `<div class="rule-chips" id="${prefix}Chips">${chips}</div>`;
}

/**
 * Markup of the rule expression editor: expression input, rule chips and the instant test row.
 *
 * @param {string} prefix id prefix of the generated elements
 * @param {object} options initial expression, extra hint and the rule list
 * @returns {string} HTML block
 */
export function validationEditorHtml(prefix, options) {
  const { value, hint, rules } = options || {};
  return `
    <div class="field"><label>${ui.escapeHtml(t('validation.form.expr'))}</label>
      <input id="${prefix}Expr" type="text" class="mono" spellcheck="false"
        placeholder="${ui.escapeHtml(t('validation.form.exprPlaceholder'))}"
        value="${value ? ui.escapeHtml(value) : ''}">
      <div class="hint" id="${prefix}ExprState"></div>
      ${chipsHtml(prefix, rules || [])}
      ${hint ? `<div class="hint">${ui.escapeHtml(hint)}</div>` : ''}
    </div>
    <div class="field"><label>${ui.escapeHtml(t('validation.form.test'))}</label>
      <div class="inline-row">
        <input id="${prefix}TestText" type="text" placeholder="${ui.escapeHtml(t('validation.form.testPlaceholder'))}">
        <button class="tool-btn" id="${prefix}TestRun" type="button">${ui.escapeHtml(t('validation.form.testRun'))}</button>
      </div>
      <div class="hint" id="${prefix}TestResult"></div>
    </div>`;
}

/**
 * Wires a mounted editor: syntax checking while typing, rule chips and the instant test.
 *
 * @param {HTMLElement} root modal root containing the editor
 * @param {string} prefix id prefix used by {@link validationEditorHtml}
 * @returns {{expression: () => (string|null), validate: () => Promise<boolean>}}
 */
export function bindValidationEditor(root, prefix) {
  const input = root.querySelector(`#${prefix}Expr`);
  const stateEl = root.querySelector(`#${prefix}ExprState`);
  const testResult = root.querySelector(`#${prefix}TestResult`);
  const testText = root.querySelector(`#${prefix}TestText`);
  let checked = '';
  let valid = true;

  const check = async () => {
    const expression = input.value.trim();
    if (!expression) {
      checked = '';
      valid = true;
      stateEl.className = 'hint';
      stateEl.textContent = t('validation.form.exprEmpty');
      return true;
    }
    try {
      const result = await api.post('/api/meta/rules/test', { expression, text: '' });
      checked = expression;
      valid = result.valid;
      stateEl.className = result.valid ? 'hint ok' : 'hint error';
      stateEl.textContent = result.valid ? t('validation.form.exprOk') : result.message;
    } catch (error) {
      checked = expression;
      valid = false;
      stateEl.className = 'hint error';
      stateEl.textContent = error.message || String(error);
    }
    return valid;
  };

  let timer = null;
  input.addEventListener('input', () => {
    window.clearTimeout(timer);
    stateEl.className = 'hint';
    stateEl.textContent = t('validation.form.exprChecking');
    timer = window.setTimeout(check, 400);
  });

  root.querySelectorAll(`#${prefix}Chips .rule-chip`).forEach((chip) => {
    chip.addEventListener('click', () => {
      const code = chip.dataset.code;
      const current = input.value.trim();
      input.value = current ? `${current} ${code}` : code;
      input.dispatchEvent(new Event('input'));
      input.focus();
    });
  });

  root.querySelector(`#${prefix}TestRun`).addEventListener('click', async () => {
    if (!(await check())) {
      testResult.className = 'hint error';
      testResult.textContent = stateEl.textContent;
      return;
    }
    const expression = input.value.trim();
    if (!expression) {
      testResult.className = 'hint';
      testResult.textContent = t('validation.form.exprEmpty');
      return;
    }
    try {
      const result = await api.post('/api/meta/rules/test', { expression, text: testText.value });
      if (!result.valid) {
        testResult.className = 'hint error';
        testResult.textContent = result.message;
        return;
      }
      testResult.className = result.matched ? 'hint ok' : 'hint error';
      testResult.textContent = result.matched
        ? t('validation.form.testMatched')
        : t('validation.form.testFailed', (result.problems || []).join(', ') || '—');
    } catch (error) {
      testResult.className = 'hint error';
      testResult.textContent = error.message || String(error);
    }
  });

  stateEl.className = 'hint';
  stateEl.textContent = t('validation.form.exprEmpty');
  if (input.value.trim()) {
    check();
  }

  return {
    expression: () => input.value.trim() || null,
    /** Resolves the current expression syntax, checking again when it changed. */
    validate: async () => {
      const expression = input.value.trim();
      if (!expression) {
        return true;
      }
      return expression === checked ? valid : check();
    },
  };
}
