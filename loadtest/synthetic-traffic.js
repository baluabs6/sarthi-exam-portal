import http from 'k6/http';
import { sleep } from 'k6';

/**
 * A rule-based synthetic traffic generator, not an LLM-driven one —
 * varying arrival timing and roll-number patterns by a few simple,
 * documented rules to look more like real human behavior than a flat
 * k6 script (constant VUs, constant sleep) would, while staying fully
 * deterministic and inspectable.
 *
 * Real result-day traffic isn't uniform: there's a sharp opening spike
 * (everyone refreshing right at declaration time), a long tail as
 * people trickle in over the following hours, and occasional secondary
 * bursts (a WhatsApp forward, a news alert). This script's stages
 * approximate that shape instead of a single flat ramp.
 *
 * Usage:
 *   k6 run loadtest/synthetic-traffic.js
 *   BASE_URL=https://staging.example.com k6 run loadtest/synthetic-traffic.js
 */

export const options = {
  scenarios: {
    opening_spike: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 300 }, // the "everyone refreshes at 11:00 AM" moment
        { duration: '20s', target: 300 },
        { duration: '30s', target: 40 },  // spike subsides
      ],
    },
    long_tail: {
      executor: 'constant-arrival-rate',
      rate: 15,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 30,
      maxVUs: 100,
      startTime: '1m', // begins once the opening spike scenario finishes
    },
    secondary_burst: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: 120 }, // e.g. a WhatsApp forward causing a mini-spike
        { duration: '15s', target: 120 },
        { duration: '10s', target: 0 },
      ],
      startTime: '2m30s',
    },
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const EXAM_IDS = ['NEET-UG-2026', 'JEE-MAIN-2026', 'SSC-CGL-2026'];

// A believable-looking distribution of roll number prefixes rather than
// pure random digits — real roll numbers cluster by issuing
// region/center in ways a uniformly random 12-digit number wouldn't.
const ROLL_PREFIXES = ['261045', '262056', '263071', '264089'];

function randomRollNumber() {
  const prefix = ROLL_PREFIXES[Math.floor(Math.random() * ROLL_PREFIXES.length)];
  const suffix = String(Math.floor(Math.random() * 900000) + 100000);
  return prefix + suffix;
}

function randomExamId() {
  // NEET gets disproportionately more traffic than the other two, like
  // a real high-stakes exam result day would.
  const roll = Math.random();
  if (roll < 0.6) return EXAM_IDS[0];
  if (roll < 0.85) return EXAM_IDS[1];
  return EXAM_IDS[2];
}

export default function () {
  const examId = randomExamId();
  const rollNumber = randomRollNumber();

  // Fetch a real captcha challenge first, same as a real browser would
  // — this also exercises the adaptive-difficulty path under load.
  const captchaRes = http.get(`${BASE_URL}/api/queue/captcha`);
  let captchaId = '';
  let answer = '0';
  try {
    const body = JSON.parse(captchaRes.body);
    captchaId = body.captchaId;
    // Only handles the simple "a + b = ?" case deliberately — this
    // script is meant to model normal traffic, not to defeat the
    // adaptive-difficulty CAPTCHA once it escalates for a given IP.
    const match = body.question.match(/(\d+)\s*\+\s*(\d+)/);
    if (match) answer = String(parseInt(match[1], 10) + parseInt(match[2], 10));
  } catch (e) {
    // Malformed/unexpected response — let the join attempt fail
    // naturally below rather than crashing the whole script.
  }

  http.post(
    `${BASE_URL}/api/queue/join`,
    JSON.stringify({ examId, rollNumber, captchaId, captchaAnswer: answer, website: '' }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  // A real person doesn't hammer refresh instantly — small human-like pause.
  sleep(Math.random() * 2 + 0.5);
}
