// Measures the gateway's own overhead, not the provider's.
//
// Two scenarios:
//
//   refusal  - an account whose budget is already exhausted. Every request is
//              refused before any provider call, so the measured latency IS the
//              budget check plus HTTP. This is the number M5 targets (< 2ms p99
//              on the Redis path).
//
//   metered  - a funded account against a stub provider. The delta between this
//              and the same stub called directly is Sluice's added latency.
//
// Run:
//   k6 run -e SLUICE_URL=http://localhost:8080 \
//          -e REFUSED_KEY=sk-sluice-... \
//          -e FUNDED_KEY=sk-sluice-... \
//          loadtest/budget-check.k6.js

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const SLUICE_URL = __ENV.SLUICE_URL || 'http://localhost:8080';
const REFUSED_KEY = __ENV.REFUSED_KEY;
const FUNDED_KEY = __ENV.FUNDED_KEY;
const MODEL = __ENV.MODEL || 'claude-opus-5';

const budgetCheck = new Trend('sluice_budget_check_ms', true);
const meteredCall = new Trend('sluice_metered_call_ms', true);

export const options = {
  scenarios: {
    refusal: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      exec: 'refusedPath',
    },
    metered: {
      executor: 'constant-arrival-rate',
      rate: 25,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 25,
      maxVUs: 100,
      exec: 'meteredPath',
      startTime: '35s',
    },
  },
  thresholds: {
    // The PRD's non-functional target for the budget check.
    'sluice_budget_check_ms': ['p(99)<2'],
    'checks': ['rate>0.99'],
  },
};

function body(maxTokens) {
  return JSON.stringify({
    model: MODEL,
    max_tokens: maxTokens,
    messages: [{ role: 'user', content: 'Say hello.' }],
  });
}

export function refusedPath() {
  if (!REFUSED_KEY) {
    throw new Error('set REFUSED_KEY to a virtual key whose budget is exhausted');
  }
  const response = http.post(`${SLUICE_URL}/v1/messages`, body(1024), {
    headers: { 'content-type': 'application/json', 'x-api-key': REFUSED_KEY },
    tags: { path: 'refusal' },
  });
  budgetCheck.add(response.timings.duration);
  check(response, {
    'refused with 402': (r) => r.status === 402,
    'names the account that tripped': (r) => r.body.includes('account_name'),
  });
}

export function meteredPath() {
  if (!FUNDED_KEY) {
    throw new Error('set FUNDED_KEY to a virtual key with credit');
  }
  const response = http.post(`${SLUICE_URL}/v1/messages`, body(64), {
    headers: {
      'content-type': 'application/json',
      'x-api-key': FUNDED_KEY,
      // Unique per iteration so each call gets its own hold/settle cycle.
      'x-sluice-request-id': `k6-${__VU}-${__ITER}-${Date.now()}`,
    },
    tags: { path: 'metered' },
  });
  meteredCall.add(response.timings.duration);
  check(response, { 'metered call succeeded': (r) => r.status === 200 });
}
