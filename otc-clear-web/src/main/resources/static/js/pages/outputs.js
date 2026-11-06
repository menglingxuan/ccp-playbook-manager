import { del, get, query } from '../api.js';
import * as ui from '../ui.js';

const state = { page: 1, size: 25, jobId: '' };

function debounce(fn, delay = 350) {
  let timer = null;
  return (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delay);
  };
}

async function load() {
  try {
    const result = await get('/api/outputs' + query({
      jobId: state.jobId, page: state.page, size: state.size,
    }));
    const rows = result.items.map((output) => `
      <tr>
        <td class="mono">${ui.escapeHtml(output.fileName)}<div class="mono-sm">${ui.escapeHtml(output.relativePath)}</div></td>
        <td class="mono"><a href="/external-jobs/${encodeURIComponent(output.jobId)}">${ui.shortId(output.jobId, 8)}</a></td>
        <td>${ui.statusBadge(output.status)}</td>
        <td class="num">${ui.bytes(output.sizeBytes)}</td>
        <td class="num">${output.caseCount}</td>
        <td class="mono">${ui.escapeHtml(output.discoveredAt)}</td>
        <td class="muted" style="font-size:12px">${ui.escapeHtml(output.message || '')}</td>
        <td><div class="row-actions">
          <a class="tool-btn small" href="/outputs/${encodeURIComponent(output.id)}">查看</a>
          <button class="tool-btn small danger" data-delete="${ui.escapeHtml(output.id)}">删除</button>
        </div></td>
      </tr>`);
    ui.setHtml('outputRows', rows.length ? rows.join('')
      : '<tr><td colspan="8" class="empty">还没有产出文件，先在外部任务详情页执行「扫描产出」</td></tr>');
    ui.byId('outputRows').querySelectorAll('button[data-delete]').forEach((button) => {
      button.addEventListener('click', () => remove(button.dataset.delete));
    });
    ui.renderPager('outputPager', result.page, result.totalPages, result.total, (page) => {
      state.page = page;
      load();
    });
  } catch (error) {
    ui.showError(error);
  }
}

async function remove(id) {
  if (!window.confirm('删除产出会同时删除其导入的批次与用例，确定继续？')) {
    return;
  }
  try {
    await del(`/api/outputs/${encodeURIComponent(id)}`);
    ui.toast('已删除', 'ok');
    load();
  } catch (error) {
    ui.showError(error);
  }
}

async function init() {
  await ui.initShell();
  await load();
  ui.on('outputJobFilter', 'input', debounce((event) => {
    state.jobId = event.target.value.trim();
    state.page = 1;
    load();
  }));
  ui.on('refreshOutputsBtn', 'click', load);
}

init();
