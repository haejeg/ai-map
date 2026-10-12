import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import { parseIntent, runAgent } from '../js/agent.js';
import { buildMockPois } from '../js/pois.js';
import { createTools } from '../js/tools.js';

// Mock telemetry: user facing due east. Mock POIs sit at fixed absolute bearings:
//   cafe 10°, pharmacy 40°, park 75°, bus 100°, library 150°,
//   grocery 200°, museum 250°, atm 290°, hospital 330°.
// With heading 90° the expected quadrants are:
//   ahead  (bearing 45–135):  park, bus
//   right  (bearing 135–225): library, grocery
//   behind (bearing 225–315): museum, atm
//   left   (bearing 315–45):  cafe, pharmacy, hospital
const ANCHOR = { lat: 51.5007, lon: -0.1246 };

function setup(heading = 90) {
  const state = { ...ANCHOR, heading, accuracy: 5, hasFix: true };
  const pois = buildMockPois(ANCHOR.lat, ANCHOR.lon);
  const reroutes = [];
  const tools = createTools({ getState: () => state, getPois: () => pois, onReroute: (r) => reroutes.push(r) });
  return { state, tools, reroutes };
}

describe('parseIntent (direction extraction)', () => {
  const cases = [
    ['What is to my left?', 'get_surrounding_pois', { direction: 'left' }],
    ["what's on my right", 'get_surrounding_pois', { direction: 'right' }],
    ['What is behind me', 'get_surrounding_pois', { direction: 'behind' }],
    ['anything in front of me?', 'get_surrounding_pois', { direction: 'ahead' }],
    ["what's around me", 'get_surrounding_pois', { direction: 'all' }],
    ['Where am I?', 'get_current_location', {}],
    ['Take me to the Public Library.', 'reroute_navigation', { destination: 'public library' }],
    ['navigate to fresh grocer', 'reroute_navigation', { destination: 'fresh grocer' }],
  ];
  for (const [utterance, name, input] of cases) {
    it(`"${utterance}" → ${name}(${JSON.stringify(input)})`, () => {
      assert.deepEqual(parseIntent(utterance), { name, input });
    });
  }
  it('returns null for unrelated speech', () => assert.equal(parseIntent('tell me a joke'), null));
});

describe('voice pipeline end-to-end (heading 90°)', () => {
  const expected = {
    left: ['Blue Door Coffee', 'Corner Pharmacy', 'General Hospital'],
    ahead: ['Market Street Bus Stop', 'Linden Pocket Park'],
    right: ['Fresh Grocer', 'Public Library'],
    behind: ['ATM Kiosk', 'City History Museum'],
  };
  const prompts = {
    left: 'What is to my left?',
    ahead: "What's ahead of me?",
    right: "What's to my right?",
    behind: "What's behind me?",
  };

  for (const [direction, names] of Object.entries(expected)) {
    it(`"${prompts[direction]}" announces exactly the ${direction} POIs, nearest first`, async () => {
      const { tools } = setup();
      const { text, trace } = await runAgent(prompts[direction], tools);

      assert.equal(trace.length, 1);
      assert.equal(trace[0].tool, 'get_surrounding_pois');
      assert.deepEqual(trace[0].input, { direction });
      assert.deepEqual(trace[0].output.pois.map((p) => p.name), names);
      assert.ok(trace[0].output.pois.every((p) => p.direction === direction));

      // Spoken reply mentions every expected POI in order and no others.
      let cursor = -1;
      for (const name of names) {
        const at = text.indexOf(name);
        assert.ok(at > cursor, `"${name}" missing or out of order in: ${text}`);
        cursor = at;
      }
      const others = Object.values(expected).flat().filter((n) => !names.includes(n));
      for (const other of others) assert.ok(!text.includes(other), `unexpected "${other}" in: ${text}`);
    });
  }

  it('turning around (heading 270°) swaps left and right', async () => {
    const { tools } = setup(270);
    const { trace } = await runAgent('What is to my left?', tools);
    assert.deepEqual(trace[0].output.pois.map((p) => p.name), ['Fresh Grocer', 'Public Library']);
  });

  it('get_current_location reports heading and facing', async () => {
    const { tools } = setup();
    const { text, trace } = await runAgent('Where am I?', tools);
    assert.equal(trace[0].output.facing, 'east');
    assert.match(text, /facing east at 90 degrees/);
  });

  it('reroute_navigation resolves a destination and fires the route callback', async () => {
    const { tools, reroutes } = setup();
    const { text, trace } = await runAgent('Take me to the public library', tools);
    const out = trace[0].output;
    assert.equal(out.ok, true);
    assert.equal(out.destination, 'Public Library');
    assert.equal(out.distance_m, 340);
    assert.equal(out.relative_direction, 'right');
    assert.equal(reroutes.length, 1);
    assert.match(text, /Rerouting to Public Library\. It's 340 meters to your right/);
  });

  it('reroute_navigation reports unknown destinations', async () => {
    const { tools, reroutes } = setup();
    const { text } = await runAgent('navigate to the moon', tools);
    assert.match(text, /No nearby place matches "the moon"|No nearby place matches "moon"/);
    assert.equal(reroutes.length, 0);
  });

  it('falls back to help text when no tool applies', async () => {
    const { tools } = setup();
    const { text, trace } = await runAgent('sing me a song', tools);
    assert.equal(trace.length, 0);
    assert.match(text, /what is to my left/i);
  });
});
