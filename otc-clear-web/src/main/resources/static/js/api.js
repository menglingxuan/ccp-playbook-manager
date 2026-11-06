/** Thin fetch wrapper that unwraps problem details into readable errors. */
const BASE = '';

async function request(path, options = {}) {
  const response = await fetch(BASE + path, {
    credentials: 'same-origin',
    ...options,
    headers: {
      ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
      ...(options.headers || {}),
    },
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    let code = '';
    try {
      const problem = await response.json();
      message = problem.detail || problem.title || message;
      code = problem.code || '';
      if (problem.details && problem.details.issues) {
        const issues = problem.details.issues;
        if (Array.isArray(issues) && issues.length) {
          message += ' · ' + issues.map((issue) => issue.message || issue).join('; ');
        }
      }
    } catch (ignored) {
      // keep the status text
    }
    const error = new Error(message);
    error.code = code;
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

export const get = (path) => request(path);
export const post = (path, body) => request(path, { method: 'POST', body: JSON.stringify(body ?? {}) });
export const patch = (path, body) => request(path, { method: 'PATCH', body: JSON.stringify(body ?? {}) });
export const del = (path) => request(path, { method: 'DELETE' });
export const put = (path, body) => request(path, { method: 'PUT', body: JSON.stringify(body ?? {}) });
export const upload = (path, file) => {
  const form = new FormData();
  form.append('file', file);
  return request(path, { method: 'POST', body: form });
};

export const query = (params) => {
  const search = new URLSearchParams();
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value === null || value === undefined || value === '') {
      return;
    }
    search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : '';
};

let cachedContext = null;
export async function context(force = false) {
  if (!cachedContext || force) {
    cachedContext = await get('/api/context');
  }
  return cachedContext;
}
