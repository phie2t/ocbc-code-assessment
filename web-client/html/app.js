'use strict';

(() => {
  const API = '/api/v1/transfers';
  const MOCK_ADMIN = '/mock/__admin';
  const RESPONSE_TIMEOUT_MS = 10000;
  const MAX_FEED_ITEMS = 100;
  const MAX_CARDS = 50;

  const $ = (id) => document.getElementById(id);

  /** Last status the service reported for each transfer, from responses and the live feed. */
  const serviceStatus = new Map();

  // ---------------------------------------------------------------- helpers

  function h(tag, props, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'className') node.className = value;
      else if (key === 'text') node.textContent = value;
      else node.setAttribute(key, value === true ? '' : String(value));
    }
    for (const child of children.flat()) {
      if (child === undefined || child === null || child === false) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function randomKey() {
    const bytes = new Uint8Array(12);
    crypto.getRandomValues(bytes);
    return 'web-' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  }

  function parseJson(text) {
    try {
      return JSON.parse(text);
    } catch {
      return undefined;
    }
  }

  function money(value) {
    return value && value.amount !== undefined ? `${value.amount} ${value.currency ?? ''}`.trim() : '–';
  }

  function time() {
    return new Date().toLocaleTimeString();
  }

  /** Fetches and reads the whole body; gives up after the timeout. */
  async function fetchText(url, options = {}, timeoutMs = RESPONSE_TIMEOUT_MS) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(url, { ...options, signal: controller.signal });
      const text = await response.text();
      return { response, text };
    } finally {
      clearTimeout(timer);
    }
  }

  /** Classifies a response the way operations sees it. */
  function classify(response, text) {
    const type = (response.headers.get('Content-Type') || '').toLowerCase();
    const base = { httpStatus: response.status, headerCorrelationId: response.headers.get('X-Correlation-Id') };
    if (type.includes('application/problem+json')) {
      const problem = parseJson(text);
      if (problem && typeof problem === 'object' && !Array.isArray(problem)) {
        return { ...base, kind: 'problem', problem };
      }
    } else if (type.includes('json')) {
      const transfer = parseJson(text);
      if (transfer && typeof transfer.transferId === 'string' && typeof transfer.status === 'string') {
        return { ...base, kind: 'transfer', transfer };
      }
    }
    return { ...base, kind: 'unrecognised', snippet: text.slice(0, 300) };
  }

  function remember(transferId, status) {
    if (transferId && status) serviceStatus.set(transferId, status);
  }

  function details(rows) {
    const dl = document.createElement('dl');

    for (const [term, value] of rows) {
      const dt = document.createElement('dt');
      dt.textContent = term;

      const dd = document.createElement('dd');
      dd.textContent = value ?? '–';

      dl.appendChild(dt);
      dl.appendChild(dd);
    }

    return dl;
  }

  function card(label, result) {
    if (result.kind === 'no-response') {
      return h('div', { className: 'card error' },
        h('div', { className: 'card-head' }, h('strong', { text: label }), h('span', { text: time(), className: 'muted' })),
        h('p', { text: 'No response from the service' }));
    }
    if (result.kind === 'transfer') {
      const t = result.transfer;
      remember(t.transferId, t.status);
      return h('div', { className: 'card' },
        h('div', { className: 'card-head' },
          h('strong', { text: label }),
          h('span', { className: `badge status-${t.status}`, text: t.status }),
          h('span', { className: 'muted', text: `HTTP ${result.httpStatus} · ${time()}` })),
        details([
          ['reasonCode', t.reasonCode ?? '–'],
          ['transferId', t.transferId],
          ['correlation ID', result.headerCorrelationId ?? '–'],
          ['debit', money(t.debit)],
          ['credit', money(t.credit)],
          ['fxRate', t.fxRate ?? '–'],
        ]));
    }
    if (result.kind === 'problem') {
      const p = result.problem;
      return h('div', { className: 'card problem' },
        h('div', { className: 'card-head' },
          h('strong', { text: label }),
          h('span', { className: 'badge neutral', text: 'problem' }),
          h('span', { className: 'muted', text: `HTTP ${result.httpStatus} · ${time()}` })),
        details([
          ['title', p.title],
          ['code', p.code],
          ['detail', p.detail],
          ['correlationId', p.correlationId],
        ]));
    }
    return h('div', { className: 'card error' },
      h('div', { className: 'card-head' },
        h('strong', { text: `${label}: Unrecognised error response` }),
        h('span', { className: 'muted', text: `HTTP ${result.httpStatus} · ${time()}` })),
      h('pre', { text: result.snippet || '(empty body)' }));
  }

  function showCard(node) {
    const list = $('responses');
    list.prepend(node);
    while (list.children.length > MAX_CARDS) list.lastElementChild.remove();
  }

  // ---------------------------------------------------------------- health

  async function checkHealth() {
    const badge = $('health');
    try {
      const { response, text } = await fetchText('/actuator/health', {}, 5000);
      const body = parseJson(text);
      if (response.ok && body && body.status === 'UP') {
        badge.className = 'badge ok';
        badge.textContent = 'Service: UP';
      } else {
        badge.className = 'badge bad';
        badge.textContent = `Service: ${body && body.status ? body.status : 'DOWN'} (HTTP ${response.status})`;
      }
    } catch {
      badge.className = 'badge bad';
      badge.textContent = 'Service: no response';
    }
  }

  // ---------------------------------------------------------------- transfers

  let lastRequest = null;

  function formPayload() {
    const form = $('transfer-form');
    const payload = {
      sourceAccount: form.sourceAccount.value.trim(),
      destinationAccount: form.destinationAccount.value.trim(),
      amount: form.amount.value.trim(),
      currency: form.currency.value,
    };
    const description = form.description.value;
    if (description) payload.description = description;
    return payload;
  }

  async function sendTransfer(payload, key) {
    try {
      const { response, text } = await fetchText(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'Idempotency-Key': key },
        body: JSON.stringify(payload),
      });
      return classify(response, text);
    } catch {
      return { kind: 'no-response' };
    }
  }

  async function send(copies) {
    const keyInput = $('idempotency-key');
    const key = keyInput.value.trim();
    const payload = formPayload();
    lastRequest = { payload, key };
    $('send-again').disabled = false;
    keyInput.value = randomKey();
    await dispatch(payload, key, copies);
  }

  async function dispatch(payload, key, copies) {
    if (copies === 1) {
      showCard(card(`Key ${key}`, await sendTransfer(payload, key)));
      return;
    }
    const group = h('div', { className: 'group' }, h('p', { className: 'muted', text: `${copies} requests at once with key ${key}` }));
    showCard(group);
    const results = await Promise.all(Array.from({ length: copies }, () => sendTransfer(payload, key)));
    results.forEach((result, i) => group.append(card(`#${i + 1}`, result)));
  }

  async function lookup() {
    const id = $('lookup-id').value.trim();
    if (!id) return;
    try {
      const { response, text } = await fetchText(`${API}/${encodeURIComponent(id)}`, { headers: { Accept: 'application/json' } });
      showCard(card(`Look up ${id}`, classify(response, text)));
    } catch {
      showCard(card(`Look up ${id}`, { kind: 'no-response' }));
    }
  }

  // ---------------------------------------------------------------- live feed

  let feedController = null;
  let feedRetry = null;

  function setFeedState(kind, text) {
    const badge = $('feed-state');
    badge.className = `badge ${kind}`;
    badge.textContent = text;
  }

  function addFeedItem(event) {
    const data = parseJson(event.data);
    if (!data) return;
    remember(data.transferId, data.status);
    const feed = $('feed');
    feed.prepend(h('li', {},
      h('span', { className: 'muted', text: time() }), ' ',
      h('span', { className: `badge status-${data.status}`, text: data.status }), ' ',
      data.reasonCode ? h('code', { text: data.reasonCode }) : null, ' ',
      h('span', { text: data.transferId }), ' ',
      h('span', { className: 'muted', text: event.id ? `#${event.id.split(':').pop()}` : '' })));
    while (feed.children.length > MAX_FEED_ITEMS) feed.lastElementChild.remove();
  }

  function scheduleReconnect() {
    clearTimeout(feedRetry);
    feedRetry = setTimeout(connectFeed, 5000);
  }

  async function connectFeed() {
    clearTimeout(feedRetry);
    if (feedController) feedController.abort();
    const controller = new AbortController();
    feedController = controller;
    const account = $('feed-filter').value.trim();
    const url = `${API}/events${account ? `?sourceAccount=${encodeURIComponent(account)}` : ''}`;
    setFeedState('neutral', 'Connecting…');
    let response;
    try {
      response = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal: controller.signal });
    } catch {
      if (controller.signal.aborted) return;
      setFeedState('bad', 'Live updates unavailable (no response)');
      scheduleReconnect();
      return;
    }
    const type = (response.headers.get('Content-Type') || '').toLowerCase();
    if (!response.ok || !type.includes('text/event-stream')) {
      setFeedState('bad', `Live updates unavailable (HTTP ${response.status})`);
      response.body?.cancel();
      scheduleReconnect();
      return;
    }
    setFeedState('ok', 'Connected');
    try {
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += value.replace(/\r\n?/g, '\n');
        let end;
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, end);
          buffer = buffer.slice(end + 2);
          handleBlock(block);
        }
      }
    } catch {
      if (controller.signal.aborted) return;
    }
    if (controller.signal.aborted) return;
    setFeedState('bad', 'Disconnected, retrying…');
    scheduleReconnect();
  }

  function handleBlock(block) {
    const event = { event: 'message', id: '', data: '' };
    const data = [];
    let comment = false;
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) {
        comment = true;
        continue;
      }
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (field === 'event') event.event = value;
      else if (field === 'id') event.id = value;
      else if (field === 'data') data.push(value);
    }
    if (data.length === 0) {
      if (comment) $('feed-heartbeat').textContent = `Last heartbeat ${time()}`;
      return;
    }
    event.data = data.join('\n');
    addFeedItem(event);
  }

  // ---------------------------------------------------------------- batch

  let batchController = null;

  function batchRow(result, rawLine, elapsedMs) {
    const lineLabel = result && result.lineId != null ? result.lineId : result && result.lineNumber != null ? `line ${result.lineNumber}` : '?';
    let outcome;
    let transferId = '';
    if (!result || typeof result !== 'object') {
      outcome = h('span', { className: 'bad-text', text: `Unrecognised: ${rawLine.slice(0, 120)}` });
    } else if (result.transfer) {
      remember(result.transfer.transferId, result.transfer.status);
      transferId = result.transfer.transferId;
      outcome = [h('span', { className: `badge status-${result.transfer.status}`, text: result.transfer.status }), ' ',
        result.transfer.reasonCode ? h('code', { text: result.transfer.reasonCode }) : null];
    } else if (result.error) {
      outcome = [h('code', { text: result.error.code ?? '?' }), ' ', h('span', { className: 'muted', text: result.error.message ?? '' })];
    } else {
      outcome = h('span', { className: 'bad-text', text: `Unrecognised: ${rawLine.slice(0, 120)}` });
    }
    return h('tr', {},
      h('td', { text: lineLabel }),
      h('td', { text: result && result.httpStatus != null ? result.httpStatus : '?' }),
      h('td', {}, outcome),
      h('td', { className: 'mono', text: transferId }),
      h('td', { text: Math.round(elapsedMs) }));
  }

  async function uploadBatch() {
    const file = $('batch-file').files[0];
    const summary = $('batch-summary');
    $('batch-error').replaceChildren();
    if (!file) {
      summary.textContent = 'Choose an NDJSON file first.';
      return;
    }
    const tbody = $('batch-results');
    tbody.replaceChildren();
    const controller = new AbortController();
    batchController = controller;
    $('batch-send').disabled = true;
    $('batch-stop').disabled = false;
    const started = performance.now();
    let first = null;
    let count = 0;
    const statuses = {};
    const describe = (finished) => {
      const parts = [`${count} results`];
      if (first !== null) parts.push(`first after ${Math.round(first)} ms`);
      parts.push(`${Math.round(performance.now() - started)} ms ${finished ? 'in total' : 'so far'}`);
      const byStatus = Object.entries(statuses).map(([k, v]) => `${k} ${v}`).join(', ');
      return parts.join(' · ') + (byStatus ? ` · ${byStatus}` : '');
    };
    const slowWarning = setTimeout(() => {
      if (first === null) summary.textContent = 'No result yet after 10 s…';
    }, RESPONSE_TIMEOUT_MS);
    summary.textContent = 'Uploading…';
    try {
      const response = await fetch(`${API}/batch`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-ndjson', Accept: 'application/x-ndjson' },
        body: file,
        signal: controller.signal,
      });
      const type = (response.headers.get('Content-Type') || '').toLowerCase();
      if (!response.ok || !type.includes('ndjson')) {
        const text = await response.text();
        summary.textContent = '';
        $('batch-error').append(card('Batch', classify(response, text)));
        return;
      }
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      let buffer = '';
      const handleLine = (line) => {
        if (!line.trim()) return;
        const elapsed = performance.now() - started;
        if (first === null) first = elapsed;
        count++;
        const result = parseJson(line);
        const key = result && result.transfer ? result.transfer.status : result && result.error ? result.error.code : 'UNRECOGNISED';
        statuses[key] = (statuses[key] || 0) + 1;
        tbody.append(batchRow(result, line, elapsed));
        summary.textContent = describe(false);
      };
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += value;
        let nl;
        while ((nl = buffer.indexOf('\n')) >= 0) {
          handleLine(buffer.slice(0, nl));
          buffer = buffer.slice(nl + 1);
        }
      }
      handleLine(buffer);
      summary.textContent = describe(true);
    } catch {
      summary.textContent = controller.signal.aborted
        ? `Stopped by you. ${describe(true)}`
        : `No response from the service. ${describe(true)}`;
    } finally {
      clearTimeout(slowWarning);
      batchController = null;
      $('batch-send').disabled = false;
      $('batch-stop').disabled = true;
    }
  }

  // ---------------------------------------------------------------- core ledger

  let ledgerTimer = null;

  function describePostings(postings) {
    return postings.map((p) => (p.status === 'POSTED' ? `POSTED ${p.coreTxnId}` : `${p.status} ${p.reasonCode ?? ''}`.trim())).join(', ');
  }

  function disagreement(status, postings) {
    if (!status) return null;
    const posted = postings.some((p) => p.status === 'POSTED');
    if (posted && (status === 'FAILED' || status === 'REJECTED')) return `Service says ${status}, but the core posted it.`;
    if (!posted && status === 'COMPLETED') return 'Service says COMPLETED, but the core has no posting.';
    return null;
  }

  async function refreshLedger() {
    let stats;
    try {
      const { response, text } = await fetchText(`${MOCK_ADMIN}/stats`, {}, 5000);
      stats = response.ok ? parseJson(text) : undefined;
    } catch {
      stats = undefined;
    }
    if (!stats || !stats.core) {
      $('ledger-sessions').textContent = 'Mock bank statistics unavailable.';
      return;
    }
    const core = stats.core;
    const s = core.sessions || {};
    $('ledger-sessions').textContent =
      `Core sessions: ${s.open ?? 0} open, peak ${s.peakOpen ?? 0} of 10, ${s.busyRejections ?? 0} BUSY rejections, ` +
      `${s.expiredWithoutClose ?? 0} expired without being closed.`;

    const duplicates = new Set(core.duplicateReferences || []);
    const hung = new Map((core.hung || []).map((x) => [x.reference, x.applied]));
    const byReference = (core.postings && core.postings.byReference) || {};
    const warnings = [];
    const rows = Object.entries(byReference).map(([reference, postings]) => {
      const status = serviceStatus.get(reference);
      const problem = disagreement(status, postings);
      if (problem) warnings.push(`${reference}: ${problem}`);
      const notes = [];
      if (duplicates.has(reference)) notes.push(`DUPLICATE: ${postings.filter((p) => p.status === 'POSTED').length} postings`);
      if (hung.has(reference)) notes.push(hung.get(reference) ? 'hung, applied' : 'hung, not applied');
      return h('tr', { className: duplicates.has(reference) ? 'duplicate' : problem ? 'mismatch' : '' },
        h('td', { className: 'mono', text: reference }),
        h('td', {}, describePostings(postings), notes.length ? h('div', { className: 'note', text: notes.join(' · ') }) : null),
        h('td', { text: status ?? '–' }));
    });
    for (const [reference, applied] of hung) {
      if (applied === false && !byReference[reference]) {
        const status = serviceStatus.get(reference);
        rows.push(h('tr', { className: status === 'COMPLETED' ? 'mismatch' : '' },
          h('td', { className: 'mono', text: reference }),
          h('td', {}, 'none', h('div', { className: 'note', text: 'hung, not applied' })),
          h('td', { text: status ?? '–' })));
      }
    }
    for (const [transferId, status] of serviceStatus) {
      if (status === 'COMPLETED' && !byReference[transferId]) {
        warnings.push(`${transferId}: Service says COMPLETED, but the core has no posting with this reference.`);
      }
    }
    $('ledger').replaceChildren(...rows);
    $('ledger-warnings').replaceChildren(...warnings.map((w) => h('p', { className: 'warning', text: w })));
  }

  // ---------------------------------------------------------------- chaos presets

  const NORMAL = {
    accounts: { latencyMs: { min: 20, max: 150 }, errorRate: 0.03, maxConcurrent: 20 },
    fx: { latencyMs: { min: 100, max: 300 }, errorRate: 0.05, maxPerSecond: 5, validitySeconds: 30 },
    fraud: { latencyMs: { min: 50, max: 250 }, errorRate: 0.02, slowRate: 0.05, slowMs: 2500 },
    core: {
      latencyMs: { min: 100, max: 300 }, hangRate: 0.03, hangMs: 5000, applyOnHangRate: 0.5,
      inquiryLatencyMs: { min: 20, max: 80 }, maxSessions: 10, sessionTtlSeconds: 120,
    },
  };

  const PRESETS = [
    ['Normal', {}],
    ['Core slow', { core: { latencyMs: { min: 800, max: 1200 } } }],
    ['Core timeouts', { core: { hangRate: 0.5, applyOnHangRate: 0.5 } }],
    ['Fraud down', { fraud: { errorRate: 1 } }],
    ['FX throttled', { fx: { maxPerSecond: 1 } }],
    ['Accounts flaky', { accounts: { errorRate: 0.3 } }],
  ];

  function merge(base, delta) {
    const out = structuredClone(base);
    for (const [key, value] of Object.entries(delta)) {
      out[key] = value && typeof value === 'object' && !Array.isArray(value) && out[key] && typeof out[key] === 'object'
        ? merge(out[key], value)
        : value;
    }
    return out;
  }

  async function applyPreset(name, delta) {
    const state = $('chaos-state');
    try {
      const { response, text } = await fetchText(`${MOCK_ADMIN}/chaos`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(merge(NORMAL, delta)),
      }, 5000);
      state.textContent = response.ok ? `${name} applied at ${time()}.` : `Mock bank refused ${name}: HTTP ${response.status} ${text.slice(0, 200)}`;
    } catch {
      state.textContent = 'Mock bank did not answer.';
    }
  }

  async function resetMockBank() {
    const state = $('chaos-state');
    try {
      const { response } = await fetchText(`${MOCK_ADMIN}/reset`, { method: 'POST' }, 5000);
      state.textContent = response.ok ? `Mock bank reset at ${time()}: default chaos, statistics cleared.` : `Reset failed: HTTP ${response.status}`;
      refreshLedger();
    } catch {
      state.textContent = 'Mock bank did not answer.';
    }
  }

  // ---------------------------------------------------------------- wiring

  function init() {
    $('idempotency-key').value = randomKey();
    $('new-key').addEventListener('click', () => { $('idempotency-key').value = randomKey(); });
    $('transfer-form').addEventListener('submit', (event) => {
      event.preventDefault();
      send(1);
    });
    $('send-again').addEventListener('click', () => {
      if (lastRequest) dispatch(lastRequest.payload, lastRequest.key, 1);
    });
    $('send-five').addEventListener('click', () => send(5));
    $('clear-responses').addEventListener('click', () => $('responses').replaceChildren());
    $('lookup').addEventListener('click', lookup);

    $('feed-connect').addEventListener('click', connectFeed);

    $('batch-send').addEventListener('click', uploadBatch);
    $('batch-stop').addEventListener('click', () => batchController && batchController.abort());

    $('ledger-refresh').addEventListener('click', refreshLedger);
    $('ledger-auto').addEventListener('change', (event) => {
      clearInterval(ledgerTimer);
      if (event.target.checked) {
        refreshLedger();
        ledgerTimer = setInterval(refreshLedger, 2000);
      }
    });

    const presets = $('presets');
    for (const [name, delta] of PRESETS) {
      presets.append(h('button', { type: 'button', text: name }));
      presets.lastElementChild.addEventListener('click', () => applyPreset(name, delta));
    }
    presets.append(h('button', { type: 'button', className: 'danger', text: 'Reset' }));
    presets.lastElementChild.addEventListener('click', resetMockBank);

    checkHealth();
    setInterval(checkHealth, 5000);
    connectFeed();
  }

  document.addEventListener('DOMContentLoaded', init);
})();
