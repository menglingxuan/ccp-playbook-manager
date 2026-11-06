import * as ui from '../ui.js';

let ctx = null;

function renderProduct(product) {
  ui.byId('schemaHash').textContent = `schema hash ${product.schemaHash}`;
  const rows = product.fields.map((field) => `
    <tr>
      <td class="num">${field.order}</td>
      <td>${ui.escapeHtml(field.header)}${field.duplicate ? ' <span class="badge warn">重名</span>' : ''}</td>
      <td>${ui.escapeHtml(field.displayHeader)}</td>
      <td class="mono">${ui.escapeHtml(field.key)}</td>
      <td><span class="chip muted">${ui.escapeHtml(field.type)}</span></td>
      <td>${field.role === 'KEY' ? '<span class="badge info">KEY</span>' : 'TRADE'}</td>
      <td>${field.required ? '✔' : ''}</td>
      <td>${field.businessKey ? '✔' : ''}</td>
      <td class="muted" style="font-size:12px">${ui.escapeHtml(field.aliases || '')}</td>
      <td class="mono">${ui.escapeHtml(field.clonePattern || '')}</td>
    </tr>`);
  ui.setHtml('fieldRows', rows.join(''));
}

async function init() {
  ctx = await ui.initShell();
  if (!ctx) {
    return;
  }
  const select = ui.byId('productFilter');
  select.innerHTML = ctx.products
    .map((product) => `<option value="${product.code}">${product.code} · ${product.name}</option>`).join('');
  select.addEventListener('change', () => {
    const product = ctx.products.find((item) => item.code === select.value);
    if (product) {
      renderProduct(product);
    }
  });
  renderProduct(ctx.products[0]);
}

init();
