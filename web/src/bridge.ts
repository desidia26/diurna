export type Method = 'week.get' | 'client.ready' | 'client.failed';
export type Week = { start:string; today:string; onTime:number; scoredCount:number; days: {date:string; morning:string; workoutName:string|null; plannedName:string|null; kcal:number|null; steps:number|null; stepGoal:number}[] };
export type Results = { 'week.get': Week|null; 'client.ready': true; 'client.failed': true };
export type Query = <M extends Method>(method:M)=>Promise<Results[M]>;
type Native = {postMessage: (data:string)=>void; onmessage?: (event:{data:string})=>void};
function validWeek(w:any): w is Week {
 const date = (d:any) => typeof d === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(d);
 const text = (t:any) => t === null || (typeof t === 'string' && t.length <= 200);
 const number = (n:any) => n === null || (Number.isSafeInteger(n) && n >= 0);
 return w === null || (w && date(w.start) && date(w.today) && number(w.onTime) && number(w.scoredCount) &&
  Array.isArray(w.days) && w.days.length <= 7 && w.days.every((d:any) => date(d.date) &&
   typeof d.morning === 'string' && d.morning.length <= 32 && text(d.workoutName) && text(d.plannedName) &&
   number(d.kcal) && number(d.steps) && number(d.stepGoal)));
}
export function createBridge(native:Native):Query {
 let counter = 0;
 const pending = new Map<string, {resolve:(value:any)=>void; reject:(error:Error)=>void; timer:ReturnType<typeof setTimeout>; method:Method}>();
 native.onmessage = event => {
  if (event.data.length > 16384) return;
  try {
   const response = JSON.parse(event.data);
   const call = pending.get(response.id);
   if (!call) return;
   clearTimeout(call.timer); pending.delete(response.id);
   if (response.v !== 1 || response.error || (call.method === 'week.get' ? !validWeek(response.data) : response.ok !== true)) call.reject(new Error('Invalid bridge response'));
   else call.resolve(response.data ?? response.ok ?? null);
  } catch { /* A malformed reply times out and triggers native fallback. */ }
 };
 const query = (method:Method):Promise<any> => new Promise((resolve,reject) => {
  const id = `web-${++counter}`;
  const timer = setTimeout(() => { pending.delete(id); reject(new Error('Bridge timed out')); }, 5000);
  pending.set(id, {resolve,reject,timer,method});
  native.postMessage(JSON.stringify({v:1,id,method}));
 });
 return query as Query;
}
