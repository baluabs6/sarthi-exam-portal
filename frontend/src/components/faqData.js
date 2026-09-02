// A small, hand-curated FAQ set the help widget searches over.
//
// This is deliberately NOT calling a real LLM: doing that safely needs a
// hosted model API (e.g. Claude/OpenAI), a real API key, and a review of
// what data leaves the browser — none of which belong hardcoded into a
// public frontend bundle. Search src/components/FaqWidget.jsx for the
// exact spot to swap this out for a real retrieval-augmented call once
// you have a backend endpoint + API key to route it through.
export const FAQ_ENTRIES = [
  {
    q: 'Why do I have to wait in a queue to see my result?',
    a: 'On result day, millions of people try to check at the same moment. Letting everyone through at once would overload the database and cause it to crash for everyone. The queue lets people through at a steady, safe rate so the site stays up.',
    keywords: ['queue', 'wait', 'waiting', 'line', 'why'],
  },
  {
    q: 'Will I lose my place if I close the tab?',
    a: "No. Your position is saved on our server, not in your browser. You can close the tab and come back later using the same link — you won't lose your place.",
    keywords: ['close', 'tab', 'lose', 'position', 'refresh', 'leave'],
  },
  {
    q: "How will I know when it's my turn?",
    a: "If your browser allows it, we'll send you a notification the instant you're admitted. Otherwise, the page updates automatically — no need to keep refreshing.",
    keywords: ['notify', 'notification', 'turn', 'admitted', 'alert'],
  },
  {
    q: 'My result looks wrong — what do I do?',
    a: "You can raise a formal grievance ticket directly from your result page. You'll get a reference number to track its status — no login needed.",
    keywords: ['wrong', 'grievance', 'complaint', 'mistake', 'error', 'marks', 'incorrect'],
  },
  {
    q: 'Is my data safe on this portal?',
    a: 'Result access is protected by a signed, time-limited ticket issued only after a genuine queue turn — results cannot be fetched by guessing a roll number. All queue activity is also tamper-evident logged.',
    keywords: ['safe', 'secure', 'security', 'privacy', 'data'],
  },
  {
    q: 'The verification question (captcha) failed — why?',
    a: "Verification answers expire after a few minutes and can only be used once. If yours failed, a fresh question is generated automatically — just answer the new one shown.",
    keywords: ['captcha', 'verification', 'challenge', 'failed', 'wrong answer'],
  },
]
