import { render, screen, waitFor } from '@testing-library/svelte';
import { describe, it, expect, vi } from 'vitest';
import App from './App.svelte';
import { createBridge } from './bridge';
describe('Week', () => {
 it('renders escaped read-only data from a fake bridge', async () => {
  const query = vi.fn().mockResolvedValue({ start:'2026-11-02', today:'2026-11-02', onTime:1, scoredCount:2, days:[{date:'2026-11-02', morning:'ON_TIME', workoutName:'<script>bad</script>', plannedName:null, kcal:200, steps:123, stepGoal:1000}] });
  render(App, { query });
  await screen.findByText('<script>bad</script>');
  expect(screen.getByText('200 kcal · 123 steps')).toBeTruthy();
  expect(document.querySelector('script')).toBeNull();
  expect(query).toHaveBeenCalledWith('week.get');
 });
 it('reports failures to native for fallback', async () => {
  const failed = vi.fn();
  render(App, { query: vi.fn().mockRejectedValue(new Error('offline')), failed });
  await waitFor(() => expect(failed).toHaveBeenCalled());
 });
 it('matches responses and rejects unsupported versions', async () => {
  const native = { postMessage: vi.fn(), onmessage: undefined as any };
  const bridge = createBridge(native);
  const promise = bridge('week.get');
  const request = JSON.parse(native.postMessage.mock.calls[0][0]);
  native.onmessage({data: JSON.stringify({v:2,id:request.id,data:{}})});
  await expect(promise).rejects.toThrow('Invalid bridge response');
 });
});

describe('closed bridge protocol', () => {
 it('keeps not-yet-loaded data null', async () => {
  const native = { postMessage: vi.fn(), onmessage: undefined as any };
  const query = createBridge(native);
  const waiting = query('week.get');
  const { id } = JSON.parse(native.postMessage.mock.calls[0][0]);
  native.onmessage({data:JSON.stringify({v:1,id,data:null})});
  await expect(waiting).resolves.toBeNull();
 });
 it('rejects malformed week DTOs', async () => {
  const native = { postMessage: vi.fn(), onmessage: undefined as any };
  const query = createBridge(native);
  const waiting = query('week.get');
  const { id } = JSON.parse(native.postMessage.mock.calls[0][0]);
  native.onmessage({data:JSON.stringify({v:1,id,data:{start:'bad',days:[]}})});
  await expect(waiting).rejects.toThrow('Invalid bridge response');
 });
 it('matches simultaneous acknowledgements by id', async () => {
  const native = { postMessage: vi.fn(), onmessage: undefined as any };
  const query = createBridge(native);
  const a = query('client.ready');
  const b = query('client.failed');
  const ids = native.postMessage.mock.calls.map(c => JSON.parse(c[0]).id);
  native.onmessage({data:JSON.stringify({v:1,id:ids[1],ok:true})});
  native.onmessage({data:JSON.stringify({v:1,id:ids[0],ok:true})});
  await expect(a).resolves.toBe(true);
  await expect(b).resolves.toBe(true);
 });
 it('times out missing replies', async () => {
  vi.useFakeTimers();
  try {
   const query = createBridge({postMessage:vi.fn()});
   const waiting = query('week.get');
   const rejected = expect(waiting).rejects.toThrow('Bridge timed out');
   await vi.advanceTimersByTimeAsync(5001);
   await rejected;
  } finally { vi.useRealTimers(); }
 });
});
