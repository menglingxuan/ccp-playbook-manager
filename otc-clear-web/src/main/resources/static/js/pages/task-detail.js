import { del, get, patch, post } from '../api.js';
import * as issueList from '../issue-list.js';
import * as ui from '../ui.js';
import { createBatchPicker } from '../batch-picker.js';
import { bindRemarks, remarkHtml } from '../remark.js';

const taskId = document.body.dataset.taskId;
const NAME_MAX_LENGTH = 256;

const ALIGN_LABEL = { KEY: '按唯一键', ROW: '按行号', TESTCASE: '按用例 ID' };
const STATUS_LABEL = {
  SAME: '一致',
  DIFFERENT: '不同',
  MOVED: '顺序不同',
  LEFT_ONLY: '仅左侧',
  RIGHT_ONLY: '仅右侧',
};

/** Name cell of one batch; clicking it swaps into an inline input. */
function nameCell(batch) {
  const name = batch.name || 'Untitled';
  return `<button type="button" class="name-edit" data-rename="${ui.escapeHtml(batch.id)}"
      data-name="${ui.escapeHtml(name)}"
      title="${ui.escapeHtml(name)}（点击重命名）">${ui.escapeHtml(name)}</button>`;
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
      ui.toast('批次名称不能为空', 'bad');
      input.replaceWith(button);
      button.dataset.editing = 'false';
      return;
    }
    try {
      await patch(`/api/batches/${encodeURIComponent(button.dataset.rename)}/name`, { name: next });
      button.dataset.name = next;
      button.textContent = next;
      button.title = `${next}（点击重命名）`;
      ui.toast('批次名称已更新', 'ok');
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

async function load() {
  try {
    const detail = await get(`/api/tasks/${encodeURIComponent(taskId)}`);
    const task = detail.task;
    ui.byId('taskTitle').textContent = `任务 ${task.id.slice(0, 8)}`;
    ui.byId('taskSubtitle').textContent = `${task.sourceName} · ${task.kind === 'FILE_UPLOAD' ? '文件导入' : '外部产出'}`;

    ui.setHtml('taskInfo', `
      <div class="k">任务 ID</div><div class="v">${ui.escapeHtml(task.id)}</div>
      <div class="k">状态</div><div class="v">${ui.statusBadge(task.status)}</div>
      <div class="k">源文件</div><div class="v">${ui.escapeHtml(task.sourceName)}</div>
      <div class="k">MD5</div><div class="v mono" title="文件内容 MD5">${ui.escapeHtml(task.sourceMd5 || '—')}</div>
      <div class="k">SHA-256</div><div class="v">${ui.escapeHtml(task.sourceSha256 || '—')}</div>
      <div class="k">创建时间</div><div class="v">${ui.escapeHtml(task.createdAt)}</div>
      <div class="k">完成时间</div><div class="v">${ui.escapeHtml(task.finishedAt || '—')}</div>
      <div class="k">说明</div><div class="v">${ui.escapeHtml(task.message || '—')}</div>`);

    const rows = detail.batches.map((batch) => `
      <tr>
        <td class="mono"><a href="/batches/${encodeURIComponent(batch.id)}">${batch.id.slice(0, 8)}</a></td>
        <td>${nameCell(batch)}</td>
        <td>${ui.escapeHtml(batch.productCode)}</td>
        <td>${ui.escapeHtml(batch.sheetName || '—')}</td>
        <td class="num">${batch.headerRow}</td>
        <td class="mono">${batch.tradeStartColumn} – ${batch.tradeEndColumn}</td>
        <td class="num">${batch.totalRows}</td>
        <td class="num">${batch.invalidRows}</td>
        <td class="mono">${ui.escapeHtml(batch.createdAt)}</td>
        <td class="remark-col">${remarkHtml('batch', batch.id, batch.note)}</td>
        <td><div class="row-actions">
          ${ui.iconLink('layers', `/batches/${encodeURIComponent(batch.id)}`, '打开批次工作台', 'sm')}
          ${ui.iconButton('diff', '与其它批次对比', 'sm', `data-compare="${ui.escapeHtml(batch.id)}"`)}
        </div></td>
      </tr>`);
    ui.setHtml('batchRows', rows.length ? rows.join('')
      : '<tr><td colspan="11" class="empty">没有成功导入的批次</td></tr>');
    bindRemarks(ui.byId('batchRows'));
    ui.byId('batchRows').querySelectorAll('button[data-rename]').forEach((button) => {
      button.addEventListener('click', () => startRename(button));
    });
    ui.byId('batchRows').querySelectorAll('button[data-compare]').forEach((button) => {
      button.addEventListener('click', () => openCompare(button.dataset.compare));
    });

    const issues = detail.issues || [];
    const errors = issues.filter((issue) => issue.level === 'ERROR').length;
    const warns = issues.filter((issue) => issue.level === 'WARN').length;
    ui.byId('issueCount').textContent = issues.length
      ? `${issues.length} 条（错误 ${errors} · 警告 ${warns}）` : '';
    issueList.mount(ui.byId('taskIssues'), issues, {
      emptyHtml: '<div class="muted">没有记录到问题。</div>',
    });
  } catch (error) {
    ui.showError(error);
  }
}

/** Comparison dialog: pick the other batch, the mode, the alignment and whether order matters. */
function openCompare(leftBatchId) {
  const modal = ui.openModal('批次对比', `
    <div class="be-field">
      <label class="be-label">对比批次</label>
      <div id="cmpBatchHost"></div>
    </div>
    <div class="be-field">
      <label class="be-label">对比方式</label>
      <div class="setting-list">
        <label class="setting"><input type="radio" name="cmpMode" value="VALUES" checked>字段名 + 值（含公式）</label>
        <label class="setting"><input type="radio" name="cmpMode" value="FIELDS">仅字段名</label>
      </div>
    </div>
    <div class="be-field">
      <label class="be-label">行对齐方式</label>
      <div class="setting-list">
        <label class="setting"><input type="radio" name="cmpAlign" value="KEY" checked>按唯一键</label>
        <label class="setting"><input type="radio" name="cmpAlign" value="ROW">按行号</label>
        <label class="setting"><input type="radio" name="cmpAlign" value="TESTCASE">按用例 ID</label>
      </div>
    </div>
    <div class="be-field">
      <label class="be-label">对比选项</label>
      <div class="setting-list">
        <label class="setting"><input type="checkbox" id="cmpOrder">比对顺序（字段顺序与用例行顺序）</label>
      </div>
    </div>`, `
    <button class="tool-btn" data-act="cancel">取消</button>
    <button class="tool-btn primary" data-act="start">开始对比</button>`);
  const picker = createBatchPicker(modal.root.querySelector('#cmpBatchHost'), {
    currentTaskId: taskId,
    excludeId: leftBatchId,
  });
  modal.root.querySelector('[data-act="cancel"]').addEventListener('click', () => modal.close());
  modal.root.querySelector('[data-act="start"]').addEventListener('click', async () => {
    const rightBatchId = picker.value() ? picker.value().id : '';
    if (!rightBatchId) {
      ui.toast('请先选择要对比的批次', 'bad');
      picker.focus();
      return;
    }
    const mode = modal.root.querySelector('input[name="cmpMode"]:checked').value;
    const align = modal.root.querySelector('input[name="cmpAlign"]:checked').value;
    const compareOrder = modal.root.querySelector('#cmpOrder').checked;
    try {
      const result = await post(`/api/batches/${encodeURIComponent(leftBatchId)}/compare`,
        { rightBatchId, mode, align, compareOrder });
      modal.close();
      renderCompare(result);
    } catch (error) {
      ui.showError(error);
    }
  });
}

/** Standalone panel with the side by side difference of the two batches. */
function renderCompare(result) {
  const host = ui.byId('drawerHost') || document.body;
  const backdrop = document.createElement('div');
  backdrop.className = 'drawer-backdrop';
  const fieldRows = result.fields.map((field) => `
    <tr class="cmp-${field.status.toLowerCase()}">
      <td class="mono">${ui.escapeHtml(field.fieldKey)}</td>
      <td>${field.leftHeader ? ui.escapeHtml(field.leftHeader) : '<span class="muted">—</span>'}</td>
      <td>${field.rightHeader ? ui.escapeHtml(field.rightHeader) : '<span class="muted">—</span>'}</td>
      <td class="num">${field.leftIndex || '—'}</td>
      <td class="num">${field.rightIndex || '—'}</td>
      <td>${ui.escapeHtml(STATUS_LABEL[field.status] || field.status)}</td>
    </tr>`).join('');
  const caseRows = result.cases.map((row, index) => {
    const values = row.values.map((value) => `
      <tr class="cmp-value">
        <td class="mono">${ui.escapeHtml(value.fieldKey)}</td>
        <td>${ui.escapeHtml(value.header)}</td>
        <td class="mono">${value.leftValue === null ? '<span class="muted">（空）</span>' : ui.escapeHtml(value.leftValue)}</td>
        <td class="mono">${value.rightValue === null ? '<span class="muted">（空）</span>' : ui.escapeHtml(value.rightValue)}</td>
        <td class="mono">${value.leftFormula ? ui.escapeHtml(value.leftFormula) : '—'}</td>
        <td class="mono">${value.rightFormula ? ui.escapeHtml(value.rightFormula) : '—'}</td>
      </tr>`).join('');
    return `
      <tr class="cmp-case cmp-${row.status.toLowerCase()}" data-cmp-row="${index}">
        <td class="mono">${ui.escapeHtml(row.key)}</td>
        <td class="num">${row.leftRow || '—'}</td>
        <td class="num">${row.rightRow || '—'}</td>
        <td>${ui.escapeHtml(STATUS_LABEL[row.status] || row.status)}</td>
        <td class="num">${row.values.length || '—'}</td>
      </tr>
      ${values ? `<tr class="cmp-detail" data-cmp-detail="${index}" hidden><td colspan="5">
        <table class="cmp-table"><thead><tr>
          <th>字段</th><th>名称</th><th>${ui.escapeHtml(result.left.name || '左侧')}</th>
          <th>${ui.escapeHtml(result.right.name || '右侧')}</th><th>左侧公式</th><th>右侧公式</th>
        </tr></thead><tbody>${values}</tbody></table></td></tr>` : ''}`;
  }).join('');
  const summary = result.summary;
  backdrop.innerHTML = `
    <div class="drawer wide cmp" role="dialog" aria-modal="true">
      <div class="drawer-head">
        <div>
          <h3>批次对比</h3>
          <div class="mono-sm">${ui.escapeHtml(result.left.name || result.left.id)} ↔ ${
            ui.escapeHtml(result.right.name || result.right.id)} · ${
            result.mode === 'FIELDS' ? '仅字段名' : '字段名 + 值（含公式）'} · ${
            ALIGN_LABEL[result.align] || result.align}</div>
        </div>
        <div class="row-actions">
          <button class="modal-close" id="cmpClose" aria-label="关闭">×</button>
        </div>
      </div>
      <div class="drawer-body">
        <div class="cmp-summary">
          <span class="chip ${summary.fieldsDifferent || summary.fieldsLeftOnly || summary.fieldsRightOnly ? 'fail' : 'muted'}">字段差异 ${summary.fieldsDifferent}</span>
          <span class="chip muted">共同字段 ${summary.fieldsCompared}</span>
          <span class="chip muted">仅左 ${summary.fieldsLeftOnly}</span>
          <span class="chip muted">仅右 ${summary.fieldsRightOnly}</span>
          ${result.cases.length ? `
            <span class="chip ${summary.casesDifferent ? 'fail' : 'muted'}">用例差异 ${summary.casesDifferent}</span>
            <span class="chip muted">一致 ${summary.casesSame}</span>
            <span class="chip muted">仅左 ${summary.casesLeftOnly}</span>
            <span class="chip muted">仅右 ${summary.casesRightOnly}</span>` : ''}
          ${result.compareOrder ? `<span class="chip muted">比对顺序${summary.orderDifferent ? '：不一致' : '：一致'}</span>` : ''}
          ${result.truncated ? '<span class="chip warn">仅对比前 1000 行</span>' : ''}
          <span class="chip muted">${ui.escapeHtml(result.left.productName || result.left.productCode)}
            ${result.left.caseCount} 行 ↔ ${ui.escapeHtml(result.right.productName || result.right.productCode)}
            ${result.right.caseCount} 行</span>
        </div>
        <h4 class="cmp-section">字段对比
          <label class="setting"><input type="checkbox" id="cmpOnlyFieldDiff">仅差异</label></h4>
        <div class="table-wrap"><table class="cmp-table" id="cmpFields"><thead><tr>
          <th>字段 Key</th><th>左侧名称</th><th>右侧名称</th><th class="num">左序</th>
          <th class="num">右序</th><th>状态</th>
        </tr></thead><tbody>${fieldRows || '<tr><td colspan="6" class="empty">没有字段</td></tr>'}</tbody></table></div>
        ${result.cases.length ? `
          <h4 class="cmp-section">用例对比（点击行展开差异字段）
            <label class="setting"><input type="checkbox" id="cmpOnlyCaseDiff">仅差异</label></h4>
          <div class="table-wrap"><table class="cmp-table" id="cmpCases"><thead><tr>
            <th>对齐键</th><th class="num">左行</th><th class="num">右行</th><th>状态</th><th class="num">差异字段</th>
          </tr></thead><tbody>${caseRows}</tbody></table></div>` : ''}
      </div>
    </div>`;
  host.appendChild(backdrop);
  const close = () => backdrop.remove();
  const applyFilter = () => {
    const onlyFieldDiff = backdrop.querySelector('#cmpOnlyFieldDiff').checked;
    backdrop.querySelectorAll('#cmpFields tbody tr').forEach((row) => {
      row.hidden = onlyFieldDiff && row.classList.contains('cmp-same');
    });
    const onlyCaseDiff = backdrop.querySelector('#cmpOnlyCaseDiff').checked;
    backdrop.querySelectorAll('#cmpCases tbody tr.cmp-case').forEach((row) => {
      row.hidden = onlyCaseDiff && row.classList.contains('cmp-same');
      const detail = row.nextElementSibling;
      if (detail && detail.classList.contains('cmp-detail') && row.hidden) {
        detail.hidden = true;
      }
    });
  };
  backdrop.querySelectorAll('#cmpOnlyFieldDiff, #cmpOnlyCaseDiff').forEach((box) => {
    box.addEventListener('change', applyFilter);
  });
  backdrop.querySelector('#cmpClose').addEventListener('click', close);
  backdrop.addEventListener('click', (event) => {
    if (event.target === backdrop) {
      close();
    }
    const row = event.target.closest('[data-cmp-row]');
    if (row) {
      const detail = backdrop.querySelector(`[data-cmp-detail="${row.dataset.cmpRow}"]`);
      if (detail) {
        detail.hidden = !detail.hidden;
      }
    }
  });
  const onKey = (event) => {
    if (event.key === 'Escape') {
      close();
      document.removeEventListener('keydown', onKey);
    }
  };
  document.addEventListener('keydown', onKey);
}

async function removeTask() {
  if (!window.confirm('删除任务会彻底删除其批次、用例与导出记录（不可恢复），确定继续？')) {
    return;
  }
  try {
    await del(`/api/tasks/${encodeURIComponent(taskId)}`);
    window.location.href = '/';
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  const ctx = await ui.initShell();
  // the same display limits as the import wizard: five rows first, a drawer beyond the limit
  issueList.configure(ctx?.ui);
  await load();
  ui.enableColumnResize(document.querySelector('#batchRows').closest('table'), 'task-batches');
  ui.on('backBtn', 'click', () => { window.location.href = '/'; });
  ui.on('deleteTaskBtn', 'click', removeTask);
}

init();
