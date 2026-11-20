// k6 load test: measures the gateway's own overhead using the zero-latency mock provider.
//
//   1. Create a tenant with a high rate limit (the bootstrap tenant is limited on purpose):
//        curl -s -X POST localhost:8080/admin/tenants -H 'Authorization: Bearer admin-dev-key' \
//          -H 'Content-Type: application/json' -d '{"name":"loadtest","requests_per_minute":1000000}'
//   2. k6 run -e API_KEY=<api_key from step 1> loadtest/chat.js
//
// Options: -e MODEL=mock (adds simulated generation time), -e STREAM=true, -e RATE=500
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'sk-dev-local';
const MODEL = __ENV.MODEL || 'mock-fast';
const STREAM = __ENV.STREAM === 'true';
const RATE = Number(__ENV.RATE || 200);

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '1m',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<250'],
  },
};

const payload = JSON.stringify({
  model: MODEL,
  stream: STREAM,
  messages: [{ role: 'user', content: 'Give me one sentence about load testing.' }],
});

const params = {
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${API_KEY}` },
};

export default function () {
  const res = http.post(`${BASE_URL}/v1/chat/completions`, payload, params);
  check(res, { 'status is 200': (r) => r.status === 200 });
}
