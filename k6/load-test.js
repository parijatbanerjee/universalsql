import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const cacheQueryDuration = new Trend('cache_query_duration');
const liveQueryDuration = new Trend('live_query_duration');
const errorRate = new Rate('error_rate');

export const options = {
  stages: [
    { duration: '10s', target: 100 },   // ramp up
    { duration: '40s', target: 500 },   // sustain ~500 RPS
    { duration: '10s', target: 0 },     // ramp down
  ],
  thresholds: {
    'cache_query_duration': ['p(50)<200', 'p(95)<500'],
    'error_rate': ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.AUTH_TOKEN || 'test-token';

const cacheQuery = JSON.stringify({
  sql: "SELECT * FROM jira_issues LIMIT 25",
  include_latest_data: false,
  max_staleness_ms: 60000,
  timeout_ms: 2000
});

const hybridQuery = JSON.stringify({
  sql: "SELECT i.issue_key, i.status, p.title FROM jira_issues i JOIN github_prs p ON p.linked_issue_key = i.issue_key WHERE i.project_key = 'PLAT'",
  include_latest_data: true,
  max_staleness_ms: 0,
  timeout_ms: 2000
});

export default function() {
  const isCacheQuery = Math.random() < 0.7; // 70% cache, 30% hybrid
  const body = isCacheQuery ? cacheQuery : hybridQuery;

  const start = Date.now();
  const res = http.post(`${BASE_URL}/v1/query`, body, {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${TOKEN}`,
    },
  });
  const duration = Date.now() - start;

  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has rows': (r) => {
      try { return JSON.parse(r.body).rows !== undefined; } catch { return false; }
    }
  });

  if (!ok) errorRate.add(1);

  if (isCacheQuery) {
    cacheQueryDuration.add(duration);
  } else {
    liveQueryDuration.add(duration);
  }

  sleep(0.001); // minimal sleep
}
