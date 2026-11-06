import { context, del, get, query } from '../api.js';
import * as ui from '../ui.js';

const state = { page: 1, size: 25, status: '', kind: '', product: '', q: '', sort: 'created' };

function debounce(fn, delay = 300) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

function renderSummary(summary) {
  const cards = [
    { key: '', label: '全部任务', value: Object.values(summary).reduce((sum, value) => sum + value, 0) },
    { key: 'SUCCEEDED', label: '成功', value: summary.SUCCEEDED || 0, tone: 'ok' },
    { key: 'PARTIAL', label: '部分成功', value: summary.PARTIAL || 0, tone: 'warn' },
    { key: 'FAILED', label: '失败', value: summary.FAILED || 0, tone: 'bad' },
    { key: 'RUNNING', label: '进行中', value: summary.RUNNING || 0 },
  ];
  ui.setHtml('summaryCards', cards.map((card) => `
    <div class="card clickable ${state.status === card.key && card.key !== '' ? 'active-filter' : ''}" data-status="${card.key}">
      <div class="card-label">${card.label}</div>
      <div class="card-value ${card.tone || ''}">${card.value}</div>
    </div>`).join(''));
  document.querySelectorAll('#summaryCards .card').forEach((card) => {
    card.addEventListener('click', () => {
      state.status = card.dataset.status;
      state.page = 1;
      ui.byId('taskStatus').value = state.status;
      load();
    });
  });
}

async function loadSummary() {
  try {
    renderSummary(await get('/api/tasks/summary'));
  } catch (error) {
    ui.showError(error);
  }
}

async function load() {
  try {
    const result = await get('/api/tasks' + query({
      status: state.status, kind: state.kind, product: state.product,
      q: state.q, sort: state.sort, page: state.page, size: state.size,
    }));
    const rows = result.items.map((task) => `
      <tr>
        <td class="mono"><a href="/tasks/${encodeURIComponent(task.id)}">${ui.shortId(task.id, 8)}</a></td>
        <td>${task.kind === 'EXTERNAL_OUTPUT' ? '<span class="chip muted">外部产出</span>' : '<span class="chip info">文件导入</span>'}</td>
        <td>${ui.escapeHtml(task.sourceName)}</td>
        <td>${ui.statusBadge(task.status)}</td>
        <td class="num">${task.sheetCount}</td>
        <td class="num">${task.batchCount}</td>
        <td class="num">${task.totalRows}</td>
        <td class="num ${task.invalidRows > 0 ? 'num-fail' : ''}">${task.invalidRows}</td>
        <td class="num">${task.durationSeconds || 0}</td>
        <td class="mono">${ui.escapeHtml(task.createdAt)}</td>
        <td>
          <div class="row-actions">
            <a class="tool-btn small" href="/tasks/${encodeURIComponent(task.id)}">查看</a>
            <button class="tool-btn small danger" data-delete="${ui.escapeHtml(task.id)}">删除</button>
          </div>
        </td>
      </tr>`);
    ui.setHtml('taskRows', rows.length ? rows.join('')
      : '<tr><td colspan="11" class="empty">还没有导入任务，点击「新建导入」上传用例文件</td></tr>');
    ui.byId('taskRows').querySelectorAll('button[data-delete]').forEach((button) => {
      button.addEventListener('click', () => removeTask(button.dataset.delete));
    });
    ui.renderPager('taskPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      load();
    });
  } catch (error) {
    ui.showError(error);
  }
}

async function removeTask(id) {
  if (!window.confirm('删除任务会级联软删其批次与用例，确定继续？')) {
    return;
  }
  try {
    await del(`/api/tasks/${encodeURIComponent(id)}`);
    ui.toast('任务已删除', 'ok');
    load();
    loadSummary();
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  const ctx = await ui.initShell();
  if (ctx) {
    const select = ui.byId('taskProduct');
    select.innerHTML = '<option value="">全部产品</option>' + ctx.products
      .map((product) => `<option value="${product.code}">${product.code}</option>`).join('');
  }
  renderSummary({});
  await loadSummary();
  await load();

  ui.on('taskSearch', 'input', debounce((event) => {
    state.q = event.target.value;
    state.page = 1;
    load();
  }, 350));
  ui.on('taskStatus', 'change', (event) => {
    state.status = event.target.value;
    state.page = 1;
    loadSummary();
    load();
  });
  ui.on('taskKind', 'change', (event) => {
    state.kind = event.target.value;
    state.page = 1;
    load();
  });
  ui.on('taskProduct', 'change', (event) => {
    state.product = event.target.value;
    state.page = 1;
    load();
  });
  ui.on('taskSort', 'change', (event) => {
    state.sort = event.target.value;
    load();
  });
  ui.on('refreshTasksBtn', 'click', () => {
    loadSummary();
    load();
  });
  ui.on('newImportBtn', 'click', () => {
    window.location.href = '/import';
  });
}

init();
