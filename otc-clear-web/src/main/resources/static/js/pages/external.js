import { get, post, query } from '../api.js';
import * as ui from '../ui.js';

const state = { page: 1, size: 25, status: '', q: '' };

function debounce(fn, delay = 350) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

async function load() {
  try {
    const result = await get('/api/external-jobs' + query({
      status: state.status, q: state.q, page: state.page, size: state.size,
    }));
    const rows = result.items.map((job) => `
      <tr>
        <td class="mono"><a href="/external-jobs/${encodeURIComponent(job.id)}">${ui.shortId(job.id, 8)}</a></td>
        <td>${ui.escapeHtml(job.productCode)}</td>
        <td>${ui.statusBadge(job.status)}</td>
        <td class="num">${job.caseCount}</td>
        <td class="mono">${ui.escapeHtml(job.externalJobId || '—')}</td>
        <td class="mono">${ui.escapeHtml(job.createdAt)}</td>
        <td class="mono">${ui.escapeHtml(job.finishedAt || '—')}</td>
        <td class="muted" style="font-size:12px">${ui.escapeHtml(job.message || '')}</td>
        <td><div class="row-actions">
          <a class="tool-btn small" href="/external-jobs/${encodeURIComponent(job.id)}">详情</a>
          <button class="tool-btn small" data-refresh="${ui.escapeHtml(job.id)}">刷新</button>
          <button class="tool-btn small" data-stop="${ui.escapeHtml(job.id)}">停止</button>
          <button class="tool-btn small" data-scan="${ui.escapeHtml(job.id)}">扫描产出</button>
        </div></td>
      </tr>`);
    ui.setHtml('jobRows', rows.length ? rows.join('')
      : '<tr><td colspan="9" class="empty">还没有外部任务，在批次页勾选用例后点击「用批量导入工具导入」</td></tr>');
    ui.byId('jobRows').querySelectorAll('button[data-refresh]').forEach((button) => {
      button.addEventListener('click', () => action(button.dataset.refresh, 'refresh'));
    });
    ui.byId('jobRows').querySelectorAll('button[data-stop]').forEach((button) => {
      button.addEventListener('click', () => action(button.dataset.stop, 'stop'));
    });
    ui.byId('jobRows').querySelectorAll('button[data-scan]').forEach((button) => {
      button.addEventListener('click', () => scan(button.dataset.scan));
    });
    ui.renderPager('jobPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      load();
    });
  } catch (error) {
    ui.showError(error);
  }
}

async function action(id, kind) {
  try {
    if (kind === 'stop' && !window.confirm('停止该外部任务？')) {
      return;
    }
    const job = await post(`/api/external-jobs/${encodeURIComponent(id)}/${kind}`, {});
    ui.toast(`状态：${ui.statusLabel(job.status)}`, 'ok');
    load();
  } catch (error) {
    ui.showError(error);
  }
}

async function scan(id) {
  try {
    const result = await post(`/api/external-jobs/${encodeURIComponent(id)}/scan`, {});
    ui.toast(`扫描完成：发现 ${result.discovered}，导入 ${result.imported}，跳过 ${result.skipped}，失败 ${result.failed}`, 'ok');
    load();
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await load();
  ui.on('jobSearch', 'input', debounce((event) => {
    state.q = event.target.value;
    state.page = 1;
    load();
  }));
  ui.on('jobStatus', 'change', (event) => {
    state.status = event.target.value;
    state.page = 1;
    load();
  });
  ui.on('refreshJobsBtn', 'click', load);
}

init();
